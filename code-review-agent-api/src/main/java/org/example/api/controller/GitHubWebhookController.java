package org.example.api.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import org.example.common.utils.JsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.api.config.WebhookProperties;
import org.example.domain.code.CodeDomainService;
import org.example.domain.code.model.CodeReviewResult;
import org.example.repository.github.GitHubClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GitHub Webhook 接收端点。
 * <p>
 * 处理两类事件：
 * <ul>
 *   <li><b>pull_request</b> (opened/synchronize): feature 分支代码审查</li>
 *   <li><b>push</b> (ref=master): 合并后同步向量库</li>
 * </ul>
 */
@Slf4j
@RestController
public class GitHubWebhookController {

    @Resource
    private WebhookProperties webhookProperties;

    @Resource
    private CodeDomainService codeDomainService;

    @Resource
    private GitHubClient gitHubClient;

    /**
     * GitHub Webhook 入口。
     * <p>
     * GitHub 在每次事件发生时 POST 到该端点，Header 中包含事件类型和 HMAC 签名。
     *
     * @param eventType    X-GitHub-Event header（如 "pull_request", "push"）
     * @param signature    X-Hub-Signature-256 header（HMAC-SHA256 签名）
     * @param deliveryId   X-GitHub-Delivery header（唯一事件 ID）
     * @param payload      Webhook 原始 body
     * @return 处理结果
     */
    @PostMapping("/webhooks/github")
    public Map<String, Object> handleWebhook(
        @RequestHeader("X-GitHub-Event") String eventType,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
        @RequestHeader("X-GitHub-Delivery") String deliveryId,
        @RequestBody String payload) {

        // HMAC 签名校验
        if (webhookProperties.getSecret() != null && !webhookProperties.getSecret().isBlank()) {
            verifySignature(payload, signature);
        }

        log.info("收到 GitHub Webhook: event={}, deliveryId={}", eventType, deliveryId);

        try {
            Map<String, Object> body = JsonUtils.fromJson(payload, new TypeReference<Map<String, Object>>() {});

            return switch (eventType) {
                case "pull_request" -> handlePullRequest(body);
                case "push" -> handlePush(body);
                default -> {
                    log.info("忽略事件类型: {}", eventType);
                    yield Map.of("status", "ignored", "event", eventType);
                }
            };
        } catch (Exception e) {
            log.error("Webhook 处理失败: {}", e.getMessage(), e);
            throw new RuntimeException("Webhook 处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理 Pull Request 事件（feature 分支代码审查）。
     * <p>
     * 流程：提取 PR 元数据 → 执行 LLM 审查 → 提交审查结论到 GitHub。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handlePullRequest(Map<String, Object> body) {
        Map<String, Object> pr = (Map<String, Object>) body.get("pull_request");
        Map<String, Object> repo = (Map<String, Object>) body.get("repository");
        String action = (String) body.get("action");

        // 只在 PR 创建或更新时审查
        if (!"opened".equals(action) && !"synchronize".equals(action)) {
            return Map.of("status", "skipped", "action", action);
        }

        String projectId = (String) repo.get("full_name");
        int prNumber = ((Number) pr.get("number")).intValue();
        String headSha = (String) ((Map<String, Object>) pr.get("head")).get("sha");
        String baseSha = (String) ((Map<String, Object>) pr.get("base")).get("sha");
        String prTitle = (String) pr.get("title");
        String prBody = (String) pr.get("body");
        String headRef = (String) ((Map<String, Object>) pr.get("head")).get("ref");
        String baseRef = (String) ((Map<String, Object>) pr.get("base")).get("ref");

        log.info("PR #{}: projectId={}, action={}, {} → {}",
            prNumber, projectId, action, headRef, baseRef);

        // 构建审查输入：PR 元数据 + 实际代码 diff
        String[] projectParts = projectId.split("/", 2);
        String owner = projectParts[0];
        String repoName = projectParts.length > 1 ? projectParts[1] : "";

        StringBuilder diffBuilder = new StringBuilder();
        diffBuilder.append("【PR 标题】").append(prTitle != null ? prTitle : "N/A").append("\n\n");
        diffBuilder.append("【分支】").append(headRef).append(" → ").append(baseRef).append("\n\n");
        if (prBody != null && !prBody.isBlank()) {
            diffBuilder.append("【变更描述】\n").append(prBody).append("\n\n");
        }
        // 拉取 GitHub 实际代码 diff
        try {
            String realDiff = gitHubClient.getPullRequestDiff(owner, repoName, prNumber);
            if (realDiff != null && !realDiff.isBlank()) {
                diffBuilder.append("【代码变更】\n").append(realDiff).append("\n");
            }
        } catch (Exception e) {
            log.warn("获取 PR diff 失败，仅使用元数据: {}", e.getMessage());
        }
        String diff = diffBuilder.toString();

        // 执行 LLM 审查 + 提交到 GitHub
        CodeReviewResult result = codeDomainService.reviewAndSubmit(projectId, prNumber, diff);

        return Map.of(
            "status", result.getStatus(),
            "projectId", projectId,
            "prNumber", prNumber,
            "riskLevel", result.getRiskLevel(),
            "review", result.getReason()
        );
    }

    /**
     * 处理 Push 事件到 master（合并后增量同步向量库 + 调用图索引）。
     * <p>
     * 合并到主分支后：
     * <ul>
     *   <li>删除的 Java 文件</li>
     *   <li>修改的 Java 文件 → 先删旧，再从 GitHub 拉取新内容导入</li>
     *   <li>新增的 Java 文件 → 从 GitHub 拉取内容，解析并导入</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handlePush(Map<String, Object> body) {
        String ref = (String) body.get("ref");
        if (!"refs/heads/master".equals(ref) && !"refs/heads/main".equals(ref)) {
            return Map.of("status", "skipped", "ref", ref);
        }
        Map<String, Object> repo = (Map<String, Object>) body.get("repository");
        String projectId = (String) repo.get("full_name");
        String after = (String) body.get("after");
        List<Map<String, Object>> commits = (List<Map<String, Object>>) body.get("commits");
        String owner = parseOwner(projectId);
        String repoName = parseRepo(projectId);
        log.info("Push to {}: projectId={}, after={}, commits={}", ref, projectId, after, commits.size());

        Set<String> addedJavaFiles = new LinkedHashSet<>();
        Set<String> modifiedJavaFiles = new LinkedHashSet<>();
        Set<String> removedJavaFiles = new LinkedHashSet<>();
        for (Map<String, Object> commit : commits) {
            collectJavaFiles(commit, addedJavaFiles, modifiedJavaFiles, removedJavaFiles);
        }
        int deletedCount = 0, methodsImported = 0, skipped = 0, failed = 0;

        // 删除的 .java：清理向量库 + 调用图索引
        for (String file : removedJavaFiles) {
            try {
                codeDomainService.deleteJavaFile(projectId, file);
                deletedCount++;
            } catch (Exception e) {
                log.error("删除文件失败: projectId={}, file={}, error={}", projectId, file, e.getMessage());
                failed++;
            }
        }

        // 修改的 .java：先删旧数据
        for (String file : modifiedJavaFiles) {
            try {
                codeDomainService.deleteJavaFile(projectId, file);
                deletedCount++;
            } catch (Exception e) {
                log.warn("修改文件清理旧数据失败，继续导入: projectId={}, file={}, error={}",
                    projectId, file, e.getMessage());
            }
        }

        // 新增 + 修改的 .java：从 GitHub 拉取内容并导入
        Set<String> filesToImport = new LinkedHashSet<>();
        filesToImport.addAll(addedJavaFiles);
        filesToImport.addAll(modifiedJavaFiles);
        for (String file : filesToImport) {
            try {
                String content = gitHubClient.getFileContent(owner, repoName, file, after);
                if (content == null || content.isBlank()) {
                    skipped++;
                    continue;
                }
                methodsImported += codeDomainService.addJavaFile(projectId, file, content);
            } catch (Exception e) {
                log.error("文件导入失败: projectId={}, file={}, error={}", projectId, file, e.getMessage());
                failed++;
            }
        }

        log.info("Push 同步完成: projectId={}, added={}, modified={}, removed={}, "
                + "methodsImported={}, deleted={}, skipped={}, failed={}",
            projectId, addedJavaFiles.size(), modifiedJavaFiles.size(), removedJavaFiles.size(),
            methodsImported, deletedCount, skipped, failed);

        return Map.of(
            "status", "synced",
            "projectId", projectId,
            "commitCount", commits.size(),
            "addedFiles", addedJavaFiles.size(),
            "modifiedFiles", modifiedJavaFiles.size(),
            "removedFiles", removedJavaFiles.size(),
            "methodsImported", methodsImported,
            "qdrantDeleted", deletedCount,
            "filesSkipped", skipped,
            "filesFailed", failed
        );
    }

    @SuppressWarnings("unchecked")
    private static void collectJavaFiles(Map<String, Object> commit,
                                          Set<String> added, Set<String> modified, Set<String> removed) {
        addIfJava((List<String>) commit.get("added"), added);
        addIfJava((List<String>) commit.get("modified"), modified);
        addIfJava((List<String>) commit.get("removed"), removed);
    }

    private static void addIfJava(List<String> files, Set<String> target) {
        if (files == null) return;
        for (String f : files) {
            if (f.endsWith(".java")) {
                target.add(f);
            }
        }
    }

    private static String parseOwner(String projectId) {
        return projectId.split("/", 2)[0];
    }

    private static String parseRepo(String projectId) {
        String[] parts = projectId.split("/", 2);
        return parts.length > 1 ? parts[1] : "";
    }

    /** HMAC-SHA256 签名校验 */
    private void verifySignature(String payload, String signature) {
        if (signature == null || signature.isBlank()) {
            throw new SecurityException("缺少 Webhook 签名，请确认 X-Hub-Signature-256 header");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                webhookProperties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);

            if (!expected.equals(signature)) {
                throw new SecurityException("Webhook 签名校验失败");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("Webhook 签名计算失败: " + e.getMessage(), e);
        }
    }
}

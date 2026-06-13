package org.example.api.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import org.example.common.utils.JsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.api.config.WebhookProperties;
import org.example.domain.code.CodeDomainService;
import org.example.domain.code.domain.CodeDomainPhysical;
import org.example.domain.code.model.CodeReviewResult;
import org.example.domain.code.service.CallGraphIndex;
import org.example.domain.code.service.CodeRepository;
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
    private CodeRepository codeRepository;

    @Resource
    private CallGraphIndex callGraphIndex;

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

        // 构建审查输入：PR 标题 + 变更描述 + 来源/目标分支
        StringBuilder diffBuilder = new StringBuilder();
        diffBuilder.append("【PR 标题】").append(prTitle != null ? prTitle : "N/A").append("\n\n");
        diffBuilder.append("【分支】").append(headRef).append(" → ").append(baseRef).append("\n\n");
        if (prBody != null && !prBody.isBlank()) {
            diffBuilder.append("【变更描述】\n").append(prBody).append("\n\n");
        }
        diffBuilder.append("【Commit SHA】\nhead: ").append(headSha)
            .append("\nbase: ").append(baseSha).append("\n");
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
     *   <li>删除的 Java 文件 → 从 Qdrant 删除向量 + 清理 {@link CallGraphIndex}</li>
     *   <li>修改的 Java 文件 → 先删旧数据，再从 GitHub 拉取新内容重新导入</li>
     *   <li>新增的 Java 文件 → 从 GitHub 拉取内容，解析并导入</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handlePush(Map<String, Object> body) {
        String ref = (String) body.get("ref");

        // 只响应 master / main 分支的 push
        if (!"refs/heads/master".equals(ref) && !"refs/heads/main".equals(ref)) {
            return Map.of("status", "skipped", "ref", ref);
        }

        Map<String, Object> repo = (Map<String, Object>) body.get("repository");
        String projectId = (String) repo.get("full_name");
        String after = (String) body.get("after");
        List<Map<String, Object>> commits = (List<Map<String, Object>>) body.get("commits");

        log.info("Push to {}: projectId={}, after={}, commits={}", ref, projectId, after, commits.size());

        // 解析 owner/repo
        String[] parts = projectId.split("/", 2);
        String owner = parts[0];
        String repoName = parts.length > 1 ? parts[1] : "";

        // 收集去重后的变更文件
        Set<String> addedFiles = new LinkedHashSet<>();
        Set<String> modifiedFiles = new LinkedHashSet<>();
        Set<String> removedFiles = new LinkedHashSet<>();

        for (Map<String, Object> commit : commits) {
            List<String> added = (List<String>) commit.get("added");
            List<String> modified = (List<String>) commit.get("modified");
            List<String> removed = (List<String>) commit.get("removed");

            if (added != null) addedFiles.addAll(added);
            if (modified != null) modifiedFiles.addAll(modified);
            if (removed != null) removedFiles.addAll(removed);
        }

        // 过滤出 Java 文件
        Set<String> addedJavaFiles = filterJavaFiles(addedFiles);
        Set<String> modifiedJavaFiles = filterJavaFiles(modifiedFiles);
        Set<String> removedJavaFiles = filterJavaFiles(removedFiles);

        int qdrantDeletedCount = 0;
        int methodsImported = 0;
        int filesSkipped = 0;
        int filesFailed = 0;

        // ── 1. 处理已删除的 Java 文件：从 Qdrant 和 CallGraphIndex 中清理 ──
        for (String file : removedJavaFiles) {
            try {
                CodeDomainPhysical coord = new CodeDomainPhysical(projectId, file, "", "");
                codeRepository.deleteByFilePath(coord);
                qdrantDeletedCount++;

                String className = extractClassName(file);
                callGraphIndex.removeCallersByFilePrefix(projectId, className);
                log.info("已删除文件清理完成: projectId={}, file={}", projectId, file);
            } catch (Exception e) {
                log.error("删除文件清理失败: projectId={}, file={}, error={}",
                    projectId, file, e.getMessage());
                filesFailed++;
            }
        }

        // ── 2. 处理已修改的 Java 文件：先删旧，再导新 ──
        for (String file : modifiedJavaFiles) {
            try {
                // 先清理旧的向量和调用图记录
                CodeDomainPhysical coord = new CodeDomainPhysical(projectId, file, "", "");
                codeRepository.deleteByFilePath(coord);
                qdrantDeletedCount++;

                String className = extractClassName(file);
                callGraphIndex.removeCallersByFilePrefix(projectId, className);
            } catch (Exception e) {
                log.warn("修改文件清理旧数据失败，继续导入: projectId={}, file={}, error={}",
                    projectId, file, e.getMessage());
            }
            // 清理完成后进入导入流程（继续到第 3 步）
        }

        // ── 3. 处理新增 + 修改的 Java 文件：从 GitHub 拉取内容并导入 ──
        Set<String> filesToImport = new LinkedHashSet<>();
        filesToImport.addAll(addedJavaFiles);
        filesToImport.addAll(modifiedJavaFiles);

        for (String file : filesToImport) {
            try {
                String content = gitHubClient.getFileContent(owner, repoName, file, after);
                if (content == null || content.isBlank()) {
                    log.warn("跳过文件（无内容）: projectId={}, file={}", projectId, file);
                    filesSkipped++;
                    continue;
                }

                int count = codeDomainService.syncFile(projectId, file, content);
                methodsImported += count;
                log.info("文件导入完成: projectId={}, file={}, 方法数={}", projectId, file, count);

            } catch (Exception e) {
                log.error("文件导入失败: projectId={}, file={}, error={}",
                    projectId, file, e.getMessage());
                filesFailed++;
            }
        }

        log.info("Push 同步完成: projectId={}, added={}, modified={}, removed={}, "
                + "methodsImported={}, qdrantDeleted={}, filesSkipped={}, filesFailed={}",
            projectId, addedJavaFiles.size(), modifiedJavaFiles.size(), removedJavaFiles.size(),
            methodsImported, qdrantDeletedCount, filesSkipped, filesFailed);

        return Map.of(
            "status", "synced",
            "projectId", projectId,
            "commitCount", commits.size(),
            "addedFiles", addedJavaFiles.size(),
            "modifiedFiles", modifiedJavaFiles.size(),
            "removedFiles", removedJavaFiles.size(),
            "methodsImported", methodsImported,
            "qdrantDeleted", qdrantDeletedCount,
            "filesSkipped", filesSkipped,
            "filesFailed", filesFailed
        );
    }

    /** 从文件全路径中过滤出 .java 文件 */
    private static Set<String> filterJavaFiles(Set<String> files) {
        Set<String> result = new LinkedHashSet<>();
        for (String f : files) {
            if (f.endsWith(".java")) {
                result.add(f);
            }
        }
        return result;
    }

    /** 从文件路径提取类名（文件名去除 .java 后缀） */
    private static String extractClassName(String filePath) {
        String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
        return fileName.replace(".java", "");
    }

    /** HMAC-SHA256 签名校验 */
    private void verifySignature(String payload, String signature) {
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

package org.example.api.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import org.example.common.utils.JsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.api.config.WebhookProperties;
import org.example.domain.code.CodeDomainService;
import org.example.domain.code.model.CodeReviewResult;
import org.example.domain.code.parser.JavaCodeParser;
import org.example.domain.code.service.CallGraphIndex;
import org.example.domain.code.service.CodeRepository;
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
    private JavaCodeParser javaCodeParser;

    @Resource
    private CallGraphIndex callGraphIndex;

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
    @SuppressWarnings("unchecked")
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
     * 处理 Push 事件到 master（合并后同步向量库 + 调用图索引）。
     * <p>
     * 合并到主分支后：
     * <ul>
     *   <li>删除的 Java 文件 → 清理 {@link CallGraphIndex} 中的引用</li>
     *   <li>新增/修改的 Java 文件 → 记录日志，需配合全量/增量导入流程更新向量库</li>
     * </ul>
     * 注：webhook payload 只含文件列表不含内容，增量更新需额外 git 操作。
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
        List<Map<String, Object>> commits = (List<Map<String, Object>>) body.get("commits");

        log.info("Push to {}: projectId={}, commits={}", ref, projectId, commits.size());

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

        // 删除的 Java 文件：从调用图索引中移除
        int cleanedCount = 0;
        for (String file : removedFiles) {
            if (file.endsWith(".java")) {
                // 从文件路径提取类名（文件名去除 .java 后缀）
                String fileName = file.substring(file.lastIndexOf('/') + 1);
                String className = fileName.replace(".java", "");

                // 尝试清理：移除可能的调用方引用（使用 best-effort 匹配）
                // 注意：精确清理需要知道完整类名（含包名）和方法签名，
                // 此处利用 CallGraphIndex 的模糊匹配做最大努力清理
                log.info("文件已删除，清理索引: projectId={}, file={}", projectId, file);
                callGraphIndex.removeCallersByFilePrefix(projectId, className);
                cleanedCount++;
            }
        }

        log.info("Push 同步完成: projectId={}, added={}, modified={}, removed={}, cleaned={}",
            projectId, addedFiles.size(), modifiedFiles.size(), removedFiles.size(), cleanedCount);

        return Map.of(
            "status", "synced",
            "projectId", projectId,
            "commitCount", commits.size(),
            "addedFiles", addedFiles.size(),
            "modifiedFiles", modifiedFiles.size(),
            "removedFiles", removedFiles.size(),
            "cleanedIndexEntries", cleanedCount
        );
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

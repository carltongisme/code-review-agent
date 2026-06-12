package org.example.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.api.config.WebhookProperties;
import org.example.domain.code.CodeDomainService;
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
import java.util.List;
import java.util.Map;

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

    private static final ObjectMapper mapper = new ObjectMapper();

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
            Map<String, Object> body = mapper.readValue(payload, Map.class);

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
        int prNumber = (int) pr.get("number");
        String headSha = (String) ((Map<String, Object>) pr.get("head")).get("sha");
        String baseSha = (String) ((Map<String, Object>) pr.get("base")).get("sha");

        log.info("PR #{}, projectId={}, action={}, head={}, base={}",
            prNumber, projectId, action, headSha, baseSha);

        // TODO: 调用 CodeReviewService.review(projectId, diff) → GitHubClient.submitReview()
        // 当前返回占位结果，审查服务在 Step 3 实现
        return Map.of(
            "status", "received",
            "projectId", projectId,
            "prNumber", prNumber,
            "message", "审查服务待实现（Step 3）"
        );
    }

    /**
     * 处理 Push 事件到 master（合并后同步向量库）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handlePush(Map<String, Object> body) {
        String ref = (String) body.get("ref");

        // 只响应 master 分支的 push
        if (!"refs/heads/master".equals(ref) && !"refs/heads/main".equals(ref)) {
            return Map.of("status", "skipped", "ref", ref);
        }

        Map<String, Object> repo = (Map<String, Object>) body.get("repository");
        String projectId = (String) repo.get("full_name");
        List<Map<String, Object>> commits = (List<Map<String, Object>>) body.get("commits");

        log.info("Push to master: projectId={}, commits={}", projectId, commits.size());

        // 提取所有变更文件
        for (Map<String, Object> commit : commits) {
            List<String> added = (List<String>) commit.get("added");
            List<String> modified = (List<String>) commit.get("modified");
            List<String> removed = (List<String>) commit.get("removed");

            // TODO: 对每个变更文件做处理
            // 新增/修改: parseFile → store
            // 删除: 从向量库移除 + 更新 CallGraphIndex
        }

        return Map.of(
            "status", "received",
            "projectId", projectId,
            "commitCount", commits.size(),
            "message", "同步逻辑待完善"
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

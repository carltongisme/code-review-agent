package org.example.repository.code;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.domain.code.model.CodeReviewResult;
import org.example.domain.code.service.CodeReviewSubmitService;
import org.example.repository.github.GitHubClient;
import org.springframework.stereotype.Service;

/**
 * {@link CodeReviewSubmitService} 的 GitHub 实现。
 */
@Slf4j
@Service
public class CodeReviewSubmitServiceImpl implements CodeReviewSubmitService {

    @Resource
    private GitHubClient gitHubClient;

    @Override
    public void submitReview(String projectId, int prNumber, CodeReviewResult result) {
        String[] parts = projectId.split("/", 2);
        if (parts.length != 2) {
            log.warn("无法解析 projectId，跳过提交: {}", projectId);
            return;
        }
        String body = "**Status: " + result.getStatus() + " | Risk: " + result.getRiskLevel() + "**\n\n" + result.getReason();
        gitHubClient.submitReview(parts[0], parts[1], prNumber, body, "COMMENT");
        log.info("GitHub Review 提交完成: {} PR#{} → {}", projectId, prNumber, result.getStatus());
    }
}
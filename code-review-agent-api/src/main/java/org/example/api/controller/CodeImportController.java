package org.example.api.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.domain.code.CodeDomainService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全量代码导入 API。
 * <p>
 * 接收 Git 仓库 URL，clone 到本地后触发全量解析及向量化入库。
 */
@Slf4j
@RestController
public class CodeImportController {

    @Resource
    private CodeDomainService codeDomainService;

    /** 从 Git URL 提取 "owner/repo" 的正则 */
    private static final Pattern GIT_URL_PATTERN =
        Pattern.compile("github\\.com[:/]([^/]+)/([^/]+?)(?:\\.git)?$");

    /**
     * 全量导入：传入 GitHub 仓库链接，自动拉取 → 解析 → 存储。
     *
     * @param body 请求体，字段: gitUrl (必填), projectId (选填，默认从URL提取)
     * @return 导入结果概要
     */
    @PostMapping("/api/projects/import")
    public Map<String, Object> importProject(@RequestBody Map<String, String> body) {
        String gitUrl = body.get("gitUrl");
        if (gitUrl == null || gitUrl.isBlank()) {
            throw new IllegalArgumentException("gitUrl 不能为空");
        }

        // 从 URL 提取 projectId（如 "user/repo"）
        String projectId = body.getOrDefault("projectId", extractProjectId(gitUrl));
        log.info("收到导入请求: gitUrl={}, projectId={}", gitUrl, projectId);

        Path tempDir = null;
        try {
            // 创建临时目录并 clone
            tempDir = Files.createTempDirectory("code-review-import-");
            log.info("git clone {} → {}", gitUrl, tempDir);
            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", gitUrl, tempDir.toString());
            pb.inheritIO();
            int exitCode = pb.start().waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("git clone 失败，exit code: " + exitCode);
            }

            // 触发全量导入
            int count = codeDomainService.importProject(projectId, tempDir);

            return Map.of(
                "projectId", projectId,
                "methodCount", count,
                "status", "success"
            );

        } catch (Exception e) {
            log.error("导入失败: {}", e.getMessage(), e);
            throw new RuntimeException("全量导入失败: " + e.getMessage(), e);
        } finally {
            // 清理临时目录
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 从 GitHub URL 提取 projectId。
     * <pre>
     * https://github.com/user/repo.git  →  user/repo
     * git@github.com:user/repo.git     →  user/repo
     * </pre>
     */
    static String extractProjectId(String gitUrl) {
        Matcher m = GIT_URL_PATTERN.matcher(gitUrl);
        if (m.find()) {
            return m.group(1) + "/" + m.group(2);
        }
        throw new IllegalArgumentException("无法从 URL 提取 projectId: " + gitUrl);
    }
}

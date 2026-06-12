package org.example.repository.github;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub API 连接配置。
 * <p>
 * Personal Access Token 用于认证 API 调用（提交 Review Comment）。
 */
@Data
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {

    /** GitHub Personal Access Token（需 repo scope） */
    private String token;

    /** GitHub API 基础地址（GitHub Enterprise 可自定义） */
    private String apiUrl = "https://api.github.com";
}

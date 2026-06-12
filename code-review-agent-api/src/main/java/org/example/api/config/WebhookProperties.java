package org.example.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Webhook 安全配置。
 * <p>
 * GitHub Webhook 需要 HMAC-SHA256 签名校验。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "webhook")
public class WebhookProperties {

    /** GitHub Webhook secret，用于校验 X-Hub-Signature-256 */
    private String secret;
}

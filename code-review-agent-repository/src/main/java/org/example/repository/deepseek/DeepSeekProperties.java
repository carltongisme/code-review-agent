package org.example.repository.deepseek;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * DeepSeek API 连接配置。
 * <p>
 * 所有属性均可通过 application.properties 中的 {@code deepseek.*} 前缀覆盖。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekProperties {

    /** DeepSeek API 基础地址 */
    private String baseUrl = "https://api.deepseek.com";

    /** API Key（必填），从环境变量或配置文件注入 */
    private String apiKey;

    /** 模型名称 */
    private String model = "deepseek-chat";

    /** 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** 读取超时，大模型响应可能较慢 */
    private Duration readTimeout = Duration.ofSeconds(60);
}

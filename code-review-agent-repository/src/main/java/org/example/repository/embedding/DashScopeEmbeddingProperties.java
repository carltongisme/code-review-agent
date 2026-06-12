package org.example.repository.embedding;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 阿里云 DashScope Embedding API 配置。
 * <p>
 * 所有属性均可通过 application.properties 中的 {@code dashscope.embedding.*} 前缀覆盖。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "dashscope.embedding")
public class DashScopeEmbeddingProperties {

    /** API 端点 */
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    /** API Key（必填） */
    private String apiKey;

    /** 模型名称，text-embedding-v2 输出 1536 维 */
    private String model = "text-embedding-v2";

    /** 文本类型：document 或 query */
    private String textType = "document";

    /** 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** 读取超时 */
    private Duration readTimeout = Duration.ofSeconds(30);
}

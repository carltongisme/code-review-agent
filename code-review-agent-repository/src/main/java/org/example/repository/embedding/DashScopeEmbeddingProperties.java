package org.example.repository.embedding;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

import com.alibaba.dashscope.embeddings.TextEmbedding;

/**
 * 阿里云 DashScope Embedding API 配置。
 * <p>
 * 所有属性均可通过 application.properties 中的 {@code dashscope.embedding.*} 前缀覆盖。
 */
@Data
@ConfigurationProperties(prefix = "dashscope.embedding")
public class DashScopeEmbeddingProperties {

    /** API 端点 */
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    /** API Key（必填） */
    private String apiKey;

    /** 模型名称，text-embedding-v4 输出维度可配 */
    private String model = TextEmbedding.Models.TEXT_EMBEDDING_V4;

    /** 向量维度，text-embedding-v4 默认 1024 */
    private int dimension = 1024;

    /** 文本类型：query（查询）或 document（文档，默认） */
    private String textType = "document";

    /** 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** 读取超时 */
    private Duration readTimeout = Duration.ofSeconds(30);
}

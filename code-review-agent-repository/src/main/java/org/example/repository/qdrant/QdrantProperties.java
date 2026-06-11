package org.example.repository.qdrant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Qdrant 连接配置。
 * <p>
 * 所有属性均可通过 application.properties 中的 {@code qdrant.*} 前缀覆盖。
 */
@Data
@ConfigurationProperties(prefix = "qdrant")
public class QdrantProperties {

    /** Qdrant 服务地址，默认 localhost */
    private String host = "localhost";

    /** gRPC 端口，默认 6334 */
    private int port = 6334;

    /** 是否启用 TLS（Qdrant Cloud 需设为 true） */
    private boolean useTls = false;

    /** API Key（Qdrant Cloud 需要），本地开发可留空 */
    private String apiKey;

    /** Qdrant 集合名称 */
    private String collectionName = "code_review_agent";

    /** 向量维度，需与 embedding 模型输出维度一致，默认 1536 */
    private int vectorSize = 1536;
}

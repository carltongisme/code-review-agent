package org.example.repository.qdrant;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Qdrant 客户端 Spring 自动配置。
 * <p>
 * 通过 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 * 被 Spring Boot 自动发现，无需在主应用中显式 import。
 */
@AutoConfiguration
@EnableConfigurationProperties(QdrantProperties.class)
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(QdrantProperties properties) {
        QdrantGrpcClient.Builder grpcBuilder = QdrantGrpcClient.newBuilder(
            properties.getHost(),
            properties.getPort(),
            properties.isUseTls()
        );

        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            grpcBuilder.withApiKey(properties.getApiKey());
        }

        return new QdrantClient(grpcBuilder.build());
    }

    @Bean
    public QdrantCodeVectorStore qdrantCodeVectorStore(
        QdrantClient qdrantClient,
        QdrantProperties properties
    ) {
        return new QdrantCodeVectorStore(qdrantClient, properties);
    }
}

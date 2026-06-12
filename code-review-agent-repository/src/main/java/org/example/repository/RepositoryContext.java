package org.example.repository;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.example.repository.deepseek.DeepSeekClient;
import org.example.repository.deepseek.DeepSeekProperties;
import org.example.repository.qdrant.QdrantCodeRepositoryImpl;
import org.example.repository.qdrant.QdrantProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Repository 层统一配置 —— 集中管理本模块所有 @Bean。
 *
 * @date 06-12-2026
 */
@AutoConfiguration
@EnableConfigurationProperties({QdrantProperties.class, DeepSeekProperties.class})
public class RepositoryContext {

    // ── Qdrant ──

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
    public QdrantCodeRepositoryImpl qdrantCodeVectorStore(
            QdrantClient qdrantClient,
            QdrantProperties properties) {
        return new QdrantCodeRepositoryImpl(qdrantClient, properties);
    }

    // ── DeepSeek ──

    @Bean
    public DeepSeekClient deepSeekClient(DeepSeekProperties properties) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException(
                "deepseek.api-key 未配置，请在 application.properties 中设置");
        }
        return new DeepSeekClient(properties);
    }
}

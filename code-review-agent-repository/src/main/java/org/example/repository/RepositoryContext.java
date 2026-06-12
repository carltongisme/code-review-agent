package org.example.repository;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.example.repository.deepseek.DeepSeekClient;
import org.example.repository.code.CodeDeepSeekAnalysisService;
import org.example.repository.deepseek.DeepSeekProperties;
import org.example.repository.embedding.AliyunEmbeddingService;
import org.example.repository.embedding.DashScopeEmbeddingClient;
import org.example.repository.embedding.DashScopeEmbeddingProperties;
import org.example.domain.code.parser.JavaCodeParser;
import org.example.domain.code.service.CallGraphIndex;
import org.example.repository.code.CodeQdrantRepository;
import org.example.repository.code.CodeReviewService;
import org.example.repository.github.GitHubClient;
import org.example.repository.github.GitHubProperties;
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
@EnableConfigurationProperties({QdrantProperties.class, DeepSeekProperties.class, DashScopeEmbeddingProperties.class, GitHubProperties.class})
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
    public CodeQdrantRepository qdrantCodeVectorStore(
            QdrantClient qdrantClient,
            QdrantProperties properties) {
        return new CodeQdrantRepository(qdrantClient, properties);
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

    @Bean
    public CodeDeepSeekAnalysisService deepSeekCodeAnalysisService(DeepSeekClient client) {
        return new CodeDeepSeekAnalysisService(client);
    }

    // ── DashScope Embedding ──

    @Bean
    public DashScopeEmbeddingClient dashScopeEmbeddingClient(DashScopeEmbeddingProperties properties) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException(
                "dashscope.embedding.api-key 未配置，请在 application.properties 中设置");
        }
        return new DashScopeEmbeddingClient(properties);
    }

    @Bean
    public AliyunEmbeddingService aliyunEmbeddingService(DashScopeEmbeddingClient client) {
        return new AliyunEmbeddingService(client);
    }

    // ── AST 解析 + 调用图 ──

    @Bean
    public JavaCodeParser javaCodeParser() {
        return new JavaCodeParser();
    }

    @Bean
    public CallGraphIndex callGraphIndex() {
        return new CallGraphIndex();
    }

    // ── GitHub + 审查 ──

    @Bean
    public CodeReviewService codeReviewService() {
        return new CodeReviewService();
    }

    @Bean
    public GitHubClient gitHubClient(GitHubProperties properties) {
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            throw new IllegalStateException(
                "github.token 未配置，请在 application.properties 中设置");
        }
        return new GitHubClient(properties);
    }
}

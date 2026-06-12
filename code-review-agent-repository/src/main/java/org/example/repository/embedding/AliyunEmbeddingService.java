package org.example.repository.embedding;

import lombok.extern.slf4j.Slf4j;
import org.example.domain.embedding.EmbeddingService;

import java.util.List;

/**
 * 基于阿里云 DashScope 的 {@link EmbeddingService} 实现。
 */
@Slf4j
public class AliyunEmbeddingService implements EmbeddingService {

    private final DashScopeEmbeddingClient client;

    public AliyunEmbeddingService(DashScopeEmbeddingClient client) {
        this.client = client;
    }

    @Override
    public List<Float> embed(String text) {
        log.debug("Embedding 向量化: {} 字符", text.length());
        return client.embed(text);
    }
}

package org.example.repository.embedding;

/**
 * DashScope Embedding API 调用异常。
 */
public class DashScopeEmbeddingException extends RuntimeException {

    public DashScopeEmbeddingException(String message) {
        super(message);
    }

    public DashScopeEmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}

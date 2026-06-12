package org.example.repository.embedding;

import org.example.common.utils.JsonUtils;
import org.example.repository.embedding.dto.EmbeddingResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;

/**
 * 阿里云 DashScope Embedding HTTP 客户端。
 * <p>
 * 调用 DashScope text-embedding API，返回浮点数向量。
 */
@Slf4j
public class DashScopeEmbeddingClient {
    private final DashScopeEmbeddingProperties properties;

    private final HttpClient httpClient;

    public DashScopeEmbeddingClient(DashScopeEmbeddingProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .build();
    }

    /**
     * 生成文本向量。
     *
     * @param text 待向量化的文本
     * @return 浮点数向量（1536 维）
     * @throws DashScopeEmbeddingException 调用失败时抛出
     */
    public List<Float> embed(String text) {
        Map<String, Object> requestBody = Map.of(
            "model", properties.getModel(),
            "input", Map.of("texts", List.of(text)),
            "parameters", Map.of("text_type", properties.getTextType())
        );

        String jsonPayload;
        try {
            jsonPayload = JsonUtils.toJson(requestBody);
        } catch (Exception e) {
            throw new DashScopeEmbeddingException("序列化请求失败", e);
        }

        log.debug("DashScope embedding 请求: model={}, textLen={}",
            properties.getModel(), text.length());

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(properties.getBaseUrl()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + properties.getApiKey())
            .timeout(properties.getReadTimeout())
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new DashScopeEmbeddingException(
                "Embedding API 请求发送失败: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            String body = response.body();
            throw new DashScopeEmbeddingException(
                "Embedding API 返回错误: HTTP " + response.statusCode()
                    + ", body: " + (body != null && body.length() > 500
                        ? body.substring(0, 500) + "…" : body));
        }

        EmbeddingResponse result;
        try {
            result = JsonUtils.fromJson(response.body(), EmbeddingResponse.class);
        } catch (Exception e) {
            throw new DashScopeEmbeddingException("解析响应失败", e);
        }

        if (result.getOutput() == null
            || result.getOutput().getEmbeddings() == null
            || result.getOutput().getEmbeddings().isEmpty()) {
            throw new DashScopeEmbeddingException("Embedding API 返回空向量");
        }

        List<Float> embedding = result.getOutput().getEmbeddings().getFirst().getEmbedding();
        log.debug("DashScope embedding 完成: 维度={}, tokens={}",
            embedding.size(),
            result.getUsage() != null ? result.getUsage().getTotalTokens() : -1);
        return embedding;
    }
}

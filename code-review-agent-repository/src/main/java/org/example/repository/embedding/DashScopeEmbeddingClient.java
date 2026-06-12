package org.example.repository.embedding;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 阿里云 DashScope Embedding SDK 客户端。
 * <p>
 * 使用官方 {@code dashscope-sdk-java}，调用 {@code text-embedding-v4} 模型生成向量。
 */
@Slf4j
public class DashScopeEmbeddingClient {

    private final DashScopeEmbeddingProperties properties;
    private final TextEmbedding textEmbedding;

    public DashScopeEmbeddingClient(DashScopeEmbeddingProperties properties) {
        this.properties = properties;

        // SDK API Key
        System.setProperty("DASHSCOPE_API_KEY", properties.getApiKey());

        // 支持自定义 base URL（新加坡地域等）
        if (properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()) {
            Constants.baseHttpApiUrl = properties.getBaseUrl();
        }

        this.textEmbedding = new TextEmbedding();
    }

    /**
     * 生成文本向量。
     *
     * @param text 待向量化的文本
     * @return 浮点数向量
     * @throws DashScopeEmbeddingException 调用失败时抛出
     */
    public List<Float> embed(String text) {
        TextEmbeddingParam.TextType textType = "query".equals(properties.getTextType())
            ? TextEmbeddingParam.TextType.QUERY
            : TextEmbeddingParam.TextType.DOCUMENT;

        TextEmbeddingParam param = TextEmbeddingParam.builder()
            .model(properties.getModel())
            .texts(Collections.singletonList(text))
            .dimension(properties.getDimension())
            .textType(textType)
            .build();

        log.debug("DashScope embedding 请求: model={}, dimension={}, textLen={}",
            properties.getModel(), properties.getDimension(), text.length());

        TextEmbeddingResult result;
        try {
            result = textEmbedding.call(param);
        } catch (NoApiKeyException e) {
            throw new DashScopeEmbeddingException("DashScope API Key 未配置", e);
        } catch (Exception e) {
            throw new DashScopeEmbeddingException(
                "Embedding API 调用失败: " + e.getMessage(), e);
        }

        if (result.getOutput() == null
            || result.getOutput().getEmbeddings() == null
            || result.getOutput().getEmbeddings().isEmpty()) {
            throw new DashScopeEmbeddingException("Embedding API 返回空向量");
        }

        List<Float> embedding = result.getOutput().getEmbeddings().getFirst()
            .getEmbedding().stream()
            .map(Number::floatValue)
            .collect(Collectors.toList());

        log.debug("DashScope embedding 完成: 维度={}, tokens={}",
            embedding.size(),
            result.getUsage() != null ? result.getUsage().getTotalTokens() : -1);
        return embedding;
    }
}

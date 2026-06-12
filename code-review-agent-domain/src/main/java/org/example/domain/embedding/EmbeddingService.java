package org.example.domain.embedding;

import java.util.List;

/**
 * 文本向量化端口 —— 将文本转为浮点数向量。
 */
@FunctionalInterface
public interface EmbeddingService {

    /**
     * 生成向量嵌入。
     *
     * @param text 待向量化的文本
     * @return 向量嵌入
     */
    List<Float> embed(String text);
}

package org.example.domain.vector;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 代码向量实体 —— 表示一个待存储或已检索的方法级代码块。
 * <p>
 * 包含物理坐标、方法用途描述、原始源代码和向量嵌入，
 * 是领域层与向量数据库之间交换的最小单元。
 */
@Data
@Builder
public class CodeVectorEntity {

    /** 物理坐标（文件路径 + 类名 + 方法签名） */
    private PhysicalCoordinate coordinate;

    /** 方法用途的自然语言描述，用于向量语义检索 */
    private String methodPurpose;

    /** 原始方法源代码 */
    private String sourceCode;

    /** 向量嵌入（由外部 embedding 模型生成后传入） */
    private List<Float> embedding;

    /**
     * 构建用于向量化的完整文本，硬拼接物理坐标。
     * <p>
     * 格式：<br>
     * 【文件路径】【所属类名】【方法签名】###【方法用途】<br>
     * &lt;完整源代码&gt;
     * <p>
     * 物理坐标被硬编码在文本中，确保 LLM 检索时无需额外查询即可获得代码定位信息。
     *
     * @return 包含物理坐标的完整向量化文本
     */
    public String buildVectorText() {
        return String.format("【%s】【%s】【%s】###【%s】\n%s",
            coordinate.filePath(),
            coordinate.className(),
            coordinate.methodSignature(),
            methodPurpose != null ? methodPurpose : "",
            sourceCode != null ? sourceCode : "");
    }
}

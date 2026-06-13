package org.example.domain.code.domain;

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
public class CodeDomain {

    /** 物理坐标（文件路径 + 类名 + 方法签名） */
    private CodeDomainPhysical coordinate;

    /** 方法用途的自然语言描述，用于向量语义检索 */
    private String methodPurpose;

    /** 原始方法源代码 */
    private String sourceCode;

    /** 向量嵌入（由外部 embedding 模型生成后传入） */
    private List<Float> embedding;

    /**
     * 构建用于 embedding 向量化的文本。
     * <p>
     * 格式：<br>
     * 【方法用途注释】：XXX<br>
     * 【类名】：XXX<br>
     * 【方法名】：XXX<br>
     * 【完整源码】：XXX
     *
     * @return 用于生成向量的结构化文本
     */
    public String buildVectorText() {
        return "【方法用途注释】：" + (methodPurpose != null ? methodPurpose : "") + "\n"
            + "【类名】：" + coordinate.getClassName() + "\n"
            + "【方法名】：" + coordinate.getMethodSignature() + "\n"
            + "【完整源码】：" + (sourceCode != null ? sourceCode : "");
    }
}

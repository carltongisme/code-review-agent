package org.example.domain.code.service;

/**
 * 代码语义分析端口 —— 传入方法源码，返回用途描述。
 */
@FunctionalInterface
public interface CodeAnalysisService {

    /**
     * 分析方法源码，返回自然语言的用途描述。
     *
     * @param sourceCode 完整的方法源码
     * @return 方法用途的简洁中文描述
     */
    String analyzePurpose(String sourceCode);
}

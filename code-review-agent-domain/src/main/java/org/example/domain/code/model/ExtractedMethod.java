package org.example.domain.code.model;

import java.util.List;

/**
 * 从 Java 源码中提取出的方法级代码块。
 *
 * @param className        所属类名（不含包名）
 * @param methodSignature  方法签名（含参数类型），如 "login(String,String)"
 * @param sourceCode       方法完整源码
 * @param calledMethods    该方法体内调用的其他方法签名列表，
 *                         格式为 "ClassName::methodSignature"
 */
public record ExtractedMethod(
    String className,
    String methodSignature,
    String sourceCode,
    List<String> calledMethods
) {}

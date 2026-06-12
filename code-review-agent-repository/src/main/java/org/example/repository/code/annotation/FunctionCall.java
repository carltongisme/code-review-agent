package org.example.repository.code.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为 LLM 可调用的审查工具。
 * <p>
 * 与 {@link FunctionCallParam} 配合，通过反射自动生成 DeepSeek Function Call 的 JSON Schema。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface FunctionCall {

    /** 工具名称（LLM 通过此名称调用） */
    String name();

    /** 工具用途描述（告知 LLM 何时使用此工具） */
    String description();
}

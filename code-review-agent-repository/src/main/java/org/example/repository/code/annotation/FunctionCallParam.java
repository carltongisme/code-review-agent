package org.example.repository.code.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Function Call 工具方法的参数元数据。
 * <p>
 * 用于生成 JSON Schema 中的 properties 定义。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface FunctionCallParam {

    /** 参数名 */
    String name();

    /** 参数描述 */
    String description();

    /** 是否必填，默认 true */
    boolean required() default true;
}

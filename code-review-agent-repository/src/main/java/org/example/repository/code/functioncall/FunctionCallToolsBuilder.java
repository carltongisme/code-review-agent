package org.example.repository.code.functioncall;

import org.example.repository.code.annotation.FunctionCall;
import org.example.repository.code.annotation.FunctionCallParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过反射扫描 {@link FunctionCall} 注解，生成 DeepSeek Function Call JSON Schema。
 */
public final class FunctionCallToolsBuilder {

    private FunctionCallToolsBuilder() {}

    /**
     * 从目标对象中扫描 @FunctionCall 方法，构建工具定义列表。
     */
    public static List<Map<String, Object>> build(Object target) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Method m : target.getClass().getDeclaredMethods()) {
            FunctionCall fc = m.getAnnotation(FunctionCall.class);
            if (fc == null) continue;

            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Parameter p : m.getParameters()) {
                FunctionCallParam fcp = p.getAnnotation(FunctionCallParam.class);
                if (fcp == null) continue;
                properties.put(fcp.name(), Map.of("type", "string", "description", fcp.description()));
                if (fcp.required()) required.add(fcp.name());
            }

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("type", "object");
            params.put("properties", properties);
            if (!required.isEmpty()) params.put("required", required);

            tools.add(Map.of(
                "type", "function",
                "function", Map.of("name", fc.name(), "description", fc.description(),
                    "parameters", params)));
        }
        return tools;
    }
}

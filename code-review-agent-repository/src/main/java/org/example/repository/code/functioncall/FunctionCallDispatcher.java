package org.example.repository.code.functioncall;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.example.common.utils.JsonUtils;
import org.example.repository.code.annotation.FunctionCall;
import org.example.repository.code.annotation.FunctionCallParam;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

/**
 * Function Call 反射路由器。
 * <p>
 * 支持两类参数：
 * <ul>
 *   <li><b>工具参数</b>：标注 {@link FunctionCallParam}，由 LLM 的 args JSON 提供值</li>
 *   <li><b>上下文参数</b>：无 {@link FunctionCallParam} 注解，由调用方通过 context Map 注入（如 projectId）</li>
 * </ul>
 */
@Slf4j
@Service
public class FunctionCallDispatcher {

    private final Map<String, MethodContext> registry = new HashMap<>();

    /** 注册目标对象中所有带 @FunctionCall 注解的方法。 */
    public void register(Object instance) {
        for (Method method : instance.getClass().getDeclaredMethods()) {
            FunctionCall fc = method.getAnnotation(FunctionCall.class);
            if (fc == null) continue;
            registry.put(fc.name(), new MethodContext(instance, method));
            log.debug("注册工具: {} → {}", fc.name(), method.getName());
        }
    }

    /**
     * 根据工具名和 JSON 参数反射调用。
     *
     * @param toolName  工具名
     * @param argsJson  LLM 传回的参数字符串
     * @param context   上下文参数（如 projectId），注入到无 @FunctionCallParam 的方法参数
     * @return 方法返回的字符串结果
     */
    public String dispatch(String toolName, String argsJson, Map<String, Object> context) {
        MethodContext ctx = registry.get(toolName);
        if (ctx == null) return "错误: 未知工具 '" + toolName + "'";

        try {
            Map<String, Object> argsMap = argsJson != null && !argsJson.isBlank()
                ? JsonUtils.fromJson(argsJson, new TypeReference<>() {})
                : Map.of();

            Object[] invokeArgs = new Object[ctx.method.getParameterCount()];

            for (int i = 0; i < invokeArgs.length; i++) {
                Parameter param = ctx.method.getParameters()[i];

                if (param.isAnnotationPresent(FunctionCallParam.class)) {
                    // LLM 传入的工具参数
                    Object rawValue = argsMap.get(param.getAnnotation(FunctionCallParam.class).name());
                    invokeArgs[i] = rawValue != null
                        ? JsonUtils.fromJson(rawValue, param.getType())
                        : null;
                } else {
                    // 上下文参数（调用方注入）
                    invokeArgs[i] = context.getOrDefault(param.getName(), null);
                }
            }

            return (String) ctx.method.invoke(ctx.instance, invokeArgs);
        } catch (Exception e) {
            log.error("工具执行异常: {} / {}", toolName, e.getMessage());
            return "错误: 工具执行失败 - " + (e.getCause() != null
                ? e.getCause().getMessage() : e.getMessage());
        }
    }

    private record MethodContext(Object instance, Method method) {}
}

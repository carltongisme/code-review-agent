package org.example.domain.code.service;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek Function Call 工具 JSON 定义。
 * <p>
 * 审查时 LLM 通过这些工具自主查找代码上下文。
 */
public final class ReviewToolDefinitions {

    private ReviewToolDefinitions() {}

    /**
     * @return 审查阶段可用的 Function Call 工具定义列表
     */
    public static List<Map<String, Object>> build() {
        return List.of(
            // ── 查找调用方（上游） ──
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "lookup_callers",
                    "description", "查找哪些方法调用了指定的方法。用于评估修改对上游调用方的影响。",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "methodSignature", Map.of(
                                "type", "string",
                                "description", "方法签名，格式：ClassName::methodName(paramTypes)，如 UserService::login(String,String)")
                        ),
                        "required", List.of("methodSignature")
                    )
                )
            ),
            // ── 查找被调用方（下游） ──
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "lookup_callees",
                    "description", "查找指定方法体内调用了哪些其他方法。用于评估修改对下游的影响。",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "methodSignature", Map.of(
                                "type", "string",
                                "description", "方法签名，格式：ClassName::methodName(paramTypes)")
                        ),
                        "required", List.of("methodSignature")
                    )
                )
            ),
            // ── 精确查找代码 ──
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "lookup_code",
                    "description", "根据物理坐标精确查找方法的完整源码和用途描述。",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "filePath", Map.of("type", "string", "description", "文件路径"),
                            "className", Map.of("type", "string", "description", "类名"),
                            "methodSignature", Map.of("type", "string", "description", "方法签名")
                        ),
                        "required", List.of("filePath", "className", "methodSignature")
                    )
                )
            ),
            // ── 语义相似搜索 ──
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "search_similar",
                    "description", "语义搜索向量数据库中功能相近的代码。用于参考项目中类似的实现模式。",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "query", Map.of("type", "string", "description", "自然语言描述，如"查找认证失败处理逻辑"")
                        ),
                        "required", List.of("query")
                    )
                )
            )
        );
    }
}

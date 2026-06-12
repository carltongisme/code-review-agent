package org.example.repository.code;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.domain.code.domain.CodeDomain;
import org.example.domain.code.domain.CodeDomainPhysical;
import org.example.domain.code.parser.JavaCodeParser;
import org.example.domain.code.service.CallGraphIndex;
import org.example.domain.code.service.CodeRepository;
import org.example.domain.code.service.ReviewToolDefinitions;
import org.example.domain.embedding.EmbeddingService;
import org.example.repository.deepseek.DeepSeekClient;
import org.example.repository.deepseek.dto.DeepSeekChatMessage;
import org.example.repository.deepseek.dto.DeepSeekResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 代码审查领域服务。
 * <p>
 * 编排 LLM + 调用图 + 向量库，完成变更代码的审查：
 * <ol>
 *   <li>将 diff 传给 DeepSeek，附带 Function Call 工具</li>
 *   <li>LLM 自主决定何时调用工具（lookup_callers / lookup_callees / lookup_code / search_similar）</li>
 *   <li>服务执行工具调用，将结果追加回对话</li>
 *   <li>LLM 综合所有信息，给出审查结论（通过/拒绝 + 原因）</li>
 * </ol>
 */
@Slf4j
public class CodeReviewService {

    /** Function Call 最大交互轮数（防止死循环） */
    private static final int MAX_TOOL_ROUNDS = 5;

    @Resource
    private DeepSeekClient deepSeekClient;

    @Resource
    private CallGraphIndex callGraphIndex;

    @Resource
    private CodeRepository codeRepository;

    @Resource
    private JavaCodeParser javaCodeParser;

    @Resource
    private EmbeddingService embeddingService;

    /**
     * 审查代码变更。
     *
     * @param projectId 项目 ID
     * @param diff      变更 diff 文本（GitHub PR diff）
     * @return LLM 审查意见原文
     */
    public String review(String projectId, String diff) {
        // 构建初始消息: system prompt + diff
        List<DeepSeekChatMessage> messages = new ArrayList<>();
        messages.add(DeepSeekChatMessage.system("""
            你是 Code Review Agent，负责审查 Java 项目的代码变更。

            审查要点：
            1. Bug 风险：空指针、线程安全、资源泄漏、边界条件
            2. 安全问题：注入攻击、敏感信息泄露、权限校验缺失
            3. 性能问题：不必要的对象创建、循环内 IO、N+1 查询
            4. 影响分析：使用工具查找上游调用方和下游被调用方，评估改动影响范围

            审查流程：
            - 先用 lookup_callers 查哪些上游方法调用了变更方法
            - 先用 lookup_callees 查变更方法调用了哪些下游方法
            - 需要了解更多上下文时用 lookup_code 精确查找
            - 需要参考类似实现时用 search_similar 语义搜索

            最后给出：
            1. 审查结论：✅ 通过 或 ❌ 拒绝
            2. 拒绝时必须给出具体原因和建议修改方式
            3. 风险等级：🟢低 / 🟡中 / 🔴高
            """));

        messages.add(DeepSeekChatMessage.user("审查以下代码变更（projectId: " + projectId + "）:\n\n" + diff));

        // 多轮 Function Call 交互
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            DeepSeekResponse response = deepSeekClient.chatWithTools(
                messages, ReviewToolDefinitions.build());

            var choice = response.getChoices().getFirst();
            var msg = choice.getMessage();

            // LLM 完成审查，返回最终结论
            if ("stop".equals(choice.getFinishReason()) && msg.getToolCalls() == null) {
                log.info("审查完成: projectId={}, tokens={}",
                    projectId,
                    response.getUsage() != null ? response.getUsage().getTotalTokens() : -1);
                return msg.getContent();
            }

            // LLM 请求调用工具 → 执行并追加结果
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                // 将 LLM 的 tool_calls 响应消息添加到对话历史
                messages.add(msg);

                // 执行每个工具调用
                for (var toolCall : msg.getToolCalls()) {
                    String toolResult = executeToolCall(projectId,
                        toolCall.getFunction().getName(),
                        toolCall.getFunction().getArguments());

                    messages.add(DeepSeekChatMessage.toolResult(
                        toolResult, toolCall.getId()));
                }
                continue;
            }

            // 其他情况，直接返回当前内容
            return msg.getContent() != null ? msg.getContent() : "审查未产生结论";
        }

        return "审查超时：已进行 " + MAX_TOOL_ROUNDS + " 轮工具调用，仍未得出结论";
    }

    /**
     * 执行 LLM 请求的工具调用，返回结果文本。
     */
    private String executeToolCall(String projectId, String toolName, String arguments) {
        log.debug("执行工具调用: {} args={}", toolName, arguments);

        return switch (toolName) {
            case "lookup_callers" -> handleLookupCallers(projectId, arguments);
            case "lookup_callees" -> handleLookupCallees(projectId, arguments);
            case "lookup_code" -> handleLookupCode(projectId, arguments);
            case "search_similar" -> handleSearchSimilar(projectId, arguments);
            default -> "未知工具: " + toolName;
        };
    }

    /**
     * 查找上游调用方：谁调用了这个方法？
     */
    private String handleLookupCallers(String projectId, String args) {
        // 从 arguments JSON 字符串中提取 methodSignature
        String methodSignature = extractJsonField(args, "methodSignature");
        if (methodSignature == null) return "错误: 缺少 methodSignature 参数";

        Set<String> callers = callGraphIndex.findCallers(projectId, methodSignature);

        if (callers.isEmpty()) {
            return "方法 " + methodSignature + " 无上游调用方（或调用图索引中未记录）";
        }

        StringBuilder sb = new StringBuilder("【上游调用方】以下方法调用了 ")
            .append(methodSignature).append(":\n");
        for (String callerId : callers) {
            // callerId 格式: projectId::ClassName::methodSig
            String[] parts = callerId.split("::", 3);
            if (parts.length >= 3) {
                Optional<CodeDomain> entity = codeRepository.searchPhysical(
                    new CodeDomainPhysical(projectId, "", parts[1], parts[2]));
                entity.ifPresentOrElse(
                    e -> sb.append("- ").append(parts[1]).append("::").append(parts[2])
                        .append(" → 用途: ").append(e.getMethodPurpose()).append("\n"),
                    () -> sb.append("- ").append(parts[1]).append("::").append(parts[2])
                        .append(" (源码未找到)\n")
                );
            }
        }
        return sb.toString();
    }

    /**
     * 查找下游被调用方：这个方法调了谁？
     * <p>
     * 按需解析：从向量库取该方法源码，用 JavaParser 提取被调方法列表。
     */
    private String handleLookupCallees(String projectId, String args) {
        String methodSignature = extractJsonField(args, "methodSignature");
        if (methodSignature == null) return "错误: 缺少 methodSignature 参数";

        // methodSignature 格式: ClassName::methodName(paramTypes)
        String[] parts = methodSignature.split("::", 2);
        if (parts.length < 2) return "错误: methodSignature 格式应为 ClassName::methodName(paramTypes)";

        // 从向量库获取该方法源码
        Optional<CodeDomain> entity = codeRepository.searchPhysical(
            new CodeDomainPhysical(projectId, "", parts[0], parts[1]));
        if (entity.isEmpty()) return "未找到方法: " + methodSignature;

        // 用 JavaParser 解析源码，提取被调方法
        List<String> callees = javaCodeParser.extractCalledMethods(entity.get().getSourceCode());

        if (callees.isEmpty()) {
            return "方法 " + methodSignature + " 未调用其他方法";
        }

        StringBuilder sb = new StringBuilder("【下游被调用】方法 ")
            .append(methodSignature).append(" 调用了以下方法:\n");
        for (String callee : callees) {
            sb.append("- ").append(callee).append("\n");
        }
        return sb.toString();
    }

    /**
     * 精确查找代码：按物理坐标从向量库检索。
     */
    private String handleLookupCode(String projectId, String args) {
        String filePath = extractJsonField(args, "filePath");
        String className = extractJsonField(args, "className");
        String methodSignature = extractJsonField(args, "methodSignature");
        if (filePath == null || className == null || methodSignature == null)
            return "错误: 缺少文件路径/类名/方法签名";

        Optional<CodeDomain> entity = codeRepository.searchPhysical(
            new CodeDomainPhysical(projectId, filePath, className, methodSignature));

        return entity.map(e -> "【代码详情】\n"
            + "物理坐标: " + filePath + " / " + className + " / " + methodSignature + "\n"
            + "方法用途: " + e.getMethodPurpose() + "\n"
            + "源码:\n" + e.getSourceCode())
            .orElse("未找到代码: " + filePath + " / " + className + " / " + methodSignature);
    }

    /**
     * 语义相似搜索：向量检索功能相近的代码。
     */
    private String handleSearchSimilar(String projectId, String args) {
        String query = extractJsonField(args, "query");
        if (query == null) return "错误: 缺少 query 参数";

        List<Float> queryEmbedding = embeddingService.embed(query);
        List<CodeDomain> results = codeRepository.searchSimilar(queryEmbedding, 5, projectId);

        if (results.isEmpty()) return "未找到与「" + query + "」语义相似的代码";

        StringBuilder sb = new StringBuilder("【语义相似代码】查询「")
            .append(query).append("」的相似结果:\n");
        for (CodeDomain entity : results) {
            sb.append("- ").append(entity.getCoordinate().className())
                .append("::").append(entity.getCoordinate().methodSignature())
                .append(" → ").append(entity.getMethodPurpose()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 简单的 JSON 字段提取（避免额外依赖 JSON 解析库，参数由 LLM 生成格式较为规整）。
     */
    private String extractJsonField(String jsonLike, String fieldName) {
        // 匹配 "fieldName": "value" 或 "fieldName":"value"
        String pattern = "\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern)
            .matcher(jsonLike);
        return m.find() ? m.group(1) : null;
    }
}

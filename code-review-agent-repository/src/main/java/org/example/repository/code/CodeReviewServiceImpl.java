package org.example.repository.code;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.common.utils.JsonUtils;
import org.example.domain.code.model.CodeReviewResult;
import org.example.domain.code.service.CodeReviewService;
import org.example.repository.code.functioncall.FunctionCallDispatcher;
import org.example.repository.code.functioncall.FunctionCallTools;
import org.example.repository.code.functioncall.FunctionCallToolsBuilder;
import org.example.repository.deepseek.DeepSeekClient;
import org.example.repository.deepseek.dto.DeepSeekChatMessage;
import org.example.repository.deepseek.dto.DeepSeekResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link CodeReviewService} 实现。
 * <p>
 * 编排 LLM + 工具调用 + 向量库，完成变更代码审查。
 * Function Call 工具逻辑解耦到 {@link FunctionCallTools}，调度解耦到 {@link FunctionCallDispatcher}。
 */
@Slf4j
@Service
public class CodeReviewServiceImpl implements CodeReviewService {

    private static final int MAX_TOOL_ROUNDS = 5;

    @Resource
    private DeepSeekClient deepSeekClient;

    @Resource
    private FunctionCallTools toolbox;

    @Resource
    private FunctionCallDispatcher dispatcher;

    @Override
    public CodeReviewResult review(String projectId, String diff) {
        // 上下文参数：projectId 由 dispatcher 自动注入到工具方法
        Map<String, Object> context = Map.of("projectId", projectId);

        // 确保工具已注册
        dispatcher.register(toolbox);

        // 构建对话消息
        List<DeepSeekChatMessage> messages = new ArrayList<>();
        messages.add(DeepSeekChatMessage.system("""
            你是 Code Review Agent，负责审查 Java 项目的代码变更。
            审查要点：Bug 风险、安全问题、性能问题、影响分析。
            使用工具查找上游调用方和下游被调用方，评估改动影响范围。

            完成审查后，你必须输出一个 JSON 对象（不要包含 markdown 标记或任何其他文字），
            严格按照以下 JSON Schema：
            {
              "status": "APPROVED 或 REJECTED",
              "reason": "审查意见的中文描述（简洁，不超过200字）",
              "riskLevel": "HIGH 或 MEDIUM 或 LOW"
            }"""));

        messages.add(DeepSeekChatMessage.user(
            "审查以下代码（projectId: " + projectId + "）:\n\n" + diff));

        // 从 FunctionCallTools 的注解生成工具 JSON Schema
        List<Map<String, Object>> tools = FunctionCallToolsBuilder.build(toolbox);

        // 多轮 Function Call 交互
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            DeepSeekResponse response = deepSeekClient.chatWithTools(messages, tools);
            var choice = response.getChoices().getFirst();
            var msg = choice.getMessage();

            // LLM 完成审查：从 JSON 输出直接反序列化
            if ("stop".equals(choice.getFinishReason()) && msg.getToolCalls() == null) {
                String reviewText = msg.getContent() != null ? msg.getContent() : "";
                log.info("审查完成: projectId={}, tokens={}",
                    projectId,
                    response.getUsage() != null ? response.getUsage().getTotalTokens() : -1);
                return parseStructured(reviewText);
            }

            // LLM 请求调用工具
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                messages.add(msg);
                for (var tc : msg.getToolCalls()) {
                    String result = dispatcher.dispatch(
                        tc.getFunction().getName(),
                        tc.getFunction().getArguments(),
                        context);
                    messages.add(DeepSeekChatMessage.toolResult(result, tc.getId()));
                }
                continue;
            }

            // 异常分支：finish_reason 非 stop 且无 tool_calls
            String fallbackText = msg.getContent() != null ? msg.getContent() : "";
            return parseStructured(fallbackText);
        }

        return new CodeReviewResult("REJECTED", "审查超时：未在 " + MAX_TOOL_ROUNDS + " 轮内完成", "HIGH");
    }

    /**
     * 将 LLM JSON 输出反序列化为 {@link CodeReviewResult}。
     * <p>
     * 处理 LLM 可能包裹的 markdown fence，以及异常 JSON 的降级兜底。
     */
    private static CodeReviewResult parseStructured(String jsonText) {
        try {
            String cleaned = jsonText.trim();
            // 如果 LLM 误包裹了 ```json ... ``` 标记，自动剥离
            if (cleaned.startsWith("```")) {
                int contentStart = cleaned.indexOf('\n');
                int contentEnd = cleaned.lastIndexOf("\n```");
                if (contentStart >= 0 && contentEnd > contentStart) {
                    cleaned = cleaned.substring(contentStart, contentEnd).trim();
                }
            }
            CodeReviewResult result = JsonUtils.fromJson(cleaned, CodeReviewResult.class);
            if (result != null) {
                return result;
            }
        } catch (Exception e) {
            log.warn("LLM JSON 解析失败: {}", e.getMessage());
        }
        // 兜底：反序列化失败时返回人工审查标记
        return new CodeReviewResult("REJECTED",
            "LLM 输出解析失败，请人工审查:\n" + jsonText, "HIGH");
    }
}

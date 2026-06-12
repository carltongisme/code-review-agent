package org.example.repository.code;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
    public String review(String projectId, String diff) {
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
            最后给出审查结论（通过/拒绝 + 原因 + 风险等级）。"""));

        messages.add(DeepSeekChatMessage.user(
            "审查以下代码（projectId: " + projectId + "）:\n\n" + diff));

        // 从 ReviewToolbox 的注解生成工具 JSON Schema
        List<Map<String, Object>> tools = FunctionCallToolsBuilder.build(toolbox);

        // 多轮 Function Call 交互
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            DeepSeekResponse response = deepSeekClient.chatWithTools(messages, tools);
            var choice = response.getChoices().getFirst();
            var msg = choice.getMessage();

            // LLM 完成审查
            if ("stop".equals(choice.getFinishReason()) && msg.getToolCalls() == null) {
                log.info("审查完成: projectId={}, tokens={}",
                    projectId,
                    response.getUsage() != null ? response.getUsage().getTotalTokens() : -1);
                return msg.getContent();
            }

            // LLM 请求调用工具
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                messages.add(msg);
                for (var tc : msg.getToolCalls()) {
                    // 通过反射路由器分发工具调用
                    String result = dispatcher.dispatch(
                        tc.getFunction().getName(),
                        tc.getFunction().getArguments(),
                        context);
                    messages.add(DeepSeekChatMessage.toolResult(result, tc.getId()));
                }
                continue;
            }

            return msg.getContent() != null ? msg.getContent() : "审查未产生结论";
        }
        return "审查超时";
    }
}

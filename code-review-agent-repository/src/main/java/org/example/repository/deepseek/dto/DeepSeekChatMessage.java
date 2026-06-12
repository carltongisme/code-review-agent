package org.example.repository.deepseek.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)  // 发送侧魔法：转成 JSON 发给大模型时，自动忽略所有 null 的字段
@JsonIgnoreProperties(ignoreUnknown = true)  // 接收侧魔法：从大模型接收 JSON 时，自动忽略大模型偷偷加的未知字段，防止报错
public class DeepSeekChatMessage {
    private String role;

    private String content;

    // Agent 工具调用特有字段：工具调用 ID
    @JsonProperty("tool_call_id")
    private String toolCallId;

    // Agent 工具调用特有字段：大模型发出的工具指令
    // 这里使用 JsonNode 是一招“架构级偷懒”：因为大模型发过来的 tool_calls 结构极度复杂，
    // 我们只需要把它原封不动地存下来，下一次请求时原封不动地还给大模型即可，不需要去解析它的内部。
    @JsonProperty("tool_calls")
    private List<DeepSeekToolCall> toolCalls;

    private DeepSeekChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    // ==========================================
    // 极其优雅的静态工厂方法 (Fluent API 设计)
    // ==========================================
    public static DeepSeekChatMessage system(String content) {
        return new DeepSeekChatMessage("system", content);
    }
    public static DeepSeekChatMessage user(String content) {
        return new DeepSeekChatMessage("user", content);
    }
    public static DeepSeekChatMessage toolResult(String result, String toolCallId) {
        DeepSeekChatMessage msg = new DeepSeekChatMessage("tool", result);
        msg.setToolCallId(toolCallId);
        return msg;
    }
}

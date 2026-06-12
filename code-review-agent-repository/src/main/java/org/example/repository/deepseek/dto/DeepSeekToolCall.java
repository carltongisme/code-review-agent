package org.example.repository.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeepSeekToolCall {
    /**
     * 大模型在决定调用工具时，由大模型主动生成的一个"唯一流水号"（单号）。核心作用只有一个：回执对账 (Callback Matching)
     */
    private String id;

    private String type; // 通常固定为 "function"

    private Function function;

    // ====================================== class ================================================

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Function {
        // 模型决定调用的方法名
        private String name;

        // 模型提取好的参数 (注意：这里大模型返回的是一个 JSON 格式的字符串，不是对象)
        private String arguments;
    }
}

package org.example.repository.deepseek.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)  // 加上这个注解，大模型无论增加什么乱七八糟的新字段（如 usage, reasoning_content），只要我们没定义，Jackson 就会安全忽略，绝不报错！

public class DeepSeekResponse {

    /**
     * 本次生成的全局唯一流水号
     */
    private String id;

    private String object;

    private Long created;

    private String model;

    /**
     * API 支持传入参数 n 来让模型一次性生成多个不同的候选答案。在工程中为了省钱，通常 n=1，所以数组里通常只有一个元素。
     */
    private List<DeepSeekChoice> choices;

    /**
     * 本次请求的 token 用量统计。
     */
    private Usage usage;

    // ====================================== class ================================================

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        /** 提示 token 数（含缓存命中 + 未命中） */
        private int promptTokens;

        /** 生成的补全 token 数 */
        private int completionTokens;

        /** 请求使用的总 token 数 */
        private int totalTokens;
    }
}

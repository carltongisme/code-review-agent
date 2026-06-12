package org.example.repository.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeepSeekChoice {
    private Integer index;

    private DeepSeekChatMessage message;

    // 核心拼图 1：Agent 状态机信号
    @JsonProperty("finish_reason")
    private String finishReason;
}

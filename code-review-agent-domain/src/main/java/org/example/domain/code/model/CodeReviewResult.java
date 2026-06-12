package org.example.domain.code.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代码审查结果 —— 同时作为 LLM 结构化输出的反序列化目标。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeReviewResult {

    /**
     * APPROVED / REJECTED
     */
    private String status;

    /**
     * LLM 审查意见原文
     */
    private String reason;

    /**
     * HIGH / MEDIUM / LOW
     */
    private String riskLevel;








    // ====================================== class ================================================

    public enum Status {
        APPROVED, REJECTED;
    }

    public enum RiskLevel {
        HIGH, MEDIUM, LOW;
    }
}
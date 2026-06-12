package org.example.domain.code.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}

package org.example.domain.code.service;

import org.example.domain.code.model.CodeReviewResult;

/**
 * 代码审查领域服务端口。
 * <p>
 * 接收项目 diff，通过 LLM + 调用图 + 向量库完成审查，返回结构化审查结果。
 */
public interface CodeReviewService {

    /**
     * 审查代码变更。
     *
     * @param projectId 项目 ID
     * @param diff      变更 diff 文本
     * @return 结构化审查结果（含状态、审查意见、风险等级）
     */
    CodeReviewResult review(String projectId, String diff);
}

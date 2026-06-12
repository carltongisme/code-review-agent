package org.example.domain.code.service;

/**
 * 代码审查领域服务端口。
 * <p>
 * 接收项目 diff，通过 LLM + 调用图 + 向量库完成审查，返回审查意见。
 */
public interface CodeReviewService {

    /**
     * 审查代码变更。
     *
     * @param projectId 项目 ID
     * @param diff      变更 diff 文本
     * @return LLM 审查意见原文
     */
    String review(String projectId, String diff);
}

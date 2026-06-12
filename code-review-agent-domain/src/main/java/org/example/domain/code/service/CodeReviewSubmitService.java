package org.example.domain.code.service;

import org.example.domain.code.model.CodeReviewResult;

/**
 * 审查结论提交端口 —— domain 接口，repository 实现。
 * <p>
 * 遵循项目已有的依赖反转模式（与 {@link CodeReviewService} / {@link CodeRepository} 相同），
 * 避免 domain → repository 的循环依赖。
 */
public interface CodeReviewSubmitService {

    /** 提交审查结论到 Git 平台 */
    void submitReview(String projectId, int prNumber, CodeReviewResult result);
}
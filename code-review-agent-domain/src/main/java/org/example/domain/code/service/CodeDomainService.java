package org.example.domain.code.service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.domain.code.domain.CodeDomain;
import org.example.domain.code.domain.CodeDomainPhysical;
import org.example.domain.embedding.EmbeddingService;
import org.springframework.stereotype.Service;

/**
 * 领域服务 —— 代码分析、向量化、存储流水线。
 *
 * @date 06-12-2026
 */
@Slf4j
@Service
public class CodeDomainService {
    @Resource
    private CodeAnalysisService codeAnalysisService;

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private CodeRepository codeRepository;

    /**
     * 处理单个方法：DeepSeek 解析含义 → 阿里云向量化 → Qdrant 存储。
     *
     * @param sourceCode 方法完整源码
     * @param coordinate 物理坐标（文件路径 + 类名 + 方法签名）
     * @return 已存储的代码实体
     */
    public CodeDomain store(String sourceCode, CodeDomainPhysical coordinate) {
        // 1. DeepSeek 语义分析
        String methodPurpose = codeAnalysisService.analyzePurpose(sourceCode);

        // 2. 构建实体
        CodeDomain entity = CodeDomain.builder()
            .coordinate(coordinate)
            .methodPurpose(methodPurpose)
            .sourceCode(sourceCode)
            .build();

        // 3. 向量化
        entity.setEmbedding(embeddingService.embed(entity.buildVectorText()));

        // 4. 存储
        codeRepository.store(entity);

        log.info("流水线完成: {}", coordinate);
        return entity;
    }
}

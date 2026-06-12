package org.example.domain.code;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.domain.code.domain.CodeDomain;
import org.example.domain.code.domain.CodeDomainPhysical;
import org.example.domain.code.model.ExtractedMethod;
import org.example.domain.code.parser.JavaCodeParser;
import org.example.domain.code.service.CallGraphIndex;
import org.example.domain.code.service.CodeAnalysisService;
import org.example.domain.code.service.CodeRepository;
import org.example.domain.code.service.CodeReviewService;
import org.example.domain.embedding.EmbeddingService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 领域服务 —— 代码分析、向量化、存储及全量导入流水线。
 *
 * @date 06-12-2026
 */
@Slf4j
@Service
public class CodeDomainService {

    /** 单方法处理时的同时运行数上限 */
    private static final int IMPORT_PARALLELISM = 4;

    @Resource
    private CodeRepository codeRepository;

    @Resource
    private CodeReviewService codeReviewService;

    @Resource
    private CodeAnalysisService codeAnalysisService;

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private JavaCodeParser javaCodeParser;

    @Resource
    private CallGraphIndex callGraphIndex;

    /**
     * 处理单个方法：DeepSeek 解析含义 → 阿里云向量化 → Qdrant 存储。
     *
     * @param sourceCode 方法完整源码
     * @param coordinate 物理坐标（含 projectId）
     * @return 已存储的代码实体
     */
    public CodeDomain store(String sourceCode, CodeDomainPhysical coordinate) {
        // 1. DeepSeek 语义分析
        String methodPurpose = codeAnalysisService.analyzePurpose(sourceCode);

        // 2. 构建领域实体
        CodeDomain entity = CodeDomain.builder()
            .coordinate(coordinate)
            .methodPurpose(methodPurpose)
            .sourceCode(sourceCode)
            .build();

        // 3. 生成 embedding 向量
        entity.setEmbedding(embeddingService.embed(entity.buildVectorText()));

        // 4. 存储到 Qdrant（projectId 从 coordinate 提取）
        codeRepository.store(entity, coordinate.projectId());

        log.info("方法存储完成: {}", coordinate);
        return entity;
    }

    /**
     * 全量导入：扫描 Git 仓库本地目录，解析所有 Java 方法并存入向量库。
     * <p>
     * 同时构建调用图反向索引，供审查阶段查询上下游调用关系。
     *
     * @param projectId 项目唯一标识（如 "user/repo"）
     * @param repoPath  已 clone 到本地的仓库根路径
     * @return 成功导入的方法数量
     */
    public int importProject(String projectId, Path repoPath) {
        log.info("开始全量导入: projectId={}, path={}", projectId, repoPath);

        // 收集所有 .java 文件
        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(repoPath)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java"))
                .filter(Files::isRegularFile)
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("扫描仓库目录失败: " + repoPath, e);
        }

        log.info("发现 {} 个 Java 文件", javaFiles.size());
        int totalMethods = 0;

        // 逐文件解析并存储（可并行）
        for (Path javaFile : javaFiles) {
            String sourceCode;
            try {
                sourceCode = Files.readString(javaFile);
            } catch (IOException e) {
                log.warn("读取文件失败: {} - {}", javaFile, e.getMessage());
                continue;
            }

            // 计算相对路径作为 filePath
            String filePath = repoPath.relativize(javaFile).toString().replace('\\', '/');

            // AST 解析提取方法
            List<ExtractedMethod> methods = javaCodeParser.parseFile(sourceCode);

            for (ExtractedMethod m : methods) {
                // 构建物理坐标
                CodeDomainPhysical coord = new CodeDomainPhysical(
                    projectId,
                    filePath,
                    m.className(),
                    m.methodSignature()
                );

                // 单方法流水线
                store(m.sourceCode(), coord);

                // 更新调用图反向索引
                String callerSignature = m.className() + "::" + m.methodSignature();
                callGraphIndex.addCalls(projectId, callerSignature, m.calledMethods());

                totalMethods++;
            }
        }

        log.info("全量导入完成: projectId={}, 方法数={}", projectId, totalMethods);
        return totalMethods;
    }
}


package org.example.repository.code.functioncall;

import jakarta.annotation.Resource;
import org.example.domain.code.domain.CodeDomain;
import org.example.domain.code.domain.CodeDomainPhysical;
import org.example.domain.code.parser.JavaCodeParser;
import org.example.domain.code.service.CallGraphIndex;
import org.example.domain.code.service.CodeRepository;
import org.example.domain.embedding.EmbeddingService;
import org.example.repository.code.annotation.FunctionCall;
import org.example.repository.code.annotation.FunctionCallParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 审查工具集 —— LLM 可调用的 Function Call 工具方法。
 * <p>
 * 每个方法第一个参数 {@code projectId} 为上下文参数（无 @FunctionCallParam），
 * 由 {@link FunctionCallDispatcher} 自动注入，不出现在 LLM 的工具 Schema 中。
 */
@Component
public class FunctionCallTools {

    @Resource
    private CallGraphIndex callGraphIndex;

    @Resource
    private CodeRepository codeRepository;

    @Resource
    private JavaCodeParser javaCodeParser;

    @Resource
    private EmbeddingService embeddingService;

    @FunctionCall(name = "lookup_callers", description = "查找哪些方法调用了指定的方法。用于评估修改对上游调用方的影响。")
    public String lookupCallers(
            String projectId,
            @FunctionCallParam(name = "methodSignature",
                description = "方法签名，格式：ClassName::methodName(paramTypes)，如 UserService::login(String,String)")
            String args) {

        String methodSignature = extractJsonField(args, "methodSignature");
        if (methodSignature == null) return "错误: 缺少 methodSignature 参数";

        Set<String> callers = callGraphIndex.findCallers(projectId, methodSignature);
        if (callers.isEmpty()) return "方法 " + methodSignature + " 无上游调用方";

        StringBuilder sb = new StringBuilder("【上游调用方】:\n");
        for (String callerId : callers) {
            String[] parts = callerId.split("::", 3);
            if (parts.length >= 3) {
                codeRepository.searchPhysical(
                    new CodeDomainPhysical(projectId, "", parts[1], parts[2]))
                    .ifPresentOrElse(
                        e -> sb.append("- ").append(parts[1]).append("::").append(parts[2])
                            .append(" → ").append(e.getMethodPurpose()).append("\n"),
                        () -> sb.append("- ").append(parts[1]).append("::").append(parts[2]).append("\n"));
            }
        }
        return sb.toString();
    }

    @FunctionCall(name = "lookup_callees", description = "查找指定方法体内调用了哪些其他方法。用于评估修改对下游的影响。")
    public String lookupCallees(
            String projectId,
            @FunctionCallParam(name = "methodSignature", description = "方法签名，格式：ClassName::methodName(paramTypes)") String args
    ) {

        String methodSignature = extractJsonField(args, "methodSignature");
        if (methodSignature == null) return "错误: 缺少 methodSignature 参数";

        String[] parts = methodSignature.split("::", 2);
        if (parts.length < 2) return "错误: 格式应为 ClassName::methodName(paramTypes)";

        Optional<CodeDomain> entity = codeRepository.searchPhysical(
            new CodeDomainPhysical(projectId, "", parts[0], parts[1]));
        if (entity.isEmpty()) return "未找到方法: " + methodSignature;

        List<String> callees = javaCodeParser.extractCalledMethods(entity.get().getSourceCode());
        if (callees.isEmpty()) return "方法 " + methodSignature + " 未调用其他方法";

        StringBuilder sb = new StringBuilder("【下游被调用】:\n");
        callees.forEach(c -> sb.append("- ").append(c).append("\n"));
        return sb.toString();
    }

    @FunctionCall(name = "lookup_code", description = "根据物理坐标精确查找方法的完整源码和用途描述。")
    public String lookupCode(
            String projectId,
            @FunctionCallParam(name = "filePath", description = "文件路径")
            String args) {

        String filePath = extractJsonField(args, "filePath");
        String className = extractJsonField(args, "className");
        String methodSignature = extractJsonField(args, "methodSignature");
        if (filePath == null || className == null || methodSignature == null)
            return "错误: 缺少参数";

        return codeRepository.searchPhysical(
            new CodeDomainPhysical(projectId, filePath, className, methodSignature))
            .map(e -> "【代码详情】\n" + filePath + " / " + className + " / " + methodSignature
                + "\n用途: " + e.getMethodPurpose() + "\n源码:\n" + e.getSourceCode())
            .orElse("未找到代码");
    }

    @FunctionCall(name = "search_similar", description = "语义搜索向量数据库中功能相近的代码，用于参考类似的实现模式。")
    public String searchSimilar(
            String projectId,
            @FunctionCallParam(name = "query",
                description = "自然语言描述，如\"查找认证失败处理逻辑\"")
            String args) {

        String query = extractJsonField(args, "query");
        if (query == null) return "错误: 缺少 query 参数";

        List<Float> queryEmbedding = embeddingService.embed(query);
        List<CodeDomain> results = codeRepository.searchSimilar(queryEmbedding, 5, projectId);
        if (results.isEmpty()) return "未找到相似代码";

        StringBuilder sb = new StringBuilder("【语义相似代码】:\n");
        for (CodeDomain entity : results) {
            sb.append("- ").append(entity.getCoordinate().className())
                .append("::").append(entity.getCoordinate().methodSignature())
                .append(" → ").append(entity.getMethodPurpose()).append("\n");
        }
        return sb.toString();
    }

    static String extractJsonField(String jsonLike, String fieldName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(jsonLike);
        return m.find() ? m.group(1) : null;
    }
}

package org.example.domain.code.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方法调用图反向索引。
 * <p>
 * 维护 "被调方法 → 调用方集合" 的映射关系，支持审查时查询上下游影响。
 * <pre>
 * 示例:
 *   AuthController.login() 调用了 UserService.authenticate()
 *   ApiGateway.handle()   调用了 UserService.authenticate()
 *   →
 *   index: "UserService::authenticate" → {"AuthController::login", "ApiGateway::handle"}
 * </pre>
 */
@Component
public class CallGraphIndex {

    private static final Logger log = LoggerFactory.getLogger(CallGraphIndex.class);

    /**
     * 核心索引结构: key = "projectId::ClassName::methodSignature" (被调方),
     *               value = 调用它的方法 ID 集合
     */
    private final Map<String, Set<String>> callerIndex = new ConcurrentHashMap<>();

    /**
     * 批量记录调用关系。
     * <p>
     * 全量导入时调用：对每个被解析的方法，记录它调用了哪些其他方法。
     *
     * @param projectId         项目 ID
     * @param callerSignature   调用方方法签名（格式: "ClassName::methodSig"）
     * @param calleeSignatures  被调方法签名列表
     */
    public void addCalls(String projectId, String callerSignature,
                         List<String> calleeSignatures) {
        // 构建调用方的完整 ID
        String callerId = projectId + "::" + callerSignature;

        for (String calleeSig : calleeSignatures) {
            String calleeId = projectId + "::" + calleeSig;
            // 为被调方法记录调用方
            callerIndex.computeIfAbsent(calleeId, k -> ConcurrentHashMap.newKeySet())
                .add(callerId);
        }
        log.debug("索引更新: caller={}, callees={}", callerId, calleeSignatures.size());
    }

    /**
     * 查询指定方法的所有调用方（上游）。
     *
     * @param projectId        项目 ID
     * @param methodSignature  方法签名（格式: "ClassName::methodSig"）
     * @return 调用该方法的其他方法 ID 集合，无调用方返回空集合
     */
    public Set<String> findCallers(String projectId, String methodSignature) {
        String key = projectId + "::" + methodSignature;
        Set<String> callers = callerIndex.getOrDefault(key, Set.of());
        log.debug("查询调用方: key={}, 结果数={}", key, callers.size());
        return Collections.unmodifiableSet(callers);
    }

    /**
     * 删除方法时清理索引。
     * <p>
     * 合并到 master 后，被删除的方法需要从索引中移除。
     *
     * @param projectId        项目 ID
     * @param methodSignature  要删除的方法签名
     */
    public void removeMethod(String projectId, String methodSignature) {
        String key = projectId + "::" + methodSignature;
        callerIndex.remove(key);
        // 同时从所有调用方集合中移除对该方法的引用
        callerIndex.values().forEach(callers -> callers.removeIf(c -> c.equals(key)));
        log.debug("索引移除: {}", key);
    }
}

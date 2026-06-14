package org.example.domain.code.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 方法调用图反向索引。
 * <p>
 * 维护 "被调方法 → 调用方集合" 的映射关系，支持审查时查询上下游影响。
 * <pre>
 * Key  （被调方）: "projectId::ClassName::methodSignature"
 * Value（调用方）: "projectId::filePath::ClassName::methodSignature"
 *
 * 示例:
 *   AuthController.login() 调用了 UserService.authenticate()
 *   ApiGateway.handle()   调用了 UserService.authenticate()
 *   →
 *   key:   "myproject::UserService::authenticate"
 *   value: {"myproject::src/.../AuthController.java::AuthController::login",
 *           "myproject::src/.../ApiGateway.java::ApiGateway::handle"}
 * </pre>
 */
@Component
public class CallGraphIndex {

    private static final Logger log = LoggerFactory.getLogger(CallGraphIndex.class);

    /**
     * 核心索引结构: key = "projectId::ClassName::methodSignature" (被调方),
     *               value = "projectId::filePath::ClassName::methodSignature" (调用方)
     */
    private final Map<String, Set<String>> callerIndex = new ConcurrentHashMap<>();

    /**
     * 批量记录调用关系。
     * <p>
     * 全量导入时调用：对每个被解析的方法，记录它调用了哪些其他方法。
     *
     * @param projectId         项目 ID
     * @param filePath          调用方所在文件路径
     * @param callerSignature   调用方方法签名（格式: "ClassName::methodSig"）
     * @param calleeSignatures  被调方法签名列表（格式: "ClassName::methodSig"，不含 filePath）
     */
    public void addCalls(String projectId, String filePath, String callerSignature,
                         List<String> calleeSignatures) {
        String callerId = projectId + "::" + filePath + "::" + callerSignature;

        for (String calleeSig : calleeSignatures) {
            String calleeId = projectId + "::" + calleeSig;
            callerIndex.computeIfAbsent(calleeId, k -> ConcurrentHashMap.newKeySet())
                .add(callerId);
        }
        log.debug("索引更新: caller={}, callees={}", callerId, calleeSignatures.size());
    }

    /** 匹配参数类型括号的模式 */
    private static final Pattern PARAM_PATTERN = Pattern.compile("\\([^)]*\\)$");

    /**
     * 查询指定方法的所有调用方（上游）。
     * <p>
     * 支持以下查询格式（与 AST 解析存储的 key 做多层次匹配）：
     * <ol>
     *   <li>精确匹配（项目::类名::方法名(参数类型)）</li>
     *   <li>去除参数类型后匹配</li>
     *   <li>忽略大小写的类名 + 方法名模糊匹配（处理 AST 变量名 vs 查询类名不一致的情况）</li>
     * </ol>
     *
     * @param projectId        项目 ID
     * @param methodSignature  方法签名（格式: "ClassName::methodSig" 或 "ClassName::methodSig(paramTypes)"）
     * @return 调用该方法的其他方法 ID 集合，无调用方返回空集合
     */
    public Set<String> findCallers(String projectId, String methodSignature) {
        // 第 1 层：精确匹配
        String exactKey = projectId + "::" + methodSignature;
        Set<String> callers = callerIndex.get(exactKey);
        if (callers != null && !callers.isEmpty()) {
            log.debug("精确匹配调用方: key={}, 结果数={}", exactKey, callers.size());
            return Collections.unmodifiableSet(callers);
        }

        // 第 2 层：去除参数类型后匹配
        //   AST 解析无法获取被调用方法的参数类型，所以索引 key 不含参数类型；
        //   但 LLM 查询时可能带上参数类型，需要兼容。
        String noParamsKey = projectId + "::" + PARAM_PATTERN.matcher(methodSignature).replaceFirst("");
        callers = callerIndex.get(noParamsKey);
        if (callers != null && !callers.isEmpty()) {
            log.debug("去参匹配调用方: key={}, 结果数={}", noParamsKey, callers.size());
            return Collections.unmodifiableSet(callers);
        }

        // 第 3 层：忽略大小写 + 方法名后缀匹配
        //   AST 中的 scope 是变量名（如 userService），LLM 查询用的是类名（如 UserService），
        //   此时需要做模糊匹配。提取查询中的方法名，在索引中找以该方法名结尾的 key。
        String methodName = extractMethodName(methodSignature);
        Set<String> fuzzyResult = new HashSet<>();
        String prefix = projectId + "::";
        for (Map.Entry<String, Set<String>> entry : callerIndex.entrySet()) {
            if (entry.getKey().startsWith(prefix)
                && endsWithMethodName(entry.getKey(), methodName)) {
                fuzzyResult.addAll(entry.getValue());
            }
        }

        log.debug("模糊匹配调用方: projectId={}, methodSig={}, methodName={}, 结果数={}",
            projectId, methodSignature, methodName, fuzzyResult.size());
        return Collections.unmodifiableSet(fuzzyResult);
    }

    /** 从方法签名中提取纯方法名（去除类名前缀和参数类型） */
    static String extractMethodName(String methodSignature) {
        // 去除参数类型
        String noParams = PARAM_PATTERN.matcher(methodSignature).replaceFirst("");
        // 取 "::" 之后的方法名；若无 "::" 则整个作为方法名
        int idx = noParams.lastIndexOf("::");
        return idx >= 0 ? noParams.substring(idx + 2) : noParams;
    }

    /** 检查 key 是否以指定方法名结尾（忽略大小写） */
    private static boolean endsWithMethodName(String key, String methodName) {
        int idx = key.lastIndexOf("::");
        if (idx < 0) return false;
        return key.substring(idx + 2).equalsIgnoreCase(methodName);
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

    /**
     * 按文件清理索引 —— 删除文件中所有方法的调用关系。
     * <p>
     * 合并到 master 后某文件被删除时调用。
     * <ul>
     *   <li>key 清理：按 className 做 best-effort 匹配（key 不含 filePath，
     *       不同路径的同名类无法区分）</li>
     *   <li>value 清理：按 filePath 精确匹配（调用方位于被删文件），
     *       同时按 key 清理结果移除对被删方法的跨文件引用</li>
     * </ul>
     *
     * @param projectId 项目 ID
     * @param filePath  被删除文件的路径
     * @param className 类名（不含包名），用于 key 的 best-effort 匹配
     */
    public void removeCallersByFilePrefix(String projectId, String filePath, String className) {
        String prefix = projectId + "::";
        Set<String> toRemove = new HashSet<>();

        // 1. 按 className 匹配 key（best-effort，key 不含 filePath）
        for (String key : callerIndex.keySet()) {
            if (!key.startsWith(prefix)) continue;
            String afterProject = key.substring(prefix.length());
            int lastColon = afterProject.lastIndexOf("::");
            if (lastColon < 0) continue;
            String keyClassName = afterProject.substring(0, lastColon);
            if (keyClassName.equalsIgnoreCase(className)
                || keyClassName.endsWith("." + className)) {
                toRemove.add(key);
            }
        }
        toRemove.forEach(callerIndex::remove);

        // 2. 清理 value：按 filePath 精确删除调用方位于本文件的记录
        String callerPrefix = projectId + "::" + filePath + "::";
        callerIndex.values().forEach(callers ->
            callers.removeIf(c -> c.startsWith(callerPrefix)));

        // 3. 清理 value：被删方法（key）的跨文件引用
        if (!toRemove.isEmpty()) {
            callerIndex.values().forEach(callers ->
                callers.removeIf(c -> {
                    String[] parts = c.split("::", 4);
                    if (parts.length < 4) return false;
                    String keyFormat = parts[0] + "::" + parts[2] + "::" + parts[3];
                    return toRemove.contains(keyFormat);
                }));
        }

        log.info("索引清理完成: projectId={}, filePath={}, className={}, 移除条目={}",
            projectId, filePath, className, toRemove.size());
    }
}

package org.example.domain.code.service;

import java.util.List;
import java.util.Optional;

import org.example.domain.code.exception.CodeServiceException;
import org.example.domain.code.domain.CodeDomainPhysical;
import org.example.domain.code.domain.CodeDomain;

/**
 * 代码向量存储接口 —— 将方法级代码块持久化到向量数据库。
 * <p>
 * 提供两个核心能力：
 * <ol>
 *   <li><b>存入</b>：将已处理好的代码块（含向量）写入向量数据库，
 *       使用确定性 UUID 保证幂等，同一方法多次存入不会产生重复记录。</li>
 *   <li><b>按物理坐标查询</b>：根据文件路径 + 类名 + 方法签名精确找回代码块，
 *       用于 LLM 需要定位具体代码时使用。</li>
 * </ol>
 */
public interface CodeRepository {
    /**
     * 将已处理好的代码块存入向量数据库。
     * <p>
     * 实现保证幂等性：使用物理坐标生成的确定性 UUID 作为向量 ID，
     * 同一物理坐标的方法无论调用多少次 store，Qdrant 中最多只有一条记录。
     *
     * @param entity 包含物理坐标、方法用途、源代码和向量的实体
     * @throws CodeServiceException 存储失败时抛出
     * @throws IllegalArgumentException 如果 entity 或其关键字段为 null
     */
    void store(CodeDomain entity, String projectId) throws CodeServiceException;

    /**
     * 按物理坐标删除该文件下所有方法的向量记录。
     * <p>
     * 用于合并到 master 后清理被删除或修改的文件的旧向量数据。
     * 仅使用 coordinate 中的 projectId 和 filePath，忽略 className 和 methodSignature。
     *
     * @param coordinate 物理坐标（使用其中的 projectId 和 filePath）
     * @throws CodeServiceException 删除失败时抛出
     */
    void delete(CodeDomainPhysical coordinate) throws CodeServiceException;

    /**
     * 根据物理坐标精确查询代码块。
     * <p>
     * 通过确定性 UUID 直接定位向量记录（非向量相似搜索），
     * 是 O(1) 级别的精确查找。
     *
     * @param coordinate 文件路径 + 类名 + 方法签名
     * @return 若存在则返回实体（不含向量），否则返回 Optional.empty()
     * @throws CodeServiceException 查询失败时抛出
     */
    Optional<CodeDomain> searchPhysical(CodeDomainPhysical coordinate) throws CodeServiceException;

    /**
     * 根据向量相似度检索，可指定按项目 ID 过滤。
     *
     * @param queryEmbedding 查询向量
     * @param limit          返回的最大结果数
     * @param projectId      项目 ID，为 null 时不按项目过滤（跨项目搜索）
     */
    List<CodeDomain> searchSimilar(List<Float> queryEmbedding, int limit, String projectId) throws CodeServiceException;

    /**
     * 返回所有已存储的代码实体（不含 embedding 向量）。
     * <p>
     * 用于启动时重建调用图索引等内存结构。
     */
    List<CodeDomain> scrollAll() throws CodeServiceException;
}

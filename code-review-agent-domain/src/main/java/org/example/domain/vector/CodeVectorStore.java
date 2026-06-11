package org.example.domain.vector;

import java.util.Optional;

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
public interface CodeVectorStore {

    /**
     * 将已处理好的代码块存入向量数据库。
     * <p>
     * 实现保证幂等性：使用物理坐标生成的确定性 UUID 作为向量 ID，
     * 同一物理坐标的方法无论调用多少次 store，Qdrant 中最多只有一条记录。
     *
     * @param entity 包含物理坐标、方法用途、源代码和向量的实体
     * @throws CodeVectorStoreException 存储失败时抛出
     * @throws IllegalArgumentException 如果 entity 或其关键字段为 null
     */
    void store(CodeVectorEntity entity) throws CodeVectorStoreException;

    /**
     * 根据物理坐标精确查询代码块。
     * <p>
     * 通过确定性 UUID 直接定位向量记录（非向量相似搜索），
     * 是 O(1) 级别的精确查找。
     *
     * @param coordinate 文件路径 + 类名 + 方法签名
     * @return 若存在则返回实体（不含向量），否则返回 Optional.empty()
     * @throws CodeVectorStoreException 查询失败时抛出
     */
    Optional<CodeVectorEntity> findByPhysicalCoordinate(PhysicalCoordinate coordinate) throws CodeVectorStoreException;
}

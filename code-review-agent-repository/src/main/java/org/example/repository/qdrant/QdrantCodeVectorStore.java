package org.example.repository.qdrant;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.UpdateResult;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import org.example.domain.vector.CodeVectorEntity;
import org.example.domain.vector.CodeVectorStore;
import org.example.domain.vector.CodeVectorStoreException;
import org.example.domain.vector.PhysicalCoordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * 基于 Qdrant 的 {@link CodeVectorStore} 实现。
 * <p>
 * 使用确定性 UUID（由物理坐标生成）作为 Qdrant 向量点的 ID，
 * 保证同一方法多次写入不会产生重复记录（天然幂等）。
 * <p>
 * 存储时硬拼接物理坐标到向量文本中，确保 LLM 检索结果自包含定位信息。
 */
public class QdrantCodeVectorStore implements CodeVectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantCodeVectorStore.class);

    // ── Payload 字段名常量 ──
    static final String PAYLOAD_FILE_PATH = "file_path";
    static final String PAYLOAD_CLASS_NAME = "class_name";
    static final String PAYLOAD_METHOD_SIGNATURE = "method_signature";
    static final String PAYLOAD_METHOD_PURPOSE = "method_purpose";
    static final String PAYLOAD_SOURCE_CODE = "source_code";
    static final String PAYLOAD_VECTOR_TEXT = "vector_text";

    private final QdrantClient qdrantClient;
    private final QdrantProperties properties;

    /** 双重检查锁 —— 确保集合只初始化一次 */
    private volatile boolean collectionEnsured;

    public QdrantCodeVectorStore(QdrantClient qdrantClient, QdrantProperties properties) {
        this.qdrantClient = qdrantClient;
        this.properties = properties;
    }

    // ── CodeVectorStore 接口实现 ──

    @Override
    public void store(CodeVectorEntity entity) throws CodeVectorStoreException {
        ensureCollectionExists();

        PhysicalCoordinate coord = entity.getCoordinate();
        UUID pointId = coord.toDeterministicId();
        String vectorText = entity.buildVectorText();

        if (entity.getEmbedding() == null || entity.getEmbedding().isEmpty()) {
            throw new CodeVectorStoreException("embedding 不能为空，物理坐标: " + coord);
        }

        if (log.isDebugEnabled()) {
            log.debug("存储代码向量: id={}, coord={}", pointId, coord);
        }

        PointStruct point = PointStruct.newBuilder()
            .setId(id(pointId))
            .setVectors(vectors(entity.getEmbedding()))
            .putPayload(PAYLOAD_FILE_PATH, value(coord.filePath()))
            .putPayload(PAYLOAD_CLASS_NAME, value(coord.className()))
            .putPayload(PAYLOAD_METHOD_SIGNATURE, value(coord.methodSignature()))
            .putPayload(PAYLOAD_METHOD_PURPOSE, value(
                entity.getMethodPurpose() != null ? entity.getMethodPurpose() : ""))
            .putPayload(PAYLOAD_SOURCE_CODE, value(
                entity.getSourceCode() != null ? entity.getSourceCode() : ""))
            .putPayload(PAYLOAD_VECTOR_TEXT, value(vectorText))
            .build();

        try {
            UpdateResult result = qdrantClient.upsertAsync(
                properties.getCollectionName(), List.of(point), null).get();
            log.debug("代码向量存储成功: id={}, status={}", pointId, result.getStatus());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeVectorStoreException("存储代码向量时线程被中断", e);
        } catch (ExecutionException e) {
            throw new CodeVectorStoreException(
                "存储代码向量失败: " + e.getCause().getMessage(), e.getCause());
        }
    }

    @Override
    public Optional<CodeVectorEntity> findByPhysicalCoordinate(PhysicalCoordinate coordinate)
        throws CodeVectorStoreException {
        ensureCollectionExists();

        UUID pointId = coordinate.toDeterministicId();

        if (log.isDebugEnabled()) {
            log.debug("查询代码向量: id={}, coord={}", pointId, coordinate);
        }

        try {
            List<RetrievedPoint> results = qdrantClient.retrieveAsync(
                properties.getCollectionName(),
                List.of(id(pointId)),
                WithPayloadSelector.newBuilder().setEnable(true).build(),
                null  // 不需要返回向量，节省带宽
            ).get();

            if (results == null || results.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(mapToEntity(results.get(0)));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeVectorStoreException("查询代码向量时线程被中断", e);
        } catch (ExecutionException e) {
            throw new CodeVectorStoreException(
                "查询代码向量失败: " + e.getCause().getMessage(), e.getCause());
        }
    }

    // ── 集合生命周期 ──

    /**
     * 双重检查锁保证集合只创建一次，避免每次操作都检查集合是否存在。
     */
    void ensureCollectionExists() {
        if (collectionEnsured) {
            return;
        }
        synchronized (this) {
            if (collectionEnsured) {
                return;
            }
            try {
                boolean exists = qdrantClient.collectionExistsAsync(
                    properties.getCollectionName()).get();
                if (!exists) {
                    log.info("创建 Qdrant 集合: {}, 向量维度: {}, 距离度量: Cosine",
                        properties.getCollectionName(), properties.getVectorSize());
                    qdrantClient.createCollectionAsync(
                        properties.getCollectionName(),
                        VectorParams.newBuilder()
                            .setSize(properties.getVectorSize())
                            .setDistance(Distance.Cosine)
                            .build(),
                        null
                    ).get();
                    log.info("Qdrant 集合创建成功");
                }
                collectionEnsured = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CodeVectorStoreException("初始化集合时线程被中断", e);
            } catch (ExecutionException e) {
                throw new CodeVectorStoreException(
                    "初始化集合失败: " + e.getCause().getMessage(), e.getCause());
            }
        }
    }

    // ── 内部工具方法 ──

    /**
     * 将 Qdrant 返回的 {@link RetrievedPoint} 映射回领域实体。
     * <p>
     * 注意：查询时不返回向量（setWithVectors=false），
     * 因此返回的实体中 embedding 为 null。
     */
    private CodeVectorEntity mapToEntity(RetrievedPoint point) {
        Map<String, com.google.protobuf.Value> payload = point.getPayloadMap();

        return CodeVectorEntity.builder()
            .coordinate(new PhysicalCoordinate(
                payload.get(PAYLOAD_FILE_PATH).getStringValue(),
                payload.get(PAYLOAD_CLASS_NAME).getStringValue(),
                payload.get(PAYLOAD_METHOD_SIGNATURE).getStringValue()
            ))
            .methodPurpose(nullSafeString(payload, PAYLOAD_METHOD_PURPOSE))
            .sourceCode(nullSafeString(payload, PAYLOAD_SOURCE_CODE))
            // embedding 不返回（查询时 setWithVectors=false），调用方如需向量需另行获取
            .embedding(null)
            .build();
    }

    private static String nullSafeString(Map<String, com.google.protobuf.Value> map, String key) {
        com.google.protobuf.Value v = map.get(key);
        return (v != null) ? v.getStringValue() : "";
    }
}

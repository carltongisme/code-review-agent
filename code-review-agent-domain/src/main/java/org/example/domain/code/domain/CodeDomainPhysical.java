package org.example.domain.code.domain;

import lombok.Data;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 代码块的物理坐标 —— 由项目ID + 文件路径 + 所属类名 + 方法签名组成的值对象。
 * <p>
 * 物理坐标是代码块在项目中的唯一定位标识，用于：
 * <ul>
 *   <li>生成确定性 UUID（同一物理坐标始终映射到同一向量 ID）</li>
 *   <li>按坐标精确检索已存储的代码块</li>
 * </ul>
 */
@Data
public class CodeDomainPhysical {

    /** 项目唯一标识（如 "owner/repo"） */
    private String projectId;

    /** 文件相对路径（如 "src/main/java/com/example/Service.java"） */
    private String filePath;

    /** 所属类名（不含包名） */
    private String className;

    /** 方法签名，含参数类型，如 login(String,String) */
    private String methodSignature;

    public CodeDomainPhysical(String projectId, String filePath,
                              String className, String methodSignature) {
        this.projectId = projectId;
        this.filePath = filePath;
        this.className = className;
        this.methodSignature = methodSignature;
    }

    /**
     * 生成确定性 UUID（基于 UUID v3/MD5 风格）。
     * 使用 "projectId::filePath::className::methodSignature" 作为命名空间输入，
     * 保证同一物理坐标在任何时间、任何环境都生成相同的 UUID。
     *
     * @return 确定性 UUID
     */
    public UUID toDeterministicId() {
        String key = projectId + "::" + filePath + "::" + className + "::" + methodSignature;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 用于日志和展示的简短描述，格式：【项目ID】【文件路径】【类名】【方法签名】
     */
    @Override
    public String toString() {
        return String.format("【%s】【%s】【%s】【%s】", projectId, filePath, className, methodSignature);
    }
}
package org.example.domain.vector;

/**
 * 向量存储操作异常，表示存储或查询过程中发生的可恢复或不可恢复错误。
 */
public class CodeVectorStoreException extends RuntimeException {

    public CodeVectorStoreException(String message) {
        super(message);
    }

    public CodeVectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

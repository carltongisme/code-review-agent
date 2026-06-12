package org.example.domain.code.exception;

/**
 * 向量存储操作异常，表示存储或查询过程中发生的可恢复或不可恢复错误。
 */
public class CodeServiceException extends RuntimeException {

    public CodeServiceException(String message) {
        super(message);
    }

    public CodeServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}

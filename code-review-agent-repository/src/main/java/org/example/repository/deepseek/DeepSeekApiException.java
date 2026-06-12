package org.example.repository.deepseek;

/**
 * DeepSeek API 调用异常。
 */
public class DeepSeekApiException extends RuntimeException {

    public DeepSeekApiException(String message) {
        super(message);
    }

    public DeepSeekApiException(String message, Throwable cause) {
        super(message, cause);
    }
}

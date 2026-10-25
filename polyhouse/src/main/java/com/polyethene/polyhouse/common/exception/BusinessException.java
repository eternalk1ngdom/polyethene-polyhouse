package com.polyethene.polyhouse.common.exception;

import com.polyethene.polyhouse.common.constant.ErrorCode;

import java.util.Objects;

/**
 * 表示可预期的业务失败，由全局异常处理器转换为统一响应，避免 Controller 重复捕获异常。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /**
     * 创建业务异常，并保留对外业务码和安全提示。
     *
     * @param errorCode 同时定义业务码和 HTTP 状态的错误类型
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage());
    }

    /**
     * 为已定义的业务失败提供具体的安全提示，HTTP 状态仍由统一错误类型决定。
     *
     * @param errorCode 统一错误类型
     * @param message 面向客户端的安全错误提示
     */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * 创建带原始原因的业务异常，便于服务层保留异常链而不泄露底层信息。
     *
     * @param errorCode 统一错误类型
     * @param message 面向客户端的安全错误提示
     * @param cause 原始异常
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(requireMessage(message), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    /**
     * 返回业务错误码，供统一响应体透传给前端。
     *
     * @return 业务错误码
     */
    public int getCode() {
        return errorCode.getCode();
    }

    /**
     * 返回统一错误类型，让异常处理器采用与业务码一致的 HTTP 状态。
     *
     * @return 统一错误类型
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    private static String requireMessage(String message) {
        Objects.requireNonNull(message, "message must not be null");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message;
    }
}

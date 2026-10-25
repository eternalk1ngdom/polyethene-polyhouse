package com.polyethene.polyhouse.common.result;

import com.polyethene.polyhouse.common.constant.ErrorCode;

import java.util.Objects;

/**
 * 统一封装所有 Controller 的 JSON 响应，避免不同模块各自定义响应结构。
 *
 * @param <T> 响应数据类型
 */
public final class Result<T> {

    /** 请求成功时使用的稳定业务码。 */
    public static final int SUCCESS_CODE = 0;

    /** 请求参数不合法时使用的稳定业务码。 */
    public static final int INVALID_PARAMETER_CODE = ErrorCode.INVALID_PARAMETER.getCode();

    /** 服务端发生未预期异常时使用的稳定业务码。 */
    public static final int INTERNAL_ERROR_CODE = ErrorCode.INTERNAL_ERROR.getCode();

    private static final String SUCCESS_MESSAGE = "操作成功";

    private final int code;
    private final String message;
    private final T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.data = data;
    }

    /**
     * 创建不携带数据的成功响应，适用于删除、退出等命令型接口。
     *
     * @param <T> 响应数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success() {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    /**
     * 创建携带数据的成功响应，统一使用默认成功文案。
     *
     * @param data 响应数据
     * @param <T> 响应数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    /**
     * 创建携带自定义提示和数据的成功响应，用于需要向用户说明结果的场景。
     *
     * @param message 面向客户端的成功提示
     * @param data 响应数据
     * @param <T> 响应数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(SUCCESS_CODE, message, data);
    }

    /**
     * 创建失败响应，业务层和异常处理器必须提供明确且稳定的业务码。
     *
     * @param code 非零业务错误码
     * @param message 面向客户端的安全错误提示
     * @param <T> 响应数据类型
     * @return 不携带数据的失败响应
     */
    public static <T> Result<T> failure(int code, String message) {
        if (code <= SUCCESS_CODE) {
            throw new IllegalArgumentException("failure code must be positive");
        }
        return new Result<>(code, message, null);
    }

    /**
     * 从统一错误定义创建失败响应，避免调用方手写错误码或默认文案。
     *
     * @param errorCode 统一错误定义
     * @param <T> 响应数据类型
     * @return 不携带数据的失败响应
     */
    public static <T> Result<T> failure(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return failure(errorCode.getCode(), errorCode.getMessage());
    }

    /**
     * 返回稳定业务码，供前端在 HTTP 状态之外判断业务结果。
     *
     * @return 业务码
     */
    public int getCode() {
        return code;
    }

    /**
     * 返回可安全展示给用户的结果说明。
     *
     * @return 结果说明
     */
    public String getMessage() {
        return message;
    }

    /**
     * 返回响应数据；失败响应或命令型成功响应可以为 null。
     *
     * @return 响应数据
     */
    public T getData() {
        return data;
    }
}

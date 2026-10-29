package com.polyethene.polyhouse.common.constant;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 集中定义业务错误码、安全提示及对应 HTTP 状态，避免各模块采用不同的错误约定。
 */
public enum ErrorCode {

    /** 输入无法通过解析或校验，调用方需要修正参数。 */
    INVALID_PARAMETER(40000, "请求参数不合法", HttpStatus.BAD_REQUEST),
    /** 尚未建立有效登录身份，前端可以据此进入登录流程。 */
    UNAUTHORIZED(40100, "请先登录", HttpStatus.UNAUTHORIZED),
    /** 已有身份但达不到资源的角色要求。 */
    FORBIDDEN(40300, "权限不足", HttpStatus.FORBIDDEN),
    /** 临时凭据仍有效但只能改密，不能按 401 清理掉完成改密所需的会话。 */
    PASSWORD_CHANGE_REQUIRED(40301, "请先修改初始密码", HttpStatus.FORBIDDEN),
    /** 请求目标不存在，不向客户端披露内部资源路径。 */
    NOT_FOUND(40400, "请求资源不存在", HttpStatus.NOT_FOUND),
    /** 请求方法与接口声明不一致，同时保留框架的 Allow 响应头。 */
    METHOD_NOT_ALLOWED(40500, "请求方法不支持", HttpStatus.METHOD_NOT_ALLOWED),
    /** 服务无法生成客户端 Accept 头要求的表示格式。 */
    NOT_ACCEPTABLE(40600, "不支持请求的响应格式", HttpStatus.NOT_ACCEPTABLE),
    /** 操作与当前数据状态冲突，例如账号唯一约束冲突。 */
    CONFLICT(40900, "数据状态冲突", HttpStatus.CONFLICT),
    /** 请求体积超出服务允许的范围。 */
    PAYLOAD_TOO_LARGE(41300, "请求内容过大", HttpStatus.PAYLOAD_TOO_LARGE),
    /** 请求的 Content-Type 与接口要求不兼容。 */
    UNSUPPORTED_MEDIA_TYPE(41500, "请求内容类型不支持", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    /** 请求受到限流或临时锁定。 */
    TOO_MANY_REQUESTS(42900, "请求过于频繁，请稍后重试", HttpStatus.TOO_MANY_REQUESTS),
    /** 其他已知客户端错误仍保留原 HTTP 状态，响应体使用安全的通用业务码。 */
    REQUEST_REJECTED(40001, "请求无法处理", HttpStatus.BAD_REQUEST),
    /** 未预期的服务端失败，禁止向客户端传递原始异常消息。 */
    INTERNAL_ERROR(50000, "系统繁忙，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR),
    /** 依赖服务暂时不可用，允许客户端稍后重试。 */
    SERVICE_UNAVAILABLE(50300, "服务暂时不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /**
     * 返回供前端稳定判断业务失败原因的编码。
     *
     * @return 业务错误码
     */
    public int getCode() {
        return code;
    }

    /**
     * 返回经过集中定义、可以向客户端展示的默认提示。
     *
     * @return 安全错误提示
     */
    public String getMessage() {
        return message;
    }

    /**
     * 返回此类业务失败应采用的 HTTP 状态。
     *
     * @return HTTP 状态
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /**
     * 根据框架给出的 HTTP 状态选择安全业务错误；未知状态不透传框架的原始消息。
     *
     * @param status 框架错误状态
     * @return 对应的业务错误定义
     */
    public static ErrorCode fromHttpStatus(HttpStatusCode status) {
        for (ErrorCode errorCode : values()) {
            if (errorCode.httpStatus.value() == status.value()) {
                return errorCode;
            }
        }
        return status.is4xxClientError() ? REQUEST_REJECTED : INTERNAL_ERROR;
    }
}

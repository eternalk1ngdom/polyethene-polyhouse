package com.polyethene.polyhouse.common.exception;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.result.Result;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.Arrays;

/**
 * 将业务异常、参数异常和未知异常统一转换为 {@link Result}，防止堆栈或内部实现泄露给客户端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String APPLICATION_PACKAGE = "com.polyethene.polyhouse.";

    /**
     * 处理服务层主动抛出的业务异常，保留其业务码和安全提示。
     *
     * @param exception 业务异常
     * @return 统一失败响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException exception) {
        if (exception.getErrorCode().getHttpStatus().is5xxServerError()) {
            logSafely(exception);
        }
        return ResponseEntity.status(exception.getErrorCode().getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.failure(exception.getCode(), exception.getMessage()));
    }

    /**
     * 统一处理请求体、表单绑定、查询参数类型转换及约束校验错误，不回显输入值或校验器消息。
     *
     * @param exception 参数校验异常
     * @return 统一失败响应
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            ConstraintViolationException.class, TypeMismatchException.class,
            HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Result<Void>> handleInvalidRequest(Exception exception) {
        return response(ErrorCode.INVALID_PARAMETER);
    }

    /**
     * 区分 MVC 方法参数和返回值校验：参数不合法是 400，服务端返回值违反约束是 500。
     *
     * @param exception MVC 方法校验异常
     * @return 统一失败响应
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleMethodValidation(HandlerMethodValidationException exception) {
        if (exception.isForReturnValue()) {
            logSafely(exception);
            return response(ErrorCode.INTERNAL_ERROR);
        }
        return response(ErrorCode.INVALID_PARAMETER);
    }

    /**
     * 保留框架错误的 HTTP 状态和必要响应头；其他异常返回 500，并只记录安全定位信息。
     *
     * @param exception 未预期异常
     * @return 统一失败响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception exception) {
        // Spring 的 404/405/415 等异常实现 ErrorResponse，不能被兜底逻辑误改为 200 或 500。
        if (exception instanceof ErrorResponse errorResponse) {
            ErrorCode errorCode = ErrorCode.fromHttpStatus(errorResponse.getStatusCode());
            if (errorResponse.getStatusCode().is5xxServerError()) {
                logSafely(exception);
            }
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .headers(errorResponse.getHeaders())
                    // 错误体固定使用 JSON，避免不兼容的 Accept 再次触发协商失败并绕过统一响应。
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Result.failure(errorCode));
        }
        logSafely(exception);
        return response(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<Result<Void>> response(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.failure(errorCode));
    }

    private void logSafely(Exception exception) {
        // 异常消息和 cause 可能携带密码、Token 或手机号，只保留应用栈帧的类、方法、文件和行号。
        String location = Arrays.stream(exception.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith(APPLICATION_PACKAGE))
                .findFirst()
                .map(StackTraceElement::toString)
                .orElse("unavailable");
        LOGGER.error("Unhandled server exception: type={}, location={}", exception.getClass().getName(), location);
    }
}

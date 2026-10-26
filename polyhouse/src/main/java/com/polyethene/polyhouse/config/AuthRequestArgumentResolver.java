package com.polyethene.polyhouse.config;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.dto.ClientRequestDTO;
import com.polyethene.polyhouse.model.dto.SessionTokenDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Collections;
import java.util.List;

/**
 * 将服务器连接信息及 Authorization 头转换为带校验的 DTO，保持 Controller 入参边界。
 * 不处理 RequestBody，防止改变既有退出、软注销 JSON Token 的契约。
 */
public class AuthRequestArgumentResolver implements HandlerMethodArgumentResolver {

    private final Validator validator;

    /** 复用 Spring 管理的校验器；自定义参数解析器需要自行执行 DTO 校验。 */
    public AuthRequestArgumentResolver(Validator validator) {
        this.validator = validator;
    }

    /** 仅处理明确的两类内部请求 DTO，不接管普通表单或实体参数。 */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return !parameter.hasParameterAnnotation(RequestBody.class)
                && (parameter.getParameterType() == SessionTokenDTO.class
                || parameter.getParameterType() == ClientRequestDTO.class);
    }

    /** 从可信的 Servlet 元数据构造 DTO，忽略同名客户端查询参数。 */
    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        if (parameter.getParameterType() == SessionTokenDTO.class) {
            return requireSession(request);
        }
        ClientRequestDTO context = new ClientRequestDTO(request.getRemoteAddr());
        if (!validator.validate(context).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        return context;
    }

    /**
     * 读取唯一 Bearer 头，不接受多个认证头、前后空白或超长 Token，所有失败均不回显输入。
     * 这里只负责运输格式，签名、白名单和账号状态仍必须由 AuthService 核验。
     *
     * @param request 当前 Servlet 请求
     * @return 不带 Bearer 前缀的已校验 DTO
     */
    public SessionTokenDTO requireSession(HttpServletRequest request) {
        List<String> headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (headers.size() != 1) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String header = headers.getFirst();
        // 4096 字符 Token 上限加上七字符 Bearer 空格前缀；不提前裁剪，否则可能掩盖重复凭据或空白输入。
        if (header.length() > 4103 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String token = header.substring(7);
        SessionTokenDTO session = new SessionTokenDTO(token);
        if (!token.equals(token.strip()) || !validator.validate(session).isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return session;
    }
}

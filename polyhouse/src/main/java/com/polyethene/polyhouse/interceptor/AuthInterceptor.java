package com.polyethene.polyhouse.interceptor;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.annotation.AllowInitialPassword;
import com.polyethene.polyhouse.common.annotation.SkipBearerAuth;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.context.UserContext;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.AuthRequestArgumentResolver;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import com.polyethene.polyhouse.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * 统一保护 API 请求，复用业务层验证 Token、Redis 和数据库最新角色，不直接访问存储。
 * 请求开始及所有完成路径均清理 ThreadLocal 和请求资料，避免拒绝、异常或异步切换后残留身份。
 */
public class AuthInterceptor implements AsyncHandlerInterceptor {

    private final AuthService authService;
    private final AuthRequestArgumentResolver arguments;

    /** 注入现有业务验证与 HTTP 参数读取，不在拦截器中重新实现 JWT 或缓存算法。 */
    public AuthInterceptor(AuthService authService, AuthRequestArgumentResolver arguments) {
        this.authService = authService;
        this.arguments = arguments;
    }

    /** 默认要求有效身份；只有明确跳过 Bearer 且没有角色门槛的方法允许自行处理身份。 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserContext.clear();
        arguments.clearCurrentUser(request);
        if (!(handler instanceof HandlerMethod method)) {
            // 让静态资源和未找到的路由维持框架的 404 等语义，不把不存在的地址误报为未登录。
            return true;
        }
        RequireRole classRole = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequireRole.class);
        RequireRole methodRole = method.getMethodAnnotation(RequireRole.class);
        if (classRole == null && methodRole == null && method.hasMethodAnnotation(SkipBearerAuth.class)) {
            return true;
        }
        try {
            UserProfileVO user = authService.getCurrentUser(arguments.requireSession(request).token());
            if (user.passwordChangeRequired() && !method.hasMethodAnnotation(AllowInitialPassword.class)) {
                throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
            }
            // 两处要求同时满足，方法上的低角色不能意外放松控制器声明的高权限门槛。
            if ((classRole != null && !user.role().hasPermission(classRole.value()))
                    || (methodRole != null && !user.role().hasPermission(methodRole.value()))) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            UserContext.set(Long.parseLong(user.id()), user.role());
            // 只复用本次已核验的脱敏资料，读取 /me 不必重复进入奖励事务；写业务仍自行复核。
            arguments.bindCurrentUser(request, user);
            return true;
        } catch (RuntimeException | Error exception) {
            // preHandle 抛出时 Spring 不保证调用当前拦截器的 afterCompletion，必须在此清理。
            UserContext.clear();
            arguments.clearCurrentUser(request);
            throw exception;
        }
    }

    /** 正常返回和 Controller 抛出异常都释放当前线程身份。 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception exception) {
        UserContext.clear();
        arguments.clearCurrentUser(request);
    }

    /** 异步处理把请求移出原线程时立即清理，不把 ThreadLocal 身份隐式传播给工作线程。 */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                              Object handler) {
        UserContext.clear();
        arguments.clearCurrentUser(request);
    }
}

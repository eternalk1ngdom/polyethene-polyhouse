package com.polyethene.polyhouse.config;

import com.polyethene.polyhouse.interceptor.AuthInterceptor;
import com.polyethene.polyhouse.service.AuthService;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 将认证拦截器及受控 DTO 解析接入真实 MVC 分发；不关闭 Spring Boot 的默认 MVC 能力。
 */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthRequestArgumentResolver arguments;
    private final AuthInterceptor authentication;

    /** 两个 Web 组件共享相同参数校验规则，认证仍由已有 Service 完成。 */
    public WebMvcConfig(AuthService authService, Validator validator) {
        arguments = new AuthRequestArgumentResolver(validator);
        authentication = new AuthInterceptor(authService, arguments);
    }

    /** 保护全部已映射的 API；公开入口通过方法注解显式声明，避免字符串路径白名单遗漏。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authentication).addPathPatterns("/api/**");
    }

    /** 自定义解析器先于普通模型绑定，客户端不能用查询参数覆盖服务端连接信息。 */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(arguments);
    }
}

package com.polyethene.polyhouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/** 在生产启动时检查必需配置，并在 MVC 解析请求前执行跨域和体积检查。 */
@Profile("prod")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiLimitProperties.class)
class ProductionConfig {
    private static final int CORS_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    private static final PathPatternParser PATHS = PathPatternParser.defaultInstance;
    private static final PathPattern LOGIN = PATHS.parse("/api/auth/login");
    private static final List<PathPattern> SENSITIVE_WRITES = List.of(
            PATHS.parse("/api/auth/password/change"), PATHS.parse("/api/auth/deregister"),
            PATHS.parse("/api/auth/avatar/submissions"), PATHS.parse("/api/auth/nickname/submissions"),
            PATHS.parse("/api/manage/**"));

    @Bean
    InitializingBean productionSettings(Environment environment, JwtProperties jwtProperties) {
        return () -> {
            jwtProperties.signingKey();
            for (String name : List.of("DB_HOST", "DB_USERNAME", "DB_PASSWORD", "REDIS_HOST", "REDIS_PASSWORD")) {
                if (!StringUtils.hasText(environment.getProperty(name))) {
                    throw new IllegalStateException("Required production setting is missing: " + name);
                }
            }
        };
    }

    @Bean
    FilterRegistrationBean<CorsFilter> productionCors(CorsFilter apiCorsFilter) {
        var registration = new FilterRegistrationBean<>(apiCorsFilter);
        // 合法预检不读请求体，跨域请求的 413 仍带 CORS 响应头。
        registration.setOrder(CORS_ORDER);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ApiRequestLimitFilter> productionApiLimit(ObjectMapper mapper, ApiLimitProperties settings) {
        var api = new ApiRequestBudget(settings.api(), System::nanoTime);
        return registerApiFilter(new ApiRequestLimitFilter(mapper, request -> api), "apiLimit", CORS_ORDER + 1);
    }

    @Bean
    FilterRegistrationBean<ApiBodyLimitFilter> productionBodyLimit(ObjectMapper mapper) {
        return registerApiFilter(new ApiBodyLimitFilter(mapper), "apiBodyLimit", CORS_ORDER + 2);
    }

    @Bean
    FilterRegistrationBean<ApiRequestLimitFilter> productionSensitiveLimit(ObjectMapper mapper,
                                                                          ApiLimitProperties settings) {
        var login = new ApiRequestBudget(settings.login(), System::nanoTime);
        var writes = new ApiRequestBudget(settings.sensitive(), System::nanoTime);
        // 先完成有界读取，慢速上传只占用入口额度，不独占登录或敏感写入的执行位置。
        return registerApiFilter(new ApiRequestLimitFilter(mapper, request -> sensitiveBudget(request, login, writes)),
                "apiSensitiveLimit", CORS_ORDER + 3);
    }

    private static ApiRequestBudget sensitiveBudget(HttpServletRequest request, ApiRequestBudget login,
                                                    ApiRequestBudget writes) {
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return null;
        }
        // 使用 MVC 同款路径解析，防止编码路径或路径参数仍命中控制器却落入普通额度。
        var path = ServletRequestPathUtils.parseAndCache(request).pathWithinApplication();
        if (HttpMethod.POST.matches(method) && LOGIN.matches(path)) {
            return login;
        }
        return SENSITIVE_WRITES.stream().anyMatch(pattern -> pattern.matches(path)) ? writes : null;
    }

    private static <T extends Filter> FilterRegistrationBean<T> registerApiFilter(T filter, String name, int order) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setName(name);
        registration.addUrlPatterns("/api/*");
        // 当前 API 同步处理，整个请求工作必须处于并发额度内。
        registration.setAsyncSupported(false);
        registration.setOrder(order);
        return registration;
    }
}

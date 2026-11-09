package com.polyethene.polyhouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/** 在生产启动时检查必需配置，并在 MVC 解析请求前执行跨域和体积检查。 */
@Profile("prod")
@Configuration(proxyBeanMethods = false)
class ProductionConfig {

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
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ApiBodyLimitFilter> productionBodyLimit(ObjectMapper mapper) {
        var registration = new FilterRegistrationBean<>(new ApiBodyLimitFilter(mapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
        return registration;
    }
}

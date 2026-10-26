package com.polyethene.polyhouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.result.Result;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;
import java.util.List;

/**
 * 允许本地前端和策划书约定的浏览器扩展来源，跨域许可不代替 Token 或角色校验。
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    /** 在 MVC 鉴权前处理预检，拒绝跨域时仍使用统一 JSON，禁止浏览器携带 Cookie 凭据。 */
    @Bean
    public CorsFilter apiCorsFilter(ObjectMapper mapper) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:5173", "http://127.0.0.1:5173",
                "chrome-extension://*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(false);
        // 只缓存浏览器预检许可一小时，不缓存实际请求的登录状态或角色判断。
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        CorsFilter filter = new CorsFilter(source);
        filter.setCorsProcessor(new DefaultCorsProcessor() {
            /** 默认 CORS 拒绝是纯文本且绕过异常处理器，这里显式维持 Result 契约。 */
            @Override
            protected void rejectRequest(ServerHttpResponse response) throws IOException {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                mapper.writeValue(response.getBody(), Result.failure(ErrorCode.FORBIDDEN));
                response.flush();
            }
        });
        return filter;
    }
}

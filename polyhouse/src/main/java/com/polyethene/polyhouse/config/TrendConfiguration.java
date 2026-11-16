package com.polyethene.polyhouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;

/** 公开日报拥有独立的有界读预算，不消耗认证 Redis，也不改变现有全站限额。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TrendProperties.class)
public class TrendConfiguration {
    /** 无来源键的固定大小预算，不信任转发头；生产环境仍先经过原 CORS 和全站预算。 */
    @Bean
    public FilterRegistrationBean<ApiRequestLimitFilter> trendReadLimit(ObjectMapper json) {
        var budget = new ApiRequestBudget(new ApiLimitProperties.Budget(60, 12, 2), System::nanoTime);
        var registration = new FilterRegistrationBean<>(new ApiRequestLimitFilter(json, request ->
                HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod()) ? budget : null));
        registration.setName("trendReadLimit");
        registration.addUrlPatterns("/api/trend/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 14);
        return registration;
    }
}

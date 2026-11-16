package com.polyethene.polyhouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;

/** 公开读与管理入口各有固定预算，拒绝发生在身份存储前，不改变现有全站限额。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TrendProperties.class, TrendAiProperties.class, TrendJobProperties.class, TrendScheduleProperties.class, TrendCacheProperties.class})
public class TrendConfiguration {
    /** 本地与生产都先限定JSON读取体积；生产全站已检查的缓冲请求仍受相同上界保护。 */
    @Bean
    public FilterRegistrationBean<ApiBodyLimitFilter> trendManagementBody(ObjectMapper json) {
        var registration = new FilterRegistrationBean<>(new ApiBodyLimitFilter(json));
        registration.setName("trendManagementBody");
        registration.addUrlPatterns("/api/manage/trend/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
        return registration;
    }

    /** 先于会话Redis和数据库拒绝管理入口过载；共享地址使用同一预算，伪造头不能增加额度。 */
    @Bean
    public FilterRegistrationBean<ApiRequestLimitFilter> trendManagementLimit(ObjectMapper json) {
        var budget = new ApiRequestBudget(new ApiLimitProperties.Budget(30, 6, 2), System::nanoTime);
        var registration = new FilterRegistrationBean<>(new ApiRequestLimitFilter(json, request -> budget));
        registration.setName("trendManagementLimit");
        registration.addUrlPatterns("/api/manage/trend/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 14);
        return registration;
    }

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

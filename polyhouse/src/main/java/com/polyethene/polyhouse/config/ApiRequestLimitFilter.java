package com.polyethene.polyhouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.result.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.Function;

/** 用同一拒绝语义执行入口与敏感路由预算，避免超量请求进入下一层。 */
final class ApiRequestLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper mapper;
    private final Function<HttpServletRequest, ApiRequestBudget> selectBudget;

    ApiRequestLimitFilter(ObjectMapper mapper, Function<HttpServletRequest, ApiRequestBudget> selectBudget) {
        this.mapper = mapper;
        this.selectBudget = selectBudget;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ApiRequestBudget budget = selectBudget.apply(request);
        if (budget == null) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = budget.enterOrRetryAfter();
        if (retryAfter > 0) {
            response.setStatus(ErrorCode.TOO_MANY_REQUESTS.getHttpStatus().value());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), Result.failure(ErrorCode.TOO_MANY_REQUESTS));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            budget.leave();
        }
    }
}

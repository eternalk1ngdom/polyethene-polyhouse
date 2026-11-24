package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.SessionTokenDTO;
import com.polyethene.polyhouse.model.dto.TrendBriefingGenerateDTO;
import com.polyethene.polyhouse.model.vo.TrendBriefingGenerateVO;
import com.polyethene.polyhouse.service.TrendBriefingService;
import com.polyethene.polyhouse.service.trend.TrendJobThrottle;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 7 日风口简报屋主按需触发入口：精确 OWNER 身份加 INSIDER 算力双门槛，响应禁用缓存；
 * 同指纹输入零费用复用，无强制重抽开关。
 */
@RestController
@RequestMapping("/api/manage/trend/briefings")
@RequireRole(UserRoleEnum.OWNER)
public class TrendBriefingController {
    private final TrendBriefingService service;

    /** Controller 仅负责参数校验与响应头控制，不直接访问实体、账本或外部网关。 */
    public TrendBriefingController(TrendBriefingService service) {
        this.service = service;
    }

    /** 同步有界生成当期或指定锚点日的 7 日风口简报，超时或拒收由独立账本兜底。 */
    @PostMapping
    @RequireRole(UserRoleEnum.INSIDER)
    public ResponseEntity<Result<TrendBriefingGenerateVO>> generate(
            @Valid @RequestBody TrendBriefingGenerateDTO request,
            @Valid SessionTokenDTO session) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Result.success(service.generate(request, session.token())));
    }

    /** 冷却限流与任务入口保持一致的 429 / Retry-After / no-store 契约。 */
    @ExceptionHandler(TrendJobThrottle.class)
    public ResponseEntity<Result<Void>> throttled(TrendJobThrottle failure) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(failure.retryAfter()))
                .body(Result.failure(failure.getCode(), failure.getMessage()));
    }
}

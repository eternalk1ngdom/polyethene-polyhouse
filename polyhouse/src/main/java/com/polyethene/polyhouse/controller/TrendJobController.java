package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.TrendJobManagementService;
import com.polyethene.polyhouse.service.trend.TrendJobThrottle;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 精确屋主范围加算力闸门；管理结果禁缓存，不等待整批网络请求。 */
@RestController
@RequestMapping("/api/manage/trend/jobs")
@RequireRole(UserRoleEnum.OWNER)
public class TrendJobController {
    private final TrendJobManagementService service;

    /** 不在Controller接触Entity、HTTP源或线程身份。 */
    public TrendJobController(TrendJobManagementService service) { this.service = service; }

    /** 202只表示受理/找到已有任务，不代表榜单已发布或全部AI完成。 */
    @PostMapping
    @RequireRole(UserRoleEnum.INSIDER)
    public ResponseEntity<Result<TrendJobSubmissionVO>> submit(@Valid @RequestBody TrendJobSubmitDTO request,
                                                               @Valid SessionTokenDTO session) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Result.success(service.submit(request, session.token())));
    }

    /** 使用DTO约束查询编号，不允许无界列出历史或暴露执行内部凭据。 */
    @GetMapping
    public ResponseEntity<Result<TrendJobVO>> query(@Valid @ModelAttribute TrendJobQueryDTO request,
                                                    @Valid SessionTokenDTO session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(service.query(request, session.token())));
    }

    /** 冷却拒绝和入口限流保持相同的429/Retry-After/no-store语义。 */
    @ExceptionHandler(TrendJobThrottle.class)
    public ResponseEntity<Result<Void>> throttled(TrendJobThrottle failure) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(failure.retryAfter()))
                .body(Result.failure(failure.getCode(), failure.getMessage()));
    }
}

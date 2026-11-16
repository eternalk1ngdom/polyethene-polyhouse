package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.SkipBearerAuth;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.vo.TrendReadinessVO;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 为外部定时唤醒提供轻量应用状态，不把数据库或付费服务变成探测依赖。 */
@RestController
public class TrendReadinessController {
    private final ApplicationAvailability availability;

    /** 仅依赖启动生命周期，使关闭技术雷达时仍能安全唤醒应用。 */
    public TrendReadinessController(ApplicationAvailability availability) {
        this.availability = availability;
    }

    /** 匿名探测复用技术雷达公开读限额；READY只代表应用接收请求，不代表日报已生成。 */
    @SkipBearerAuth
    @GetMapping("/api/trend/readiness")
    public ResponseEntity<Result<TrendReadinessVO>> readiness() {
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                    .header("Retry-After", "5").body(Result.failure(ErrorCode.SERVICE_UNAVAILABLE));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(new TrendReadinessVO("READY", "APPLICATION")));
    }
}

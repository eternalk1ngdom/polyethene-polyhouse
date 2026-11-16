package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.TrendFeeService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 只供屋主查看内部预算及保守关闭，不开放退款、额度重置或平台调用能力。 */
@RestController
@RequestMapping("/api/manage/trend/fees")
@RequireRole(UserRoleEnum.OWNER)
public class TrendFeeController {
    private final TrendFeeService service;

    /** 复用管理路由预算，Controller不接触实体、账本SQL或外部HTTP。 */
    public TrendFeeController(TrendFeeService service) { this.service = service; }

    /** 内部费用与待核对请求不进入浏览器缓存。 */
    @GetMapping
    public ResponseEntity<Result<TrendFeeVO>> query(@Valid @ModelAttribute TrendFeeQueryDTO request, @Valid SessionTokenDTO session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(service.query(request, session.token())));
    }

    /** 这是已存在费用的受控管理，不新发起AI请求；精确OWNER校验由服务事务再次执行。 */
    @PostMapping("/resolutions")
    public ResponseEntity<Result<TrendFeeResolutionVO>> resolve(@Valid @RequestBody TrendFeeResolveDTO request, @Valid SessionTokenDTO session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(service.resolve(request, session.token())));
    }

    /** 确认接口返回原处理时间，不能通过刷新制造新的结算。 */
    @GetMapping("/resolution")
    public ResponseEntity<Result<TrendFeeResolutionVO>> confirm(@Valid @ModelAttribute TrendFeeResolutionQueryDTO request, @Valid SessionTokenDTO session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(service.confirm(request, session.token())));
    }
}

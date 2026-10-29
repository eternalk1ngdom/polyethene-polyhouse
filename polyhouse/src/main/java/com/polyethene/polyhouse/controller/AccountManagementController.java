package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.AccountManagementAction;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.AccountManagementService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 管理 HTTP 入口只接受 DTO 和返回脱敏 VO；每个动作仍由服务重新核对精确目标。 */
@RestController
@RequestMapping("/api/manage/accounts")
@RequireRole(UserRoleEnum.ADMIN)
public class AccountManagementController {
    private final AccountManagementService service;

    /** 保持控制器只做请求转发，凭据不写字段或共享状态。 */
    public AccountManagementController(AccountManagementService service) { this.service = service; }

    /** 管理范围由服务端限制，查询和一次凭据响应都禁止缓存。 */
    @GetMapping
    public ResponseEntity<Result<AccountPageVO>> list(@Valid @ModelAttribute AccountListDTO query, @Valid SessionTokenDTO session) {
        return response(service.list(query, session.token()));
    }

    /** 管理员仅 USER，屋主仅非 OWNER；禁止用请求体目标覆盖已校验的路径编号。 */
    @PostMapping("/{id}/ban")
    public ResponseEntity<Result<AccountOperationVO>> ban(@Valid @PathVariable("id") AccountTargetDTO target,
            @Valid @RequestBody AccountOperationDTO request, @Valid SessionTokenDTO session) {
        return response(service.operate(target, request, AccountManagementAction.BAN, session.token()));
    }

    /** 只有屋主可解封，版本与会话均不倒退。 */
    @RequireRole(UserRoleEnum.OWNER)
    @PostMapping("/{id}/unban")
    public ResponseEntity<Result<AccountOperationVO>> unban(@Valid @PathVariable("id") AccountTargetDTO target,
            @Valid @RequestBody AccountOperationDTO request, @Valid SessionTokenDTO session) {
        return response(service.operate(target, request, AccountManagementAction.UNBAN, session.token()));
    }

    /** 屋主再验密码后一次交付新的临时凭据，不改变封禁状态。 */
    @RequireRole(UserRoleEnum.OWNER)
    @PostMapping("/{id}/password/reset")
    public ResponseEntity<Result<AccountOperationVO>> reset(@Valid @PathVariable("id") AccountTargetDTO target,
            @Valid @RequestBody AccountOperationDTO request, @Valid SessionTokenDTO session) {
        return response(service.operate(target, request, AccountManagementAction.PASSWORD_RESET, session.token()));
    }

    /** 主动核对操作提交结果；不提供密码找回或对他人请求号的查询。 */
    @GetMapping("/operations/confirmation")
    public ResponseEntity<Result<AccountOperationVO>> confirm(@Valid @ModelAttribute AccountCreationQueryDTO query,
            @Valid SessionTokenDTO session) {
        return response(service.confirm(query, session.token()));
    }

    private <T> ResponseEntity<Result<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(data));
    }
}

package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.AccountCreateDTO;
import com.polyethene.polyhouse.model.dto.AccountCreationQueryDTO;
import com.polyethene.polyhouse.model.dto.SessionTokenDTO;
import com.polyethene.polyhouse.model.vo.AccountCreationVO;
import com.polyethene.polyhouse.service.AccountProvisioningService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 屋主开户 HTTP 边界；统一拦截器和服务事务分别核对身份，不提供公共注册替代通道。 */
@RestController
@RequestMapping("/api/manage/accounts")
@RequireRole(UserRoleEnum.OWNER)
public class AccountProvisioningController {
    private final AccountProvisioningService service;

    /** 只转发已校验 DTO，不在控制器保存随机密码或访问持久层。 */
    public AccountProvisioningController(AccountProvisioningService service) {
        this.service = service;
    }

    /** no-store 覆盖首次凭据及幂等重放；Result 保留统一业务响应，响应不进入公共缓存。 */
    @PostMapping
    public ResponseEntity<Result<AccountCreationVO>> create(@Valid @RequestBody AccountCreateDTO request,
                                                            @Valid SessionTokenDTO session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(service.create(request, session.token())));
    }

    /** 响应丢失后主动查询成功事实，固定没有初始密码，404 也不代表可以换请求号盲目重试。 */
    @GetMapping("/creation")
    public ResponseEntity<Result<AccountCreationVO>> confirm(@Valid @ModelAttribute AccountCreationQueryDTO request,
                                                             @Valid SessionTokenDTO session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(service.confirm(request, session.token())));
    }
}

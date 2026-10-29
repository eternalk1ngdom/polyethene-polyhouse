package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.AllowInitialPassword;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.PasswordChangeDTO;
import com.polyethene.polyhouse.model.dto.SessionTokenDTO;
import com.polyethene.polyhouse.service.AccountSecurityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 本人账号安全入口，不承担屋主开户、代他人重置或资料审核。 */
@RestController
public class AccountSecurityController {
    private final AccountSecurityService security;

    /** Controller 只转交受校验的 DTO，身份来自统一 Bearer 解析器。 */
    public AccountSecurityController(AccountSecurityService security) { this.security = security; }

    /** 改密成功后客户端清理本次会话；临时凭据允许调用但不能绕过原密码验证。 */
    @AllowInitialPassword
    @PostMapping("/api/auth/password/change")
    public Result<Void> change(@Valid @RequestBody PasswordChangeDTO request, @Valid SessionTokenDTO session) {
        security.changePassword(request, session.token());
        return Result.success();
    }
}

package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.SessionTokenDTO;
import com.polyethene.polyhouse.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 将会话撤销请求交给服务层，身份只取自验签后的 Token，不接受客户端指定用户 ID。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /** 注入现有认证服务，Controller 不直接访问数据库或 Redis。 */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 退出只撤销当前终端的匹配会话；成功后客户端应清理自己的 Token 和用户资料。 */
    @PostMapping("/logout")
    public Result<Void> logout(@Valid @RequestBody SessionTokenDTO request) {
        authService.logout(request.token());
        return Result.success();
    }

    /** 注销当前有效会话所属账号并撤销所有支持终端的会话，禁止代他人注销。 */
    @PostMapping("/deregister")
    public Result<Void> deregister(@Valid @RequestBody SessionTokenDTO request) {
        authService.deregister(request.token());
        return Result.success();
    }
}

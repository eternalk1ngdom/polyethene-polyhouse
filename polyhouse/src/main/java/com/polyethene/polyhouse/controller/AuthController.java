package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.common.annotation.SkipBearerAuth;
import com.polyethene.polyhouse.common.annotation.AllowInitialPassword;
import com.polyethene.polyhouse.model.dto.ClientRequestDTO;
import com.polyethene.polyhouse.model.dto.CurrentUserDTO;
import com.polyethene.polyhouse.model.dto.LoginDTO;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.model.dto.SessionTokenDTO;
import com.polyethene.polyhouse.model.vo.LoginVO;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import com.polyethene.polyhouse.model.vo.UserGrowthVO;
import com.polyethene.polyhouse.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供认证 HTTP 入口，复用已有业务；身份只取自验签结果，不接受客户端指定角色或用户 ID。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /** 注入现有认证服务，Controller 不直接访问数据库或 Redis。 */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 注册只返回脱敏资料，不自动登录或允许客户端指定角色。 */
    @SkipBearerAuth
    @PostMapping("/register")
    public Result<UserProfileVO> register(@Valid @RequestBody RegisterDTO request) {
        return Result.success(authService.register(request));
    }

    /** 连接地址由专用解析器从 Servlet 获取，不采信请求体或转发头中的审计地址。 */
    @SkipBearerAuth
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO request, @Valid ClientRequestDTO client) {
        return Result.success(authService.login(request, client.clientIp()));
    }

    /** 返回本次拦截器已核验的本人资料，省去同一请求第二次完整认证，不跨请求缓存身份。 */
    @GetMapping("/me")
    @AllowInitialPassword
    public Result<UserProfileVO> me(@Valid CurrentUserDTO current) {
        return Result.success(current.profile());
    }

    /** 身份只来自唯一 Bearer 头的 DTO，统一认证仍按既定规则结算每日登录；展示查询不提供发奖入参。 */
    @GetMapping("/growth")
    public Result<UserGrowthVO> growth(@Valid SessionTokenDTO session) {
        return Result.success(authService.getGrowthStatus(session.token()));
    }

    /** 退出只撤销当前终端的匹配会话；成功后客户端应清理自己的 Token 和用户资料。 */
    @SkipBearerAuth
    @PostMapping("/logout")
    public Result<Void> logout(@Valid @RequestBody SessionTokenDTO request) {
        authService.logout(request.token());
        return Result.success();
    }

    /** 注销当前有效会话所属账号并撤销所有支持终端的会话，禁止代他人注销。 */
    @SkipBearerAuth
    @PostMapping("/deregister")
    public Result<Void> deregister(@Valid @RequestBody SessionTokenDTO request) {
        authService.deregister(request.token());
        return Result.success();
    }
}

package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.annotation.SkipBearerAuth;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.context.UserContext;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.config.AuthRequestArgumentResolver;
import com.polyethene.polyhouse.config.CorsConfig;
import com.polyethene.polyhouse.config.WebMvcConfig;
import com.polyethene.polyhouse.model.dto.LoginDTO;
import com.polyethene.polyhouse.model.dto.CurrentUserDTO;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.model.vo.LoginVO;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import com.polyethene.polyhouse.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 MVC 配置、参数解析和过滤器共同运行；角色探针仅存在于测试，不新增生产管理接口。 */
@WebMvcTest(controllers = {AuthController.class, AuthWebMvcTest.ProbeController.class,
        AuthWebMvcTest.RestrictedController.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({WebMvcConfig.class, CorsConfig.class, AuthWebMvcTest.ProbeController.class,
        AuthWebMvcTest.RestrictedController.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthWebMvcTest {

    private static final String TOKEN = "web-fixture-credential";

    @Autowired
    private MockMvc mvc;
    @MockBean
    private AuthService service;
    @Autowired
    private Validator validator;

    @AfterEach
    void ensureNoIdentityRemainsOnCallerThread() {
        assertThat(UserContext.getUserId()).isNull();
        assertThat(UserContext.getRole()).isNull();
    }

    @Test
    void shouldExposeRegistrationWithoutBearerAndOnlyReturnSafeProfile() throws Exception {
        when(service.register(any())).thenReturn(profile(UserRoleEnum.USER));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"fixture-credentials\",\"nickname\":\"居民\",\"role\":\"OWNER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("42"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist());
        verify(service).register(new RegisterDTO("alice", "fixture-credentials", "居民"));
    }

    @Test
    void shouldLoginUsingServerAddressRatherThanClientSuppliedAuditFields() throws Exception {
        when(service.login(any(), eq("192.0.2.20"))).thenReturn(new LoginVO(TOKEN, "Bearer",
                Instant.parse("2026-11-01T00:00:00Z"), profile(UserRoleEnum.USER)));
        mvc.perform(post("/api/auth/login").with(request -> { request.setRemoteAddr("192.0.2.20"); return request; })
                        .param("clientIp", "192.0.2.99").header("X-Forwarded-For", "192.0.2.98")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"fixture-credentials\",\"clientType\":\"H5\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").value(TOKEN))
                .andExpect(jsonPath("$.data.user.role").value("USER"));
        verify(service).login(new LoginDTO("alice", "fixture-credentials", "H5"), "192.0.2.20");
    }

    @Test
    void shouldApplyDtoValidationBeforeCallingBusinessCode() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").with(request -> { request.setRemoteAddr("x".repeat(65)); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"fixture-credentials\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void shouldGetCurrentUserFromBearerAndIgnoreTokenQueryParameter() throws Exception {
        when(service.getCurrentUser(TOKEN)).thenReturn(profile(UserRoleEnum.INSIDER));
        var completed = mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "bEaReR " + TOKEN)
                        .param("token", "ignored-query-credential").param("profile.id", "99")
                        .param("profile.role", "OWNER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("INSIDER"));
        // 同一次资料请求只核验一次；下一次请求仍必须重新进入业务鉴权。
        verify(service).getCurrentUser(TOKEN);
        verifyNoMoreInteractions(service);
        assertNoRequestIdentity(completed.andReturn().getRequest());
    }

    @Test
    void shouldRecheckEachRequestAndObserveRoleChangesAndRevocation() throws Exception {
        when(service.getCurrentUser(TOKEN)).thenReturn(profile(UserRoleEnum.INSIDER), profile(UserRoleEnum.USER))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("INSIDER"));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("USER"));
        var rejected = mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized()).andReturn();
        verify(service, times(3)).getCurrentUser(TOKEN);
        verifyNoMoreInteractions(service);
        assertNoRequestIdentity(rejected.getRequest());
    }

    @Test
    void shouldNotBindCurrentUserFromQueriesOrAStaleAttributeOnPublicRequests() throws Exception {
        var arguments = new AuthRequestArgumentResolver(validator);
        var rejected = mvc.perform(get("/api/__auth-test/unverified-profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .param("profile.id", "99").param("profile.role", "OWNER")
                        .with(request -> {
                            // 模拟前一次分发残留；当前分发必须先清理，公开入口不能凭旧属性取得身份。
                            arguments.bindCurrentUser(request, profile(UserRoleEnum.OWNER));
                            return request;
                        }))
                .andExpect(status().isUnauthorized()).andReturn();
        verifyNoInteractions(service);
        assertNoRequestIdentity(rejected.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Basic credential", "Bearer ", "Bearer  credential", "Bearer credential "})
    void shouldRejectMalformedBearerWithoutCallingBusinessCode(String authorization) throws Exception {
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectMissingDuplicateAndOversizedHeaders() throws Exception {
        mvc.perform(get("/api/auth/me").param("token", TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + "x".repeat(4097)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectRevokedIdentityAndKeepStorageFailureAs503() throws Exception {
        when(service.getCurrentUser(TOKEN)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(service).getCurrentUser(TOKEN);
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300));
    }

    @ParameterizedTest
    @EnumSource(UserRoleEnum.class)
    void shouldRequireRoleAndAllowOwnerDownwardCompatibility(UserRoleEnum role) throws Exception {
        when(service.getCurrentUser(TOKEN)).thenReturn(profile(role));
        mvc.perform(get("/api/__auth-test/insider").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().is(role.hasPermission(UserRoleEnum.INSIDER) ? 200 : 403));
        mvc.perform(get("/api/__auth-test/class/owner").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().is(role.hasPermission(UserRoleEnum.OWNER) ? 200 : 403));
        mvc.perform(get("/api/__auth-test/class/weaker-method").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().is(role.hasPermission(UserRoleEnum.INSIDER) ? 200 : 403));
    }

    @Test
    void shouldRequireIdentityForUnannotatedApiAndExposeContextOnlyDuringRequest() throws Exception {
        mvc.perform(get("/api/__auth-test/identity")).andExpect(status().isUnauthorized());
        when(service.getCurrentUser(TOKEN)).thenReturn(profile(UserRoleEnum.USER));
        mvc.perform(get("/api/__auth-test/identity").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("42"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldClearContextAfterControllerFailureAndBeforePublicRequest(CapturedOutput output) throws Exception {
        when(service.getCurrentUser(TOKEN)).thenReturn(profile(UserRoleEnum.USER));
        var failed = mvc.perform(get("/api/__auth-test/failure").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isInternalServerError()).andReturn();
        assertNoRequestIdentity(failed.getRequest());
        assertThat(UserContext.isAuthenticated()).isFalse();
        UserContext.set(99L, UserRoleEnum.OWNER);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @Test
    void shouldClearOriginalThreadWhenRequestSwitchesToAsync() throws Exception {
        when(service.getCurrentUser(TOKEN)).thenReturn(profile(UserRoleEnum.USER));
        var pending = mvc.perform(get("/api/__auth-test/async").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(UserContext.isAuthenticated()).isFalse();
        assertNoRequestIdentity(pending.getRequest());
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("42"));
        verify(service, times(2)).getCurrentUser(TOKEN);
        assertNoRequestIdentity(pending.getRequest());
    }

    @Test
    void shouldRecheckRevocationOnAsyncRedispatchRatherThanReuseItsFirstIdentity() throws Exception {
        when(service.getCurrentUser(TOKEN)).thenReturn(profile(UserRoleEnum.USER))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        var pending = mvc.perform(get("/api/__auth-test/async").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(request().asyncStarted()).andReturn();
        assertNoRequestIdentity(pending.getRequest());
        mvc.perform(asyncDispatch(pending)).andExpect(status().isUnauthorized());
        verify(service, times(2)).getCurrentUser(TOKEN);
        assertNoRequestIdentity(pending.getRequest());
    }

    @Test
    void shouldKeepExistingBodyTokenCommandsIndependentOfBearer() throws Exception {
        for (String command : new String[]{"logout", "deregister"}) {
            mvc.perform(post("/api/auth/" + command).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + TOKEN + "\"}"))
                    .andExpect(status().isOk());
        }
        verify(service).logout(TOKEN);
        verify(service).deregister(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:5173", "chrome-extension://fixture-extension"})
    void shouldAllowApprovedPreflightWithoutCredentials(String origin) throws Exception {
        mvc.perform(options("/api/auth/me").header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        verifyNoInteractions(service);
    }

    @Test
    void shouldNotLetCorsPermissionBypassAuthenticationAndRejectOtherOriginsAsJson() throws Exception {
        mvc.perform(get("/api/auth/me").header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        mvc.perform(options("/api/auth/me").header(HttpHeaders.ORIGIN, "https://unapproved.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
        mvc.perform(options("/api/auth/me").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
        verifyNoInteractions(service);
    }

    @Test
    void shouldKeepUnknownApiAs404InsteadOfInventingAuthenticationRoute() throws Exception {
        mvc.perform(get("/api/__auth-test/absent")).andExpect(status().isNotFound());
    }

    private static UserProfileVO profile(UserRoleEnum role) {
        return new UserProfileVO("42", "alice", "居民", null, role, "0", 1, "0");
    }

    private void assertNoRequestIdentity(jakarta.servlet.http.HttpServletRequest request) {
        assertThatThrownBy(() -> new AuthRequestArgumentResolver(validator).requireCurrentUser(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    private static UserProfileVO contextProfile() {
        return new UserProfileVO(UserContext.getUserId().toString(), "fixture", "请求身份", null,
                UserContext.getRole(), "0", 1, "0");
    }

    /** 未加角色注解的 API 仍默认要求登录，角色探针不调用收费服务。 */
    @RestController
    @RequestMapping("/api/__auth-test")
    public static class ProbeController {
        /** 公开方法没有经过鉴权，专用 DTO 必须拒绝解析，不绑定客户端资料或旧请求结果。 */
        @SkipBearerAuth
        @GetMapping("/unverified-profile")
        public Result<UserProfileVO> unverifiedProfile(@Valid CurrentUserDTO current) {
            return Result.success(current.profile());
        }

        /** 验证上下文已经由拦截器建立。 */
        @GetMapping("/identity")
        public Result<UserProfileVO> identity() { return Result.success(contextProfile()); }

        /** SkipBearerAuth 不能覆盖显式角色要求。 */
        @SkipBearerAuth
        @RequireRole(UserRoleEnum.INSIDER)
        @GetMapping("/insider")
        public Result<UserProfileVO> insider() { return Result.success(contextProfile()); }

        /** 异常路径必须清理身份且不能输出原始异常详情。 */
        @GetMapping("/failure")
        public Result<UserProfileVO> failure() { throw new IllegalStateException("private-web-fixture-detail"); }

        /** 异步任务显式捕获脱敏资料，不依赖跨线程传播 ThreadLocal。 */
        @GetMapping("/async")
        public Callable<Result<UserProfileVO>> async() {
            UserProfileVO profile = contextProfile();
            return () -> Result.success(profile);
        }
    }

    /** 类级与方法级门槛必须同时满足，不能由弱方法注解降低类级权限。 */
    @RestController
    @RequireRole(UserRoleEnum.INSIDER)
    @RequestMapping("/api/__auth-test/class")
    public static class RestrictedController {
        /** 方法可进一步提高权限。 */
        @RequireRole(UserRoleEnum.OWNER)
        @GetMapping("/owner")
        public Result<UserProfileVO> owner() { return Result.success(contextProfile()); }

        /** 低方法权限不会覆盖类级 INSIDER。 */
        @RequireRole(UserRoleEnum.USER)
        @GetMapping("/weaker-method")
        public Result<UserProfileVO> weakerMethod() { return Result.success(contextProfile()); }
    }
}

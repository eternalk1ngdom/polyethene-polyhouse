package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.context.UserContext;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.config.WebMvcConfig;
import com.polyethene.polyhouse.model.dto.PasswordChangeDTO;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import com.polyethene.polyhouse.service.AccountSecurityService;
import com.polyethene.polyhouse.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证首次改密的 HTTP 闸门和 ADMIN 精确授权；管理探针仅存在于测试。 */
@WebMvcTest(controllers = {AuthController.class, AccountSecurityController.class, AccountSecurityWebMvcTest.Probe.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({WebMvcConfig.class, AccountSecurityWebMvcTest.Probe.class})
class AccountSecurityWebMvcTest {
    @Autowired private MockMvc mvc;
    @MockBean private AuthService auth;
    @MockBean private AccountSecurityService security;

    @AfterEach
    void clearCheck() { assertThat(UserContext.isAuthenticated()).isFalse(); }

    @Test
    void shouldAllowMePasswordAndLogoutButDenyProtectedAccessForInitialCredentials() throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.USER, true));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer fixture"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.passwordChangeRequired").value(true));
        mvc.perform(get("/api/auth/growth").header("Authorization", "Bearer fixture"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40301));
        mvc.perform(post("/api/auth/password/change").header("Authorization", "Bearer fixture")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"old-fixture\",\"newPassword\":\"new-fixture\",\"userId\":99}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        verify(security).changePassword(new PasswordChangeDTO("old-fixture", "new-fixture"), "fixture");
        verify(auth, never()).getGrowthStatus(any());
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"fixture\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldValidateAuthenticationAndPasswordDtoBeforeChangingAnything() throws Exception {
        mvc.perform(post("/api/auth/password/change").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.USER, false));
        mvc.perform(post("/api/auth/password/change").header("Authorization", "Bearer fixture")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"old-fixture\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(security);
    }

    @ParameterizedTest
    @EnumSource(UserRoleEnum.class)
    void shouldNotGrantAdminAccessByInsiderWeight(UserRoleEnum role) throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(role, false));
        mvc.perform(get("/api/__security-test/admin").header("Authorization", "Bearer fixture"))
                .andExpect(status().is(role == UserRoleEnum.ADMIN || role == UserRoleEnum.OWNER ? 200 : 403));
    }

    private static UserProfileVO profile(UserRoleEnum role, boolean required) {
        return new UserProfileVO("42", "fixture", "测试", null, role, "0", 1, "0", required);
    }

    /** 无生产管理业务的权限探针，不调用数据库或收费接口。 */
    @RestController
    public static class Probe {
        /** 验证 ADMIN 的入口门槛不能被 INSIDER 权重越过。 */
        @RequireRole(UserRoleEnum.ADMIN)
        @GetMapping("/api/__security-test/admin")
        public Result<Void> admin() { return Result.success(); }
    }
}

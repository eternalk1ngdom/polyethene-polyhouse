package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.context.UserContext;
import com.polyethene.polyhouse.config.WebMvcConfig;
import com.polyethene.polyhouse.model.dto.AccountCreateDTO;
import com.polyethene.polyhouse.model.vo.AccountCreationVO;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import com.polyethene.polyhouse.service.AccountProvisioningService;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 MVC 验证匿名、四角色、首次限制、DTO 与不可缓存交付；业务并发另在隔离 MySQL 验收。 */
@WebMvcTest(controllers = AccountProvisioningController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(WebMvcConfig.class)
class AccountProvisioningWebMvcTest {
    @Autowired private MockMvc mvc;
    @MockBean private AuthService auth;
    @MockBean private AccountProvisioningService provisioning;
    private static final String REQUEST = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String BODY = "{\"requestId\":\"" + REQUEST + "\",\"role\":\"USER\"}";

    @AfterEach void contextCleared() { assertThat(UserContext.isAuthenticated()).isFalse(); }

    @ParameterizedTest @EnumSource(UserRoleEnum.class)
    void onlyOwnerCanCreateAndConfirm(UserRoleEnum role) throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(role, false));
        when(provisioning.create(any(), any())).thenReturn(new AccountCreationVO("42", "ph000001", "小屋住户000001", UserRoleEnum.USER, true, "Temporary-fixture!234"));
        when(provisioning.confirm(any(), any())).thenReturn(new AccountCreationVO("42", "ph000001", "小屋住户000001", UserRoleEnum.USER, false, null));
        mvc.perform(post("/api/manage/accounts").header("Authorization", "Bearer fixture").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(role == UserRoleEnum.OWNER ? 200 : 403));
        mvc.perform(get("/api/manage/accounts/creation").param("requestId", REQUEST).header("Authorization", "Bearer fixture"))
                .andExpect(status().is(role == UserRoleEnum.OWNER ? 200 : 403));
        if (role != UserRoleEnum.OWNER) verifyNoInteractions(provisioning);
    }

    @Test void shouldValidateAndDisableCacheWithoutBindingSensitiveExtraFields() throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, false));
        when(provisioning.create(any(), any())).thenReturn(new AccountCreationVO("42", "ph000001", "小屋住户000001", UserRoleEnum.USER, true, "Temporary-fixture!234"));
        mvc.perform(post("/api/manage/accounts").header("Authorization", "Bearer fixture").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("}", ",\"username\":\"override\",\"experience\":999,\"id\":99}")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.username").value("ph000001"));
        verify(provisioning).create(new AccountCreateDTO(REQUEST, UserRoleEnum.USER, null), "fixture");
        for (String body : new String[]{"{}", BODY.replace(REQUEST, "bad"), BODY.replace("USER", "UNKNOWN"), BODY.replace("USER", "ADMIN")}) {
            mvc.perform(post("/api/manage/accounts").header("Authorization", "Bearer fixture").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(new AccountCreateDTO(REQUEST, UserRoleEnum.ADMIN, "private-fixture").toString()).doesNotContain("private-fixture");
    }

    @Test void shouldRejectAnonymousAndInitialOwnerBeforeService() throws Exception {
        mvc.perform(post("/api/manage/accounts").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, true));
        mvc.perform(post("/api/manage/accounts").header("Authorization", "Bearer fixture").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40301));
        verifyNoInteractions(provisioning);
    }

    private UserProfileVO profile(UserRoleEnum role, boolean initial) {
        return new UserProfileVO("42", "fixture", "测试", null, role, "0", 1, "0", initial);
    }
}

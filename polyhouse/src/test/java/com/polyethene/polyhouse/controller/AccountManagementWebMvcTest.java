package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.context.UserContext;
import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 合约验收：精确角色、首次限制、DTO、URI 编号防覆盖和禁止缓存，不用假测试代替真实事务验收。 */
@WebMvcTest(controllers = AccountManagementController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({WebMvcConfig.class, AccountTargetConverter.class})
class AccountManagementWebMvcTest {
    @Autowired private MockMvc mvc;
    @MockBean private AuthService auth;
    @MockBean private AccountManagementService management;
    private static final String ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String BODY = """
        {"requestId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","expectedVersion":"0","expectedRole":"USER","expectedStatus":1,"reason":"测试封禁"}
        """;
    @BeforeEach void setup() {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, false));
        when(management.list(any(), any())).thenReturn(new AccountPageVO(List.of(), "0", 1, 20));
    }
    @AfterEach void cleared() { assertThat(UserContext.isAuthenticated()).isFalse(); }
    @ParameterizedTest @EnumSource(UserRoleEnum.class)
    void exactRoleAndMethodGates(UserRoleEnum role) throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(role, false));
        boolean manager = role == UserRoleEnum.OWNER || role == UserRoleEnum.ADMIN;
        mvc.perform(get("/api/manage/accounts").header("Authorization", "Bearer fixture"))
                .andExpect(status().is(manager ? 200 : 403));
        for (String suffix : List.of("ban", "unban", "password/reset")) {
            mvc.perform(post("/api/manage/accounts/42/" + suffix).header("Authorization", "Bearer fixture")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().is((suffix.equals("ban") ? manager : role == UserRoleEnum.OWNER) ? 200 : 403));
        }
        if (!manager) verifyNoInteractions(management);
    }
    @Test void targetComesOnlyFromUriAndResponseCannotBeCached() throws Exception {
        mvc.perform(post("/api/manage/accounts/42/ban?id=999").header("Authorization", "Bearer fixture")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(management).operate(new AccountTargetDTO(42L), new AccountOperationDTO(ID, 0L, UserRoleEnum.USER, 1, "测试封禁", null), AccountManagementAction.BAN, "fixture");
        mvc.perform(get("/api/manage/accounts").header("Authorization", "Bearer fixture"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.total").value("0"));
    }
    @Test void invalidQueryAndOperationNeverReachService() throws Exception {
        for (String query : List.of("page=0", "pageSize=51", "role=UNKNOWN", "status=2", "username=%25")) {
            mvc.perform(get("/api/manage/accounts?" + query).header("Authorization", "Bearer fixture")).andExpect(status().isBadRequest());
        }
        for (String body : List.of("{}", BODY.replace(ID, "bad"), BODY.replace("测试封禁", ""), BODY.replace("USER", "UNKNOWN"), BODY.replace("\"0\"", "-1"))) {
            mvc.perform(post("/api/manage/accounts/42/ban").header("Authorization", "Bearer fixture")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/manage/accounts/invalid/ban").header("Authorization", "Bearer fixture")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(management);
    }
    @Test void anonymousAndInitialManagerCannotProceed() throws Exception {
        mvc.perform(get("/api/manage/accounts")).andExpect(status().isUnauthorized());
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, true));
        mvc.perform(get("/api/manage/accounts").header("Authorization", "Bearer fixture"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40301));
        verifyNoInteractions(management);
    }
    @Test void confirmationValidatedAndRedacted() throws Exception {
        mvc.perform(get("/api/manage/accounts/operations/confirmation?requestId=bad").header("Authorization", "Bearer fixture"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/manage/accounts/operations/confirmation?requestId=" + ID).header("Authorization", "Bearer fixture"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        assertThat(new AccountOperationDTO(ID, 0L, UserRoleEnum.USER, 1, "private-reason", "private-password").toString()).doesNotContain("private-password", "private-reason");
    }
    private UserProfileVO profile(UserRoleEnum role, boolean first) { return new UserProfileVO("1", "fixture", "测试", null, role, "0", 1, "0", first); }
}

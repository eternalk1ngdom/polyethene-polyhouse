package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.config.WebMvcConfig;
import com.polyethene.polyhouse.controller.TrendJobController;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.*;
import com.polyethene.polyhouse.service.trend.TrendJobThrottle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 精确四角色、首次改密、DTO、异步受理及冷却响应在真实MVC链中验证。 */
@WebMvcTest(TrendJobController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(WebMvcConfig.class)
class TrendJobControllerTest {
    private static final String ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String BODY = "{\"requestId\":\"" + ID + "\"}";
    @Autowired private MockMvc mvc;
    @MockBean private AuthService auth;
    @MockBean private TrendJobManagementService jobs;

    @ParameterizedTest @EnumSource(UserRoleEnum.class)
    void onlyOwnerCanSubmitAndRead(UserRoleEnum role) throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(role, false));
        when(jobs.submit(any(), any())).thenReturn(new TrendJobSubmissionVO(true, false, job()));
        when(jobs.query(any(), any())).thenReturn(job());
        mvc.perform(post("/api/manage/trend/jobs").header("Authorization", "Bearer fixture")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().is(role == UserRoleEnum.OWNER ? 202 : 403));
        mvc.perform(get("/api/manage/trend/jobs").param("jobId", ID).header("Authorization", "Bearer fixture"))
                .andExpect(status().is(role == UserRoleEnum.OWNER ? 200 : 403));
        if (role != UserRoleEnum.OWNER) verifyNoInteractions(jobs);
    }

    @Test void anonymousAndInitialOwnerCannotReachBusiness() throws Exception {
        mvc.perform(post("/api/manage/trend/jobs").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, true));
        mvc.perform(post("/api/manage/trend/jobs").header("Authorization", "Bearer fixture").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40301));
        verifyNoInteractions(jobs);
    }

    @Test void malformedInputsFailAndAcceptedResponseDoesNotExposeAuthority() throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, false));
        for (String body : new String[]{"{}", "{\"requestId\":\"bad\"}"}) {
            mvc.perform(post("/api/manage/trend/jobs").header("Authorization", "Bearer fixture").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/manage/trend/jobs").param("jobId", "bad").header("Authorization", "Bearer fixture")).andExpect(status().isBadRequest());
        verifyNoInteractions(jobs);
        when(jobs.submit(any(), any())).thenReturn(new TrendJobSubmissionVO(true, false, job()));
        mvc.perform(post("/api/manage/trend/jobs").header("Authorization", "Bearer fixture").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.job.state").value("QUEUED"))
                .andExpect(jsonPath("$.data.job.credentialVersion").doesNotExist()).andExpect(jsonPath("$.data.job.generation").doesNotExist());
    }

    @Test void cooldownHasRetryAfter() throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, false));
        when(jobs.submit(any(), any())).thenThrow(new TrendJobThrottle(24));
        mvc.perform(post("/api/manage/trend/jobs").header("Authorization", "Bearer fixture").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "24"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value(42900));
    }

    private static TrendJobVO job() {
        return new TrendJobVO(ID, "QUEUED", "QUEUED", false, TrendAiFixture.NOW, null, null, TrendAiFixture.NOW.plusSeconds(1800), null, null);
    }
    private static UserProfileVO profile(UserRoleEnum role, boolean initial) {
        return new UserProfileVO("42", "fixture", "测试", null, role, "0", 1, "0", initial);
    }
}

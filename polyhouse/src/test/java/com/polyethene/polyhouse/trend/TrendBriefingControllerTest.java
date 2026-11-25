package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.config.WebMvcConfig;
import com.polyethene.polyhouse.controller.TrendBriefingController;
import com.polyethene.polyhouse.model.vo.TrendBriefingGenerateVO;
import com.polyethene.polyhouse.model.vo.TrendBriefingVO;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import com.polyethene.polyhouse.service.AuthService;
import com.polyethene.polyhouse.service.TrendBriefingService;
import com.polyethene.polyhouse.service.trend.TrendJobThrottle;
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

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 MVC 验证简报触发接口仅对已完成改密的 OWNER 开放，参数校验、no-store 与 429 冷却语义严格生效。 */
@WebMvcTest(TrendBriefingController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(WebMvcConfig.class)
class TrendBriefingControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private AuthService auth;
    @MockBean private TrendBriefingService service;

    @ParameterizedTest @EnumSource(UserRoleEnum.class)
    void onlyOwnerCanTriggerBriefingGeneration(UserRoleEnum role) throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(role, false));
        when(service.generate(any(), any())).thenReturn(sampleResult());

        int expected = role == UserRoleEnum.OWNER ? 200 : 403;
        mvc.perform(post("/api/manage/trend/briefings")
                        .header("Authorization", "Bearer fixture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(expected))
                .andExpect(header().string("Cache-Control", "no-store"));

        if (role != UserRoleEnum.OWNER) {
            verifyNoInteractions(service);
        }
    }

    @Test void rejectsAnonymousBriefingRequest() throws Exception {
        mvc.perform(post("/api/manage/trend/briefings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void rejectsInitialPasswordOwnerBriefingRequest() throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, true));
        mvc.perform(post("/api/manage/trend/briefings")
                        .header("Authorization", "Bearer fixture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        verifyNoInteractions(service);
    }

    @Test void rejectsMalformedPeriodKeyBeforeService() throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, false));
        mvc.perform(post("/api/manage/trend/briefings")
                        .header("Authorization", "Bearer fixture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodKey\":\"bad-date\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        verifyNoInteractions(service);
    }

    @Test void returns429WithRetryAfterWhenThrottled() throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, false));
        when(service.generate(any(), any())).thenThrow(new TrendJobThrottle(12));

        mvc.perform(post("/api/manage/trend/briefings")
                        .header("Authorization", "Bearer fixture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodKey\":\"2026-11-23\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private static UserProfileVO profile(UserRoleEnum role, boolean initial) {
        return new UserProfileVO("42", "fixture", "测试", null, role, "0", 1, "0", initial);
    }

    private static TrendBriefingGenerateVO sampleResult() {
        var anchor = LocalDate.of(2026, 11, 23);
        var briefing = new TrendBriefingVO("READY", anchor, anchor.minusDays(6), anchor, 3, "LIMITED",
                3, 5, true, null, false, TrendAiFixture.NOW, "deepseek-flash", "briefing-1",
                "本地代理编排与离线配置校验成为本周集中方向",
                "本期覆盖 3 天日榜，属于有限样本观察。", List.of(), List.of("样本覆盖仅 3 天。"));
        return new TrendBriefingGenerateVO("GENERATED", true, briefing);
    }
}

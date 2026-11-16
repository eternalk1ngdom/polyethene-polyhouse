package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.config.WebMvcConfig;
import com.polyethene.polyhouse.controller.TrendFeeController;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实MVC验证费用三入口只对屋主开放，确认字段及金额在业务前校验，响应禁缓存。 */
@WebMvcTest(TrendFeeController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(WebMvcConfig.class)
class TrendFeeControllerTest {
    private static final String ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String REQUEST = "11111111-2222-4333-8444-555555555555";
    private static final String BODY = "{\"resolutionId\":\"" + ID + "\",\"requestId\":\"" + REQUEST
            + "\",\"expectedReservedMicros\":2800,\"confirmation\":\"COUNT_RESERVED\",\"reason\":\"RESULT_UNRECOVERABLE\"}";
    @Autowired private MockMvc mvc;
    @MockBean private AuthService auth;
    @MockBean private TrendFeeService fees;

    @ParameterizedTest @EnumSource(UserRoleEnum.class)
    void onlyOwnerCanReadResolveAndConfirm(UserRoleEnum role) throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(role, false));
        when(fees.query(any(), any())).thenReturn(new TrendFeeVO("2026-11", 10_000_000, 0, 0, 10_000_000, 0, false, List.of(), null));
        when(fees.resolve(any(), any())).thenReturn(resolution()); when(fees.confirm(any(), any())).thenReturn(resolution());
        int status = role == UserRoleEnum.OWNER ? 200 : 403;
        mvc.perform(get("/api/manage/trend/fees").header("Authorization", "Bearer fixture")).andExpect(status().is(status));
        mvc.perform(post("/api/manage/trend/fees/resolutions").header("Authorization", "Bearer fixture")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().is(status));
        mvc.perform(get("/api/manage/trend/fees/resolution").param("resolutionId", ID).header("Authorization", "Bearer fixture"))
                .andExpect(status().is(status));
        if (role != UserRoleEnum.OWNER) verifyNoInteractions(fees);
    }

    @Test void anonymousAndInitialOwnerCannotReachFeeService() throws Exception {
        mvc.perform(get("/api/manage/trend/fees")).andExpect(status().isUnauthorized());
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, true));
        mvc.perform(post("/api/manage/trend/fees/resolutions").header("Authorization", "Bearer fixture")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40301));
        verifyNoInteractions(fees);
    }

    @Test void amountConfirmationReasonAndBoundedQueryAreValidatedBeforeService() throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, false));
        for (String body : List.of("{}", BODY.replace("COUNT_RESERVED", "REFUND"), BODY.replace("2800", "0"),
                BODY.replace("2800", "10000001"), BODY.replace(ID, "bad"), BODY.replace("RESULT_UNRECOVERABLE", "raw-private-key"))) {
            mvc.perform(post("/api/manage/trend/fees/resolutions").header("Authorization", "Bearer fixture").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        for (String query : List.of("month=2026-13", "month=0000-01", "limit=51", "limit=0", "after=bad")) {
            mvc.perform(get("/api/manage/trend/fees?" + query).header("Authorization", "Bearer fixture")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/manage/trend/fees/resolution?resolutionId=bad").header("Authorization", "Bearer fixture")).andExpect(status().isBadRequest());
        verifyNoInteractions(fees);
    }

    @Test void privateAccountingResponseDoesNotExposeOwnerVersionOrRawMaterial() throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(UserRoleEnum.OWNER, false));
        when(fees.resolve(any(), any())).thenReturn(resolution());
        mvc.perform(post("/api/manage/trend/fees/resolutions").header("Authorization", "Bearer fixture").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.accountedMicros").value(2800)).andExpect(jsonPath("$.data.actorId").doesNotExist())
                .andExpect(jsonPath("$.data.credentialVersion").doesNotExist()).andExpect(jsonPath("$.data.apiKey").doesNotExist());
    }

    private static UserProfileVO profile(UserRoleEnum role, boolean initial) {
        return new UserProfileVO("42", "fixture", "测试", null, role, "0", 1, "0", initial);
    }
    private static TrendFeeResolutionVO resolution() {
        return new TrendFeeResolutionVO(ID, REQUEST, "2026-11", 2800, "RESULT_UNRECOVERABLE", TrendAiFixture.NOW, false);
    }
}

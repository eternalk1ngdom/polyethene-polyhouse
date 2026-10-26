package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.common.exception.GlobalExceptionHandler;
import com.polyethene.polyhouse.model.dto.SessionTokenDTO;
import com.polyethene.polyhouse.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证真实 MVC 的 DTO 校验、路由和错误响应，避免只调用 Java 方法却遗漏 HTTP 接线。 */
class AuthControllerTest {

    private AuthService service;
    private MockMvc mvc;

    @BeforeEach
    void prepareMvc() {
        service = mock(AuthService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void shouldRouteLogoutAndDeregisterToExistingServiceAndReturnNoCredentials() throws Exception {
        for (String command : new String[]{"logout", "deregister"}) {
            mvc.perform(post("/api/auth/" + command).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"opaque-test-credential\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").isEmpty());
        }
        verify(service).logout("opaque-test-credential");
        verify(service).deregister("opaque-test-credential");
    }

    @Test
    void shouldRejectMissingBlankOversizedOrMalformedRequestBeforeServiceCall() throws Exception {
        for (String command : new String[]{"logout", "deregister"}) {
            for (String body : new String[]{"{}", "{\"token\":\" \"}", "{", "",
                    "{\"token\":\"" + "x".repeat(4097) + "\"}"}) {
                mvc.perform(post("/api/auth/" + command).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void shouldPreserveUnauthorizedAndStorageFailureHttpStatus() throws Exception {
        doThrow(new BusinessException(ErrorCode.UNAUTHORIZED)).when(service).deregister("expired-credential");
        mvc.perform(post("/api/auth/deregister").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"expired-credential\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(service).logout("retry-credential");
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"retry-credential\"}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(new SessionTokenDTO("hidden-credential").toString()).doesNotContain("hidden-credential");
    }
}

package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.WebMvcConfig;
import com.polyethene.polyhouse.controller.TrendReadinessController;
import com.polyethene.polyhouse.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TrendReadinessController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(WebMvcConfig.class)
class TrendReadinessControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private AuthService auth;
    @MockBean private ApplicationAvailability availability;

    @Test void readyIsAnonymousUncachedAndLimitedToApplicationScope() throws Exception {
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        mvc.perform(get("/api/trend/readiness").header("Authorization", "Bearer malformed"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.scope").value("APPLICATION"));
        verifyNoInteractions(auth);
    }

    @ParameterizedTest(name = "非就绪状态返回 503 与 Retry-After：{0}")
    @MethodSource("unreadyStates")
    void refusingOrUnknownStateNeverClaimsReady(ReadinessState state) throws Exception {
        when(availability.getReadinessState()).thenReturn(state);
        mvc.perform(get("/api/trend/readiness")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value(50300)).andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(auth);
    }

    private static Stream<ReadinessState> unreadyStates() {
        return Stream.of(ReadinessState.REFUSING_TRAFFIC, null);
    }

    @Test void headSharesReadyStateAndPostCannotTriggerWork() throws Exception {
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        // MockMvc不模拟Tomcat对HEAD正文的抑制；真实HTTP正文断言在TrendReadLimitHttpTest。
        mvc.perform(head("/api/trend/readiness")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/trend/readiness")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(auth);
    }
}

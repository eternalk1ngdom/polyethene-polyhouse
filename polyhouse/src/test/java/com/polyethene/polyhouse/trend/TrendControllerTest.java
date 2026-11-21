package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.WebMvcConfig;
import com.polyethene.polyhouse.controller.TrendController;
import com.polyethene.polyhouse.model.vo.TrendPeriodsVO;
import com.polyethene.polyhouse.service.AuthService;
import com.polyethene.polyhouse.service.TrendReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TrendController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(WebMvcConfig.class)
class TrendControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private AuthService auth;
    @MockBean private TrendReportService reports;

    @Test void anonymousReadsPublishedDataWithoutRestoringIdentity() throws Exception {
        when(reports.report(any())).thenReturn(TrendFixture.report());
        when(reports.periods(any())).thenReturn(new TrendPeriodsVO("DAILY", List.of(), null));
        mvc.perform(get("/api/trend/report")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items[0].insightStatus").value("NOT_ANALYZED"))
                .andExpect(jsonPath("$.data.items[0].readmeText").doesNotExist());
        mvc.perform(get("/api/trend/periods")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        verifyNoInteractions(auth);
    }

    @Test void rejectsUnsupportedCycleMalformedDateAndUnboundedPageBeforeService() throws Exception {
        for (String path : List.of("/api/trend/report?periodType=WEEKLY", "/api/trend/report?periodKey=bad",
                "/api/trend/periods?limit=31", "/api/trend/periods?limit=0", "/api/trend/periods?before=bad")) {
            mvc.perform(get(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        }
        verifyNoInteractions(reports, auth);
    }
}

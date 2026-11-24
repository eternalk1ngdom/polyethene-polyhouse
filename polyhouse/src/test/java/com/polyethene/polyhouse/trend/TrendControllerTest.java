package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.WebMvcConfig;
import com.polyethene.polyhouse.controller.TrendController;
import com.polyethene.polyhouse.model.vo.TrendBriefingVO;
import com.polyethene.polyhouse.model.vo.TrendPeriodsVO;
import com.polyethene.polyhouse.service.AuthService;
import com.polyethene.polyhouse.service.TrendBriefingService;
import com.polyethene.polyhouse.service.TrendReportService;
import com.polyethene.polyhouse.service.trend.TrendWindowAggregator;
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
    @MockBean private TrendBriefingService briefings;

    @Test void anonymousReadsPublishedDataWithoutRestoringIdentity() throws Exception {
        var sampleReport = TrendFixture.report();
        when(reports.report(any())).thenReturn(sampleReport);
        when(reports.periods(any())).thenReturn(new TrendPeriodsVO("DAILY", List.of(), null));
        when(reports.window(any())).thenReturn(TrendWindowAggregator.aggregate(sampleReport.periodKey(), List.of(sampleReport)));
        when(briefings.query(any())).thenReturn(new TrendBriefingVO("ABSENT", sampleReport.periodKey(),
                sampleReport.periodKey().minusDays(6), sampleReport.periodKey(), 1, "INSUFFICIENT", 1, 0,
                false, "INSUFFICIENT_COVERED_DAYS", false, null, null, null, null, null, List.of(), List.of()));
        mvc.perform(get("/api/trend/report")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items[0].insightStatus").value("NOT_ANALYZED"))
                .andExpect(jsonPath("$.data.items[0].readmeText").doesNotExist());
        mvc.perform(get("/api/trend/periods")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/trend/window")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.windowDays").value(7))
                .andExpect(jsonPath("$.data.coveredDays").value(1));
        mvc.perform(get("/api/trend/briefing")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("ABSENT"));
        verifyNoInteractions(auth);
    }

    @Test void rejectsUnsupportedCycleMalformedDateAndUnboundedPageBeforeService() throws Exception {
        for (String path : List.of("/api/trend/report?periodType=WEEKLY", "/api/trend/report?periodKey=bad",
                "/api/trend/window?periodType=WEEKLY", "/api/trend/window?periodKey=bad",
                "/api/trend/briefing?periodKey=bad",
                "/api/trend/periods?limit=31", "/api/trend/periods?limit=0", "/api/trend/periods?before=bad")) {
            mvc.perform(get(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        }
        verifyNoInteractions(reports, briefings, auth);
    }
}

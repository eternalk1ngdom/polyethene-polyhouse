package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.mapper.TrendMapper;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.entity.TrendReportRecord;
import com.polyethene.polyhouse.service.TrendReportService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrendReportServiceTest {
    private final TrendMapper mapper = mock(TrendMapper.class);
    private final TrendReportService service = new TrendReportService(mapper, TrendFixture.json(), new TrendProperties(true, null));

    @Test void latestMissingIs404WhileHistoryCanBeEmpty() {
        assertCode(() -> service.report(new TrendReportQueryDTO(null, null)), ErrorCode.NOT_FOUND);
        when(mapper.periods(null, 15)).thenReturn(List.of());
        assertThat(service.periods(new TrendPeriodsQueryDTO(null, null, null)).items()).isEmpty();
    }

    @Test void calendarValidationDoesNotFallThroughToDatabase() {
        for (String date : List.of("2026-02-30", "2026-13-01", "0000-01-01", "2026-1-01", "2026-01-01' OR 1=1")) {
            assertCode(() -> service.report(new TrendReportQueryDTO("DAILY", date)), ErrorCode.INVALID_PARAMETER);
        }
        verifyNoInteractions(mapper);
    }

    @Test void keysetPageHasExclusiveCursorAndNoUnboundedCount() {
        LocalDate today = LocalDate.of(2026, 11, 16);
        when(mapper.periods(today, 3)).thenReturn(List.of(today.minusDays(1), today.minusDays(2), today.minusDays(3)));
        var result = service.periods(new TrendPeriodsQueryDTO(null, today.toString(), 2));
        assertThat(result.items()).containsExactly(today.minusDays(1), today.minusDays(2));
        assertThat(result.nextBefore()).isEqualTo(today.minusDays(2));
        verify(mapper).periods(today, 3);
    }

    @Test void disabledOrUnavailableStorageReturnsSafe503() {
        var disabled = new TrendReportService(mapper, TrendFixture.json(), new TrendProperties(false, null));
        assertCode(() -> disabled.report(new TrendReportQueryDTO(null, null)), ErrorCode.SERVICE_UNAVAILABLE);
        verifyNoInteractions(mapper);
        when(mapper.latest()).thenThrow(new DataAccessResourceFailureException("private connection information"));
        assertCode(() -> service.report(new TrendReportQueryDTO(null, null)), ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test void refusesDamagedOrMismatchedSavedVersions() throws Exception {
        var view = TrendFixture.report();
        when(mapper.latest()).thenReturn(new TrendReportRecord("wrong-id", view.periodKey(), null, TrendFixture.json().writeValueAsString(view)));
        assertCode(() -> service.report(new TrendReportQueryDTO(null, null)), ErrorCode.SERVICE_UNAVAILABLE);
        when(mapper.latest()).thenReturn(new TrendReportRecord(view.reportId(), view.periodKey(), null, "{}"));
        assertCode(() -> service.report(new TrendReportQueryDTO(null, null)), ErrorCode.SERVICE_UNAVAILABLE);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
    }
}

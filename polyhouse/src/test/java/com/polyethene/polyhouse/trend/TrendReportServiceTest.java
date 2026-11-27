package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.mapper.TrendMapper;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.entity.TrendPeriodBounds;
import com.polyethene.polyhouse.model.entity.TrendReportRecord;
import com.polyethene.polyhouse.service.TrendReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
        assertThat(service.periods(new TrendPeriodsQueryDTO(null, null, null, null)).items()).isEmpty();
    }

    @ParameterizedTest(name = "拒绝非法日报日期参数：{0}")
    @ValueSource(strings = {"2026-02-30", "2026-13-01", "0000-01-01", "2026-1-01", "2026-01-01' OR 1=1"})
    void calendarValidationDoesNotFallThroughToDatabase(String date) {
        assertCode(() -> service.report(new TrendReportQueryDTO("DAILY", date)), ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(mapper);
    }

    @Test void keysetPageHasExclusiveCursorAndNoUnboundedCount() {
        LocalDate today = LocalDate.of(2026, 11, 16);
        when(mapper.periods(today, 3)).thenReturn(List.of(today.minusDays(1), today.minusDays(2), today.minusDays(3)));
        when(mapper.periodBounds()).thenReturn(new TrendPeriodBounds(LocalDate.of(2026, 10, 1), today));
        var result = service.periods(new TrendPeriodsQueryDTO(null, today.toString(), 2, null));
        assertThat(result.items()).containsExactly(today.minusDays(1), today.minusDays(2));
        assertThat(result.nextBefore()).isEqualTo(today.minusDays(2));
        assertThat(result.earliestDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(result.latestDate()).isEqualTo(today);
        verify(mapper).periods(today, 3);
    }

    @Test void monthQueryReturnsAllPublishedDaysInMonthWithBoundsAndNullCursor() {
        var bounds = new TrendPeriodBounds(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 11, 27));
        when(mapper.periodBounds()).thenReturn(bounds);
        when(mapper.periodsInMonth(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)))
                .thenReturn(List.of(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 14)));
        var result = service.periods(new TrendPeriodsQueryDTO("DAILY", null, null, "2024-02"));
        assertThat(result.items()).containsExactly(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 14));
        assertThat(result.nextBefore()).isNull();
        assertThat(result.earliestDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(result.latestDate()).isEqualTo(LocalDate.of(2026, 11, 27));
        verify(mapper).periodsInMonth(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29));
        verify(mapper, never()).periods(any(), anyInt());
    }

    @ParameterizedTest(name = "拒绝非法或互斥的按月查询参数：before={0}, month={1}")
    @CsvSource(value = {
            "2026-11-16, 2026-11",
            "null, 2026-13",
            "null, 2026-00",
            "null, 0000-01",
            "null, 2026-1",
            "null, bad"
    }, nullValues = "null")
    void rejectsMutuallyExclusiveOrMalformedMonthQueryBeforeDatabase(String before, String month) {
        assertCode(() -> service.periods(new TrendPeriodsQueryDTO("DAILY", before, 14, month)), ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(mapper);
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

    @Test void oldReportsWithoutInsightFieldRemainReadable() throws Exception {
        var view = TrendFixture.report();
        var tree = TrendFixture.json().valueToTree(view);
        for (var item : tree.path("items")) ((com.fasterxml.jackson.databind.node.ObjectNode)item).remove("insight");
        when(mapper.latest()).thenReturn(new TrendReportRecord(view.reportId(), view.periodKey(), null, tree.toString()));
        var result = service.report(new TrendReportQueryDTO(null, null));
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().getFirst().insight()).isNull();
        assertThat(result.insightComplete()).isFalse();
    }

    @Test void onlyTrustedMissingArticleIdsFromLatestPublicationAffectPriority() throws Exception {
        assertThat(service.missingInsightIds()).isEmpty();
        var report = TrendFixture.report();
        var tree = TrendFixture.json().valueToTree(report);
        var items = (com.fasterxml.jackson.databind.node.ArrayNode) tree.path("items");
        var first = (com.fasterxml.jackson.databind.node.ObjectNode) items.get(0);
        first.put("repositoryId", "42");
        var docs = (com.fasterxml.jackson.databind.node.ObjectNode) items.get(1);
        docs.put("repositoryId", "7").put("repoType", "DOC_LIST");
        items.add(first.deepCopy().put("repositoryId", "bad"));
        items.add(first.deepCopy().put("repositoryId", "43").set("insight", TrendFixture.json().valueToTree(TrendAiFixture.insight(TrendAiFixture.NOW))));
        when(mapper.latest()).thenReturn(new TrendReportRecord(report.reportId(), report.periodKey(), null, tree.toString()));
        assertThat(service.missingInsightIds()).containsExactly("42");
        when(mapper.latest()).thenThrow(new DataAccessResourceFailureException("private"));
        assertThat(service.missingInsightIds()).isEmpty();
    }

    @ParameterizedTest(name = "拒绝非法窗口查询：periodType={0}, periodKey={1}")
    @CsvSource(value = {"WEEKLY, null", "DAILY, 2026-02-30"}, nullValues = "null")
    void rejectsInvalidWindowQueryBeforeDatabase(String periodType, String periodKey) {
        assertCode(() -> service.window(new TrendWindowQueryDTO(periodType, periodKey)), ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(mapper);
    }

    @Test void windowStatsReadsBoundedSevenDayRangeAndRejectsDamagedArchive() throws Exception {
        assertCode(() -> service.window(new TrendWindowQueryDTO("DAILY", null)), ErrorCode.NOT_FOUND);

        var view = TrendFixture.report();
        LocalDate anchor = view.periodKey();
        var header = new TrendReportRecord(view.reportId(), anchor, null, null);
        var full = new TrendReportRecord(view.reportId(), anchor, null, TrendFixture.json().writeValueAsString(view));
        when(mapper.latestHeader()).thenReturn(header);
        when(mapper.publishedRange(anchor.minusDays(6), anchor)).thenReturn(List.of(full));
        var stats = service.window(new TrendWindowQueryDTO(null, null));
        assertThat(stats.anchorDate()).isEqualTo(anchor);
        assertThat(stats.coveredDays()).isEqualTo(1);
        assertThat(stats.missingDates()).hasSize(6);

        // 区间内任一快照损坏时拒绝伪装为完整窗口统计。
        when(mapper.publishedRange(anchor.minusDays(6), anchor))
                .thenReturn(List.of(full, new TrendReportRecord("bad-id", anchor.minusDays(1), null, "{}")));
        assertCode(() -> service.window(new TrendWindowQueryDTO("DAILY", null)), ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test void reportEnrichesPresenceFromPriorSevenDaysAndDegradesGracefullyOnHistoryFailure() throws Exception {
        var current = TrendFixture.report();
        LocalDate anchor = current.periodKey();
        var currentRecord = new TrendReportRecord(current.reportId(), anchor, null, TrendFixture.json().writeValueAsString(current));
        when(mapper.latest()).thenReturn(currentRecord);

        // 构造昨日快照：仅保留当前第 1 项，模拟第 2 项为新上榜、另有一条昨日退榜项。
        var yesterdayDate = anchor.minusDays(1);
        var kept = current.items().getFirst();
        var dropped = new com.polyethene.polyhouse.model.vo.TrendProjectVO(
                3, "999", "org/yesterday-gone", "https://github.com/org/yesterday-gone",
                "gone", "Go", 500L, 80L, 10L, "MIT", false, "CODE_PROJECT",
                "HAS_LANGUAGE", "AVAILABLE", "AVAILABLE", "NOT_ANALYZED");
        var yesterday = new com.polyethene.polyhouse.model.vo.TrendReportVO(
                "22222222-2222-2222-2222-222222222222", "DAILY", yesterdayDate,
                current.capturedAt().minusSeconds(86400), current.publishedAt().minusSeconds(86400),
                current.sourceUrl(), false, List.of(kept, dropped));
        var yesterdayRecord = new TrendReportRecord(yesterday.reportId(), yesterdayDate, null,
                TrendFixture.json().writeValueAsString(yesterday));

        when(mapper.publishedRange(anchor.minusDays(7), anchor.minusDays(1))).thenReturn(List.of(yesterdayRecord));
        var enriched = service.report(new TrendReportQueryDTO(null, null));
        assertThat(enriched.presenceSummary()).isNotNull();
        assertThat(enriched.presenceSummary().previousDate()).isEqualTo(yesterdayDate);
        assertThat(enriched.presenceSummary().newCount()).isEqualTo(1);
        assertThat(enriched.presenceSummary().streakCount()).isEqualTo(1);
        assertThat(enriched.presenceSummary().droppedItems()).hasSize(1);
        assertThat(enriched.presenceSummary().droppedItems().getFirst().fullName()).isEqualTo("org/yesterday-gone");

        // 历史查询异常或快照损坏时优雅降级隐藏异动标识，绝不阻断当期日报主阅读。
        when(mapper.publishedRange(anchor.minusDays(7), anchor.minusDays(1)))
                .thenThrow(new DataAccessResourceFailureException("history timeout"));
        var degraded = service.report(new TrendReportQueryDTO(null, null));
        assertThat(degraded.reportId()).isEqualTo(current.reportId());
        assertThat(degraded.presenceSummary()).isNull();
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
    }
}

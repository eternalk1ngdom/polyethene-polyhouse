package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.model.entity.*;
import com.polyethene.polyhouse.model.vo.TrendInsightVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.model.vo.TrendPublicationSummaryVO;
import com.polyethene.polyhouse.model.entity.TrendSourceIssue.Reason;
import com.polyethene.polyhouse.model.entity.TrendSourceIssue.Stage;
import com.polyethene.polyhouse.service.*;
import com.polyethene.polyhouse.service.trend.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class TrendAiServiceTest {
    private final TrendAiLedger ledger = mock(TrendAiLedger.class);
    private final DeepSeekGateway gateway = mock(DeepSeekGateway.class);
    private final TrendInsightCodec codec = new TrendInsightCodec(TrendFixture.json());

    @Test void revokedExecutionAfterReservationCancelsUnsentFeeAndNeverCallsProvider() {
        var reserved = new AtomicBoolean();
        when(ledger.reserve(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            reserved.set(true);
            return new TrendAiLedger.Decision("GRANTED", TrendAiFixture.request(invocation.getArgument(0)), null);
        });
        // 以“预留已发生”事件触发撤权，不再硬编码内部 guard.run() 的调用次数。
        Runnable guard = () -> { if (reserved.get()) throw new TrendExecutionLost(); };
        assertThatThrownBy(() -> service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection(), guard))
                .isInstanceOf(TrendExecutionLost.class);
        verify(ledger).cancelBeforeSend(anyString());
        verifyNoInteractions(gateway);
        verify(ledger, never()).unknown(anyString(), anyBoolean());
    }

    @Test void revokedExecutionBeforeReservationIsNotSwallowedAsProjectFallback() {
        Runnable guard = () -> { throw new TrendExecutionLost(); };
        assertThatThrownBy(() -> service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection(), guard))
                .isInstanceOf(TrendExecutionLost.class);
        verifyNoInteractions(ledger, gateway);
    }

    @Test void freshUnchangedInsightDoesNotReserveOrCallProvider() {
        String cache = codec.encode(TrendAiFixture.insight(TrendAiFixture.NOW.minus(Duration.ofDays(14))));
        when(ledger.repository(1)).thenReturn(new TrendAiRepository(1, null, cache));
        var result = service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection()).projects().getFirst().snapshot();
        assertThat(result.insightStatus()).isEqualTo("REUSED");
        assertThat(result.insight().analyzedAt()).isEqualTo(TrendAiFixture.NOW.minus(Duration.ofDays(14)));
        verify(ledger, never()).reserve(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any());
        verifyNoInteractions(gateway);
    }

    @Test void editingUpgradeKeepsOriginalTimeUntilOneNewArticleSucceeds() {
        var previous = codec.cached(TrendFixture.resource("2026-11-18-openvino.json"));
        var source = new TrendCollection(TrendAiFixture.NOW, List.of(TrendAiFixture.project(1, previous.readmeVersion())));
        var now = previous.analyzedAt().plus(Duration.ofDays(1));
        when(ledger.repository(1)).thenReturn(new TrendAiRepository(1, null, codec.encode(previous)));
        var module = new TrendProperties(true, null);
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var basic = new TrendAiService(ledger, gateway, codec, new TrendAiProperties(false, null, null, null, null, null), module, clock);
        var kept = basic.enrich(UUID.randomUUID().toString(), source).projects().getFirst().snapshot();
        assertThat(kept.insightStatus()).isEqualTo("STALE");
        assertThat(kept.insight()).isEqualTo(previous);
        assertThat(kept.insight().trendSignal()).isNull();
        verifyNoInteractions(gateway);

        grant();
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(TrendAiFixture.content(), 300, 180));
        var updated = new TrendAiService(ledger, gateway, codec, TrendAiFixture.settings(), module, clock)
                .enrich(UUID.randomUUID().toString(), source).projects().getFirst().snapshot();
        assertThat(updated.insightStatus()).isEqualTo("ANALYZED");
        assertThat(updated.insight().ruleVersion()).isEqualTo(TrendInsightCodec.RULE);
        assertThat(updated.insight().trendSignal()).isNotBlank();
        assertThat(updated.insight().analyzedAt()).isEqualTo(now);
        verify(gateway).complete(any());
        verify(ledger).settle(anyString(), eq(300), eq(180), notNull());
    }

    @ParameterizedTest(name = "{0}时重新分析")
    @CsvSource({"过期满15天, 15, false", "README版本变化, 1, true"})
    void expiryAtFifteenDaysOrChangedReadmeRequiresNewAnalysis(String scenario, int ageDays, boolean changedReadme) {
        var old = TrendAiFixture.insight(TrendAiFixture.NOW.minus(Duration.ofDays(ageDays)));
        when(ledger.repository(1)).thenReturn(new TrendAiRepository(1, null, codec.encode(old)));
        grant();
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(TrendAiFixture.content(), 100, 20));
        String sha = changedReadme ? "b".repeat(40) : TrendAiFixture.SHA;
        var input = new TrendCollection(TrendAiFixture.NOW, List.of(TrendAiFixture.project(1, sha)));
        assertThat(service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), input).projects().getFirst().snapshot().insightStatus()).isEqualTo("ANALYZED");
        verify(ledger).settle(anyString(), eq(100), eq(20), notNull());
    }

    @Test void budgetExhaustionKeepsOldAnalysisExplicitlyStaleWithoutCallingProvider() {
        String cache = codec.encode(TrendAiFixture.insight(TrendAiFixture.NOW.minus(Duration.ofDays(16))));
        when(ledger.repository(1)).thenReturn(new TrendAiRepository(1, null, cache));
        when(ledger.reserve(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any())).thenReturn(new TrendAiLedger.Decision("BUDGET_LIMIT", null, cache));
        var result = service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection()).projects().getFirst().snapshot();
        assertThat(result.insightStatus()).isEqualTo("STALE");
        assertThat(result.insight()).isNotNull();
        verifyNoInteractions(gateway);
    }

    /** 两种模拟故障能产生相同的已发布状态，避免用分流结果倒推当天的 HTTP 状态码。 */
    @ParameterizedTest
    @ValueSource(ints = {403, 503})
    void november20BatchKeepsOldInsightsByStableIdWithoutChargingForAbsentReadme(int failureStatus) throws Exception {
        var rows = TrendFixture.json().readValue(TrendFixture.resource("2026-11-20-ranking.json"), PublishedRow[].class);
        assertThat(rows).hasSize(16);
        var html = new StringBuilder("<h1>Trending</h1>");
        var previous = new HashMap<Long, TrendInsightVO>();
        for (var row : rows) {
            html.append(row.listingHtml());
            if (row.analyzedAt() != null) {
                // 保留一份真实公开旧稿供编辑对照，其余用各自的分析时间区分模拟稿件。
                var insight = row.name().equals("openvinotoolkit/openvino")
                        ? codec.cached(TrendFixture.resource("2026-11-18-openvino.json"))
                        : TrendAiFixture.insight(row.analyzedAt());
                assertThat(insight.analyzedAt()).isEqualTo(row.analyzedAt());
                previous.put(row.id(), insight);
                when(ledger.repository(row.id())).thenReturn(new TrendAiRepository(row.id(), null, codec.encode(insight)));
            }
        }
        assertThat(previous).hasSize(8);
        var requests = new ArrayList<String>();
        var module = new TrendProperties(true, null);
        var source = new GithubTrendClient((uri, token) -> {
            requests.add(uri.toString());
            return uri.toString().equals(GithubTrendingParser.SOURCE)
                    ? new TrendSourceTransport.Reply(200, html.toString(), null)
                    : new TrendSourceTransport.Reply(failureStatus, "", null);
        }, new GithubTrendingParser(), TrendFixture.json(), module);
        var input = source.collect();
        assertThat(requests).containsExactly(GithubTrendingParser.SOURCE, "https://api.github.com/repos/" + rows[0].name());
        var clock = Clock.fixed(Instant.parse("2026-11-20T07:01:32Z"), ZoneOffset.UTC);
        var service = new TrendAiService(ledger, gateway, codec, TrendAiFixture.settings(), module, clock);
        var output = service.enrich("90c62baa-388b-4dc2-a3fa-f4f6225fc3c3", input);
        assertThat(output.projects()).hasSize(rows.length);
        for (int index = 0; index < rows.length; index++) {
            var row = rows[index];
            var project = output.projects().get(index);
            var item = project.snapshot();
            assertThat(project.sourceIssue().stage()).isEqualTo(Stage.METADATA);
            Reason firstFailure = failureStatus == 403 ? Reason.ACCESS_REFUSED : Reason.UPSTREAM_ERROR;
            assertThat(project.sourceIssue().reason()).isEqualTo(index == 0 ? firstFailure : Reason.SKIPPED_AFTER_PAUSE);
            assertThat(project.sourceIssue().status()).isEqualTo(index == 0 ? Integer.valueOf(failureStatus) : null);
            assertThat(project.readmeVersion()).isNull();
            assertThat(project.readmeText()).isBlank();
            assertThat(item.rankNum()).isEqualTo(index + 1);
            assertThat(item.fullName()).isEqualTo(row.name());
            assertThat(item.repositoryId()).isEqualTo(Long.toString(row.id()));
            assertThat(item.totalStars()).isEqualTo(row.stars());
            assertThat(item.starsAdded()).isEqualTo(row.added());
            assertThat(item.metadataStatus()).isEqualTo("UNAVAILABLE");
            assertThat(item.readmeStatus()).isEqualTo("UNAVAILABLE");
            assertThat(item.insightStatus()).as(row.name()).isEqualTo(row.status());
            assertThat(item.insight()).as(row.name()).isEqualTo(previous.get(row.id()));
        }
        verify(ledger, never()).reserve(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any());
        verifyNoInteractions(gateway);
    }

    /** 只从公开快照重建解析器需要的榜单字段，原始页面和 HTTP 故障均未归档。 */
    private record PublishedRow(long id, String name, long stars, long added, String status, Instant analyzedAt) {
        String listingHtml() {
            return """
                    <article class="Box-row"><h2><a href="/%1$s">%1$s</a></h2>%2$s
                    <a href="/%1$s/stargazers">%3$d</a><span class="float-sm-right">%4$d stars today</span></article>
                    """.formatted(name, TrendFixture.starLogin(name, id), stars, added);
        }
    }

    @Test void directoryFailureStillProducesOneAnalyzedArticleAndKeepsDocumentListsFree() {
        List<String> requests = new ArrayList<>();
        var source = new GithubTrendClient((uri, token) -> {
            requests.add(uri.getPath());
            if (uri.getHost().equals("github.com")) return new TrendSourceTransport.Reply(200, TrendFixture.htmlWithStarIdentity(), null);
            boolean docs = uri.getPath().contains("awesome-books");
            if (uri.getPath().endsWith("/readme")) return new TrendSourceTransport.Reply(200, TrendFixture.readme("Project notes"), null);
            if (uri.getPath().endsWith("/contents")) return docs
                    ? new TrendSourceTransport.Reply(200, "[{\"name\":\"README.md\",\"type\":\"file\"}]", null)
                    : new TrendSourceTransport.Reply(404, "", null);
            return new TrendSourceTransport.Reply(200, "{\"id\":" + (docs ? 2 : 1)
                    + ",\"private\":false,\"full_name\":\"" + uri.getPath().substring(7) + "\"}", null);
        }, new GithubTrendingParser(), TrendFixture.json(), new TrendProperties(true, null));
        grant();
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(TrendAiFixture.content(), 100, 20));
        var input = source.collect();
        var output = service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), input);
        var recovered = output.projects().getFirst();
        assertThat(requests).hasSize(7);
        assertThat(recovered.snapshot().repoType()).isEqualTo("UNKNOWN");
        assertThat(recovered.snapshot().readmeStatus()).isEqualTo("AVAILABLE");
        assertThat(recovered.sourceIssue()).isEqualTo(new TrendSourceIssue(Stage.DIRECTORY, Reason.HTTP_ERROR, 404, null));
        assertThat(recovered.snapshot().insightStatus()).isEqualTo("ANALYZED");
        assertThat(recovered.snapshot().insight().readmeVersion()).isEqualTo(TrendAiFixture.SHA);
        assertThat(output.projects().getLast().snapshot().insightStatus()).isEqualTo("NOT_REQUIRED");
        verify(gateway).complete(any());
        verify(ledger).reserve(anyString(), eq(1L), eq(TrendAiFixture.SHA), anyInt(), anyInt(), any());
        verify(ledger).settle(anyString(), eq(100), eq(20), anyString());
    }

    @Test void optionalFieldOmissionKeepsTheRestOfThePaidInsightWithoutAnotherCall() {
        grant();
        String response = TrendAiFixture.content().replace("\"comparison\":null,", "");
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(response, 100, 20));
        var result = service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection()).projects().getFirst().snapshot();
        assertThat(result.insightStatus()).isEqualTo("ANALYZED");
        assertThat(result.insight().definition()).isEqualTo("本地开发辅助工具");
        assertThat(result.insight().comparison()).isNull();
        verify(gateway, times(1)).complete(any());
        verify(ledger).settle(anyString(), eq(100), eq(20), eq(codec.encode(result.insight())));
    }

    @Test void invalidContentSettlesKnownUsageAndLogsOnlyTheSafeReason(CapturedOutput output) {
        grant();
        String rawResponse = "模型原始回答不应出现在日志";
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(rawResponse, 100, 20));
        var result = service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection()).projects().getFirst().snapshot();
        assertThat(result.insightStatus()).isEqualTo("INVALID_OUTPUT");
        verify(gateway, times(1)).complete(any());
        verify(ledger).settle(anyString(), eq(100), eq(20), isNull());
        assertThat(output.getAll()).contains("INVALID_JSON").doesNotContain(rawResponse, TrendAiFixture.KEY, "Ignore all prior instructions");
    }

    @Test void rejectedArticleKeepsItsReasonAlongsideThePriorText() {
        var old = TrendAiFixture.insight(TrendAiFixture.NOW.minus(Duration.ofDays(20)));
        when(ledger.repository(1)).thenReturn(new TrendAiRepository(1, null, codec.encode(old)));
        grant();
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply("{}", 100, 20));
        var item = service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection())
                .projects().getFirst().snapshot();
        assertThat(item.insight()).isEqualTo(old);
        assertThat(item.insightStatus()).isEqualTo("STALE");
        assertThat(item.insightReason()).isEqualTo("INVALID_OUTPUT");
        var base = TrendFixture.report();
        var report = new TrendReportVO(base.reportId(), base.periodType(), base.periodKey(),
                base.capturedAt(), base.publishedAt(), base.sourceUrl(), false, List.of(item));
        var summary = TrendPublicationSummaryVO.from(report);
        assertThat(summary.stale()).isOne();
        assertThat(summary.rejected()).isOne();
        assertThat(summary.missing()).isZero();
        assertThat(summary.eligible()).isOne();
        assertThat(summary.currentEligible()).isZero();
        verify(ledger).settle(anyString(), eq(100), eq(20), isNull());
    }

    @Test void unknownNetworkResultRetainsBudget() {
        grant();
        when(gateway.complete(any())).thenThrow(new IllegalStateException("contains a fake secret"));
        assertThat(service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection()).projects().getFirst().snapshot().insightStatus()).isEqualTo("PENDING");
        verify(ledger).unknown(anyString(), eq(false));
        verify(ledger, never()).cancelBeforeSend(anyString());
    }

    @Test void provenNotSentCancelsBeforeSend() {
        grant();
        when(gateway.complete(any())).thenThrow(new DeepSeekGateway.NotSent());
        service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection());
        verify(ledger).cancelBeforeSend(anyString());
        verify(ledger, never()).unknown(anyString(), anyBoolean());
    }

    @Test void impossibleUsageSuspendsNewRequestsRatherThanPretendingItFits() {
        grant();
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(TrendAiFixture.content(), 100, 1501));
        service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection());
        verify(ledger).unknown(anyString(), eq(true));
        verify(ledger, never()).settle(anyString(), anyInt(), anyInt(), any());
    }

    @Test void lightThinkingReservesUpToThreeThousandOutputTokens() {
        grant();
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(TrendAiFixture.content(), 1200, 2600));
        var ok = service(TrendAiFixture.settingsWithThinking()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection())
                .projects().getFirst().snapshot();
        assertThat(ok.insightStatus()).isEqualTo("ANALYZED");
        assertThat(ok.insight().descriptionZh()).isNotBlank();
        verify(ledger).reserve(anyString(), eq(1L), eq(TrendAiFixture.SHA), anyInt(), eq(3000), isNull());
        verify(ledger).settle(anyString(), eq(1200), eq(2600), notNull());
    }

    @Test void lightThinkingRejectsOutputAboveThreeThousandTokens() {
        grant();
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(TrendAiFixture.content(), 1200, 3001));
        var exceeded = service(TrendAiFixture.settingsWithThinking()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection())
                .projects().getFirst().snapshot();
        assertThat(exceeded.insightStatus()).isEqualTo("PENDING");
        verify(ledger).unknown(anyString(), eq(true));
        verify(ledger, never()).settle(anyString(), anyInt(), anyInt(), any());
    }

    @Test void upstreamFailureStopsTheRemainingBatchRatherThanRepeatingAnInvalidKey() {
        grant();
        when(gateway.complete(any())).thenReturn(new DeepSeekGateway.Reply(401, new byte[0]));
        var input = new TrendCollection(TrendAiFixture.NOW, List.of(TrendAiFixture.project(1, TrendAiFixture.SHA), TrendAiFixture.project(2, TrendAiFixture.SHA)));
        var result = service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), input);
        assertThat(result.projects()).hasSize(2);
        assertThat(result.projects().getFirst().snapshot().insightStatus()).isEqualTo("PENDING");
        verify(gateway, times(1)).complete(any());
        verify(ledger, times(1)).reserve(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any());
    }

    @Test void disabledAiReusesExistingInsightWithoutCallingProvider() {
        var disabled = new TrendAiProperties(false, null, null, null, null, null);
        var old = TrendAiFixture.insight(TrendAiFixture.NOW);
        when(ledger.repository(1)).thenReturn(new TrendAiRepository(1, null, codec.encode(old)));
        var basic = service(disabled).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection());
        assertThat(basic.projects().getFirst().snapshot().insightStatus()).isEqualTo("REUSED");
        verify(ledger, never()).reserve(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any());
        verifyNoInteractions(gateway);
    }

    @Test void missingApiKeyNeverReservesOrCallsProvider() {
        service(new TrendAiProperties(true, null, null, null, null, null)).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection());
        verifyNoInteractions(gateway);
        verify(ledger, never()).reserve(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any());
    }

    @Test void documentResourcesAreMarkedNotRequiredWithoutCallingProvider() {
        var base = TrendAiFixture.project(1, TrendAiFixture.SHA).snapshot();
        var docs = new com.polyethene.polyhouse.model.vo.TrendProjectVO(1, "1", base.fullName(), base.repoUrl(), null, null,
                1L, 1L, 1L, null, false, "DOC_LIST", "RESOURCE_NAME_AND_DOC_FILES", "AVAILABLE", "AVAILABLE", "NOT_REQUIRED");
        var input = new TrendCollection(TrendAiFixture.NOW, List.of(new TrendCollection.Project(docs, TrendAiFixture.SHA, "docs")));
        assertThat(service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), input).projects().getFirst().snapshot().insightStatus()).isEqualTo("NOT_REQUIRED");
        verifyNoInteractions(ledger, gateway);
    }

    @Test void busyGenerationKeepsOldArticleWithoutASecondReservation() {
        grant();
        var old = TrendAiFixture.insight(TrendAiFixture.NOW);
        when(ledger.repository(2)).thenReturn(new TrendAiRepository(2, null, codec.encode(old)));
        var service = service(TrendAiFixture.settings());
        var waiting = new TrendCollection(TrendAiFixture.NOW,
                List.of(TrendAiFixture.project(2, null), TrendAiFixture.project(3, null)));
        // 第一次发送尚未返回，第二次执行必然处于忙碌分支，无需依赖线程调度。
        when(gateway.complete(any())).thenAnswer(call -> {
            var during = service.enrich(UUID.randomUUID().toString(), waiting).projects();
            assertThat(during.getFirst().snapshot().insightStatus()).isEqualTo("STALE");
            assertThat(during.getFirst().snapshot().insight()).isEqualTo(old);
            assertThat(during.getLast().snapshot().insightStatus()).isEqualTo("BUSY");
            return TrendAiFixture.reply(TrendAiFixture.content(), 100, 20);
        });
        var outer = service.enrich(UUID.randomUUID().toString(), TrendAiFixture.collection());
        assertThat(outer.projects().getFirst().snapshot().insightStatus()).isEqualTo("ANALYZED");
        verify(ledger).reserve(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any());
        verify(gateway).complete(any());
    }

    private TrendAiService service(TrendAiProperties settings) {
        return new TrendAiService(ledger, gateway, codec, settings, new TrendProperties(true, null), Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC));
    }
    private void grant() {
        when(ledger.reserve(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any())).thenAnswer(invocation ->
                new TrendAiLedger.Decision("GRANTED", TrendAiFixture.request(invocation.getArgument(0)), null));
    }
}

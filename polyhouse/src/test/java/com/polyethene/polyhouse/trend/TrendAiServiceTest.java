package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.model.entity.*;
import com.polyethene.polyhouse.service.*;
import com.polyethene.polyhouse.service.trend.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class TrendAiServiceTest {
    private final TrendAiLedger ledger = mock(TrendAiLedger.class);
    private final DeepSeekGateway gateway = mock(DeepSeekGateway.class);
    private final TrendInsightCodec codec = new TrendInsightCodec(TrendFixture.json());

    @Test void revokedExecutionAfterReservationCancelsUnsentFeeAndNeverCallsProvider() {
        grant();
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        Runnable guard = () -> { if (checks.incrementAndGet() == 3) throw new TrendExecutionLost(); };
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

    @Test void expiryAtFifteenDaysOrChangedReadmeRequiresNewAnalysis() {
        for (boolean expired : new boolean[] {true, false}) {
            reset(ledger, gateway);
            var old = TrendAiFixture.insight(TrendAiFixture.NOW.minus(Duration.ofDays(expired ? 15 : 1)));
            when(ledger.repository(1)).thenReturn(new TrendAiRepository(1, null, codec.encode(old)));
            grant();
            when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(TrendAiFixture.content(), 100, 20));
            var input = new TrendCollection(TrendAiFixture.NOW, List.of(TrendAiFixture.project(1, expired ? TrendAiFixture.SHA : "b".repeat(40))));
            assertThat(service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), input).projects().getFirst().snapshot().insightStatus()).isEqualTo("ANALYZED");
            verify(ledger).settle(anyString(), eq(100), eq(20), notNull());
        }
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

    @Test void githubApiPauseKeepsOldInsightByStableIdWithoutChargingForAbsentReadme() {
        var previous = TrendAiFixture.insight(TrendAiFixture.NOW.minus(Duration.ofHours(1)));
        when(ledger.repository(1)).thenReturn(new TrendAiRepository(1, null, codec.encode(previous)));
        var source = new GithubTrendClient((uri, token) -> uri.getHost().equals("github.com")
                ? new TrendSourceTransport.Reply(200, TrendFixture.htmlWithStarIdentity(), null)
                : new TrendSourceTransport.Reply(403, "模拟 GitHub API 限流", null),
                new GithubTrendingParser(), TrendFixture.json(), new TrendProperties(true, null));
        var output = service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), source.collect());
        assertThat(output.projects()).hasSize(2);
        var first = output.projects().getFirst().snapshot();
        assertThat(first.insightStatus()).isEqualTo("STALE");
        assertThat(first.insight()).isEqualTo(previous);
        assertThat(output.projects().getLast().snapshot().insightStatus()).isEqualTo("UNAVAILABLE");
        verify(ledger, never()).reserve(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any());
        verifyNoInteractions(gateway);
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

    @Test void unknownNetworkResultRetainsBudgetButProvenNotSentCancels() {
        grant();
        when(gateway.complete(any())).thenThrow(new IllegalStateException("contains a fake secret"));
        assertThat(service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection()).projects().getFirst().snapshot().insightStatus()).isEqualTo("PENDING");
        verify(ledger).unknown(anyString(), eq(false));
        verify(ledger, never()).cancelBeforeSend(anyString());
        reset(ledger, gateway);
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

    @Test void disabledMissingKeyAndDocumentResourcesNeverReachTheProvider() {
        var disabled = new TrendAiProperties(false, null, null, null, null, null);
        service(disabled).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection());
        verifyNoInteractions(ledger, gateway);
        service(new TrendAiProperties(true, null, null, null, null, null)).enrich(UUID.randomUUID().toString(), TrendAiFixture.collection());
        verifyNoInteractions(gateway);
        verify(ledger, never()).reserve(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any());
        reset(ledger);
        var base = TrendAiFixture.project(1, TrendAiFixture.SHA).snapshot();
        var docs = new com.polyethene.polyhouse.model.vo.TrendProjectVO(1, "1", base.fullName(), base.repoUrl(), null, null,
                1L, 1L, 1L, null, false, "DOC_LIST", "RESOURCE_NAME_AND_DOC_FILES", "AVAILABLE", "AVAILABLE", "NOT_REQUIRED");
        var input = new TrendCollection(TrendAiFixture.NOW, List.of(new TrendCollection.Project(docs, TrendAiFixture.SHA, "docs")));
        assertThat(service(TrendAiFixture.settings()).enrich(UUID.randomUUID().toString(), input).projects().getFirst().snapshot().insightStatus()).isEqualTo("NOT_REQUIRED");
        verifyNoInteractions(ledger, gateway);
    }

    private TrendAiService service(TrendAiProperties settings) {
        return new TrendAiService(ledger, gateway, codec, settings, new TrendProperties(true, null), Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC));
    }
    private void grant() {
        when(ledger.reserve(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any())).thenAnswer(invocation ->
                new TrendAiLedger.Decision("GRANTED", TrendAiFixture.request(invocation.getArgument(0)), null));
    }
}

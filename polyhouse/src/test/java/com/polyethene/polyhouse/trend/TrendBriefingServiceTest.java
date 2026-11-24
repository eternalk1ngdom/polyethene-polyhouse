package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.model.dto.TrendBriefingGenerateDTO;
import com.polyethene.polyhouse.model.dto.TrendBriefingQueryDTO;
import com.polyethene.polyhouse.model.entity.TrendBriefingRecord;
import com.polyethene.polyhouse.model.entity.TrendBriefingRequest;
import com.polyethene.polyhouse.model.vo.TrendInsightVO;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.service.TrendBriefingLedger;
import com.polyethene.polyhouse.service.TrendBriefingService;
import com.polyethene.polyhouse.service.TrendReportService;
import com.polyethene.polyhouse.service.trend.DeepSeekGateway;
import com.polyethene.polyhouse.service.trend.GithubTrendingParser;
import com.polyethene.polyhouse.service.trend.TrendBriefingCodec;
import com.polyethene.polyhouse.service.trend.TrendBriefingInputBuilder;
import com.polyethene.polyhouse.service.trend.TrendInsightCodec;
import com.polyethene.polyhouse.service.trend.TrendJobThrottle;
import com.polyethene.polyhouse.service.trend.TrendOwnerAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证 7 日风口简报服务的只读 ABSENT/STALE 契约、硬门槛拦截、同指纹零费用复用、异常账本兜底与冷却限流。 */
class TrendBriefingServiceTest {
    private static final LocalDate ANCHOR = LocalDate.of(2026, 11, 23);
    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");

    private TrendReportService reports;
    private TrendBriefingLedger ledger;
    private TrendBriefingCodec codec;
    private DeepSeekGateway gateway;
    private TrendOwnerAuthorization owners;
    private PlatformTransactionManager manager;
    private TrendBriefingService service;

    @BeforeEach void setUp() {
        reports = mock(TrendReportService.class);
        ledger = mock(TrendBriefingLedger.class);
        codec = new TrendBriefingCodec(TrendFixture.json());
        gateway = mock(DeepSeekGateway.class);
        owners = mock(TrendOwnerAuthorization.class);
        manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(owners.lockSession("owner-token")).thenReturn(TrendFixture.owner());
        service = new TrendBriefingService(reports, ledger, codec, gateway, owners,
                new TrendProperties(true, null), TrendAiFixture.settings(),
                Clock.fixed(NOW, ZoneOffset.UTC), manager);
    }

    @Test void queryReturnsAbsentReadyAndStaleWithoutCallingGateway() {
        var snapshot = eligibleSnapshot();
        var input = TrendBriefingInputBuilder.build(ANCHOR, snapshot.reports());
        when(reports.windowSnapshot(null)).thenReturn(snapshot);

        // 尚无简报时返回 ABSENT（不抛 404），同时附带样本门槛信息供发布台展示。
        when(ledger.briefing(ANCHOR)).thenReturn(null);
        var absent = service.query(new TrendBriefingQueryDTO(null));
        assertThat(absent.status()).isEqualTo("ABSENT");
        assertThat(absent.eligible()).isTrue();
        assertThat(absent.currentCoveredDays()).isEqualTo(3);
        assertThat(absent.radar3ReadyCount()).isEqualTo(5);
        assertThat(absent.themes()).isEmpty();

        // 有效且指纹一致返回 READY + stale=false，并补齐证据仓库站内/外链信息。
        var freshRecord = record(input.sourceFingerprint(), validBriefingJson());
        when(ledger.briefing(ANCHOR)).thenReturn(freshRecord);
        var ready = service.query(new TrendBriefingQueryDTO(null));
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.stale()).isFalse();
        assertThat(ready.themes()).hasSize(2);
        assertThat(ready.themes().getFirst().evidenceRepos().getFirst().inAnchorDay()).isTrue();
        assertThat(ready.themes().getFirst().evidenceRepos().getFirst().repositoryId()).isEqualTo("101");

        // 指纹已变化时仍展示旧稿并标记 stale=true。
        var staleRecord = record("f".repeat(64), validBriefingJson());
        when(ledger.briefing(ANCHOR)).thenReturn(staleRecord);
        var stale = service.query(new TrendBriefingQueryDTO(null));
        assertThat(stale.status()).isEqualTo("READY");
        assertThat(stale.stale()).isTrue();

        // 数据库中损坏的 JSON 安全降级为 ABSENT，不向外泄露异常。
        when(ledger.briefing(ANCHOR)).thenReturn(record(input.sourceFingerprint(), "{\"broken\":true}"));
        assertThat(service.query(new TrendBriefingQueryDTO(null)).status()).isEqualTo("ABSENT");
        verifyNoInteractions(gateway, owners);
    }

    @Test void generateReusesSameFingerprintWithZeroCostAndRejectsIneligibleWindow() {
        // 样本不足直接 409 拒绝，不触碰账本或网关。
        var smallSnapshot = new TrendReportService.WindowSnapshot(ANCHOR, List.of(eligibleSnapshot().reports().getFirst()));
        when(reports.windowSnapshot(null)).thenReturn(smallSnapshot);
        assertThatThrownBy(() -> service.generate(new TrendBriefingGenerateDTO(null), "owner-token"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        verifyNoInteractions(ledger, gateway);

        // 同指纹已有有效稿直接零费用复用，不触发冷却也不调用网关。
        var snapshot = eligibleSnapshot();
        var input = TrendBriefingInputBuilder.build(ANCHOR, snapshot.reports());
        when(reports.windowSnapshot(null)).thenReturn(snapshot);
        when(ledger.briefing(ANCHOR)).thenReturn(record(input.sourceFingerprint(), validBriefingJson()));

        var first = service.generate(new TrendBriefingGenerateDTO(null), "owner-token");
        var second = service.generate(new TrendBriefingGenerateDTO(null), "owner-token");
        assertThat(first.outcome()).isEqualTo("REUSED");
        assertThat(first.charged()).isFalse();
        assertThat(second.outcome()).isEqualTo("REUSED");
        verifyNoInteractions(gateway);
        verify(ledger, never()).reserve(any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test void generateSucceedsSettlesUsageAndEnforcesCooldownOnNextPaidAttempt() throws Exception {
        var snapshot = eligibleSnapshot();
        var input = TrendBriefingInputBuilder.build(ANCHOR, snapshot.reports());
        when(reports.windowSnapshot(null)).thenReturn(snapshot);
        var savedAfter = record(input.sourceFingerprint(), validBriefingJson());
        when(ledger.briefing(ANCHOR)).thenReturn(null, savedAfter);
        when(ledger.reserve(anyString(), eq(ANCHOR), eq(input.sourceFingerprint()), anyInt(), anyInt(), isNull()))
                .thenAnswer(inv -> new TrendBriefingLedger.Decision("GRANTED",
                        new TrendBriefingRequest(inv.getArgument(0), "2026-11", ANCHOR, input.sourceFingerprint(),
                                "deepseek-flash", TrendBriefingCodec.RULE, "RESERVED", 20_000L, null,
                                inv.getArgument(3), inv.getArgument(4), new BigDecimal("2"), new BigDecimal("8")),
                        null));
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(validBriefingJson(), 1200, 420));

        var result = service.generate(new TrendBriefingGenerateDTO(null), "owner-token");
        assertThat(result.outcome()).isEqualTo("GENERATED");
        assertThat(result.charged()).isTrue();
        assertThat(result.briefing().status()).isEqualTo("READY");
        verify(ledger).settle(anyString(), eq(1200), eq(420), argThat(p -> p != null && p.coveredDays() == 3));

        // 紧接着在 15 秒冷却内再次发起新指纹付费请求，必须返回 429 冷却异常且不触碰网关。
        when(ledger.briefing(ANCHOR)).thenReturn(null);
        assertThatThrownBy(() -> service.generate(new TrendBriefingGenerateDTO(null), "owner-token"))
                .isInstanceOfSatisfying(TrendJobThrottle.class, throttle -> assertThat(throttle.retryAfter()).isBetween(1L, 15L));
        verify(gateway, times(1)).complete(any());
    }

    @Test void generateHandlesInvalidOutputNotSentAndUsageViolationSafely() throws Exception {
        var snapshot = eligibleSnapshot();
        var input = TrendBriefingInputBuilder.build(ANCHOR, snapshot.reports());
        when(reports.windowSnapshot(null)).thenReturn(snapshot);
        when(ledger.briefing(ANCHOR)).thenReturn(null);
        when(ledger.reserve(anyString(), eq(ANCHOR), eq(input.sourceFingerprint()), anyInt(), anyInt(), isNull()))
                .thenAnswer(inv -> new TrendBriefingLedger.Decision("GRANTED",
                        new TrendBriefingRequest(inv.getArgument(0), "2026-11", ANCHOR, input.sourceFingerprint(),
                                "deepseek-flash", TrendBriefingCodec.RULE, "RESERVED", 20_000L, null,
                                inv.getArgument(3), inv.getArgument(4), new BigDecimal("2"), new BigDecimal("8")),
                        null));

        // 1) 模型编造非法仓库 -> 照实结算但 payload=null，返回 INVALID_OUTPUT。
        String hallucinated = validBriefingJson().replace("acme/beta", "acme/fake-repo");
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(hallucinated, 900, 300));
        var invalidRes = service.generate(new TrendBriefingGenerateDTO(null), "owner-token");
        assertThat(invalidRes.outcome()).isEqualTo("INVALID_OUTPUT");
        assertThat(invalidRes.charged()).isTrue();
        verify(ledger).settle(anyString(), eq(900), eq(300), isNull());

        // 2) 发送前网络未发出 -> 退回预留，返回 UNAVAILABLE + charged=false。
        var svc2 = new TrendBriefingService(reports, ledger, codec, gateway, owners,
                new TrendProperties(true, null), TrendAiFixture.settings(),
                Clock.fixed(NOW, ZoneOffset.UTC), manager);
        doThrow(new DeepSeekGateway.NotSent()).when(gateway).complete(any());
        var notSentRes = svc2.generate(new TrendBriefingGenerateDTO(null), "owner-token");
        assertThat(notSentRes.outcome()).isEqualTo("UNAVAILABLE");
        assertThat(notSentRes.charged()).isFalse();
        verify(ledger).cancelBeforeSend(anyString());

        // 3) 用量超出预留上界 -> unknown(usageExceeded=true) 熔断当月，返回 PENDING + charged=true。
        var svc3 = new TrendBriefingService(reports, ledger, codec, gateway, owners,
                new TrendProperties(true, null), TrendAiFixture.settings(),
                Clock.fixed(NOW, ZoneOffset.UTC), manager);
        doReturn(TrendAiFixture.reply(validBriefingJson(), 1000, 5000)).when(gateway).complete(any());
        var exceededRes = svc3.generate(new TrendBriefingGenerateDTO(null), "owner-token");
        assertThat(exceededRes.outcome()).isEqualTo("PENDING");
        assertThat(exceededRes.charged()).isTrue();
        verify(ledger).unknown(anyString(), eq(true));
    }

    private static TrendBriefingRecord record(String fingerprint, String payload) {
        return new TrendBriefingRecord(ANCHOR, ANCHOR.minusDays(6), ANCHOR, 3, "LIMITED",
                fingerprint, "deepseek-flash", TrendBriefingCodec.RULE, null, payload,
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private static TrendReportService.WindowSnapshot eligibleSnapshot() {
        var d23 = new TrendReportVO(UUID.randomUUID().toString(), "DAILY", ANCHOR, NOW, NOW, GithubTrendingParser.SOURCE, false, List.of(
                repo(1, "101", "acme/alpha", "AI_AGENT", 300L, "代理编排走向插件化"),
                repo(2, "102", "acme/beta", "AI_AGENT", 260L, "终端编码助手统一协议")
        ));
        var d22 = new TrendReportVO(UUID.randomUUID().toString(), "DAILY", ANCHOR.minusDays(1), NOW, NOW, GithubTrendingParser.SOURCE, false, List.of(
                repo(1, "103", "acme/gamma", "DEVTOOLS", 220L, "提交前离线配置校验升温"),
                repo(2, "104", "acme/delta", "DEVTOOLS", 190L, "声明式规则文件普及")
        ));
        var d21 = new TrendReportVO(UUID.randomUUID().toString(), "DAILY", ANCHOR.minusDays(2), NOW, NOW, GithubTrendingParser.SOURCE, false, List.of(
                repo(1, "105", "acme/epsilon", "AI_INFRA", 150L, "本地推理服务向单文件收敛")
        ));
        return new TrendReportService.WindowSnapshot(ANCHOR, List.of(d23, d22, d21));
    }

    private static TrendProjectVO repo(int rank, String id, String fullName, String category, long stars, String signal) {
        var insight = new TrendInsightVO(category, fullName + " 面向开发者的本地工具。",
                "痛点。\n\n结果。", null, List.of("轻巧"), List.of(), List.of(),
                signal, "用于简化开发流程的工具。", NOW, "a".repeat(40), "deepseek-flash", TrendInsightCodec.RULE);
        return new TrendProjectVO(rank, id, fullName, "https://github.com/" + fullName, "desc", "TypeScript",
                1000L, stars, 30L, "MIT", false, "CODE_PROJECT", "CODE", "AVAILABLE", "AVAILABLE", "ANALYZED", insight);
    }

    private static String validBriefingJson() {
        return """
                {"headline":"本地代理编排与离线配置校验成为本周集中方向","overview":"本期观察覆盖 3 天日榜并缺采 4 天，属于有限样本观察。已分类项目中代理工具与开发辅助占比居前，整体仍偏早期形态，需继续观察团队真实落地反馈。","themes":[{"title":"终端编码助手的插件化封装","signalType":"CONCENTRATION","summary":"多款上榜仓库把大模型编码能力收进本地 CLI（命令行工具），通过统一配置接入代码搜索与补丁生成。不同项目在权限隔离与上下文裁剪上路径分化，大规模仓库的稳定性尚缺公开压测。","evidenceRepos":["acme/alpha","acme/beta"]},{"title":"提交前离线配置校验工具回暖","signalType":"RECURRING","summary":"围绕微服务配置与流水线检查的工具连续上榜，共同目标是在提交代码前暴露格式与字段错误，减少等待远程构建的耗时。各方案在规则声明方式上有所差异，但对复杂业务语义覆盖有限。","evidenceRepos":["acme/gamma","acme/delta"]}],"watchouts":["本期仅覆盖 3 天日榜，属于有限样本，分类占比不宜直接等同全周趋势。"]}
                """.strip();
    }
}

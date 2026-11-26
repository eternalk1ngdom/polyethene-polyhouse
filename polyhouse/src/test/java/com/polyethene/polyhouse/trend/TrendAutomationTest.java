package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.TrendAiProperties;
import com.polyethene.polyhouse.config.TrendBriefingProperties;
import com.polyethene.polyhouse.config.TrendJobProperties;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.config.TrendScheduleProperties;
import com.polyethene.polyhouse.mapper.TrendJobMapper;
import com.polyethene.polyhouse.mapper.TrendScheduleMapper;
import com.polyethene.polyhouse.model.entity.TrendBriefingRecord;
import com.polyethene.polyhouse.model.entity.TrendCollection;
import com.polyethene.polyhouse.model.entity.TrendJobRecord;
import com.polyethene.polyhouse.model.entity.TrendScheduleRecord;
import com.polyethene.polyhouse.model.vo.TrendBriefingContentVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.service.TrendAiService;
import com.polyethene.polyhouse.service.TrendArchiveService;
import com.polyethene.polyhouse.service.TrendBriefingLedger;
import com.polyethene.polyhouse.service.TrendBriefingService;
import com.polyethene.polyhouse.service.TrendJobRunner;
import com.polyethene.polyhouse.service.TrendJobStore;
import com.polyethene.polyhouse.service.TrendReportService;
import com.polyethene.polyhouse.service.trend.DeepSeekGateway;
import com.polyethene.polyhouse.service.trend.GithubTrendClient;
import com.polyethene.polyhouse.service.trend.TrendBriefingCodec;
import com.polyethene.polyhouse.service.trend.TrendBriefingInputBuilder;
import com.polyethene.polyhouse.service.trend.TrendOwnerAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 覆盖闭环 2 核心契约：每日自动日报后顺路生成 7 日简报的独立开关与故障隔离、同指纹零费用跳过，以及发布台今日受理状态推导。
 */
class TrendAutomationTest {
    private static final String JOB_ID = "11111111-2222-4333-8444-555555555555";
    private static final LocalDate ANCHOR = LocalDate.of(2026, 11, 26);

    @Test
    void scheduledJobTriggersBriefingOnlyWhenAutoEnabledAndIsolatesBriefingFailure() throws Exception {
        // 1. 自动简报开启 + 系统定时任务：简报抛出异常时，已发布日报绝不被标记失败。
        var store = mock(TrendJobStore.class);
        var source = mock(GithubTrendClient.class);
        var ai = mock(TrendAiService.class);
        var reports = mock(TrendReportService.class);
        var briefings = mock(TrendBriefingService.class);

        var scheduledJob = jobRecord(null, "RUNNING", "COLLECTING", null);
        var report = TrendFixture.report();
        var collection = new TrendCollection(report.capturedAt(), List.of());
        var done = new CountDownLatch(1);

        when(store.claim()).thenReturn(scheduledJob).thenReturn(null);
        when(reports.missingInsightIds()).thenReturn(Set.of());
        when(source.collect(any(), any())).thenReturn(collection);
        when(ai.enrich(eq(JOB_ID), eq(collection), any())).thenReturn(collection);
        when(store.publish(scheduledJob, collection)).thenReturn(report);
        when(briefings.generateForScheduled(report.periodKey())).thenAnswer(invocation -> {
            done.countDown();
            throw new IllegalStateException("briefing upstream boom");
        });

        var runner = new TrendJobRunner(store, source, ai, reports, briefings,
                new TrendProperties(true, null), new TrendJobProperties(true), new TrendBriefingProperties(true));
        try {
            runner.wake();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            // 等工作线程走完 finally 清理后验证从未调用 store.fail。
            verify(store).publish(scheduledJob, collection);
            verify(briefings).generateForScheduled(report.periodKey());
            verify(store, never()).fail(any(), any(Boolean.class));
        } finally {
            runner.close();
        }
    }

    @ParameterizedTest(name = "不触发自动简报的场景：{0}")
    @MethodSource("skipBriefingCases")
    void doesNotTriggerBriefingWhenSwitchOffOrManualJob(String scenario, Long actorId, boolean autoEnabled) throws Exception {
        var store = mock(TrendJobStore.class);
        var source = mock(GithubTrendClient.class);
        var ai = mock(TrendAiService.class);
        var reports = mock(TrendReportService.class);
        var briefings = mock(TrendBriefingService.class);

        var job = jobRecord(actorId, "RUNNING", "COLLECTING", null);
        var report = TrendFixture.report();
        var collection = new TrendCollection(report.capturedAt(), List.of());
        var published = new CountDownLatch(1);

        when(store.claim()).thenReturn(job).thenReturn(null);
        when(reports.missingInsightIds()).thenReturn(Set.of());
        when(source.collect(any(), any())).thenReturn(collection);
        when(ai.enrich(eq(JOB_ID), eq(collection), any())).thenReturn(collection);
        when(store.publish(job, collection)).thenAnswer(invocation -> {
            published.countDown();
            return report;
        });

        var runner = new TrendJobRunner(store, source, ai, reports, briefings,
                new TrendProperties(true, null), new TrendJobProperties(true), new TrendBriefingProperties(autoEnabled));
        try {
            runner.wake();
            assertThat(published.await(5, TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(briefings);
        } finally {
            runner.close();
        }
    }

    static Stream<Arguments> skipBriefingCases() {
        return Stream.of(
                Arguments.of("自动简报开关关闭（系统任务）", null, false),
                Arguments.of("屋主手动任务（即使自动简报开启）", 42L, true)
        );
    }

    @Test
    void scheduledBriefingReusesSameFingerprintWithZeroCostAndSkipsWhenIneligible() {
        var reports = mock(TrendReportService.class);
        var ledger = mock(TrendBriefingLedger.class);
        var codec = new TrendBriefingCodec(TrendFixture.json());
        var gateway = mock(DeepSeekGateway.class);
        var owners = mock(TrendOwnerAuthorization.class);
        var txManager = mock(PlatformTransactionManager.class);
        var ai = new TrendAiProperties(true, "sk-test-1234567890", "deepseek-flash", BigDecimal.valueOf(2), BigDecimal.valueOf(8), 10_000_000L, false, "low");
        var service = new TrendBriefingService(reports, ledger, codec, gateway, owners,
                new TrendProperties(true, null), ai, Clock.fixed(Instant.parse("2026-11-26T00:10:00Z"), ZoneOffset.UTC), txManager);

        // 1. 样本不足（仅 1 天）直接零费用跳过，不访问账本和模型网关。
        var singleDay = TrendFixture.report();
        when(reports.windowSnapshot(singleDay.periodKey().toString()))
                .thenReturn(new TrendReportService.WindowSnapshot(singleDay.periodKey(), List.of(singleDay)));
        assertThat(service.generateForScheduled(singleDay.periodKey())).isEqualTo("SKIPPED_INELIGIBLE");
        verifyNoInteractions(ledger, gateway);

        // 2. 样本达标且同指纹已有有效简报 -> 零费用 REUSED，绝不调用模型网关。
        var multiDayReports = eligibleThreeDayReports();
        var input = TrendBriefingInputBuilder.build(ANCHOR, multiDayReports);
        assertThat(input.eligible()).isTrue();
        var cachedContent = new TrendBriefingContentVO(
                "本地代理编排与开发工具成为本周集中方向",
                "本期观察覆盖 3 天日榜并缺采 4 天，属于有限样本观察。已分类项目中代理工具与开发辅助占比居前，整体仍偏早期形态，需继续观察团队真实落地反馈。",
                List.of(
                        new TrendBriefingContentVO.Theme("终端编码助手的插件化封装", "CONCENTRATION",
                                "多款上榜仓库把大模型编码能力收进本地 CLI（命令行工具），通过统一配置接入代码搜索与补丁生成。不同项目在权限隔离与上下文裁剪上路径分化，大规模仓库的稳定性尚缺公开压测。",
                                List.of("org/repo-1", "org/repo-2")),
                        new TrendBriefingContentVO.Theme("提交前离线配置校验工具回暖", "RECURRING",
                                "围绕微服务配置与流水线检查的工具连续上榜，共同目标是在提交代码前暴露格式与字段错误，减少等待远程构建的耗时。各方案在规则声明方式上有所差异，但对复杂业务语义覆盖有限。",
                                List.of("org/repo-3", "org/repo-4"))
                ),
                List.of("本期仅覆盖 3 天日榜，属于有限样本，分类占比不宜直接等同全周趋势。"));
        var saved = new TrendBriefingRecord(
                ANCHOR, ANCHOR.minusDays(6), ANCHOR, 3, "LIMITED",
                input.sourceFingerprint(), ai.model(), TrendBriefingCodec.RULE, null,
                codec.encode(cachedContent), LocalDateTime.of(2026, 11, 26, 0, 5));
        when(reports.windowSnapshot(ANCHOR.toString()))
                .thenReturn(new TrendReportService.WindowSnapshot(ANCHOR, multiDayReports));
        when(ledger.briefing(ANCHOR)).thenReturn(saved);

        assertThat(service.generateForScheduled(ANCHOR)).isEqualTo("REUSED");
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest(name = "发布台今日自动受理状态推导：{0}")
    @MethodSource("scheduleStatusCases")
    void derivesTodayScheduleStatusDeterministically(
            String scenario,
            boolean scheduleEnabled,
            LocalDateTime utcNow,
            boolean todayPublished,
            List<TrendScheduleRecord> attempts,
            List<TrendJobRecord> recentJobs,
            String expectedStatus) {
        var jobMapper = mock(TrendJobMapper.class);
        var scheduleMapper = mock(TrendScheduleMapper.class);
        var owners = mock(TrendOwnerAuthorization.class);
        var archive = mock(TrendArchiveService.class);
        var txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jobMapper.now()).thenReturn(utcNow);
        when(jobMapper.recent(5)).thenReturn(recentJobs);
        when(scheduleMapper.published(ANCHOR)).thenReturn(todayPublished);
        when(scheduleMapper.byDay(ANCHOR)).thenReturn(attempts);

        var store = new TrendJobStore(
                jobMapper, owners, archive,
                new TrendProperties(true, null),
                new TrendJobProperties(true),
                new TrendAiProperties(false, "", "deepseek-flash", BigDecimal.valueOf(2), BigDecimal.valueOf(8), 10_000_000L, false, "low"),
                txManager,
                new TrendScheduleProperties(scheduleEnabled, "06:30", 45),
                scheduleMapper,
                new TrendBriefingProperties(true));

        var status = store.scheduleStatus();
        assertThat(status.todayDate()).isEqualTo(ANCHOR);
        assertThat(status.todayStatus()).isEqualTo(expectedStatus);
        assertThat(status.briefingAutoEnabled()).isEqualTo(scheduleEnabled);
        assertThat(status.recentJobs()).hasSize(recentJobs.size());
        assertThat(status.recentJobs()).allSatisfy(item -> assertThat(item.summary()).isNull());
        verifyNoInteractions(archive);
        if (!scheduleEnabled) {
            verify(scheduleMapper, never()).byDay(any());
        }
    }

    static Stream<Arguments> scheduleStatusCases() {
        // 北京时间 06:00 = UTC 前一日 22:00；北京时间 07:00 = UTC 前一日 23:00；北京时间 08:00 = UTC 当日 00:00
        var bj0600 = LocalDateTime.of(2026, 11, 25, 22, 0);
        var bj0700 = LocalDateTime.of(2026, 11, 25, 23, 0);
        var bj0800 = LocalDateTime.of(2026, 11, 26, 0, 0);
        var attempt0 = new TrendScheduleRecord(ANCHOR, 0, JOB_ID, bj0600);
        var publishedAutoJob = jobRecord(null, "SUCCEEDED", "DONE", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        var failedCollectingJob = jobRecord(null, "FAILED", "COLLECTING", null);
        var runningAutoJob = jobRecord(null, "RUNNING", "ANALYZING", null);

        return Stream.of(
                Arguments.of("定时开关关闭时返回 DISABLED 且不读 014 表", false, bj0600, false, List.of(), List.of(), "DISABLED"),
                Arguments.of("窗口前无尝试返回 WAITING_WINDOW", true, bj0600, false, List.of(), List.of(), "WAITING_WINDOW"),
                Arguments.of("今日自动任务运行中返回 RUNNING", true, bj0600, false, List.of(attempt0), List.of(runningAutoJob), "RUNNING"),
                Arguments.of("今日自动发布成功返回 PUBLISHED_AUTO", true, bj0700, true, List.of(attempt0), List.of(publishedAutoJob), "PUBLISHED_AUTO"),
                Arguments.of("今日已由屋主手动发布返回 PUBLISHED_MANUAL", true, bj0700, true, List.of(), List.of(), "PUBLISHED_MANUAL"),
                Arguments.of("首次采集失败且未过补偿窗口返回 RETRY_PENDING", true, bj0700, false, List.of(attempt0), List.of(failedCollectingJob), "RETRY_PENDING"),
                Arguments.of("已过今日采集窗口且无尝试返回 MISSED_WINDOW", true, bj0800, false, List.of(), List.of(), "MISSED_WINDOW")
        );
    }

    private static TrendJobRecord jobRecord(Long actorId, String state, String phase, String reportId) {
        var now = LocalDateTime.of(2026, 11, 25, 22, 30);
        return new TrendJobRecord(JOB_ID, actorId, actorId == null ? null : 1L, true, 1L,
                state, phase, now, now, reportId == null ? null : now.plusMinutes(1),
                now.plusMinutes(2), now.plusMinutes(30), reportId, null);
    }

    private static List<TrendReportVO> eligibleThreeDayReports() {
        var now = Instant.parse("2026-11-26T00:00:00Z");
        var insight = new com.polyethene.polyhouse.model.vo.TrendInsightVO(
                "AI_AGENT", "定义", "痛点", null, List.of("亮点"), List.of("限制"), List.of("风险"),
                "风向信号", null, now, "sha", "deepseek-flash",
                com.polyethene.polyhouse.service.trend.TrendInsightCodec.RULE);
        List<com.polyethene.polyhouse.model.vo.TrendProjectVO> items = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(i -> new com.polyethene.polyhouse.model.vo.TrendProjectVO(
                        i, String.valueOf(100 + i), "org/repo-" + i, "https://github.com/org/repo-" + i,
                        "desc", "Python", 1000L, 200L, 50L, "MIT", false, "CODE_PROJECT",
                        "HAS_LANGUAGE", "AVAILABLE", "AVAILABLE", "ANALYZED", insight))
                .toList();
        return List.of(
                new TrendReportVO("00000000-0000-0000-0000-000000000026", "DAILY", ANCHOR, now, now, "https://github.com/trending?since=daily", true, items),
                new TrendReportVO("00000000-0000-0000-0000-000000000025", "DAILY", ANCHOR.minusDays(1), now.minusSeconds(86400), now, "https://github.com/trending?since=daily", true, items),
                new TrendReportVO("00000000-0000-0000-0000-000000000024", "DAILY", ANCHOR.minusDays(2), now.minusSeconds(172800), now, "https://github.com/trending?since=daily", true, items)
        );
    }
}

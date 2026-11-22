package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.model.vo.TrendInsightVO;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.service.trend.GithubTrendingParser;
import com.polyethene.polyhouse.service.trend.TrendInsightCodec;
import com.polyethene.polyhouse.service.trend.TrendWindowAggregator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 逐项手算复核 7 日榜内风向聚合规则，确保去重、缺口、中位数、旧稿时间与小样本护栏准确无误。 */
class TrendWindowAggregatorTest {

    @Test void aggregatesFiveDayWindowWithMissingDaysRenameMergeNullStarsAndStaleDates() {
        LocalDate anchor = LocalDate.of(2026, 11, 22);
        Instant staleOld = Instant.parse("2026-11-15T08:00:00Z");
        Instant fresh = Instant.parse("2026-11-22T02:00:00Z");

        // 11-22（锚点日）：
        // #1 id=101 acme/agent-new（由旧名 acme/agent 改名，DEVTOOLS，radar-3，+300）
        // #2 id=102 acme/model（AI_INFRA，STALE 旧稿，stars=null 缺失不计零）
        // #3 id=null acme/ghost（无稳定 ID，缺解读）
        // #4 id=104 acme/books（DOC_LIST，资料清单）
        var day22 = report(anchor, "2026-11-22T02:10:00Z", List.of(
                project(1, "101", "acme/agent-new", 300L, "CODE_PROJECT", "ANALYZED", insight("DEVTOOLS", fresh, TrendInsightCodec.RULE, "插件生态趋于标准化")),
                project(2, "102", "acme/model", null, "CODE_PROJECT", "STALE", insight("AI_INFRA", staleOld, "radar-2", null)),
                project(3, null, "acme/ghost", 80L, "CODE_PROJECT", "UNAVAILABLE", null),
                project(4, "104", "acme/books", 50L, "DOC_LIST", "NOT_REQUIRED", null)
        ));

        // 11-21：
        // #1 id=101 acme/agent（同 ID 旧名，+200）
        // #2 id=103 acme/cli（DEVTOOLS，radar-3，+100）
        var day21 = report(LocalDate.of(2026, 11, 21), "2026-11-21T02:10:00Z", List.of(
                project(1, "101", "acme/agent", 200L, "CODE_PROJECT", "REUSED", insight("DEVTOOLS", fresh, TrendInsightCodec.RULE, "插件生态趋于标准化")),
                project(2, "103", "acme/cli", 100L, "CODE_PROJECT", "ANALYZED", insight("DEVTOOLS", fresh, TrendInsightCodec.RULE, "命令行工作流简化"))
        ));

        // 11-20 缺采（自然断开 11-19 与 11-21 的连榜）
        // 11-19：
        // #1 id=null acme/agent-new（当天元数据缺失无 ID，但与窗口内唯一 ID=101 同名，自动归并）
        // #2 id=105 acme/ui（FRONTEND，+400）
        var day19 = report(LocalDate.of(2026, 11, 19), "2026-11-19T02:10:00Z", List.of(
                project(1, null, "acme/agent-new", 150L, "CODE_PROJECT", "UNAVAILABLE", null),
                project(2, "105", "acme/ui", 400L, "CODE_PROJECT", "ANALYZED", insight("FRONTEND", fresh, "radar-1", null))
        ));

        // 11-18：
        // #1 id=105 acme/ui（FRONTEND，+350）
        var day18 = report(LocalDate.of(2026, 11, 18), "2026-11-18T02:10:00Z", List.of(
                project(1, "105", "acme/ui", 350L, "CODE_PROJECT", "ANALYZED", insight("FRONTEND", fresh, "radar-1", null))
        ));

        // 11-17：
        // #1 id=106 acme/engine（AI_INFRA，+500）
        var day17 = report(LocalDate.of(2026, 11, 17), "2026-11-17T02:10:00Z", List.of(
                project(1, "106", "acme/engine", 500L, "CODE_PROJECT", "ANALYZED", insight("AI_INFRA", fresh, "radar-3", "推理框架下沉"))
        ));

        // 11-16 缺采；11-15 超出 7 日窗口必须被忽略；同日更早采集版本必须被忽略。
        var outOfWindow = report(LocalDate.of(2026, 11, 15), "2026-11-15T02:10:00Z", List.of(
                project(1, "999", "acme/old", 999L, "CODE_PROJECT", "ANALYZED", insight("OTHER", fresh, "radar-1", null))
        ));
        var earlierSameDay = report(anchor, "2026-11-22T01:00:00Z", List.of(
                project(1, "888", "acme/replaced", 10L, "CODE_PROJECT", "ANALYZED", insight("OTHER", fresh, "radar-1", null))
        ));

        var stats = TrendWindowAggregator.aggregate(anchor,
                List.of(day18, outOfWindow, earlierSameDay, day22, day17, day21, day19));

        assertThat(stats.periodType()).isEqualTo("DAILY");
        assertThat(stats.anchorDate()).isEqualTo(anchor);
        assertThat(stats.windowStart()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(stats.windowEnd()).isEqualTo(anchor);
        assertThat(stats.windowDays()).isEqualTo(7);
        assertThat(stats.coveredDays()).isEqualTo(5);
        assertThat(stats.sampleStatus()).isEqualTo("SUFFICIENT");
        assertThat(stats.coveredDates()).containsExactly(
                LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 21),
                LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 17));
        assertThat(stats.missingDates()).containsExactly(
                LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 16));

        // 锚点当日分母：4 项 = 2 已分类（DEVTOOLS + AI_INFRA）+ 1 资料清单 + 1 缺解读
        assertThat(stats.anchorSummary().totalItems()).isEqualTo(4);
        assertThat(stats.anchorSummary().classifiedItems()).isEqualTo(2);
        assertThat(stats.anchorSummary().unclassifiedItems()).isEqualTo(2);
        assertThat(stats.anchorSummary().docListItems()).isEqualTo(1);
        assertThat(stats.anchorSummary().missingInsightItems()).isEqualTo(1);

        // 7 日去重分母：独立仓库 7 个（101, 102, ghost, 104, 103, 105, 106），累计 10 人次
        // 已分类 5 个（101, 102, 103, 105, 106），未分类 2 个（104 DOC_LIST + ghost 缺解读）
        // 有涨星 6 个（102 缺失不计零），STALE 旧稿分类 1 个（102），未核实身份 1 个（ghost），radar-3 含 wind signal 3 个（101, 103, 106）
        var ws = stats.windowSummary();
        assertThat(ws.uniqueRepoCount()).isEqualTo(7);
        assertThat(ws.totalAppearances()).isEqualTo(10);
        assertThat(ws.classifiedRepoCount()).isEqualTo(5);
        assertThat(ws.unclassifiedRepoCount()).isEqualTo(2);
        assertThat(ws.docListRepoCount()).isEqualTo(1);
        assertThat(ws.missingInsightRepoCount()).isEqualTo(1);
        assertThat(ws.starsKnownRepoCount()).isEqualTo(6);
        assertThat(ws.staleClassifiedRepoCount()).isEqualTo(1);
        assertThat(ws.oldestStaleAnalyzedAt()).isEqualTo(staleOld);
        assertThat(ws.unverifiedIdentityCount()).isEqualTo(1);
        assertThat(ws.radar3ReadyCount()).isEqualTo(3);

        // 分类手算核对：
        // 前序 4 天（21/19/18/17）去重已分类 4 个：101(DEVTOOLS), 103(DEVTOOLS), 105(FRONTEND), 106(AI_INFRA)
        // DEVTOOLS：锚点 1/2=0.5000，前序 2/4=0.5000，delta=0.0；窗口去重 2/5=0.4000，人次 4（101 三次 + 103 一次）
        //           涨星样本 2 个（300, 100）-> 中位数 200.0，最大 300
        // AI_INFRA：锚点 1/2=0.5000，前序 1/4=0.2500，delta=+0.2500；窗口去重 2/5=0.4000，人次 2
        //           涨星样本仅 1 个（106 的 500；102 为 null 不计零！）-> 中位数 500.0，stale=1，最早时间 staleOld
        // FRONTEND：锚点 0/2=0.0000，前序 1/4=0.2500，delta=-0.2500；窗口去重 1/5=0.2000，人次 2，中位数 400.0
        // DEVTOOLS 与 AI_INFRA 去重仓库数均为 2、锚点均为 1，按窗口累计上榜人次（4 > 2）排序。
        assertThat(stats.categories()).extracting("category").containsExactly("DEVTOOLS", "AI_INFRA", "FRONTEND");
        var devtools = stats.categories().stream().filter(c -> "DEVTOOLS".equals(c.category())).findFirst().orElseThrow();
        assertThat(devtools.anchorDayCount()).isEqualTo(1);
        assertThat(devtools.anchorDayShare()).isEqualTo(0.5);
        assertThat(devtools.windowUniqueCount()).isEqualTo(2);
        assertThat(devtools.windowAppearances()).isEqualTo(4);
        assertThat(devtools.windowShare()).isEqualTo(0.4);
        assertThat(devtools.shareDelta()).isEqualTo(0.0);
        assertThat(devtools.starsSampleCount()).isEqualTo(2);
        assertThat(devtools.medianStarsAdded()).isEqualTo(200.0);
        assertThat(devtools.maxRepoStarsAdded()).isEqualTo(300L);
        assertThat(devtools.staleClassifiedCount()).isZero();

        var infra = stats.categories().stream().filter(c -> "AI_INFRA".equals(c.category())).findFirst().orElseThrow();
        assertThat(infra.shareDelta()).isEqualTo(0.25);
        assertThat(infra.starsSampleCount()).isEqualTo(1);
        assertThat(infra.medianStarsAdded()).isEqualTo(500.0);
        assertThat(infra.staleClassifiedCount()).isEqualTo(1);
        assertThat(infra.oldestStaleAnalyzedAt()).isEqualTo(staleOld);

        var frontend = stats.categories().stream().filter(c -> "FRONTEND".equals(c.category())).findFirst().orElseThrow();
        assertThat(frontend.anchorDayCount()).isZero();
        assertThat(frontend.anchorDayShare()).isEqualTo(0.0);
        assertThat(frontend.shareDelta()).isEqualTo(-0.25);
        assertThat(frontend.medianStarsAdded()).isEqualTo(400.0);

        // 多日上榜仓库：
        // 101（acme/agent-new）：上榜 3 天（22, 21, 19），20 号缺采断开连榜 -> 最长连续 2 天，截至锚点连续 2 天，中位数 median(300,200,150)=200.0
        // 105（acme/ui）：上榜 2 天（19, 18），最长连续 2 天，截至锚点连续 0 天，inAnchorDay=false，中位数 median(400,350)=375.0
        assertThat(stats.recurringRepos()).hasSize(2);
        var firstRecurring = stats.recurringRepos().get(0);
        assertThat(firstRecurring.fullName()).isEqualTo("acme/agent-new");
        assertThat(firstRecurring.identityVerified()).isTrue();
        assertThat(firstRecurring.inAnchorDay()).isTrue();
        assertThat(firstRecurring.appearanceDays()).isEqualTo(3);
        assertThat(firstRecurring.consecutiveDays()).isEqualTo(2);
        assertThat(firstRecurring.anchorStreakDays()).isEqualTo(2);
        assertThat(firstRecurring.medianStarsAdded()).isEqualTo(200.0);
        assertThat(firstRecurring.appearedDates()).containsExactly(
                LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 19));

        var secondRecurring = stats.recurringRepos().get(1);
        assertThat(secondRecurring.fullName()).isEqualTo("acme/ui");
        assertThat(secondRecurring.inAnchorDay()).isFalse();
        assertThat(secondRecurring.appearanceDays()).isEqualTo(2);
        assertThat(secondRecurring.consecutiveDays()).isEqualTo(2);
        assertThat(secondRecurring.anchorStreakDays()).isZero();
        assertThat(secondRecurring.medianStarsAdded()).isEqualTo(375.0);
    }

    @Test void singleDayAndZeroClassifiedWindowStaySafeWithoutDivideByZero() {
        LocalDate anchor = LocalDate.of(2026, 11, 22);
        var onlyDay = report(anchor, "2026-11-22T02:10:00Z", List.of(
                project(1, "201", "acme/raw", null, "CODE_PROJECT", "NOT_ANALYZED", null),
                project(2, "202", "acme/docs", 120L, "DOC_LIST", "NOT_REQUIRED", null)
        ));
        var stats = TrendWindowAggregator.aggregate(anchor, List.of(onlyDay));
        assertThat(stats.coveredDays()).isEqualTo(1);
        assertThat(stats.missingDates()).hasSize(6);
        assertThat(stats.sampleStatus()).isEqualTo("INSUFFICIENT");
        assertThat(stats.categories()).isEmpty();
        assertThat(stats.recurringRepos()).isEmpty();
        assertThat(stats.anchorSummary().classifiedItems()).isZero();
        assertThat(stats.windowSummary().starsKnownRepoCount()).isEqualTo(1);
    }

    @Test void threeDayWindowMarksLimitedAndNullsDeltaWhenPreviousHasNoClassifiedItems() {
        LocalDate anchor = LocalDate.of(2026, 11, 22);
        Instant now = Instant.parse("2026-11-22T02:00:00Z");
        var d22 = report(anchor, "2026-11-22T02:10:00Z", List.of(
                project(1, "301", "acme/one", 100L, "CODE_PROJECT", "ANALYZED", insight("AI_AGENT", now, "radar-3", "代理编排"))));
        var d21 = report(LocalDate.of(2026, 11, 21), "2026-11-21T02:10:00Z", List.of(
                project(1, "302", "acme/two", 200L, "CODE_PROJECT", "NOT_ANALYZED", null)));
        var d20 = report(LocalDate.of(2026, 11, 20), "2026-11-20T02:10:00Z", List.of(
                project(1, "303", "acme/three", 300L, "CODE_PROJECT", "NOT_ANALYZED", null)));

        var stats = TrendWindowAggregator.aggregate(anchor, List.of(d22, d21, d20));
        assertThat(stats.sampleStatus()).isEqualTo("LIMITED");
        assertThat(stats.categories()).hasSize(1);
        assertThat(stats.categories().getFirst().anchorDayShare()).isEqualTo(1.0);
        assertThat(stats.categories().getFirst().shareDelta()).isNull();
    }

    @Test void categoryReturnsMoreThanThreeSampleReposInDeterministicOrder() {
        LocalDate anchor = LocalDate.of(2026, 11, 22);
        Instant now = Instant.parse("2026-11-22T02:00:00Z");
        var d22 = report(anchor, "2026-11-22T02:10:00Z", List.of(
                project(1, "401", "acme/alpha", 500L, "CODE_PROJECT", "ANALYZED", insight("DEVTOOLS", now, "radar-3", "信号")),
                project(2, "402", "acme/beta", 300L, "CODE_PROJECT", "ANALYZED", insight("DEVTOOLS", now, "radar-3", "信号")),
                project(3, "403", "acme/gamma", 200L, "CODE_PROJECT", "ANALYZED", insight("DEVTOOLS", now, "radar-3", "信号")),
                project(4, "404", "acme/delta", 100L, "CODE_PROJECT", "ANALYZED", insight("DEVTOOLS", now, "radar-3", "信号"))
        ));
        var d21 = report(LocalDate.of(2026, 11, 21), "2026-11-21T02:10:00Z", List.of(
                project(5, "404", "acme/delta", 90L, "CODE_PROJECT", "ANALYZED", insight("DEVTOOLS", now, "radar-3", "信号"))
        ));
        var stats = TrendWindowAggregator.aggregate(anchor, List.of(d22, d21));
        assertThat(stats.categories()).hasSize(1);
        // acme/delta 上榜 2 天排第一，其余均上榜 1 天按锚点排名升序排列，全部 4 个均完整返回。
        assertThat(stats.categories().getFirst().sampleRepos())
                .extracting("fullName")
                .containsExactly("acme/delta", "acme/alpha", "acme/beta", "acme/gamma");
    }

    private static TrendReportVO report(LocalDate date, String capturedAt, List<TrendProjectVO> items) {
        return new TrendReportVO(UUID.randomUUID().toString(), "DAILY", date, Instant.parse(capturedAt),
                Instant.parse(capturedAt).plusSeconds(30), GithubTrendingParser.SOURCE, false, items);
    }

    private static TrendProjectVO project(int rank, String repoId, String fullName, Long starsAdded,
                                          String repoType, String insightStatus, TrendInsightVO insight) {
        return new TrendProjectVO(rank, repoId, fullName, "https://github.com/" + fullName,
                "Example project", "TypeScript", 1000L, starsAdded, 50L, "MIT", false,
                repoType, "MANIFEST", repoId != null ? "AVAILABLE" : "UNAVAILABLE",
                insight != null ? "AVAILABLE" : "UNAVAILABLE", insightStatus, insight);
    }

    private static TrendInsightVO insight(String category, Instant analyzedAt, String rule, String signal) {
        return new TrendInsightVO(category, "用于测试的中文导语", "用于测试的具体场景说明。", null,
                List.of("测试机制亮点"), List.of(), List.of(), signal, analyzedAt, "a".repeat(40), "deepseek-flash", rule);
    }
}

package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.model.vo.TrendInsightVO;
import com.polyethene.polyhouse.model.vo.TrendPresenceVO;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.service.trend.TrendPresenceCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖日报异动计算器的核心业务契约：首期防误标、4 类榜上身份、缺采日、仓库改名与昨日退榜。
 */
class TrendPresenceCalculatorTest {
    private static final LocalDate ANCHOR = LocalDate.of(2026, 11, 26);
    private static final Instant BASE_TIME = Instant.parse("2026-11-26T00:05:00Z");

    @ParameterizedTest(name = "无有效前 7 日历史时优雅隐藏异动标识：{0}")
    @MethodSource("noBaselineCases")
    void hidesPresenceWhenNoValidPriorReportsExist(String caseName, List<TrendReportVO> priorReports) {
        var current = reportOn(ANCHOR, List.of(
                project(1, "101", "org/alpha", "TypeScript", "AI_AGENT", 320L),
                project(6, "102", "org/beta", "Rust", "DEVTOOLS", 150L)
        ));

        var enriched = TrendPresenceCalculator.enrich(current, priorReports);

        assertThat(enriched.presenceSummary()).isNull();
        assertThat(enriched.items()).allSatisfy(item -> assertThat(item.presence()).isNull());
    }

    static Stream<Arguments> noBaselineCases() {
        var tooOld = reportOn(ANCHOR.minusDays(8), List.of(project(1, "101", "org/alpha", "TypeScript", "AI_AGENT", 100L)));
        var sameDay = reportOn(ANCHOR, List.of(project(1, "101", "org/alpha", "TypeScript", "AI_AGENT", 100L)));
        var futureDay = reportOn(ANCHOR.plusDays(1), List.of(project(1, "101", "org/alpha", "TypeScript", "AI_AGENT", 100L)));
        return Stream.of(
                Arguments.of("历史为空列表", List.of()),
                Arguments.of("历史为 null", null),
                Arguments.of("仅含 8 天前超窗快照", List.of(tooOld)),
                Arguments.of("仅含当日或未来日期快照", List.of(sameDay, futureDay))
        );
    }

    @ParameterizedTest(name = "单项目身份判定：{0}")
    @MethodSource("identityClassificationCases")
    void classifiesPresenceSignalsDeterministically(
            String caseName,
            TrendProjectVO currentItem,
            List<TrendReportVO> priorReports,
            String expectedStatus,
            int expectedStreak,
            Integer expectedPreviousRank,
            Integer expectedRankDelta) {
        var current = reportOn(ANCHOR, List.of(currentItem));

        var enriched = TrendPresenceCalculator.enrich(current, priorReports);
        TrendPresenceVO presence = enriched.items().getFirst().presence();

        assertThat(presence).isNotNull();
        assertThat(presence.status()).isEqualTo(expectedStatus);
        assertThat(presence.streakDays()).isEqualTo(expectedStreak);
        assertThat(presence.previousRank()).isEqualTo(expectedPreviousRank);
        assertThat(presence.rankDelta()).isEqualTo(expectedRankDelta);
    }

    static Stream<Arguments> identityClassificationCases() {
        var dayMinus1 = reportOn(ANCHOR.minusDays(1), List.of(
                project(8, "201", "org/streak-up", "Go", "BACKEND", 200L),
                project(2, "202", "org/streak-down", "Rust", "DEVTOOLS", 180L),
                project(4, "203", "org/old-name", "Python", "AI_AGENT", 260L),
                project(5, null, "org/no-id-prev", "Java", "BACKEND", 120L)
        ));
        var dayMinus2 = reportOn(ANCHOR.minusDays(2), List.of(
                project(10, "201", "org/streak-up", "Go", "BACKEND", 190L),
                project(3, "301", "org/returning", "TypeScript", "FRONTEND", 150L)
        ));
        var dayMinus3 = reportOn(ANCHOR.minusDays(3), List.of(
                project(11, "201", "org/streak-up", "Go", "BACKEND", 170L)
        ));
        var dayMinus7 = reportOn(ANCHOR.minusDays(7), List.of(
                project(9, "302", "org/edge-return", "C++", "GRAPHICS", 110L)
        ));
        // 缺采日场景：昨天未采集，上一已发布日是前天（anchor - 2）
        var missingYesterdayPrior = List.of(
                reportOn(ANCHOR.minusDays(2), List.of(
                        project(7, "401", "org/gap-streak", "Rust", "DEVTOOLS", 210L)
                )),
                reportOn(ANCHOR.minusDays(4), List.of(
                        project(9, "401", "org/gap-streak", "Rust", "DEVTOOLS", 160L)
                ))
        );

        return Stream.of(
                Arguments.of(
                        "首次上榜且进入第 5 名判为空降前 5",
                        project(5, "901", "org/new-top", "Python", "AI_AGENT", 500L),
                        List.of(dayMinus1, dayMinus2),
                        TrendPresenceCalculator.STATUS_NEW_TOP, 1, null, null
                ),
                Arguments.of(
                        "首次上榜且排第 6 名判为普通新上榜",
                        project(6, "902", "org/new-normal", "Go", "DEVTOOLS", 220L),
                        List.of(dayMinus1, dayMinus2),
                        TrendPresenceCalculator.STATUS_NEW, 1, null, null
                ),
                Arguments.of(
                        "昨日不在榜但 2 天前在榜判为重回榜单",
                        project(3, "301", "org/returning", "TypeScript", "FRONTEND", 280L),
                        List.of(dayMinus1, dayMinus2),
                        TrendPresenceCalculator.STATUS_RETURNING, 1, null, null
                ),
                Arguments.of(
                        "7 天边界日曾在榜仍判为重回榜单",
                        project(1, "302", "org/edge-return", "C++", "GRAPHICS", 400L),
                        List.of(dayMinus1, dayMinus7),
                        TrendPresenceCalculator.STATUS_RETURNING, 1, null, null
                ),
                Arguments.of(
                        "连续 4 天在榜且排名上升 4 位",
                        project(4, "201", "org/streak-up", "Go", "BACKEND", 310L),
                        List.of(dayMinus1, dayMinus2, dayMinus3),
                        TrendPresenceCalculator.STATUS_STREAK, 4, 8, 4
                ),
                Arguments.of(
                        "连榜 2 天且排名下降 3 位",
                        project(5, "202", "org/streak-down", "Rust", "DEVTOOLS", 130L),
                        List.of(dayMinus1, dayMinus2),
                        TrendPresenceCalculator.STATUS_STREAK, 2, 2, -3
                ),
                Arguments.of(
                        "仓库改名但稳定 repositoryId 相同判为连榜而非新面孔",
                        project(1, "203", "org/renamed-repo", "Python", "AI_AGENT", 360L),
                        List.of(dayMinus1),
                        TrendPresenceCalculator.STATUS_STREAK, 2, 4, 3
                ),
                Arguments.of(
                        "上期缺 repositoryId 但当期与同全名唯一 ID 归并判为连榜",
                        project(5, "204", "org/no-id-prev", "Java", "BACKEND", 140L),
                        List.of(dayMinus1),
                        TrendPresenceCalculator.STATUS_STREAK, 2, 5, 0
                ),
                Arguments.of(
                        "缺采日沿上一已发布日连续计数并计算排名升降",
                        project(2, "401", "org/gap-streak", "Rust", "DEVTOOLS", 290L),
                        missingYesterdayPrior,
                        TrendPresenceCalculator.STATUS_STREAK, 3, 7, 5
                )
        );
    }

    @Test
    void buildsSummaryCountsAndYesterdayDroppedListExcludingRenamedRepos() {
        var yesterday = reportOn(ANCHOR.minusDays(1), List.of(
                project(1, "203", "org/old-name", "Python", "AI_AGENT", 400L),
                project(2, "501", "org/dropped-first", "Rust", "DEVTOOLS", 350L),
                project(7, "502", "org/dropped-second", "TypeScript", null, 120L)
        ));
        var twoDaysAgo = reportOn(ANCHOR.minusDays(2), List.of(
                project(1, "601", "org/older-dropped", "Go", "BACKEND", 300L),
                project(4, "301", "org/returning", "Python", "AI_INFRA", 220L)
        ));
        var current = reportOn(ANCHOR, List.of(
                project(1, "901", "org/airdrop", "Python", "AI_AGENT", 800L),
                project(2, "203", "org/new-name", "Python", "AI_AGENT", 500L),
                project(3, "301", "org/returning", "Python", "AI_INFRA", 300L),
                project(8, "902", "org/fresh-eight", "Go", "DEVTOOLS", 180L)
        ));

        var enriched = TrendPresenceCalculator.enrich(current, List.of(twoDaysAgo, yesterday));
        var summary = enriched.presenceSummary();

        assertThat(summary).isNotNull();
        assertThat(summary.previousDate()).isEqualTo(ANCHOR.minusDays(1));
        assertThat(summary.lookbackDays()).isEqualTo(2);
        assertThat(summary.newCount()).isEqualTo(2);
        assertThat(summary.newTopCount()).isEqualTo(1);
        assertThat(summary.returningCount()).isEqualTo(1);
        assertThat(summary.streakCount()).isEqualTo(1);

        // 改名仓库（repositoryId=203）仍在榜，不应出现在退榜列表；两日前退榜的 601 也不属于昨日退榜。
        assertThat(summary.droppedItems()).hasSize(2);
        assertThat(summary.droppedItems().get(0).fullName()).isEqualTo("org/dropped-first");
        assertThat(summary.droppedItems().get(0).previousRank()).isEqualTo(2);
        assertThat(summary.droppedItems().get(0).category()).isEqualTo("DEVTOOLS");
        assertThat(summary.droppedItems().get(0).previousStarsAdded()).isEqualTo(350L);
        assertThat(summary.droppedItems().get(1).fullName()).isEqualTo("org/dropped-second");
        assertThat(summary.droppedItems().get(1).previousRank()).isEqualTo(7);
        assertThat(summary.droppedItems().get(1).category()).isNull();
    }

    private static TrendReportVO reportOn(LocalDate date, List<TrendProjectVO> items) {
        return new TrendReportVO(
                "00000000-0000-0000-0000-0000000000" + String.format("%02d", date.getDayOfMonth()),
                "DAILY",
                date,
                BASE_TIME.minusSeconds((long) (ANCHOR.toEpochDay() - date.toEpochDay()) * 86400L),
                BASE_TIME,
                "https://github.com/trending?since=daily",
                true,
                items
        );
    }

    private static TrendProjectVO project(
            int rank, String repoId, String fullName, String language, String category, Long starsAdded) {
        TrendInsightVO insight = category == null ? null : new TrendInsightVO(
                category,
                "示例定义",
                "示例痛点",
                null,
                List.of("亮点"),
                List.of("限制"),
                List.of("风险"),
                null,
                null,
                BASE_TIME,
                "sha-1",
                "deepseek-flash",
                "v1"
        );
        return new TrendProjectVO(
                rank,
                repoId,
                fullName,
                "https://github.com/" + fullName,
                "description",
                language,
                1000L,
                starsAdded,
                100L,
                "MIT",
                false,
                "CODE_PROJECT",
                "HAS_LANGUAGE",
                "AVAILABLE",
                "AVAILABLE",
                insight != null ? "ANALYZED" : "NOT_ANALYZED",
                insight
        );
    }
}

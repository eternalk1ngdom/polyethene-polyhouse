package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.model.vo.TrendInsightVO;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.service.trend.GithubTrendingParser;
import com.polyethene.polyhouse.service.trend.TrendBriefingInputBuilder;
import com.polyethene.polyhouse.service.trend.TrendInsightCodec;
import com.polyethene.polyhouse.service.trend.TrendWindowAggregator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交叉验证 2A 确定性窗口统计与 2B 简报输入构造在四类仓库身份场景下保持同一归并口径：
 * 改名同 ID、同名不同 ID、缺 ID 但可唯一回查、缺 ID 且歧义或无候选不可回查。
 */
class TrendWindowIdentityTest {
    private static final LocalDate ANCHOR = LocalDate.of(2026, 11, 25);
    private static final Instant NOW = Instant.parse("2026-11-25T02:00:00Z");

    @Test void alignsFourIdentityCasesAcrossWindowStatsAndBriefingInput() {
        // 1. 改名同 ID：id=1001 从 acme/rename-old 改名为 acme/rename-new，且第 3 天缺 ID 但用新名可唯一回查 -> 合并为 1 个仓库、累计 3 天。
        // 2. 同名不同 ID：acme/takeover 分别以 id=2001 与 id=2002 上榜，且第 3 天缺 ID 同名出现（存在 2 个 ID 歧义）-> 不可盲目回查合并。
        // 3. 缺 ID 但唯一回查：acme/recover 以 id=3001 和 id=null 出现 -> 合并为 1 个仓库、累计 2 天。
        // 4. 缺 ID 且无候选：acme/orphan 两日均无 ID -> 按小写全名归并为 1 个未核实仓库、累计 2 天。
        var day25 = report(ANCHOR, List.of(
                repo(1, "1001", "acme/rename-new", "DEVTOOLS", 300L),
                repo(2, "2002", "acme/takeover", "BACKEND", 260L),
                repo(3, "3001", "acme/recover", "AI_AGENT", 220L),
                repo(4, null, "acme/orphan", "FRONTEND", 180L)
        ));
        var day24 = report(ANCHOR.minusDays(1), List.of(
                repo(1, "1001", "acme/rename-old", "DEVTOOLS", 280L),
                repo(2, "2001", "acme/takeover", "BACKEND", 240L),
                repo(3, null, "ACME/RECOVER", "AI_AGENT", 200L),
                repo(4, null, "ACME/ORPHAN", "FRONTEND", 160L)
        ));
        var day23 = report(ANCHOR.minusDays(2), List.of(
                repo(1, null, "acme/rename-new", "DEVTOOLS", 250L),
                repo(2, null, "acme/takeover", "BACKEND", 210L)
        ));

        var reports = List.of(day25, day24, day23);
        var stats = TrendWindowAggregator.aggregate(ANCHOR, reports);
        var briefing = TrendBriefingInputBuilder.build(ANCHOR, reports);

        // 窗口去重仓库总数应为 6：
        // id:1001（3 天）、id:2002（1 天）、id:2001（1 天）、name:acme/takeover（歧义未回查，1 天）、
        // id:3001（2 天）、name:acme/orphan（无候选未核实，2 天）。
        assertThat(stats.windowSummary().uniqueRepoCount()).isEqualTo(6);
        assertThat(stats.windowSummary().unverifiedIdentityCount()).isEqualTo(2);

        // 2A 多日上榜仓库与 2B 候选仓库在改名同 ID、唯一回查和无候选归并上的天数完全一致。
        var recurringDays = stats.recurringRepos().stream()
                .collect(java.util.stream.Collectors.toMap(r -> r.fullName().toLowerCase(java.util.Locale.ROOT), r -> r.appearanceDays()));
        assertThat(recurringDays)
                .containsEntry("acme/rename-new", 3)
                .containsEntry("acme/recover", 2)
                .containsEntry("acme/orphan", 2)
                .doesNotContainKey("acme/takeover");

        var renameCandidate = briefing.candidatesByLowerName().get("acme/rename-new");
        var recoverCandidate = briefing.candidatesByLowerName().get("acme/recover");
        var orphanCandidate = briefing.candidatesByLowerName().get("acme/orphan");
        var takeoverCandidate = briefing.candidatesByLowerName().get("acme/takeover");

        assertThat(renameCandidate).isNotNull();
        assertThat(renameCandidate.repositoryId()).isEqualTo("1001");
        assertThat(renameCandidate.appearanceDays()).isEqualTo(3);

        assertThat(recoverCandidate).isNotNull();
        assertThat(recoverCandidate.repositoryId()).isEqualTo("3001");
        assertThat(recoverCandidate.appearanceDays()).isEqualTo(2);

        assertThat(orphanCandidate).isNotNull();
        assertThat(orphanCandidate.repositoryId()).isNull();
        assertThat(orphanCandidate.appearanceDays()).isEqualTo(2);

        // 同名不同 ID + 歧义缺 ID 场景：不会把三个条目错误合并成 3 天，白名单按优先级保留锚点日最新条目 id=2002（1 天）。
        assertThat(takeoverCandidate).isNotNull();
        assertThat(takeoverCandidate.repositoryId()).isEqualTo("2002");
        assertThat(takeoverCandidate.appearanceDays()).isEqualTo(1);
    }

    private static TrendReportVO report(LocalDate day, List<TrendProjectVO> items) {
        return new TrendReportVO(UUID.randomUUID().toString(), "DAILY", day, NOW, NOW,
                GithubTrendingParser.SOURCE, true, items);
    }

    private static TrendProjectVO repo(int rank, String id, String fullName, String category, Long starsAdded) {
        var insight = new TrendInsightVO(category, fullName + " 导语", "解决常见研发痛点", null,
                List.of("亮点一"), List.of(), List.of(), "方向观察：" + fullName, null,
                NOW, "a".repeat(40), "deepseek-flash", TrendInsightCodec.RULE);
        return new TrendProjectVO(rank, id, fullName, "https://github.com/" + fullName,
                "desc", "Java", 1000L, starsAdded, 50L, "MIT", false,
                "CODE_PROJECT", "CODE", "AVAILABLE", "AVAILABLE", "ANALYZED", insight);
    }
}

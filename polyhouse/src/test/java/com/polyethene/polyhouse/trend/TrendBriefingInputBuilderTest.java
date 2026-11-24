package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.vo.TrendInsightVO;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.service.trend.GithubTrendingParser;
import com.polyethene.polyhouse.service.trend.TrendBriefingInputBuilder;
import com.polyethene.polyhouse.service.trend.TrendInsightCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 验证 7 日风口简报输入组装器的硬样本门槛、候选优先级、字节预算上限与确定性指纹。 */
class TrendBriefingInputBuilderTest {
    private static final LocalDate ANCHOR = LocalDate.of(2026, 11, 23);
    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");

    @Test void rejectsWhenCoveredDaysBelowThreeOrRadar3ReadyBelowFive() {
        // 仅 2 天日报即使 radar-4 仓库超过 5 个也必须拒绝。
        var day23 = report(ANCHOR, List.of(
                radarRepo(1, "101", "acme/alpha", "AI_AGENT", 300L, "本地代理编排趋于插件化"),
                radarRepo(2, "102", "acme/beta", "AI_AGENT", 260L, "终端编码助手接入统一协议"),
                radarRepo(3, "103", "acme/gamma", "DEVTOOLS", 220L, "配置校验前移到提交前")
        ));
        var day22 = report(ANCHOR.minusDays(1), List.of(
                radarRepo(1, "104", "acme/delta", "AI_INFRA", 200L, "本地推理服务向单文件收敛"),
                radarRepo(2, "105", "acme/epsilon", "BACKEND", 180L, "轻量任务队列替代重型中间件")
        ));

        var twoDays = TrendBriefingInputBuilder.build(ANCHOR, List.of(day23, day22));
        assertThat(twoDays.eligible()).isFalse();
        assertThat(twoDays.ineligibleReason()).isEqualTo("INSUFFICIENT_COVERED_DAYS");
        assertThat(twoDays.stats().sampleStatus()).isEqualTo("INSUFFICIENT");
        assertThatThrownBy(twoDays::requireEligible).isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少需要 3 天");

        // 覆盖 3 天但仅 4 个含 trendSignal 的仓库（其余为旧版 radar-2 与资料清单），同样拒绝。
        var day21 = report(ANCHOR.minusDays(2), List.of(
                radarRepo(1, "106", "acme/zeta", "DEVTOOLS", 150L, "命令行日志筛选加速"),
                legacyRepo(2, "107", "acme/legacy-a", "FRONTEND", 400L),
                legacyRepo(3, "108", "acme/legacy-b", "BACKEND", 350L),
                docListRepo(4, "109", "acme/awesome-list", 500L)
        ));
        var day22Few = report(ANCHOR.minusDays(1), List.of(
                radarRepo(1, "101", "acme/alpha", "AI_AGENT", 280L, "本地代理编排趋于插件化")
        ));

        var fourReady = TrendBriefingInputBuilder.build(ANCHOR, List.of(day23, day22Few, day21));
        assertThat(fourReady.stats().coveredDays()).isEqualTo(3);
        assertThat(fourReady.stats().windowSummary().radar3ReadyCount()).isEqualTo(4);
        assertThat(fourReady.eligible()).isFalse();
        assertThat(fourReady.ineligibleReason()).isEqualTo("INSUFFICIENT_RADAR3_READY");
        assertThatThrownBy(fourReady::requireEligible).isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少需要 5 个");
    }

    @Test void acceptsLimitedAndSufficientSamplesWithDeterministicOrderIndependentFingerprint() {
        var day23 = report(ANCHOR, List.of(
                radarRepo(1, "101", "Acme/Agent-New", "AI_AGENT", 320L, "代理工具从单脚本转向插件协议"),
                radarRepo(2, "102", "acme/cli-guard", "DEVTOOLS", 210L, "提交前配置校验逐渐普及"),
                docListRepo(3, "199", "acme/books", 900L)
        ));
        var day22 = report(ANCHOR.minusDays(1), List.of(
                // 同 ID 改名仓库应合并为锚点日最新全名 Acme/Agent-New，累计 2 天上榜。
                radarRepo(1, "101", "Acme/Agent-Old", "AI_AGENT", 290L, "代理工具从单脚本转向插件协议"),
                radarRepo(2, "103", "acme/sandbox", "AI_INFRA", 180L, "代码执行隔离向轻量容器收敛")
        ));
        var day21 = report(ANCHOR.minusDays(2), List.of(
                radarRepo(1, "104", "acme/schema-kit", "DEVTOOLS", 150L, "契约校验向声明式规则集中"),
                radarRepo(2, "105", "acme/trace-ui", "FRONTEND", 120L, "调用链排查面板趋于本地化"),
                legacyRepo(3, "106", "acme/old-tool", "BACKEND", 600L)
        ));

        var limitedA = TrendBriefingInputBuilder.build(ANCHOR, List.of(day23, day22, day21));
        var limitedB = TrendBriefingInputBuilder.build(ANCHOR, List.of(day21, day23, day22));

        assertThat(limitedA.eligible()).isTrue();
        assertThat(limitedA.ineligibleReason()).isNull();
        assertThat(limitedA.stats().sampleStatus()).isEqualTo("LIMITED");
        assertThatCode(limitedA::requireEligible).doesNotThrowAnyException();

        // 指纹必须为 64 位小写十六进制且与输入日报列表顺序无关。
        assertThat(limitedA.sourceFingerprint()).matches("[a-f0-9]{64}");
        assertThat(limitedA.sourceFingerprint()).isEqualTo(limitedB.sourceFingerprint());
        assertThat(limitedA.materialJson()).isEqualTo(limitedB.materialJson());

        // 资料清单被排除；有 trendSignal 且多日上榜的仓库排在最前；无 trendSignal 的旧稿排在最后。
        assertThat(limitedA.candidates()).extracting(TrendBriefingInputBuilder.CandidateRepo::fullName)
                .containsExactly("Acme/Agent-New", "acme/cli-guard", "acme/sandbox", "acme/schema-kit", "acme/trace-ui", "acme/old-tool");
        assertThat(limitedA.allowedReposByLowerName()).containsEntry("acme/agent-new", "Acme/Agent-New")
                .doesNotContainKey("acme/books");
        assertThat(limitedA.candidates().getFirst().appearanceDays()).isEqualTo(2);

        // 任一候选的 trendSignal 变动后，指纹必须立即变化。
        var mutatedDay21 = report(ANCHOR.minusDays(2), List.of(
                radarRepo(1, "104", "acme/schema-kit", "DEVTOOLS", 150L, "契约校验向编译期静态分析集中"),
                radarRepo(2, "105", "acme/trace-ui", "FRONTEND", 120L, "调用链排查面板趋于本地化"),
                legacyRepo(3, "106", "acme/old-tool", "BACKEND", 600L)
        ));
        var mutated = TrendBriefingInputBuilder.build(ANCHOR, List.of(day23, day22, mutatedDay21));
        assertThat(mutated.sourceFingerprint()).isNotEqualTo(limitedA.sourceFingerprint());

        // 凑齐 5 天后样本档位升为 SUFFICIENT。
        var day20 = report(ANCHOR.minusDays(3), List.of(radarRepo(1, "107", "acme/day20", "AI_AGENT", 110L, "轻代理工具增多")));
        var day19 = report(ANCHOR.minusDays(4), List.of(radarRepo(1, "108", "acme/day19", "DEVTOOLS", 100L, "本地检查工具增多")));
        var sufficient = TrendBriefingInputBuilder.build(ANCHOR, List.of(day23, day22, day21, day20, day19));
        assertThat(sufficient.eligible()).isTrue();
        assertThat(sufficient.stats().coveredDays()).isEqualTo(5);
        assertThat(sufficient.stats().sampleStatus()).isEqualTo("SUFFICIENT");
    }

    @Test void capsCandidatePoolAt45AndRespectsMaterialByteBudgetForLongChineseSignals() {
        List<TrendProjectVO> day23Items = new ArrayList<>();
        List<TrendProjectVO> day22Items = new ArrayList<>();
        List<TrendProjectVO> day21Items = new ArrayList<>();
        String longSignal = "开发者工作流正从手工拼接转向声明式编排与离线校验，多个工具把复杂配置收敛成单文件入口。".repeat(4);

        for (int i = 1; i <= 20; i++) {
            day23Items.add(radarRepo(i, String.valueOf(1000 + i), "acme/d23-repo-" + i, "AI_AGENT", 500L - i, longSignal));
            day22Items.add(radarRepo(i, String.valueOf(2000 + i), "acme/d22-repo-" + i, "DEVTOOLS", 400L - i, longSignal));
            day21Items.add(radarRepo(i, String.valueOf(3000 + i), "acme/d21-repo-" + i, "AI_INFRA", 300L - i, longSignal));
        }

        var input = TrendBriefingInputBuilder.build(ANCHOR, List.of(
                report(ANCHOR, day23Items),
                report(ANCHOR.minusDays(1), day22Items),
                report(ANCHOR.minusDays(2), day21Items)));

        assertThat(input.eligible()).isTrue();
        assertThat(input.candidates().size()).isBetween(5, TrendBriefingInputBuilder.MAX_CANDIDATE_REPOS);
        assertThat(input.materialJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(TrendBriefingInputBuilder.MAX_ESCAPED_MATERIAL_BYTES);
        // 锚点日高涨星仓库优先保留在候选池头部。
        assertThat(input.candidates().getFirst().fullName()).isEqualTo("acme/d23-repo-1");
    }

    private static TrendReportVO report(LocalDate day, List<TrendProjectVO> items) {
        return new TrendReportVO(UUID.randomUUID().toString(), "DAILY", day, NOW, NOW,
                GithubTrendingParser.SOURCE, false, items);
    }

    private static TrendProjectVO radarRepo(int rank, String id, String fullName, String category, Long starsAdded, String signal) {
        var insight = new TrendInsightVO(category, fullName + " 面向开发者的本地效率工具。",
                "第一段说明痛点。\n\n第二段说明交付。", null, List.of("轻量单文件"), List.of(), List.of(),
                signal, "用于提升开发流程效率的开源项目。", NOW, "a".repeat(40), "deepseek-flash", TrendInsightCodec.RULE);
        return new TrendProjectVO(rank, id, fullName, "https://github.com/" + fullName, "desc", "TypeScript",
                1000L, starsAdded, 50L, "MIT", false, "CODE_PROJECT", "CODE", "AVAILABLE", "AVAILABLE", "ANALYZED", insight);
    }

    private static TrendProjectVO legacyRepo(int rank, String id, String fullName, String category, Long starsAdded) {
        var insight = new TrendInsightVO(category, fullName + " 旧版规则解读的开源项目。",
                "旧版正文说明。", null, List.of("旧版亮点"), List.of(), List.of(),
                null, null, NOW, "b".repeat(40), "deepseek-flash", "radar-2");
        return new TrendProjectVO(rank, id, fullName, "https://github.com/" + fullName, "desc", "Go",
                800L, starsAdded, 40L, "MIT", false, "CODE_PROJECT", "CODE", "AVAILABLE", "AVAILABLE", "ANALYZED", insight);
    }

    private static TrendProjectVO docListRepo(int rank, String id, String fullName, Long starsAdded) {
        return new TrendProjectVO(rank, id, fullName, "https://github.com/" + fullName, "awesome list", null,
                2000L, starsAdded, 100L, "MIT", false, "DOC_LIST", "AWESOME", "AVAILABLE", "AVAILABLE", "NOT_REQUIRED", null);
    }
}

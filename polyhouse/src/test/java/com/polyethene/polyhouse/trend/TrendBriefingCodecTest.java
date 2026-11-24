package com.polyethene.polyhouse.trend;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.vo.TrendInsightVO;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.service.trend.GithubTrendingParser;
import com.polyethene.polyhouse.service.trend.TrendBriefingCodec;
import com.polyethene.polyhouse.service.trend.TrendBriefingCodec.OutputRejected;
import com.polyethene.polyhouse.service.trend.TrendBriefingInputBuilder;
import com.polyethene.polyhouse.service.trend.TrendInsightCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 验证 briefing-1 提示词构造、24 KiB 有界约束、Few-Shot 自洽、双仓白名单防幻觉与严格反 Markdown 校验。 */
class TrendBriefingCodecTest {
    private static final LocalDate ANCHOR = LocalDate.of(2026, 11, 23);
    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private final TrendBriefingCodec codec = new TrendBriefingCodec(TrendFixture.json());

    @Test void preparesBoundedPromptWithValidEmbeddedFewShotAndNoCredentials() throws Exception {
        var input = largeEligibleInput();
        var prepared = codec.prepare(input, "deepseek-flash");

        assertThat(prepared.body().length).isLessThanOrEqualTo(TrendBriefingCodec.MAX_REQUEST_BYTES);
        assertThat(prepared.inputBound()).isEqualTo(prepared.body().length + 512);
        assertThat(prepared.outputBound()).isEqualTo(1500);
        assertThat(prepared.toString()).doesNotContain("acme/");
        assertThat(new String(prepared.body(), StandardCharsets.UTF_8)).doesNotContain(TrendAiFixture.KEY);

        var body = TrendFixture.json().readTree(prepared.body());
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.has("tools")).isFalse();

        // 嵌入在 system prompt 中的正面示范 JSON 自身必须严格通过编解码器校验，且包含防硬凑与中文指标要求。
        String system = body.path("messages").get(0).path("content").asText();
        assertThat(system).contains("宁可只输出 2 条高置信度主题", "严禁照抄输入 JSON 的英文键名");
        String fewShotJson = system.substring(system.indexOf("\n{") + 1).strip();
        var demoWhitelist = Map.of(
                "demo/ota-diff", "demo/ota-diff",
                "demo/flash-pack", "demo/flash-pack",
                "demo/synth-graph", "demo/synth-graph",
                "demo/dsp-rack", "demo/dsp-rack");
        var parsedDemo = codec.parse(fewShotJson, demoWhitelist);
        assertThat(parsedDemo.themes()).hasSize(2);
        assertThat(parsedDemo.watchouts()).hasSize(2);
        assertThat(codec.cached(codec.encode(parsedDemo))).isEqualTo(parsedDemo);

        // 轻度思考仅放行 low 档，其他档位必须拒绝。
        var lowThinking = codec.prepare(input, "deepseek-flash", true, "low");
        assertThat(lowThinking.outputBound()).isEqualTo(3000);
        assertThat(lowThinking.body().length).isLessThanOrEqualTo(TrendBriefingCodec.MAX_REQUEST_BYTES);
        assertThatThrownBy(() -> codec.prepare(input, "deepseek-flash", true, "high"))
                .isInstanceOf(BusinessException.class);
    }

    @Test void normalizesEvidenceRepoCaseAndRoundTripsCachedPayload() {
        var whitelist = Map.of(
                "acme/code-pilot", "Acme/Code-Pilot",
                "acme/shell_kit", "Acme/Shell_Kit",
                "acme/yaml__guard", "Acme/Yaml__Guard",
                "acme/schema-lint", "Acme/Schema-Lint");

        String raw = validJson(
                List.of("acme/code-pilot", "ACME/SHELL_KIT"),
                List.of("acme/yaml__guard", "acme/schema-lint"));

        var parsed = codec.parse(raw, whitelist);
        assertThat(parsed.themes().get(0).evidenceRepos()).containsExactly("Acme/Code-Pilot", "Acme/Shell_Kit");
        assertThat(parsed.themes().get(1).evidenceRepos()).containsExactly("Acme/Yaml__Guard", "Acme/Schema-Lint");
        assertThat(codec.cached(codec.encode(parsed))).isEqualTo(parsed);
        assertThat(codec.cached(null)).isNull();
    }

    @Test void rejectsHallucinatedReposDuplicateEvidenceAndIdenticalThemeEvidenceSets() {
        var whitelist = Map.of(
                "acme/alpha", "acme/alpha",
                "acme/beta", "acme/beta",
                "acme/gamma", "acme/gamma");

        // 编造不在快照白名单中的仓库必须拒收。
        assertRejected(validJson(List.of("acme/alpha", "acme/hallucinated"), List.of("acme/beta", "acme/gamma")),
                whitelist, "UNLISTED_EVIDENCE_REPO");

        // 单主题内重复引用同一仓库（含大小写变体）必须拒收。
        assertRejected(validJson(List.of("acme/alpha", "ACME/ALPHA"), List.of("acme/beta", "acme/gamma")),
                whitelist, "DUPLICATE_EVIDENCE_REPO");

        // 跨主题完全复用同一组证据仓库（即使顺序颠倒）必须拒收。
        assertRejected(validJson(List.of("acme/alpha", "acme/beta"), List.of("ACME/BETA", "acme/alpha")),
                whitelist, "DUPLICATE_THEME_EVIDENCE_SET");

        // 单主题仅引用 1 个仓库（低于双仓下限）必须拒收。
        assertRejected(validJson(List.of("acme/alpha"), List.of("acme/beta", "acme/gamma")),
                whitelist, "INVALID_LIST_evidenceRepos");
    }

    @Test void rejectsMarkdownFormattingDuplicateKeysUnknownFieldsTrailingJsonAndBadLengths() throws Exception {
        var whitelist = Map.of(
                "acme/alpha", "acme/alpha",
                "acme/beta", "acme/beta",
                "acme/gamma", "acme/gamma",
                "acme/delta", "acme/delta");
        String base = validJson(List.of("acme/alpha", "acme/beta"), List.of("acme/gamma", "acme/delta"));

        // 重复键与尾随 JSON。
        assertRejected(base.replace("\"headline\":", "\"headline\":\"这是重复键占位十二个字符以上\",\"headline\":"),
                whitelist, "DUPLICATE_JSON_KEY");
        assertRejected(base + "{}", whitelist, "INVALID_JSON");
        assertRejected("   ", whitelist, "EMPTY_CONTENT");
        assertRejected(null, whitelist, "NO_COMPLETE_TEXT");

        // 顶层多键、缺键。
        assertRejected(base.replace("\"headline\":", "\"extra\":\"x\",\"headline\":"), whitelist, "UNEXPECTED_FIELDS");
        var missingOverview = (ObjectNode) TrendFixture.json().readTree(base);
        missingOverview.remove("overview");
        assertRejected(missingOverview.toString(), whitelist, "MISSING_overview");

        // Markdown 符号拒收：反引号、双星号加粗、成对双下划线、行首标题、方括号链接。
        var backtickHeadline = (ObjectNode) TrendFixture.json().readTree(base);
        backtickHeadline.put("headline", "本地 `CLI` 代理工具本周持续升温聚集");
        assertRejected(backtickHeadline.toString(), whitelist, "MARKDOWN_IN_headline");

        var boldSummary = (ObjectNode) TrendFixture.json().readTree(base);
        ((ObjectNode) boldSummary.get("themes").get(0)).put("summary",
                "多款项目围绕 **本地代理编排** 与插件化工具协议展开，把模型调用、代码检索和文件补丁收进同一终端入口，各方案在隔离机制与上下文裁剪上路径分化，但在复杂多仓协作场景下仍缺少公开稳定性验证与长期维护记录。");
        assertRejected(boldSummary.toString(), whitelist, "MARKDOWN_IN_summary");

        var underlineSummary = (ObjectNode) TrendFixture.json().readTree(base);
        ((ObjectNode) underlineSummary.get("themes").get(0)).put("summary",
                "多款项目围绕 __本地代理编排__ 与插件化工具协议展开，把模型调用、代码检索和文件补丁收进同一终端入口，各方案在隔离机制与上下文裁剪上路径分化，但在复杂多仓协作场景下仍缺少公开稳定性验证与长期维护记录。");
        assertRejected(underlineSummary.toString(), whitelist, "MARKDOWN_IN_summary");

        var headingOverview = (ObjectNode) TrendFixture.json().readTree(base);
        headingOverview.put("overview", "## 本周观察\n本期覆盖 5 天日榜并缺采 2 天，开发工具与代理框架占比居前，整体仍处于早期迭代阶段，需继续观察实际工程落地反馈。");
        assertRejected(headingOverview.toString(), whitelist, "MARKDOWN_IN_overview");

        var linkWatchout = (ObjectNode) TrendFixture.json().readTree(base);
        linkWatchout.putArray("watchouts").add("详见 [榜单](https://github.com/trending) 当日页面。");
        assertRejected(linkWatchout.toString(), whitelist, "MARKDOWN_IN_watchouts");

        // 非法 signalType、超短标题与超长 watchout。
        var badSignal = (ObjectNode) TrendFixture.json().readTree(base);
        ((ObjectNode) badSignal.get("themes").get(0)).put("signalType", "EXPLODING");
        assertRejected(badSignal.toString(), whitelist, "UNKNOWN_SIGNAL_TYPE");

        var shortHeadline = (ObjectNode) TrendFixture.json().readTree(base);
        shortHeadline.put("headline", "标题过短");
        assertRejected(shortHeadline.toString(), whitelist, "TOO_SHORT_headline");

        var longWatchout = (ObjectNode) TrendFixture.json().readTree(base);
        longWatchout.putArray("watchouts").add("界".repeat(101));
        assertRejected(longWatchout.toString(), whitelist, "TOO_LONG_watchouts");

        // 损坏的持久化 JSON（非法仓库名格式）在 cached() 中必须被拒绝。
        var badCached = (ObjectNode) TrendFixture.json().readTree(base);
        ((ObjectNode) badCached.get("themes").get(0)).putArray("evidenceRepos").add("not-a-repo").add("acme/beta");
        assertThatThrownBy(() -> codec.cached(badCached.toString())).isInstanceOf(BusinessException.class);

        // 历史入库旧稿（导语/摘要在 161～240 字符之间）在 cached() 中必须继续兼容，但新生成 parse() 须受 160 字符精炼上限约束。
        var legacyLong = (ObjectNode) TrendFixture.json().readTree(base);
        String legacyOverview = "这周大家都在给编程助手配现成技能包与插件，让本地开发少写重复配置。".repeat(6).substring(0, 190);
        String legacySummary = "好几个上榜项目都在帮开发者把常用的需求澄清和代码检查流程打包成即插即用的小技能。".repeat(5).substring(0, 200);
        legacyLong.put("overview", legacyOverview);
        ((ObjectNode) legacyLong.get("themes").get(0)).put("summary", legacySummary);
        assertThat(codec.cached(legacyLong.toString()).overview()).isEqualTo(legacyOverview);
        assertRejected(legacyLong.toString(), whitelist, "TOO_LONG_overview");
    }

    @Test void validatesEnvelopeUsageAndIgnoresIncompleteChoices() throws Exception {
        String okContent = validJson(List.of("acme/alpha", "acme/beta"), List.of("acme/gamma", "acme/delta"));
        var envelope = codec.envelope(TrendAiFixture.reply(okContent, 800, 350).body());
        assertThat(envelope.input()).isEqualTo(800);
        assertThat(envelope.output()).isEqualTo(350);
        assertThat(envelope.content()).isEqualTo(okContent);

        String truncated = new String(TrendAiFixture.reply(okContent, 800, 1500).body(), StandardCharsets.UTF_8)
                .replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"length\"");
        var truncatedEnvelope = codec.envelope(truncated.getBytes(StandardCharsets.UTF_8));
        assertThat(truncatedEnvelope.input()).isEqualTo(800);
        assertThat(truncatedEnvelope.output()).isEqualTo(1500);
        assertThat(truncatedEnvelope.content()).isNull();

        String badTotal = new String(TrendAiFixture.reply("{}", 100, 20).body(), StandardCharsets.UTF_8)
                .replace("\"total_tokens\":120", "\"total_tokens\":121");
        assertThatThrownBy(() -> codec.envelope(badTotal.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class);
    }

    private void assertRejected(String content, Map<String, String> whitelist, String expectedReason) {
        assertThatExceptionOfType(OutputRejected.class)
                .isThrownBy(() -> codec.parse(content, whitelist))
                .satisfies(failure -> assertThat(failure.reason()).isEqualTo(expectedReason));
    }

    private static String validJson(List<String> theme1Repos, List<String> theme2Repos) {
        try {
            var mapper = TrendFixture.json();
            var root = mapper.createObjectNode();
            root.put("headline", "本地代理编排与离线配置校验成为本周集中方向");
            root.put("overview", "本期观察覆盖 5 天日榜并缺采 2 天，已分类项目中开发工具与智能体框架占比居前。多款仓库围绕离线执行与标准化规则展开，整体仍偏早期工具形态，需持续观察团队落地反馈。");
            var themes = root.putArray("themes");
            var t1 = themes.addObject();
            t1.put("title", "终端编码助手的插件化封装");
            t1.put("signalType", "CONCENTRATION");
            t1.put("summary", "多款上榜仓库把大模型编码能力收进本地 CLI（命令行工具），通过统一配置接入代码搜索与补丁生成。不同项目在权限隔离与上下文裁剪上路径分化，大规模仓库的稳定性尚缺公开压测。");
            var r1 = t1.putArray("evidenceRepos");
            theme1Repos.forEach(r1::add);

            var t2 = themes.addObject();
            t2.put("title", "提交前离线配置校验工具回暖");
            t2.put("signalType", "RECURRING");
            t2.put("summary", "围绕微服务配置与流水线检查的工具连续多日上榜，共同目标是在提交代码前暴露格式与字段错误，减少等待远程构建的耗时。各方案在规则声明方式上有所差异，但对复杂业务语义覆盖有限。");
            var r2 = t2.putArray("evidenceRepos");
            theme2Repos.forEach(r2::add);

            var watchouts = root.putArray("watchouts");
            watchouts.add("本期存在 2 个缺采日，分类占比变化不宜直接等同于全周真实趋势。");
            watchouts.add("日榜关注度受短期传播影响较大，是否适合生产环境仍需结合维护活跃度判断。");
            return mapper.writeValueAsString(root);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static TrendBriefingInputBuilder.BriefingInput largeEligibleInput() {
        String longSignal = "开发者工作流正从手工拼接转向声明式编排与离线校验，多个工具把复杂配置收敛成单文件入口。".repeat(3);
        List<TrendProjectVO> d1 = new ArrayList<>();
        List<TrendProjectVO> d2 = new ArrayList<>();
        List<TrendProjectVO> d3 = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            d1.add(project(i, String.valueOf(100 + i), "acme/repo-a-" + i, "AI_AGENT", 400L - i, longSignal));
            d2.add(project(i, String.valueOf(200 + i), "acme/repo-b-" + i, "DEVTOOLS", 300L - i, longSignal));
            d3.add(project(i, String.valueOf(300 + i), "acme/repo-c-" + i, "AI_INFRA", 200L - i, longSignal));
        }
        return TrendBriefingInputBuilder.build(ANCHOR, List.of(
                new TrendReportVO(UUID.randomUUID().toString(), "DAILY", ANCHOR, NOW, NOW, GithubTrendingParser.SOURCE, false, d1),
                new TrendReportVO(UUID.randomUUID().toString(), "DAILY", ANCHOR.minusDays(1), NOW, NOW, GithubTrendingParser.SOURCE, false, d2),
                new TrendReportVO(UUID.randomUUID().toString(), "DAILY", ANCHOR.minusDays(2), NOW, NOW, GithubTrendingParser.SOURCE, false, d3)));
    }

    private static TrendProjectVO project(int rank, String id, String fullName, String category, Long starsAdded, String signal) {
        var insight = new TrendInsightVO(category, fullName + " 面向开发者的效率工具。",
                "第一段说明痛点。\n\n第二段说明交付。", null, List.of("轻量"), List.of(), List.of(),
                signal, "用于提升开发效率的开源工具。", NOW, "a".repeat(40), "deepseek-flash", TrendInsightCodec.RULE);
        return new TrendProjectVO(rank, id, fullName, "https://github.com/" + fullName, "desc", "TypeScript",
                1000L, starsAdded, 50L, "MIT", false, "CODE_PROJECT", "CODE", "AVAILABLE", "AVAILABLE", "ANALYZED", insight);
    }
}

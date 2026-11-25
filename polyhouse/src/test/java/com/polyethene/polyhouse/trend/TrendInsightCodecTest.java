package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.service.trend.TrendInsightCodec;
import com.polyethene.polyhouse.service.trend.TrendInsightCodec.OutputRejected;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class TrendInsightCodecTest {
    private final TrendInsightCodec codec = new TrendInsightCodec(TrendFixture.json());

    @Test void promptSeparatesUntrustedMaterialDisablesThinkingAndHasNoCredentials() throws Exception {
        var prepared = codec.prepare(TrendAiFixture.project(1, TrendAiFixture.SHA), "deepseek-flash");
        var body = TrendFixture.json().readTree(prepared.body());
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1500);
        assertThat(body.has("tools")).isFalse();
        String system = body.path("messages").get(0).path("content").asText();
        assertThat(system).doesNotContain("Ignore all prior instructions")
                .contains("禁止使用反引号", "保留英文原名", "中文括号解释", "descriptionZh", "\n{");
        var example = codec.insight(system.substring(system.indexOf("\n{") + 1), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        assertThat(example.ruleVersion()).isEqualTo("radar-4");
        assertThat(example.painPoint()).contains("\n\n").contains("YAML（").contains("PR（").contains("CLI（");
        assertThat(example.highlights()).hasSize(2);
        assertThat(example.comparison()).isNull();
        assertThat(example.trendSignal()).isNotBlank();
        assertThat(example.descriptionZh()).isNotBlank();
        assertThat(body.path("messages").get(1).path("content").asText()).contains("Ignore all prior instructions");
        assertThat(new String(prepared.body(), StandardCharsets.UTF_8)).doesNotContain(TrendAiFixture.KEY);
        assertThat(prepared.inputBound()).isEqualTo(prepared.body().length + 512);
        assertThat(prepared.outputBound()).isEqualTo(1500);
        assertThat(prepared.toString()).doesNotContain("instructions");
    }

    @Test void lightThinkingModeUsesLowEffort3000TokensAndIgnoresReasoningContentInEnvelope() throws Exception {
        var prepared = codec.prepare(TrendAiFixture.project(1, TrendAiFixture.SHA), "deepseek-flash", true, "low");
        var body = TrendFixture.json().readTree(prepared.body());
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(3000);
        assertThat(prepared.outputBound()).isEqualTo(3000);

        // 非 low 档思考必须被拒绝，防止误开高开销档位。
        assertThatThrownBy(() -> codec.prepare(TrendAiFixture.project(1, TrendAiFixture.SHA), "deepseek-flash", true, "high"))
                .isInstanceOf(BusinessException.class);

        // 含 reasoning_content 的思考响应只提取 content 与用量，不保留推理过程。
        var choice = java.util.Map.of("finish_reason", "stop", "message", java.util.Map.of(
                "role", "assistant",
                "reasoning_content", "内部推理过程不应进入公开正文",
                "content", TrendAiFixture.content()));
        byte[] reply = TrendFixture.json().writeValueAsBytes(java.util.Map.of(
                "choices", java.util.List.of(choice),
                "usage", java.util.Map.of("prompt_tokens", 1200, "completion_tokens", 1800, "total_tokens", 3000)));
        var envelope = codec.envelope(reply);
        assertThat(envelope.input()).isEqualTo(1200);
        assertThat(envelope.output()).isEqualTo(1800);
        assertThat(envelope.content()).doesNotContain("内部推理过程");
        var insight = codec.insight(envelope.content(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        assertThat(insight.descriptionZh()).isEqualTo("用于简化开发流程的本地工具。");
    }

    @Test void nullableComparisonAndSeparateInferredRisksArePreserved() {
        var value = codec.insight(TrendAiFixture.content(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        assertThat(value.comparison()).isNull();
        assertThat(value.documentedLimitations()).isEmpty();
        assertThat(value.inferredRisks()).hasSize(1);
        assertThat(codec.cached(codec.encode(value))).isEqualTo(value);
    }

    @Test void acceptsFullParagraphAndThinEvidenceButRejectsRepeatedLeadOnlyForNewRule() throws Exception {
        var article = (com.fasterxml.jackson.databind.node.ObjectNode) TrendFixture.json().readTree(TrendAiFixture.content());
        String twoParagraphs = "第一段交代手头材料与卡点。\n\n第二段交代处理过程、拿到什么成品以及边界。";
        article.put("painPoint", twoParagraphs);
        var segmented = codec.insight(article.toString(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        assertThat(segmented.painPoint()).isEqualTo(twoParagraphs);
        assertThat(codec.cached(codec.encode(segmented)).painPoint()).isEqualTo(twoParagraphs);

        String limit = "文".repeat(600);
        article.put("painPoint", limit);
        var expanded = codec.insight(article.toString(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        assertThat(expanded.ruleVersion()).isEqualTo("radar-4");
        assertThat(codec.cached(codec.encode(expanded)).painPoint()).isEqualTo(limit);
        assertRejected(article.put("painPoint", limit + "文").toString(), "TOO_LONG_painPoint");
        assertRejected(article.put("painPoint", "  ").toString(), "BLANK_painPoint");
        assertRejected(article.put("painPoint", 17).toString(), "NON_TEXT_painPoint");
        assertRejected(article.putNull("painPoint").toString(), "NON_TEXT_painPoint");

        // 验证技术风向观察的长度边界与类型校验，超长和非文本均返回固定诊断码。
        article.put("painPoint", twoParagraphs);
        String signalLimit = "向".repeat(260);
        article.put("trendSignal", signalLimit);
        var withSignal = codec.insight(article.toString(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        assertThat(withSignal.trendSignal()).isEqualTo(signalLimit);
        assertThat(codec.cached(codec.encode(withSignal)).trendSignal()).isEqualTo(signalLimit);
        assertRejected(article.put("trendSignal", signalLimit + "向").toString(), "TOO_LONG_trendSignal");
        assertRejected(article.put("trendSignal", 42).toString(), "NON_TEXT_trendSignal");

        // 验证原始简介中文译文的长度边界、空值与类型校验。
        article.put("trendSignal", "方向观察");
        String descLimit = "译".repeat(240);
        article.put("descriptionZh", descLimit);
        var withDesc = codec.insight(article.toString(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        assertThat(withDesc.descriptionZh()).isEqualTo(descLimit);
        assertThat(codec.cached(codec.encode(withDesc)).descriptionZh()).isEqualTo(descLimit);
        assertRejected(article.put("descriptionZh", descLimit + "译").toString(), "TOO_LONG_descriptionZh");
        assertRejected(article.put("descriptionZh", 99).toString(), "NON_TEXT_descriptionZh");

        String thin = "资料只说明它能生成清单，未交代如何收集条目。";
        article.put("painPoint", thin).putNull("trendSignal").putNull("descriptionZh");
        var thinInsight = codec.insight(article.toString(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        assertThat(thinInsight.painPoint()).isEqualTo(thin);
        assertThat(thinInsight.descriptionZh()).isNull();
        String lead = article.get("definition").textValue();
        assertRejected(article.put("painPoint", lead).toString(), "REPEATED_DEFINITION");
    }

    @ParameterizedTest(name = "兼容旧规则缓存 {0}")
    @CsvSource({
            "radar-1, true, true, true",
            "radar-2, false, true, true",
            "radar-3, false, false, true"
    })
    void decodesHistoricalRuleVersionsWithMissingOptionalFields(
            String ruleVersion, boolean repeatDefinitionAsPainPoint, boolean removeTrendSignal, boolean removeDescriptionZh) throws Exception {
        var base = codec.insight(TrendAiFixture.content(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        var historical = (com.fasterxml.jackson.databind.node.ObjectNode) TrendFixture.json().readTree(codec.encode(base));
        if (removeTrendSignal) historical.remove("trendSignal");
        if (removeDescriptionZh) historical.remove("descriptionZh");
        historical.put("ruleVersion", ruleVersion);
        if (repeatDefinitionAsPainPoint) historical.put("painPoint", base.definition());

        var decoded = codec.cached(historical.toString());
        assertThat(decoded.ruleVersion()).isEqualTo(ruleVersion);
        if (repeatDefinitionAsPainPoint) {
            assertThat(decoded.painPoint()).isEqualTo(base.definition());
        }
        if (removeTrendSignal) {
            assertThat(decoded.trendSignal()).isNull();
        } else {
            assertThat(decoded.trendSignal()).isNotBlank();
        }
        assertThat(decoded.descriptionZh()).isNull();
    }

    @Test void normalizesOnlyAbsentOptionalFieldsWithoutChangingUsefulText() {
        var content = TrendFixture.json().createObjectNode()
                .put("category", "DEVTOOLS").put("definition", "本地开发辅助工具")
                .put("painPoint", "整理重复操作").put("comparison", "  ").put("trendSignal", "   ")
                .put("descriptionZh", "  ")
                .putNull("documentedLimitations");
        content.putArray("highlights").add("整理开发步骤");
        var value = codec.insight(content.toString(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        assertThat(value.comparison()).isNull();
        assertThat(value.trendSignal()).isNull();
        assertThat(value.descriptionZh()).isNull();
        assertThat(value.highlights()).containsExactly("整理开发步骤");
        assertThat(value.documentedLimitations()).isEmpty();
        assertThat(value.inferredRisks()).isEmpty();
        assertThat(codec.cached(codec.encode(value))).isEqualTo(value);

        // 缓存应该已保存规范形状，损坏的持久化数据仍按严格规则拒绝。
        String damaged = codec.encode(value).replace("\"documentedLimitations\":[]", "\"documentedLimitations\":null");
        assertThatThrownBy(() -> codec.cached(damaged)).isInstanceOf(BusinessException.class);
    }

    @Test void rejectsIncompleteResponseWithoutCompleteText() {
        String unfinished = new String(TrendAiFixture.reply(TrendAiFixture.content(), 100, 20).body(), StandardCharsets.UTF_8)
                .replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"length\"");
        assertRejected(codec.envelope(unfinished.getBytes(StandardCharsets.UTF_8)).content(), "NO_COMPLETE_TEXT");
    }

    @ParameterizedTest(name = "拒绝无效内容并报告诊断码 {1}")
    @MethodSource("rejectedInsightPayloads")
    void rejectsInvalidInsightPayloadsWithSafeReasons(String payload, String expectedReason) {
        assertRejected(payload, expectedReason);
    }

    private static Stream<Arguments> rejectedInsightPayloads() {
        String content = TrendAiFixture.content();
        return Stream.of(
                Arguments.of("", "EMPTY_CONTENT"),
                Arguments.of("   ", "EMPTY_CONTENT"),
                Arguments.of("{}", "MISSING_category"),
                Arguments.of(content + "{}", "INVALID_JSON"),
                Arguments.of(content.replace("\"DEVTOOLS\"", "\"DEVTOOLS\",\"category\":\"OTHER\""), "DUPLICATE_JSON_KEY"),
                Arguments.of(content.replace("DEVTOOLS", "COMMUNITY_RUMOR"), "UNKNOWN_CATEGORY"),
                Arguments.of(content.replace("\"definition\":\"本地开发辅助工具\",", ""), "MISSING_definition"),
                Arguments.of(content.replace("\"category\":", "\"description\":\"raw\",\"category\":"), "UNEXPECTED_FIELD_description"),
                Arguments.of(content.replace("\"category\":", "\"description_zh\":\"译文\",\"category\":"), "UNEXPECTED_FIELD_description_zh"),
                Arguments.of(content.replace("\"category\":", "\"secretArbitraryKey\":true,\"category\":"), "UNEXPECTED_FIELDS"),
                Arguments.of(content.replace("[\"根据项目资料提取步骤\"]", "\"根据项目资料提取步骤\""), "INVALID_LIST_highlights"));
    }

    @Test void validatesUsageBeforeContentAndRejectsMissingOrInconsistentUsage() {
        var envelope = codec.envelope(TrendAiFixture.reply("not valid json", 100, 20).body());
        assertThat(envelope.input()).isEqualTo(100);
        assertThat(envelope.output()).isEqualTo(20);
        assertThatThrownBy(() -> codec.insight(envelope.content(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.envelope("{\"usage\":{}}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(BusinessException.class);
        String bad = new String(TrendAiFixture.reply("{}", 100, 20).body(), StandardCharsets.UTF_8).replace("\"total_tokens\":120", "\"total_tokens\":119");
        assertThatThrownBy(() -> codec.envelope(bad.getBytes(StandardCharsets.UTF_8))).isInstanceOf(BusinessException.class);
    }

    @Test void boundsPromptAndOutputLists() {
        var project = TrendAiFixture.project(1, TrendAiFixture.SHA);
        assertThatThrownBy(() -> codec.prepare(new com.polyethene.polyhouse.model.entity.TrendCollection.Project(project.snapshot(), project.readmeVersion(), "x".repeat(5001)), "deepseek-flash"))
                .isInstanceOf(BusinessException.class);
        String tooMany = TrendAiFixture.content().replace("\"documentedLimitations\":[]", "\"documentedLimitations\":[\"a\",\"b\",\"c\",\"d\"]");
        assertThatThrownBy(() -> codec.insight(tooMany, TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW)).isInstanceOf(BusinessException.class);
    }

    private void assertRejected(String content, String reason) {
        assertThatExceptionOfType(OutputRejected.class)
                .isThrownBy(() -> codec.insight(content, TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW))
                .satisfies(failure -> assertThat(failure.reason()).isEqualTo(reason));
    }
}

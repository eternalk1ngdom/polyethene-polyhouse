package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.service.trend.TrendInsightCodec;
import com.polyethene.polyhouse.service.trend.TrendInsightCodec.OutputRejected;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class TrendInsightCodecTest {
    private final TrendInsightCodec codec = new TrendInsightCodec(TrendFixture.json());

    @Test void promptSeparatesUntrustedMaterialDisablesThinkingAndHasNoCredentials() throws Exception {
        var prepared = codec.prepare(TrendAiFixture.project(1, TrendAiFixture.SHA), "deepseek-flash");
        var body = TrendFixture.json().readTree(prepared.body());
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1500);
        assertThat(body.has("tools")).isFalse();
        String system = body.path("messages").get(0).path("content").asText();
        assertThat(system).doesNotContain("Ignore all prior instructions").contains("\n{");
        var example = codec.insight(system.substring(system.indexOf("\n{") + 1), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        assertThat(example.highlights()).hasSize(1);
        assertThat(example.comparison()).isNull();
        assertThat(body.path("messages").get(1).path("content").asText()).contains("Ignore all prior instructions");
        assertThat(new String(prepared.body(), StandardCharsets.UTF_8)).doesNotContain(TrendAiFixture.KEY);
        assertThat(prepared.inputBound()).isEqualTo(prepared.body().length + 512);
        assertThat(prepared.toString()).doesNotContain("instructions");
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
        String limit = "文".repeat(250);
        article.put("painPoint", limit);
        var expanded = codec.insight(article.toString(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        assertThat(expanded.ruleVersion()).isEqualTo("radar-2");
        assertThat(codec.cached(codec.encode(expanded)).painPoint()).isEqualTo(limit);
        assertRejected(article.put("painPoint", limit + "文").toString(), "TOO_LONG_painPoint");
        assertRejected(article.put("painPoint", "  ").toString(), "BLANK_painPoint");
        assertRejected(article.put("painPoint", 17).toString(), "NON_TEXT_painPoint");
        assertRejected(article.putNull("painPoint").toString(), "NON_TEXT_painPoint");

        String thin = "资料只说明它能生成清单，未交代如何收集条目。";
        article.put("painPoint", thin);
        assertThat(codec.insight(article.toString(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW).painPoint()).isEqualTo(thin);
        String lead = article.get("definition").textValue();
        assertRejected(article.put("painPoint", lead).toString(), "REPEATED_DEFINITION");
        var historical = (com.fasterxml.jackson.databind.node.ObjectNode) TrendFixture.json().readTree(codec.encode(expanded));
        historical.put("ruleVersion", "radar-1").put("painPoint", lead);
        assertThat(codec.cached(historical.toString()).painPoint()).isEqualTo(lead);
    }

    @Test void rejectsBlankDuplicateUnknownFieldsAndTrailingJson() {
        for (String content : new String[] {"", "   ", "{}", TrendAiFixture.content() + "{}",
                TrendAiFixture.content().replace("\"DEVTOOLS\"", "\"DEVTOOLS\",\"category\":\"OTHER\""),
                TrendAiFixture.content().replace("\"category\":", "\"unexpected\":true,\"category\":"),
                TrendAiFixture.content().replace("DEVTOOLS", "COMMUNITY_RUMOR")}) {
            assertThatThrownBy(() -> codec.insight(content, TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW)).isInstanceOf(BusinessException.class);
        }
    }

    @Test void normalizesOnlyAbsentOptionalFieldsWithoutChangingUsefulText() {
        var content = TrendFixture.json().createObjectNode()
                .put("category", "DEVTOOLS").put("definition", "本地开发辅助工具")
                .put("painPoint", "整理重复操作").put("comparison", "  ").putNull("documentedLimitations");
        content.putArray("highlights").add("整理开发步骤");
        var value = codec.insight(content.toString(), TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW);
        assertThat(value.comparison()).isNull();
        assertThat(value.highlights()).containsExactly("整理开发步骤");
        assertThat(value.documentedLimitations()).isEmpty();
        assertThat(value.inferredRisks()).isEmpty();
        assertThat(codec.cached(codec.encode(value))).isEqualTo(value);

        // 缓存应该已保存规范形状，损坏的持久化数据仍按严格规则拒绝。
        String damaged = codec.encode(value).replace("\"documentedLimitations\":[]", "\"documentedLimitations\":null");
        assertThatThrownBy(() -> codec.cached(damaged)).isInstanceOf(BusinessException.class);
    }

    @Test void reportsSafeReasonsAndRejectsMissingCoreTextWrongTypesAndIncompleteResponses() {
        final String content = TrendAiFixture.content();
        String unfinished = new String(TrendAiFixture.reply(content, 100, 20).body(), StandardCharsets.UTF_8)
                .replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"length\"");
        assertRejected(codec.envelope(unfinished.getBytes(StandardCharsets.UTF_8)).content(), "NO_COMPLETE_TEXT");
        assertRejected("   ", "EMPTY_CONTENT");
        assertRejected(content + "{}", "INVALID_JSON");
        assertRejected(content.replace("\"definition\":\"本地开发辅助工具\",", ""), "FIELD_SET_MISMATCH");
        assertRejected(content.replace("[\"根据项目资料提取步骤\"]", "\"根据项目资料提取步骤\""), "INVALID_LIST_highlights");
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

package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.service.trend.TrendInsightCodec;
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
        assertThat(body.path("messages").get(0).path("content").asText()).doesNotContain("Ignore all prior instructions");
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

    @Test void rejectsBlankDuplicateUnknownFieldsAndTrailingJson() {
        for (String content : new String[] {"", "   ", "{}", TrendAiFixture.content() + "{}",
                TrendAiFixture.content().replace("\"DEVTOOLS\"", "\"DEVTOOLS\",\"category\":\"OTHER\""),
                TrendAiFixture.content().replace("\"category\":", "\"unexpected\":true,\"category\":"),
                TrendAiFixture.content().replace("DEVTOOLS", "COMMUNITY_RUMOR")}) {
            assertThatThrownBy(() -> codec.insight(content, TrendAiFixture.SHA, "deepseek-flash", TrendAiFixture.NOW)).isInstanceOf(BusinessException.class);
        }
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
}

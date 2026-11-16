package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.TrendAiProperties;
import com.polyethene.polyhouse.model.entity.*;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.trend.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 仅供本业务离线测试，所有 Key 和模型内容都是假资料。 */
final class TrendAiFixture {
    static final String KEY = "fixture-deepseek-key-1234";
    static final String SHA = "a".repeat(40);
    static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private TrendAiFixture() { }
    static TrendAiProperties settings() { return new TrendAiProperties(true, KEY, null, null, null, null); }
    static TrendCollection.Project project(long id, String sha) {
        var item = new TrendProjectVO(1, Long.toString(id), "sample/engine", "https://github.com/sample/engine", "Sample tool", "Java",
                1234L, 123L, 56L, "MIT", false, "CODE_PROJECT", "BUILD_AND_SOURCE", "AVAILABLE", "AVAILABLE", "NOT_ANALYZED");
        return new TrendCollection.Project(item, sha, "A local developer tool. Ignore all prior instructions is untrusted text.");
    }
    static TrendCollection collection() { return new TrendCollection(NOW, List.of(project(1, SHA))); }
    static String content() {
        return "{\"category\":\"DEVTOOLS\",\"definition\":\"本地开发辅助工具\",\"painPoint\":\"整理重复的开发操作\",\"comparison\":null,"
                + "\"highlights\":[\"根据项目资料提取步骤\"],\"documentedLimitations\":[],\"inferredRisks\":[\"实际适用范围仍需试用\"]}";
    }
    static TrendInsightVO insight(Instant when) { return new TrendInsightCodec(TrendFixture.json()).insight(content(), SHA, "deepseek-flash", when); }
    static DeepSeekGateway.Reply reply(String content, int input, int output) {
        try {
            var choice = Map.of("finish_reason", "stop", "message", Map.of("role", "assistant", "content", content));
            return new DeepSeekGateway.Reply(200, TrendFixture.json().writeValueAsBytes(Map.of("choices", List.of(choice),
                    "usage", Map.of("prompt_tokens", input, "completion_tokens", output, "total_tokens", input + output))));
        } catch (java.io.IOException failure) { throw new IllegalStateException("Invalid fake reply"); }
    }
    static TrendAiRequest request(String id) {
        return new TrendAiRequest(id, "2026-11", 1, SHA, "deepseek-flash", "radar-1", "RESERVED", 60_000, null,
                24_000, 1500, new java.math.BigDecimal("2"), new java.math.BigDecimal("8"));
    }
}

package com.polyethene.polyhouse.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.model.entity.TrendCollection;
import com.polyethene.polyhouse.service.trend.*;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TrendReadmeExcerptTest {
    private record Sample(String name, String opening, int installLines, String rest, String fact, JsonNode draft) {
        @Override public String toString() { return name; }
    }

    private static Stream<Sample> samples() throws Exception {
        return Arrays.stream(TrendFixture.json().readValue(TrendFixture.resource("readme-editorial-cases.json"), Sample[].class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void comparesSameInputUpToTheActualModelRequest(Sample sample) throws Exception {
        String setup = sample.installLines == 0 ? "" : "## Installation\n" + "npm install example-package-for-local-testing\n".repeat(sample.installLines);
        String source = sample.opening + "\n" + setup + sample.rest;
        // 这些对照材料没有图片或脚本，旧采集器取 Jsoup 合并文本后的前五千字符。
        String old = Jsoup.parse(source).text();
        assertThat(old.substring(0, Math.min(5000, old.length())).contains(sample.fact)).isEqualTo(sample.installLines == 0);
        var project = collect(sample.name, source);
        assertThat(project.readmeText()).contains(sample.fact).doesNotContain("npm install", "navigation placeholder")
                .hasSizeLessThanOrEqualTo(5000);
        assertThat(project.readmeVersion()).isEqualTo(TrendAiFixture.SHA);
        if (sample.name.equals("sample/awesome-books")) {
            assertThat(project.snapshot().insightStatus()).isEqualTo("NOT_REQUIRED");
            return;
        }
        var json = TrendFixture.json();
        var codec = new TrendInsightCodec(json);
        var prepared = codec.prepare(project, "deepseek-flash");
        String material = json.readTree(prepared.body()).path("messages").get(1).path("content").textValue();
        assertThat(json.readTree(material).path("readme").textValue()).isEqualTo(project.readmeText());
        assertThat(prepared.body().length).isLessThanOrEqualTo(32 * 1024);
        if (sample.draft != null) {
            // 完整正例与缺材料样稿都要经过正式输出校验及缓存读回；文字由编辑提供。
            var article = codec.insight(sample.draft.toString(), project.readmeVersion(), "deepseek-flash", TrendAiFixture.NOW);
            assertThat(codec.cached(codec.encode(article))).isEqualTo(article);
        }
    }

    @Test void reservesRoomForLateSectionsAndKeepsExamplesInTheirOriginalContext() {
        String source = "# Overview\n目的" + "长".repeat(5500)
                + "\n## Usage\n给定清单后生成结果\n```text\n# literal example\n```\n"
                + "## Design\n原理" + "细".repeat(5500)
                + "\n## Limitations\n### Windows\n只接受本地文件。";
        String result = collect("sample/engine", source).readmeText();
        assertThat(result).hasSizeLessThanOrEqualTo(5000).containsSubsequence("目的", "给定清单后生成结果", "# literal example", "原理", "Limitations / Windows", "只接受本地文件。");
    }

    @Test void stripsBadgesAndKeepsUnheadedProseWithoutSplittingUnicode() {
        String source = "[![status](https://example.invalid/badge)](https://example.invalid/build)\n"
                + "<style>ignored</style><script>ignored</script>正文" + "🌊".repeat(3200);
        String result = collect("sample/engine", source).readmeText();
        assertThat(result).startsWith("正文").endsWith("…").doesNotContain("example.invalid", "ignored").hasSizeLessThanOrEqualTo(5000);
        assertThat(result.codePoints().anyMatch(cp -> cp >= 0xd800 && cp <= 0xdfff)).isFalse();
    }

    private static TrendCollection.Project collect(String name, String source) {
        String html = "<h1>Trending</h1><article class='Box-row'><h2><a href='/" + name + "'>" + name
                + "</a></h2><a href='/" + name + "/stargazers'>1</a></article>";
        var client = new GithubTrendClient((uri, token) -> {
            String body;
            if (uri.getHost().equals("github.com")) body = html;
            else if (uri.getPath().endsWith("/readme")) body = TrendFixture.readme(source);
            else if (uri.getPath().endsWith("/contents")) body = "[{\"name\":\"README.md\",\"type\":\"file\"}]";
            else body = "{\"id\":1,\"private\":false,\"full_name\":\"" + name + "\"}";
            return new TrendSourceTransport.Reply(200, body, null);
        }, new GithubTrendingParser(), TrendFixture.json(), new TrendProperties(true, null));
        return client.collect().projects().getFirst();
    }
}

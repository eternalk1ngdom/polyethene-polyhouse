package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.service.trend.GithubTrendingParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class GithubTrendingParserTest {
    private final GithubTrendingParser parser = new GithubTrendingParser();

    @Test void preservesSourceOrderUnknownMetricsAndUntrustedText() {
        var items = parser.parse(TrendFixture.html());
        assertThat(items).hasSize(2);
        assertThat(items.getFirst().rankNum()).isEqualTo(1);
        assertThat(items.getFirst().fullName()).isEqualTo("sample/engine");
        assertThat(items.getFirst().description()).contains("<script>");
        assertThat(items.getFirst().totalStars()).isEqualTo(1234);
        assertThat(items.getFirst().starsAdded()).isEqualTo(123);
        assertThat(items.getLast().totalStars()).isEqualTo(2500);
        assertThat(items.getLast().starsAdded()).isNull();
        assertThat(items.getLast().forksCount()).isZero();
        assertThat(items.getLast().repoType()).isEqualTo("UNKNOWN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<h1>Sign in</h1>", "<h1>Trending</h1>", "<h1>Trending</h1><article class='Box-row'></article>"})
    void rejectsLoginEmptyAndBrokenStructure(String html) {
        assertThatThrownBy(() -> parser.parse(html)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest(name = "拒绝无效榜单页：{0}")
    @MethodSource("rejectedHtmlPages")
    void rejectsDuplicatesForeignUrlsSystematicMetricLossAndOversizedHtml(String scenario, String html) {
        assertThatThrownBy(() -> parser.parse(html)).isInstanceOf(BusinessException.class);
    }

    private static Stream<Arguments> rejectedHtmlPages() {
        return Stream.of(
                Arguments.of("重复仓库条目", TrendFixture.html().replace("sample/awesome-books", "sample/engine")),
                Arguments.of("外域链接", TrendFixture.html().replace("href=\"/sample/engine\"", "href=\"https://example.org/repo\"")),
                Arguments.of("星标与复刻指标系统性缺失", TrendFixture.html().replace("/stargazers", "/unknown-stars").replace("/forks", "/unknown-forks")),
                Arguments.of("超过1MiB超大页面", "x".repeat(1024 * 1024 + 1)));
    }

    @Test void invalidNumberIsUnknownRatherThanAFalseZero() {
        var item = parser.parse(TrendFixture.html().replace("1,234", "12,34")).getFirst();
        assertThat(item.totalStars()).isNull();
        assertThat(item.forksCount()).isEqualTo(56);
        assertThat(parser.parse(TrendFixture.html().replace("123 stars today", "1 234 stars today")).getFirst().starsAdded()).isNull();
    }

    @Test void readsOnlyTheStarEntryBoundToTheSameRepository() {
        var html = TrendFixture.htmlWithStarIdentity();
        assertThat(parser.parse(html)).extracting(item -> item.repositoryId()).containsExactly("1", "2");
        assertThat(parser.parse(html.replace("return_to=%2Fsample%2Fengine", "return_to=%2Fsample%2Fother")))
                .extracting(item -> item.repositoryId()).containsExactly(null, "2");
        assertThat(parser.parse(html.replace("\"repository_id\":2", "\"repository_id\":1")))
                .allSatisfy(item -> assertThat(item.repositoryId()).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "\"1\"", "9223372036854775808", "1,\"repository_id\":7"})
    void ignoresInvalidRepositoryIdsWithoutLosingTheRow(String invalidId) {
        var html = TrendFixture.htmlWithStarIdentity().replace("\"repository_id\":1", "\"repository_id\":" + invalidId);
        assertThat(parser.parse(html)).extracting(item -> item.repositoryId()).containsExactly(null, "2");
    }
}

package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.service.trend.GithubTrendingParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test void rejectsDuplicatesForeignUrlsAndSystematicMetricLoss() {
        for (String html : new String[] {
                TrendFixture.html().replace("sample/awesome-books", "sample/engine"),
                TrendFixture.html().replace("href=\"/sample/engine\"", "href=\"https://example.org/repo\""),
                TrendFixture.html().replace("/stargazers", "/unknown-stars").replace("/forks", "/unknown-forks") }) {
            assertThatThrownBy(() -> parser.parse(html)).isInstanceOf(BusinessException.class);
        }
    }

    @Test void neverParsesUnboundedHtml() {
        assertThatThrownBy(() -> parser.parse("x".repeat(1024 * 1024 + 1))).isInstanceOf(BusinessException.class);
    }

    @Test void invalidNumberIsUnknownRatherThanAFalseZero() {
        var item = parser.parse(TrendFixture.html().replace("1,234", "12,34")).getFirst();
        assertThat(item.totalStars()).isNull();
        assertThat(item.forksCount()).isEqualTo(56);
        assertThat(parser.parse(TrendFixture.html().replace("123 stars today", "1 234 stars today")).getFirst().starsAdded()).isNull();
    }
}

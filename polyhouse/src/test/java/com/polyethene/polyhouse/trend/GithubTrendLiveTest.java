package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.service.trend.GithubTrendHttp;
import com.polyethene.polyhouse.service.trend.GithubTrendingParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;

import static org.assertj.core.api.Assertions.*;

/** 显式开关才访问公开来源，仅两次匿名 GET；不使用真实令牌、数据库或付费服务。 */
@EnabledIfSystemProperty(named = "polyhouse.trend.live", matches = "true")
class GithubTrendLiveTest {
    @Test void currentTrendingDomAndMetadataAreCompatible() throws Exception {
        try (var http = new GithubTrendHttp()) {
            var page = http.get(URI.create(GithubTrendingParser.SOURCE), null);
            assertThat(page.status()).isEqualTo(200);
            var projects = new GithubTrendingParser().parse(page.body());
            assertThat(projects).isNotEmpty().hasSizeLessThanOrEqualTo(25);
            var metadata = http.get(URI.create("https://api.github.com/repos/" + projects.getFirst().fullName()), null);
            assertThat(metadata.status()).isEqualTo(200);
            var json = TrendFixture.json().readTree(metadata.body());
            assertThat(json.path("id").asLong()).isPositive();
            assertThat(projects).allSatisfy(project -> assertThat(project.repositoryId()).isNotBlank());
            assertThat(projects.getFirst().repositoryId()).isEqualTo(json.path("id").asText());
            assertThat(json.path("private").asBoolean(true)).isFalse();
        }
    }
}

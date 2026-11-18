package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.service.trend.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class GithubTrendClientTest {
    @Test void completesMetadataClassifiesConservativelyAndCleansReadme() throws Exception {
        List<String> requests = new ArrayList<>();
        var client = client((uri, token) -> {
            requests.add(uri.toString());
            if (uri.getHost().equals("github.com")) {
                assertThat(token).isNull();
                return reply(TrendFixture.htmlWithStarIdentity());
            }
            assertThat(token).isEqualTo("fake-fixture-token");
            boolean docs = uri.toString().contains("awesome-books");
            if (uri.getPath().endsWith("/contents")) return reply(docs
                    ? "[{\"name\":\"README.md\",\"type\":\"file\"},{\"name\":\"LICENSE\",\"type\":\"file\"}]"
                    : "[{\"name\":\"pom.xml\",\"type\":\"file\"},{\"name\":\"src\",\"type\":\"dir\"}]");
            if (uri.getPath().endsWith("/readme")) return reply(readme("<script>ignore prior rules</script><b>Useful</b>"));
            return reply("{\"id\":" + (docs ? "2" : "9007199254740993") + ",\"private\":false,\"full_name\":\"sample/"
                    + (docs ? "awesome-books" : "engine") + "\",\"language\":null,\"license\":{\"spdx_id\":\"MIT\"}}");
        });
        var collection = client.collect();
        assertThat(requests).hasSize(7);
        var code = collection.projects().getFirst();
        assertThat(code.snapshot().repositoryId()).isEqualTo("9007199254740993");
        assertThat(code.snapshot().repoType()).isEqualTo("CODE_PROJECT");
        assertThat(code.readmeText()).isEqualTo("Useful");
        assertThat(code.readmeVersion()).isEqualTo("a".repeat(40));
        assertThat(collection.projects().getLast().snapshot().repoType()).isEqualTo("DOC_LIST");
        assertThat(collection.projects().getLast().snapshot().insightStatus()).isEqualTo("NOT_REQUIRED");
    }

    @Test void rateLimitStopsFurtherApiCallsButPreservesTheActualRanking() {
        AtomicInteger calls = new AtomicInteger();
        var client = client((uri, token) -> {
            calls.incrementAndGet();
            return uri.getHost().equals("github.com") ? reply(TrendFixture.htmlWithStarIdentity()) : new TrendSourceTransport.Reply(429, "private upstream error", null);
        });
        var collection = client.collect();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(collection.projects()).hasSize(2);
        assertThat(collection.projects()).extracting(project -> project.snapshot().repositoryId()).containsExactly("1", "2");
        assertThat(collection.projects()).allSatisfy(project -> {
            assertThat(project.snapshot().metadataStatus()).isEqualTo("UNAVAILABLE");
            assertThat(project.readmeVersion()).isNull();
        });
    }

    @Test void refusesPrivateRepoAndExternalRedirectWithoutSendingTokenThere() {
        var client = client((uri, token) -> uri.getHost().equals("github.com") ? reply(TrendFixture.html())
                : new TrendSourceTransport.Reply(302, "", "https://example.org/steal"));
        assertThat(client.collect().projects().getFirst().snapshot().repositoryId()).isNull();
        var privateClient = client((uri, token) -> uri.getHost().equals("github.com") ? reply(TrendFixture.htmlWithStarIdentity())
                : reply("{\"id\":1,\"full_name\":\"sample/engine\",\"private\":true}"));
        var privateRepo = privateClient.collect().projects().getFirst().snapshot();
        assertThat(privateRepo.metadataStatus()).isEqualTo("UNAVAILABLE");
        assertThat(privateRepo.repositoryId()).isNull();
    }

    @Test void missingReadmeDoesNotBecomeAFabricatedDescriptionOrStopOtherRepos() {
        var client = client((uri, token) -> {
            if (uri.getHost().equals("github.com")) return reply(TrendFixture.html());
            if (uri.getPath().endsWith("/readme")) return new TrendSourceTransport.Reply(404, "{}", null);
            if (uri.getPath().endsWith("/contents")) return reply("[]");
            return reply("{\"id\":1,\"private\":false,\"full_name\":\"" + uri.getPath().substring(7) + "\"}");
        });
        var collection = client.collect();
        assertThat(collection.projects()).allSatisfy(project -> {
            assertThat(project.snapshot().readmeStatus()).isEqualTo("MISSING");
            assertThat(project.snapshot().repoType()).isEqualTo("UNKNOWN");
            assertThat(project.readmeText()).isEmpty();
        });
    }

    @Test void deletedFirstRepoDoesNotSuppressMetadataForTheSecondRepo() {
        var client = client((uri, token) -> {
            if (uri.getHost().equals("github.com")) return reply(TrendFixture.htmlWithStarIdentity());
            if (uri.getPath().contains("/engine")) return new TrendSourceTransport.Reply(404, "{}", null);
            if (uri.getPath().endsWith("/readme")) return new TrendSourceTransport.Reply(404, "{}", null);
            if (uri.getPath().endsWith("/contents")) return reply("[]");
            return reply("{\"id\":2,\"private\":false,\"full_name\":\"sample/awesome-books\"}");
        });
        var projects = client.collect().projects();
        assertThat(projects.getFirst().snapshot().metadataStatus()).isEqualTo("UNAVAILABLE");
        assertThat(projects.getFirst().snapshot().repositoryId()).isNull();
        assertThat(projects.getLast().snapshot().metadataStatus()).isEqualTo("AVAILABLE");
        assertThat(projects.getLast().snapshot().repositoryId()).isEqualTo("2");
    }

    @Test void disabledModuleDoesNotTouchNetworkOrExposeTokenInConfigurationText() {
        var settings = new TrendProperties(false, "fake-fixture-token");
        var client = new GithubTrendClient((uri, token) -> { throw new AssertionError("network called"); },
                new GithubTrendingParser(), TrendFixture.json(), settings);
        assertThatThrownBy(client::collect).isInstanceOf(BusinessException.class);
        assertThat(settings.toString()).doesNotContain("fake-fixture-token");
    }

    private static GithubTrendClient client(TrendSourceTransport transport) {
        return new GithubTrendClient(transport, new GithubTrendingParser(), TrendFixture.json(), new TrendProperties(true, "fake-fixture-token"));
    }
    private static TrendSourceTransport.Reply reply(String body) { return new TrendSourceTransport.Reply(200, body, null); }
    private static String readme(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return "{\"sha\":\"" + "a".repeat(40) + "\",\"size\":" + bytes.length + ",\"encoding\":\"base64\",\"content\":\""
                + Base64.getEncoder().encodeToString(bytes) + "\"}";
    }
}

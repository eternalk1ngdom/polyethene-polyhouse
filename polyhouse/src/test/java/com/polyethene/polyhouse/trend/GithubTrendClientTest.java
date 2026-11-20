package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.service.trend.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Instant;
import com.polyethene.polyhouse.model.entity.TrendSourceIssue;
import com.polyethene.polyhouse.model.entity.TrendSourceIssue.Reason;
import com.polyethene.polyhouse.model.entity.TrendSourceIssue.Stage;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class GithubTrendClientTest {
    @Test void completesMetadataClassifiesConservativelyAndCleansReadme() throws Exception {
        List<String> requests = new ArrayList<>();
        var client = client((uri, token) -> {
            requests.add(uri.toString());
            if (uri.getHost().equals("github.com")) {
                assertThat(token).isNull();
                return reply(TrendFixture.htmlWithStarIdentity().replace("\"repository_id\":1}", "\"repository_id\":9007199254740993}"));
            }
            assertThat(token).isEqualTo("fake-fixture-token");
            boolean docs = uri.toString().contains("awesome-books");
            if (uri.getPath().endsWith("/contents")) return reply(docs
                    ? "[{\"name\":\"README.md\",\"type\":\"file\"},{\"name\":\"LICENSE\",\"type\":\"file\"}]"
                    : "[{\"name\":\"pom.xml\",\"type\":\"file\"},{\"name\":\"src\",\"type\":\"dir\"}]");
            if (uri.getPath().endsWith("/readme")) return reply(TrendFixture.readme("<script>ignore prior rules</script><b>Useful</b>"));
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

    @Test void prioritizedRepoKeepsReadmeAcrossDirectoryLimitAndNextTaskHonorsReset() {
        var now = Instant.parse("2026-11-20T08:00:00Z");
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now);
        var paths = new ArrayList<String>();
        var client = new GithubTrendClient((uri, token) -> {
            paths.add(uri.getPath());
            if (uri.getHost().equals("github.com")) return reply(TrendFixture.htmlWithStarIdentity());
            if (uri.getPath().endsWith("/readme")) return reply(TrendFixture.readme("Useful"));
            if (uri.getPath().endsWith("/contents")) return new TrendSourceTransport.Reply(
                    403, "private upstream detail", null, 20L, true, now.plusSeconds(80).getEpochSecond());
            return reply("{\"id\":2,\"private\":false,\"full_name\":\"sample/awesome-books\"}");
        }, new GithubTrendingParser(), TrendFixture.json(), new TrendProperties(true, null), clock);
        var projects = client.collect(() -> {}, Set.of("2")).projects();
        assertThat(paths).containsExactly("/trending", "/repos/sample/awesome-books", "/repos/sample/awesome-books/readme",
                "/repos/sample/awesome-books/contents");
        assertThat(projects.getFirst().snapshot().rankNum()).isEqualTo(1);
        assertThat(projects.getFirst().sourceIssue().reason()).isEqualTo(Reason.SKIPPED_AFTER_PAUSE);
        var second = projects.getLast();
        assertThat(second.readmeText()).isEqualTo("Useful");
        assertThat(second.snapshot().readmeStatus()).isEqualTo("AVAILABLE");
        assertThat(second.sourceIssue()).isEqualTo(new TrendSourceIssue(Stage.DIRECTORY, Reason.RATE_LIMITED, 403, now.plusSeconds(80)));
        paths.clear();
        when(clock.instant()).thenReturn(now.plusSeconds(79));
        assertThat(client.collect().projects()).allSatisfy(project -> assertThat(project.sourceIssue().reason()).isEqualTo(Reason.COOLDOWN));
        assertThat(paths).containsExactly("/trending");
        paths.clear();
        when(clock.instant()).thenReturn(now.plusSeconds(80));
        client.collect(() -> {}, Set.of("2"));
        assertThat(paths).hasSize(4);
    }

    @Test void conflictCannotReadOrReuseEitherIdentityAndFirstLocalFailureSurvivesLaterPause(CapturedOutput log) {
        var paths = new ArrayList<String>();
        var projects = client((uri, token) -> {
            paths.add(uri.getPath());
            if (uri.getHost().equals("github.com")) return reply(TrendFixture.htmlWithStarIdentity());
            if (uri.getPath().endsWith("/engine")) return reply("{\"id\":99,\"private\":false,\"full_name\":\"sample/engine\"}");
            return new TrendSourceTransport.Reply(503, "secret upstream diagnostic", null);
        }).collect().projects();
        assertThat(paths).containsExactly("/trending", "/repos/sample/engine", "/repos/sample/awesome-books");
        assertThat(projects.getFirst().snapshot().repositoryId()).isNull();
        assertThat(projects.getFirst().sourceIssue().reason()).isEqualTo(Reason.IDENTITY_CONFLICT);
        assertThat(projects.getLast().sourceIssue().reason()).isEqualTo(Reason.UPSTREAM_ERROR);
        assertThat(log.getAll()).contains("首个仓库=sample/engine", "故障数=2", "整仓跳过数=0")
                .doesNotContain("secret upstream diagnostic", "fake-fixture-token");
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
            assertThat(project.sourceIssue().reason()).isEqualTo(Reason.README_NOT_FOUND);
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

    @Test void explicitResourcePermissionFailureDoesNotConsultOldIdentityOrPauseOtherRepos() {
        var projects = client((uri, token) -> {
            if (uri.getHost().equals("github.com")) return reply(TrendFixture.htmlWithStarIdentity());
            if (uri.getPath().endsWith("/engine")) return new TrendSourceTransport.Reply(403,
                    "{\"message\":\"Resource not accessible by integration\"}", null);
            if (uri.getPath().endsWith("/readme")) return reply(TrendFixture.readme("Project notes"));
            if (uri.getPath().endsWith("/contents")) return reply("[]");
            return reply("{\"id\":2,\"private\":false,\"full_name\":\"sample/awesome-books\"}");
        }).collect().projects();
        assertThat(projects.getFirst().sourceIssue().reason()).isEqualTo(Reason.RESOURCE_ACCESS_REFUSED);
        assertThat(projects.getFirst().snapshot().repositoryId()).isNull();
        assertThat(projects.getLast().readmeText()).isEqualTo("Project notes");
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
}

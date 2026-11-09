package com.polyethene.polyhouse.system;

import com.polyethene.polyhouse.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "DB_HOST=localhost", "DB_USERNAME=unused-test-user", "DB_PASSWORD=unused",
        "REDIS_HOST=localhost", "REDIS_PASSWORD=unused",
        "polyhouse.api-limits.api.requests-per-minute=1", "polyhouse.api-limits.api.burst=9",
        "polyhouse.api-limits.login.requests-per-minute=1", "polyhouse.api-limits.login.burst=2",
        "polyhouse.api-limits.sensitive.requests-per-minute=1", "polyhouse.api-limits.sensitive.burst=1"
})
class ProductionRateLimitHttpTest {

    private static final String PAGES = "https://eternalk1ngdom.github.io";
    private static final String LOGIN = "/api/auth/login";
    private static final String ME = "/api/auth/me";
    private static final String CREDENTIALS =
            "{\"username\":\"fixture_user\",\"password\":\"fixture-password\",\"clientType\":\"PC\"}";

    @LocalServerPort
    private int port;

    @MockBean
    private AuthService auth;

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        registry.add("POLYHOUSE_JWT_SECRET", () -> Base64.getEncoder().encodeToString(key));
    }

    @Test
    void layersShareOnlyTheirIntendedBudgetsAndKeepPreflightsWorking() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(auth.login(any(), anyString())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        });
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var first = client.sendAsync(post(LOGIN, CREDENTIALS), HttpResponse.BodyHandlers.ofString());
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertLimited(send(client, post("/api/auth/%6Cogin;fixture=one", CREDENTIALS)));
                assertThat(send(client, request(ME).GET().build()).statusCode()).isEqualTo(401);
            } finally {
                finish.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);

            // 编码、路径参数、查询和转发头均不能拿到独立额度。并发拒绝也没有扣掉第二个令牌。
            assertThat(send(client, post("/api/auth/%6Cogin;fixture=two?nonce=one", CREDENTIALS)).statusCode())
                    .isEqualTo(200);
            assertLimited(send(client, post(LOGIN + "?nonce=two", CREDENTIALS)));
            assertThat(send(client, post("/api/auth/password/change", "{}")).statusCode()).isEqualTo(401);
            assertLimited(send(client, post("/api/manage/accounts/42/ban", "{}")));
            assertThat(send(client, post("/api/auth/logout", "{}")).statusCode()).isEqualTo(400);

            String oversized = "x".repeat(384 * 1024 + 1);
            assertThat(send(client, post(LOGIN, oversized)).statusCode()).isEqualTo(413);
            assertLimited(send(client, post(LOGIN, oversized)));
            var preflight = request(LOGIN).header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "Content-Type")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            assertThat(send(client, preflight).statusCode()).isEqualTo(200);
            verify(auth, times(2)).login(any(), anyString());
        }
    }

    private HttpRequest post(String path, String body) {
        return request(path).header("Content-Type", "application/json")
                .header("X-Forwarded-For", "192.0.2.1").header("CF-Connecting-IP", "192.0.2.2")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Origin", PAGES).timeout(Duration.ofSeconds(12));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertLimited(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("\"code\":42900");
        assertThat(response.headers().firstValue("Retry-After"))
                .hasValueSatisfying(seconds -> assertThat(Integer.parseInt(seconds)).isPositive());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(PAGES);
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers")).contains("Retry-After");
    }
}

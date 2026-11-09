package com.polyethene.polyhouse.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "DB_HOST=localhost", "DB_USERNAME=unused-test-user", "DB_PASSWORD=unused",
        "REDIS_HOST=localhost", "REDIS_PASSWORD=unused"
})
class ProductionHttpTest {
    private static final String PAGES = "https://eternalk1ngdom.github.io";
    private static final int BODY_LIMIT = 384 * 1024;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        registry.add("POLYHOUSE_JWT_SECRET", () -> Base64.getEncoder().encodeToString(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {PAGES, "http://localhost:5173", "http://localhost:3000"})
    void shouldAllowProductionPreflightWithoutAuthenticationOrCookieCredentials(String origin) throws Exception {
        var response = preflight(origin, "PUT", "Authorization, Content-Type, Accept");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods"))
                .hasValueSatisfying(methods -> assertThat(methods.split(",")).containsExactly("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    @Test
    void shouldRejectUnlistedOriginMethodAndHeader() throws Exception {
        for (var response : new HttpResponse<?>[]{
                preflight("https://eternalk1ngdom.github.io.example.org", "GET", "Accept"),
                preflight("http://127.0.0.1:5173", "GET", "Accept"),
                preflight("chrome-extension://fixture-extension", "GET", "Accept"),
                preflight(PAGES, "PATCH", "Accept"),
                preflight(PAGES, "GET", "X-Unknown")
        }) {
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body().toString()).contains("\"code\":40300");
        }
    }

    @Test
    void shouldKeepAuthenticationForActualRequests() throws Exception {
        var response = send(api("/api/auth/me").header("Origin", PAGES).GET());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(PAGES);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRejectOversizedBodyBeforeParsingAndPreserveCors(boolean chunked) throws Exception {
        byte[] invalidJson = new byte[BODY_LIMIT + 1];
        var response = submit(chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(invalidJson))
                : HttpRequest.BodyPublishers.ofByteArray(invalidJson));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":41300");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(PAGES);
    }

    @Test
    void shouldPassBodyAtLimitToExistingDtoValidation() throws Exception {
        var response = submit(HttpRequest.BodyPublishers.ofString(" ".repeat(BODY_LIMIT - 2) + "{}"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":40000");
    }

    private HttpResponse<String> preflight(String origin, String method, String headers) throws Exception {
        return send(api("/api/auth/me").header("Origin", origin)
                .header("Access-Control-Request-Method", method).header("Access-Control-Request-Headers", headers)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
    }

    private HttpResponse<String> submit(HttpRequest.BodyPublisher body) throws Exception {
        return send(api("/api/auth/login").header("Origin", PAGES).header("Content-Type", "application/json").POST(body));
    }

    private HttpRequest.Builder api(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}

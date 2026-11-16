package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实本机HTTP检查在Redis/业务前拒绝并发、伪造头、超大正文，随后能恢复。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrendManagementLimitHttpTest {
    private static final String ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    @LocalServerPort private int port;
    @MockBean private AuthService auth;
    @MockBean private TrendJobManagementService jobs;

    @Test void sharedBudgetRejectsBeforeAuthenticationAndRecoversWithoutLockingAccount() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        when(auth.getCurrentUser("fixture")).thenAnswer(invocation -> {
            entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new UserProfileVO("42", "fixture", "测试", null, UserRoleEnum.OWNER, "0", 1, "0", false);
        });
        when(jobs.query(any(), any())).thenReturn(new TrendJobVO(ID, "QUEUED", "QUEUED", false, TrendAiFixture.NOW,
                null, null, TrendAiFixture.NOW.plusSeconds(1800), null, null));
        try (var client = HttpClient.newHttpClient()) {
            var a = client.sendAsync(request("/api/manage/trend/jobs?jobId=" + ID, 1).GET().build(), HttpResponse.BodyHandlers.ofString());
            var b = client.sendAsync(request("/api/manage/trend/jobs?jobId=" + ID, 2).GET().build(), HttpResponse.BodyHandlers.ofString());
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                limited(client.send(request("/api/manage/%74rend/jobs;fixture=1?jobId=" + ID, 3).GET().build(), HttpResponse.BodyHandlers.ofString()));
                verify(auth, times(2)).getCurrentUser("fixture");
            } finally { release.countDown(); }
            assertThat(a.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(b.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            clearInvocations(auth, jobs);
            var large = client.send(request("/api/manage/trend/jobs", 4).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(384 * 1024 + 1))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(large.statusCode()).isEqualTo(413); verifyNoInteractions(auth, jobs);
            boolean rejected = false;
            for (int i = 0; i < 12; i++) {
                var response = client.send(request("/api/manage/trend/jobs?jobId=" + ID, i + 5).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429) { limited(response); rejected = true; break; }
                assertThat(response.statusCode()).isEqualTo(200);
            }
            assertThat(rejected).isTrue();
            Thread.sleep(2200);
            assertThat(client.send(request("/api/manage/trend/jobs?jobId=" + ID, 20).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
    }
    private HttpRequest.Builder request(String path, int address) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer fixture").header("X-Forwarded-For", "192.0.2." + address)
                .header("CF-Connecting-IP", "198.51.100." + address);
    }
    private static void limited(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow())).isBetween(1, 2);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).contains("42900").doesNotContain("192.0.2");
    }
}

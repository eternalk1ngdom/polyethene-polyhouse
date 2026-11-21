package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.model.vo.TrendPeriodsVO;
import com.polyethene.polyhouse.service.AuthService;
import com.polyethene.polyhouse.service.TrendReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrendReadLimitHttpTest {
    @LocalServerPort private int port;
    @MockBean private AuthService auth;
    @MockBean private TrendReportService reports;

    @Test void sharedAddressesSpoofedHeadersConcurrencyAndRecoveryRemainBounded() throws Exception {
        var entered = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        when(reports.periods(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return new TrendPeriodsVO("DAILY", List.of(), null);
        });
        try (var client = HttpClient.newHttpClient()) {
            var readiness = client.send(request("/api/trend/readiness"), HttpResponse.BodyHandlers.ofString());
            assertThat(readiness.statusCode()).isEqualTo(200);
            assertThat(readiness.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(readiness.body()).contains("\"scope\":\"APPLICATION\"", "\"status\":\"READY\"");
            var head = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/trend/readiness"))
                    .timeout(Duration.ofSeconds(12)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(head.statusCode()).isEqualTo(200);
            assertThat(head.body()).isEmpty();
            var one = client.sendAsync(request("/api/trend/periods?a=1"), HttpResponse.BodyHandlers.ofString());
            var two = client.sendAsync(request("/api/trend/periods?a=2"), HttpResponse.BodyHandlers.ofString());
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                limited(client.send(request("/api/%74rend/readiness;fixture=one?a=3"), HttpResponse.BodyHandlers.ofString()));
            } finally { finish.countDown(); }
            assertThat(one.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(two.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            boolean rejected = false;
            for (int i = 0; i < 20; i++) {
                var response = client.send(request("/api/trend/periods?nonce=" + i), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429) { limited(response); rejected = true; break; }
                assertThat(response.statusCode()).isEqualTo(200);
            }
            assertThat(rejected).isTrue();
            Thread.sleep(1200);
            assertThat(client.send(request("/api/trend/readiness"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
        verifyNoInteractions(auth);
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(12))
                .header("X-Forwarded-For", "192.0.2.10").header("CF-Connecting-IP", "192.0.2.11").GET().build();
    }
    private static void limited(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).contains("1");
        assertThat(response.body()).contains("42900").doesNotContain("192.0.2");
    }
}

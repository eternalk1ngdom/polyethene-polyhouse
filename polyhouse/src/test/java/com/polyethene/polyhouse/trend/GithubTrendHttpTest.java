package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.service.trend.GithubTrendHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GithubTrendHttpTest {
    @ParameterizedTest
    @ValueSource(strings = {"http://api.github.com/repos/a/b", "https://user@api.github.com/repos/a/b",
            "https://api.github.com:8443/repos/a/b", "https://api.github.com/repos/a/b?token=bad",
            "https://api.github.com/repos/a/b/../../user", "https://api.github.com/repos/a/b%2f..",
            "https://example.org/trending?since=daily", "https://github.com/trending?since=weekly"})
    void refusesUnexpectedHostsPathsAndCredentialDestinations(String target) {
        assertThatThrownBy(() -> GithubTrendHttp.requireAllowed(URI.create(target))).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 429})
    void oversizeChunkCancelsBodyAndRetainsKnownRateLimit(int status) {
        var http = mock(HttpClient.class);
        var cancelled = new AtomicBoolean();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    var info = mock(HttpResponse.ResponseInfo.class);
                    when(info.statusCode()).thenReturn(status);
                    when(info.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of("120"),
                            "X-RateLimit-Remaining", List.of("0"), "X-RateLimit-Reset", List.of("invalid")), (key, value) -> true));
                    var subscriber = handler.apply(info);
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override public void request(long count) { }
                        @Override public void cancel() { cancelled.set(true); }
                    });
                    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1024 * 1024 + 1])));
                    return subscriber.getBody().toCompletableFuture().thenApply(body -> {
                        throw new AssertionError("oversized body accepted");
                    });
                });
        try (var client = new GithubTrendHttp(http)) {
            var target = URI.create("https://api.github.com/repos/a/b");
            if (status == 200) {
                assertThatThrownBy(() -> client.get(target, "fake-fixture-token"))
                        .isInstanceOf(BusinessException.class).hasMessage("GitHub 资料暂时不可用");
            } else {
                var reply = client.get(target, "fake-fixture-token");
                assertThat(reply.status()).isEqualTo(429);
                assertThat(reply.retrySeconds()).isEqualTo(120);
                assertThat(reply.exhausted()).isTrue();
                assertThat(reply.resetEpoch()).isNull();
                assertThat(reply.body()).isEmpty();
            }
        }
        assertThat(cancelled.get()).isTrue();
    }

    @Test void requestHasFiniteTimeoutAndNeverSendsTokenToTrendingHtml() {
        var http = mock(HttpClient.class);
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    assertThat(request.timeout()).contains(java.time.Duration.ofSeconds(15));
                    assertThat(request.headers().firstValue("Authorization")).isEmpty();
                    @SuppressWarnings("unchecked") // HttpClient 泛型在 Mockito 调用边界擦除，响应体与本调用固定为 byte[]。
                    HttpResponse<byte[]> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(200);
                    when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("text/html")), (key, value) -> true));
                    when(response.body()).thenReturn(new byte[0]);
                    return CompletableFuture.completedFuture(response);
                });
        try (var client = new GithubTrendHttp(http)) {
            assertThat(client.get(URI.create("https://github.com/trending?since=daily"), "fake-fixture-token").status()).isEqualTo(200);
        }
    }
}

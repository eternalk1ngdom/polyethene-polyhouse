package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendAiProperties;
import com.polyethene.polyhouse.service.trend.*;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeepSeekHttpTest {
    @Test void keyOnlyGoesToOfficialHeaderAndRedirectIsNotReplayed() {
        var client = mock(HttpClient.class);
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            assertThat(request.uri().toString()).isEqualTo("https://api.deepseek.com/chat/completions");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer " + TrendAiFixture.KEY);
            assertThat(request.timeout()).contains(java.time.Duration.ofSeconds(30));
            return CompletableFuture.completedFuture(response(302, new byte[0]));
        });
        try (var http = new DeepSeekHttp(TrendAiFixture.settings(), client)) {
            assertThat(http.complete("{}".getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(302);
        }
        verify(client, times(1)).sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertThat(TrendAiFixture.settings().toString()).doesNotContain(TrendAiFixture.KEY);
    }

    @Test void unconfiguredOrOversizedRequestIsProvablyNotSent() {
        var client = mock(HttpClient.class);
        try (var http = new DeepSeekHttp(new TrendAiProperties(true, null, null, null, null, null), client)) {
            assertThatThrownBy(() -> http.complete(new byte[1])).isInstanceOf(DeepSeekGateway.NotSent.class);
        }
        try (var http = new DeepSeekHttp(new TrendAiProperties(true, TrendAiFixture.KEY, null, null, null, null, true, "high"), client)) {
            assertThatThrownBy(() -> http.complete(new byte[1])).isInstanceOf(DeepSeekGateway.NotSent.class);
        }
        try (var http = new DeepSeekHttp(TrendAiFixture.settings(), client)) {
            assertThatThrownBy(() -> http.complete(new byte[32 * 1024 + 1])).isInstanceOf(DeepSeekGateway.NotSent.class);
            assertThatThrownBy(() -> http.complete(TrendAiFixture.KEY.getBytes(StandardCharsets.UTF_8))).isInstanceOf(DeepSeekGateway.NotSent.class);
        }
        verify(client, never()).sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test void lightThinkingUsesSixtySecondTimeoutWithoutLeakingCredentials() {
        var client = mock(HttpClient.class);
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            assertThat(request.timeout()).contains(java.time.Duration.ofSeconds(60));
            return CompletableFuture.completedFuture(response(200, "{}".getBytes(StandardCharsets.UTF_8)));
        });
        var thinking = TrendAiFixture.settingsWithThinking();
        try (var http = new DeepSeekHttp(thinking, client)) {
            assertThat(http.complete("{}".getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(200);
        }
        assertThat(thinking.toString()).contains("thinkingEnabled=true").doesNotContain(TrendAiFixture.KEY);
    }

    @Test void responseCannotEchoTheKeyAndExceptionCannotExposeHeaders() {
        var client = mock(HttpClient.class);
        var echoed = response(200, TrendAiFixture.KEY.getBytes(StandardCharsets.UTF_8));
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(echoed));
        try (var http = new DeepSeekHttp(TrendAiFixture.settings(), client)) {
            assertThatThrownBy(() -> http.complete(new byte[1])).isInstanceOf(BusinessException.class)
                    .hasMessageNotContaining(TrendAiFixture.KEY).hasNoCause();
        }
    }

    @Test void chunkedResponsesStopAtTheAiSpecificLimit() {
        var client = mock(HttpClient.class);
        var cancelled = new AtomicBoolean();
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
            var subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { }
                @Override public void cancel() { cancelled.set(true); }
            });
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[256 * 1024 + 1])));
            return subscriber.getBody().toCompletableFuture().thenApply(body -> { throw new AssertionError("unbounded body accepted"); });
        });
        try (var http = new DeepSeekHttp(TrendAiFixture.settings(), client)) {
            assertThatThrownBy(() -> http.complete(new byte[1])).isInstanceOf(BusinessException.class).hasNoCause();
        }
        assertThat(cancelled.get()).isTrue();
    }

    @SuppressWarnings("unchecked") // Mockito接口类型擦除，夹具响应体固定为byte[]。
    private static HttpResponse<byte[]> response(int code, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (key, value) -> true));
        return response;
    }
}

package com.polyethene.polyhouse.service.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.entity.TrendSourceIssue.Reason;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 固定来源、单请求并发、完整响应十五秒和一 MiB 边界，禁止隐式跨域携带 GitHub 令牌。 */
@Component
public class GithubTrendHttp implements TrendSourceTransport, AutoCloseable {
    private static final int MAX_BYTES = 1024 * 1024;
    private final HttpClient client;
    private final Semaphore active = new Semaphore(1);

    /** 不跟随客户端默认重定向，所有跳转必须再次经过允许列表。 */
    @Autowired
    public GithubTrendHttp() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** 显式客户端边界用于验证分块、慢读和取消行为，不放宽生产目标校验。 */
    public GithubTrendHttp(HttpClient client) { this.client = client; }

    /** 请求时限覆盖正文消费，不能用 ofInputStream 把慢速读取移出超时范围。 */
    @Override
    public Reply get(URI uri, String token) {
        requireAllowed(uri);
        if (!active.tryAcquire()) throw unavailable();
        CompletableFuture<HttpResponse<byte[]>> future = null;
        var received = new AtomicReference<HttpResponse.ResponseInfo>();
        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Polyhouse-Tech-Radar")
                    .header("Accept", uri.getHost().equals("api.github.com")
                            ? "application/vnd.github+json" : "text/html");
            if (uri.getHost().equals("api.github.com") && token != null && !token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            if (uri.getHost().equals("api.github.com")) request.header("X-GitHub-Api-Version", "2026-03-10");
            future = client.sendAsync(request.GET().build(), info -> {
                received.set(info);
                return new LimitedResponseBody(MAX_BYTES);
            });
            var response = future.get(15, TimeUnit.SECONDS);
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() == 200 && !(uri.getHost().equals("api.github.com")
                    ? contentType.startsWith("application/json") : contentType.startsWith("text/html"))) {
                throw new BodyFailure(Reason.INVALID_RESPONSE, response.headers());
            }
            return reply(response.statusCode(), response.headers(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException failure) {
            // 错误页超大或传到一半超时，仍须遵守已经收到的 429 与等待时间。
            var info = received.get();
            if (info != null && info.statusCode() != 200) return reply(info.statusCode(), info.headers(), "");
            if (info != null) {
                var reason = failure.getCause() instanceof LimitedResponseBody.TooLarge
                        ? Reason.INVALID_RESPONSE : Reason.TRANSPORT_FAILURE;
                throw new BodyFailure(reason, info.headers());
            }
            throw unavailable();
        } catch (IllegalArgumentException failure) {
            // 异常及 cause 可能包含请求头或输入，不向业务层传播它们。
            throw unavailable();
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
            active.release();
        }
    }

    private static Reply reply(int status, HttpHeaders headers, String body) {
        return new Reply(status, body, headers.firstValue("Location").orElse(null), seconds(headers, "Retry-After"),
                headers.firstValue("X-RateLimit-Remaining").filter("0"::equals).isPresent(), seconds(headers, "X-RateLimit-Reset"));
    }

    private static Long seconds(HttpHeaders headers, String name) {
        return headers.firstValue(name).filter(value -> value.matches("[0-9]{1,10}"))
                .map(Long::valueOf).orElse(null);
    }

    /** 200 响应的正文无法使用时，已收到的限流信息仍适用于后续请求。 */
    static final class BodyFailure extends BusinessException {
        final Reason reason;
        final Reply limits;
        BodyFailure(Reason reason, HttpHeaders headers) {
            super(ErrorCode.SERVICE_UNAVAILABLE, "GitHub 资料暂时不可用");
            this.reason = reason;
            this.limits = new Reply(200, "", null, seconds(headers, "Retry-After"),
                    headers.firstValue("X-RateLimit-Remaining").filter("0"::equals).isPresent(), seconds(headers, "X-RateLimit-Reset"));
        }
    }

    /** 防止绝对重定向、端口、编码路径和用户信息将令牌送往其他目标。 */
    public static void requireAllowed(URI uri) {
        boolean common = "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getFragment() == null;
        boolean trending = "github.com".equals(uri.getHost()) && "/trending".equals(uri.getRawPath())
                && "since=daily".equals(uri.getRawQuery());
        boolean api = "api.github.com".equals(uri.getHost()) && uri.getRawQuery() == null
                && uri.getRawPath().matches("/(repos/[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}|repositories/[1-9][0-9]{0,18})(/(contents|readme))?");
        if (!common || !(trending || api)) throw unavailable();
    }

    /** 生命周期结束取消本模块连接，不影响其他网络客户端。 */
    @PreDestroy
    @Override
    public void close() { client.shutdownNow(); }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "GitHub 资料暂时不可用");
    }

}

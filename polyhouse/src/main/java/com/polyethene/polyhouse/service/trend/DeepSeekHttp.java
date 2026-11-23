package com.polyethene.polyhouse.service.trend;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendAiProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

/** 只访问 DeepSeek 官方固定地址，永不跟随重定向，不记录请求头或上游原始异常。 */
@Component
public class DeepSeekHttp implements DeepSeekGateway, AutoCloseable {
    private final TrendAiProperties settings;
    private final HttpClient http;
    private final Semaphore active = new Semaphore(1);

    /** 连接五秒，自动重定向关闭；不接受页面、Prompt 或配置指定的代理目标 URL。 */
    @Autowired
    public DeepSeekHttp(TrendAiProperties settings) {
        this(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** 显式客户端边界用于离线检查密钥目的地、超时和分块体积，不放宽生产地址。 */
    public DeepSeekHttp(TrendAiProperties settings, HttpClient http) { this.settings = settings; this.http = http; }

    /** 内部方法仍声明算力要求；未来 MVC 触发还须明确校验 OWNER，后台依赖开关与账本。 */
    @Override
    @RequireRole(UserRoleEnum.INSIDER)
    public Reply complete(byte[] request) {
        if (!settings.ready() || request == null || request.length == 0 || request.length > 32 * 1024) throw new NotSent();
        if (new String(request, StandardCharsets.UTF_8).contains(settings.apiKey())) throw new NotSent();
        if (!active.tryAcquire()) throw new NotSent();
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            int timeoutSeconds = settings.requestTimeoutSeconds();
            var message = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request)).build();
            future = http.sendAsync(message, info -> new LimitedResponseBody(256 * 1024));
            var response = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (response.statusCode() == 200 && !response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) throw unavailable();
            // 正常响应不应含认证头中的 Key；异常回显即停止，不让它进入解读或日志。
            if (new String(response.body(), StandardCharsets.UTF_8).contains(settings.apiKey())) throw unavailable();
            return new Reply(response.statusCode(), response.body());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            throw unavailable(); // 不携带 cause，JDK 异常可能保留 HttpRequest 与认证头。
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
            active.release();
        }
    }

    /** 仅关闭本模块连接，应用退出不留下后台请求。 */
    @Override @PreDestroy public void close() { http.shutdownNow(); }

    private static BusinessException unavailable() { return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI 调用结果待核对"); }
}

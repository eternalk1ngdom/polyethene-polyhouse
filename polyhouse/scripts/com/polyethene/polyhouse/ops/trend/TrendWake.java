package com.polyethene.polyhouse.ops.trend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * JDK 21单文件外部探测程序：不依赖后端类路径、不启动Spring、不调用生成接口。
 * 放在scripts并保持包目录一致，避免运维代码随业务应用打包或引入额外运行时。
 */
public final class TrendWake {
    private static final System.Logger LOG = System.getLogger(TrendWake.class.getName());
    private static final ZoneOffset BJT = ZoneOffset.ofHours(8);
    private static final LocalTime START = LocalTime.of(6, 20);
    private static final LocalTime END = LocalTime.of(8, 0);
    // 仅单个Render服务名，禁止借后缀、子域或用户信息改变目的地。
    private static final Pattern RENDER = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.onrender\\.com");
    private static final Set<Integer> RETRY = Set.of(408, 425, 500, 502, 503, 504, 522, 523, 524);
    private static final int MAX_BYTES = 4096;

    private TrendWake() { }

    /** 配置只读环境变量；CLI只开放本地回环测试，不提供改时间、地址路径或凭据选项。 */
    public static void main(String[] args) {
        if (args.length > 1 || args.length == 1 && !"--allow-local".equals(args[0])) {
            LOG.log(System.Logger.Level.ERROR, "trend-wake: invalid-arguments");
            System.exit(1);
        }
        int code = execute(System.getenv(), args.length == 1, Clock.systemUTC(), Duration.ofSeconds(45),
                Duration.ofSeconds(30), Duration.ofSeconds(195),
                message -> LOG.log(System.Logger.Level.INFO, message));
        System.exit(code);
    }

    /** 包内时钟/时限注入用于离线验证，不成为可由外部工作流绕过窗口的配置。 */
    static int execute(Map<String, String> env, boolean allowLocal, Clock clock, Duration requestTimeout,
                       Duration backoff, Duration total, Consumer<String> log) {
        if (!"true".equals(env.get("TREND_WAKE_ENABLED"))) {
            log.accept("trend-wake: disabled");
            return 0;
        }
        if (remainingWindow(clock.instant()).isZero()) {
            log.accept("trend-wake: outside-window");
            return 0;
        }
        long started = System.nanoTime();
        HttpClient client = null;
        try {
            URI target = probeUri(env.get("TREND_WAKE_BASE_URL"), allowLocal);
            // 显式直连、无认证/Cookie、不跟随跳转；不能继承JVM默认代理或认证器。
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .proxy(ProxySelector.of(null)).followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1).build();
            for (int attempt = 1; attempt <= 3; attempt++) {
                Duration remaining = min(total.minusNanos(System.nanoTime() - started), remainingWindow(clock.instant()));
                if (remaining.isNegative() || remaining.isZero()) break;
                Reply reply = request(client, target, min(requestTimeout, remaining));
                log.accept("trend-wake: attempt=" + attempt + " result=" + reply.category());
                if (reply.ready()) {
                    log.accept("trend-wake: application-ready; dependencies-and-task-not-checked");
                    return 0;
                }
                if (!reply.retry() || attempt == 3) break;
                remaining = min(total.minusNanos(System.nanoTime() - started), remainingWindow(clock.instant()));
                if (remaining.compareTo(backoff) <= 0) break;
                TimeUnit.NANOSECONDS.sleep(backoff.toNanos());
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            log.accept("trend-wake: interrupted");
            return 1;
        } catch (IllegalArgumentException failure) {
            // URI、远端响应和异常原文都不入日志，避免未来配置或上游回显泄露私密信息。
            log.accept("trend-wake: configuration-error");
            return 1;
        } finally {
            // 不用可能等待未完成响应的close；超时请求已取消，此处立即停止该客户端。
            if (client != null) client.shutdownNow();
        }
        log.accept("trend-wake: application-not-ready");
        return 1;
    }

    static Duration remainingWindow(Instant instant) {
        var local = instant.atOffset(BJT);
        if (local.toLocalTime().isBefore(START) || !local.toLocalTime().isBefore(END)) return Duration.ZERO;
        return Duration.between(local, local.with(END));
    }

    static URI probeUri(String raw, boolean allowLocal) {
        if (raw == null || raw.isEmpty() || raw.length() > 256
                || raw.chars().anyMatch(c -> c <= 32 || c >= 127 || c == '\\')) throw new IllegalArgumentException();
        URI base = URI.create(raw);
        if (base.isOpaque() || base.getRawPath() == null || base.getRawUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null
                || !(base.getRawPath().isEmpty() || "/".equals(base.getRawPath())) || base.getHost() == null) {
            throw new IllegalArgumentException();
        }
        String host = base.getHost().toLowerCase(Locale.ROOT);
        int port = base.getPort();
        String origin;
        if ("https".equalsIgnoreCase(base.getScheme()) && RENDER.matcher(host).matches()
                && (port == -1 || port == 443)
                && (base.getRawAuthority().equalsIgnoreCase(host) || base.getRawAuthority().equalsIgnoreCase(host + ":443"))) {
            origin = "https://" + host;
        } else if (allowLocal && "http".equalsIgnoreCase(base.getScheme()) && port >= 1024 && port <= 65535
                && ("127.0.0.1".equals(host) || "[::1]".equals(host))) {
            origin = "http://" + host + ":" + port;
        } else throw new IllegalArgumentException();
        return URI.create(origin + "/api/trend/readiness");
    }

    private static Duration min(Duration a, Duration b) { return a.compareTo(b) < 0 ? a : b; }

    private static Reply request(HttpClient client, URI uri, Duration timeout) throws InterruptedException {
        var message = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json")
                .header("User-Agent", "Polyhouse-Trend-Wake/1").GET().build();
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(message,
                info -> new BoundedBody(info.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_BYTES));
        try {
            // future只在有界正文读完后完成，外层get覆盖连接、响应头和慢速正文的总时间。
            var response = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            int status = response.statusCode();
            if (status != 200) return new Reply(false, RETRY.contains(status), "http-" + status);
            boolean ready = isReady(response.body());
            return new Reply(ready, !ready, ready ? "ready" : "not-ready");
        } catch (TimeoutException failure) {
            return new Reply(false, true, "timeout");
        } catch (ExecutionException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof BodyLimit) return new Reply(false, false, "body-limit");
            }
            return new Reply(false, true, "transport");
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    static boolean isReady(byte[] bytes) {
        if (bytes.length > MAX_BYTES) return false;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Object value = new Json(text).document();
            if (!(value instanceof Map<?, ?> root) || !(root.get("code") instanceof NumberToken code)
                    || !"0".equals(code.text()) || !(root.get("data") instanceof Map<?, ?> data)) return false;
            return "READY".equals(data.get("status")) && "APPLICATION".equals(data.get("scope"));
        } catch (IOException | IllegalArgumentException failure) {
            return false;
        }
    }

    /** 固定类别而非异常/响应原文，保证失败路径也不泄露上游内容。 */
    private record Reply(boolean ready, boolean retry, String category) { }
    /** 保留JSON数字词法类型，false、0.0或字符串0不能冒充整数业务码。 */
    private record NumberToken(String text) { }
    /** 独立标记体积超限，不能把它当成可重复下载的大响应。 */
    private static final class BodyLimit extends IOException { }

    /** 在聚合前限字节并取消订阅，不能先下载完整正文再检查体积。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final boolean rejectLength;
        private Flow.Subscription subscription;

        BoundedBody(boolean rejectLength) { this.rejectLength = rejectLength; }
        /** 只有正文完成或失败才结束future，确保外层超时包含读取阶段。 */
        @Override public CompletionStage<byte[]> getBody() { return result; }
        /** 已知长度超限时不请求正文；未知长度继续逐批检查。 */
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (rejectLength) { value.cancel(); result.completeExceptionally(new BodyLimit()); }
            else value.request(1);
        }
        /** Flow串行回调中累计大小，任何一批超限都在复制前取消。 */
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            long count = bytes.size();
            for (var buffer : buffers) count += buffer.remaining();
            if (count > MAX_BYTES) {
                subscription.cancel();
                result.completeExceptionally(new BodyLimit());
                return;
            }
            for (var buffer : buffers) {
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        /** 异常只进入内部future，由外层转成固定类别。 */
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        /** 完整读取后才允许验证JSON及就绪状态。 */
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    /**
     * JDK没有内置JSON解析器；仅在4096字节/16层边界内读取JSON，不添加依赖或依赖字段顺序。
     * 拒绝重复键和尾随内容，避免嵌套字段、字符串片段或前后两份文档伪造就绪事实。
     */
    private static final class Json {
        private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
        private final String text;
        private int position;

        Json(String text) { this.text = text; }
        Object document() {
            Object value = value(0);
            whitespace();
            if (position != text.length()) throw new IllegalArgumentException();
            return value;
        }
        private Object value(int depth) {
            whitespace();
            if (depth > 16 || position >= text.length()) throw new IllegalArgumentException();
            char c = text.charAt(position);
            if (c == '"') return string();
            if (c == '{') {
                position++;
                Map<String, Object> object = new HashMap<>();
                if (take('}')) return object;
                do {
                    whitespace();
                    String key = string();
                    if (!take(':') || object.containsKey(key)) throw new IllegalArgumentException();
                    object.put(key, value(depth + 1));
                    if (take('}')) return object;
                } while (take(','));
                throw new IllegalArgumentException();
            }
            if (c == '[') {
                position++;
                List<Object> array = new ArrayList<>();
                if (take(']')) return array;
                do {
                    array.add(value(depth + 1));
                    if (take(']')) return array;
                } while (take(','));
                throw new IllegalArgumentException();
            }
            for (String literal : List.of("true", "false", "null")) {
                if (text.startsWith(literal, position)) {
                    position += literal.length();
                    return switch (literal) { case "true" -> true; case "false" -> false; default -> null; };
                }
            }
            var number = NUMBER.matcher(text).region(position, text.length());
            if (!number.lookingAt()) throw new IllegalArgumentException();
            position = number.end();
            return new NumberToken(number.group());
        }
        private String string() {
            if (position >= text.length() || text.charAt(position++) != '"') throw new IllegalArgumentException();
            StringBuilder value = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') return value.toString();
                if (c < 32) throw new IllegalArgumentException();
                if (c != '\\') { value.append(c); continue; }
                if (position >= text.length()) throw new IllegalArgumentException();
                char escaped = text.charAt(position++);
                if (escaped == 'u') {
                    if (position + 4 > text.length()) throw new IllegalArgumentException();
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        char digit = text.charAt(position++);
                        int hex = digit <= 127 ? Character.digit(digit, 16) : -1;
                        if (hex < 0) throw new IllegalArgumentException();
                        code = code * 16 + hex;
                    }
                    value.append((char) code);
                } else value.append(switch (escaped) {
                    case '"', '\\', '/' -> escaped;
                    case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                    default -> throw new IllegalArgumentException();
                });
            }
            throw new IllegalArgumentException();
        }
        private boolean take(char expected) {
            whitespace();
            if (position < text.length() && text.charAt(position) == expected) { position++; return true; }
            return false;
        }
        private void whitespace() {
            while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
        }
    }
}

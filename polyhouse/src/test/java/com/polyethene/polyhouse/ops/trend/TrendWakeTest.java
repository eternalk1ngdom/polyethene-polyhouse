package com.polyethene.polyhouse.ops.trend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class TrendWakeTest {
    private static final Path SOURCE = Path.of("scripts/com/polyethene/polyhouse/ops/trend/TrendWake.java");
    private static final Instant NOW = Instant.parse("2026-11-15T22:20:00Z");
    private static final byte[] READY = bytes("{\"code\":0,\"message\":\"操作成功\",\"data\":{\"status\":\"READY\",\"scope\":\"APPLICATION\"}}");
    @TempDir static Path classes;
    private static URLClassLoader loader;
    private static Method execute;
    private static Method probeUri;
    private static Method isReady;

    @BeforeAll static void compileStandaloneSourceWithoutApplicationClasspath() throws Exception {
        // 脚本不进后端JAR。用JDK临时编译并反射包内入口，不为测试扩大生产API或修改pom。
        var diagnostics = new ByteArrayOutputStream();
        int result = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "--release", "21", "-encoding", "UTF-8", "-classpath", classes.toString(), "-d", classes.toString(), SOURCE.toString());
        assertThat(result).withFailMessage(diagnostics.toString(StandardCharsets.UTF_8)).isZero();
        loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        Class<?> script = loader.loadClass("com.polyethene.polyhouse.ops.trend.TrendWake");
        execute = script.getDeclaredMethod("execute", Map.class, boolean.class, Clock.class,
                Duration.class, Duration.class, Duration.class, Consumer.class);
        probeUri = script.getDeclaredMethod("probeUri", String.class, boolean.class);
        isReady = script.getDeclaredMethod("isReady", byte[].class);
        for (Method method : List.of(execute, probeUri, isReady)) method.setAccessible(true);
    }

    @AfterAll static void closeLoader() throws IOException { if (loader != null) loader.close(); }

    @ParameterizedTest(name = "未显式启用开关时静默退出：''{0}''")
    @ValueSource(strings = {"", "false", "TRUE", "1"})
    void disabledConfigurationDoesNotValidateOrContactTarget(String value) throws Exception {
        var logs = new ArrayList<String>();
        assertThat(run(Map.of("TREND_WAKE_ENABLED", value), fixed(NOW), Duration.ofSeconds(2), Duration.ZERO,
                Duration.ofSeconds(5), logs::add, false)).isZero();
        assertThat(logs).containsExactly("trend-wake: disabled");
    }

    @Test void missingEnabledVariableDefaultsToDisabled() throws Exception {
        var logs = new ArrayList<String>();
        assertThat(run(Map.of(), fixed(NOW), Duration.ofSeconds(2), Duration.ZERO,
                Duration.ofSeconds(5), logs::add, false)).isZero();
        assertThat(logs).containsExactly("trend-wake: disabled");
    }

    @ParameterizedTest(name = "窗口外时刻直接退出：{0}")
    @ValueSource(strings = {"2026-11-15T22:19:59Z", "2026-11-16T00:00:00Z"})
    void outsideWindowDoesNotValidateOrContactTarget(String isoInstant) throws Exception {
        var logs = new ArrayList<String>();
        assertThat(run(env("invalid"), fixed(Instant.parse(isoInstant)), Duration.ofSeconds(2), Duration.ZERO,
                Duration.ofSeconds(5), logs::add, false)).isZero();
        assertThat(logs).containsExactly("trend-wake: outside-window");
    }

    @ParameterizedTest(name = "SSRF 与 URL 白名单拒绝非法目标：{0}")
    @MethodSource("rejectedAllowlistTargets")
    void allowlistRejectsCredentialsPathsQueriesPortsAndLookalikes(String raw) {
        assertThatThrownBy(() -> invoke(probeUri, raw, true)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<String> rejectedAllowlistTargets() {
        return Stream.of(
                "https://user:private@a.onrender.com", "https://a.onrender.com/?key=private",
                "https://a.onrender.com/#private", "https://a.onrender.com/other", "https://a.onrender.com.evil.test",
                "https://onrender.com", "https://a.b.onrender.com", "http://a.onrender.com", "https://a.onrender.com:444",
                "https://a.onrender.com:", " https://a.onrender.com", "https://a.onrender.com\n",
                "https://a.onrender.com\\@evil.test", "http://localhost:8080", "http://192.0.2.1:8080",
                "http://127.0.0.1:80", "mailto:private", "invalid", "https://例子.onrender.com"
        );
    }

    @Test void allowlistAcceptsCanonicalRenderAndExplicitLocalLoopbackAndHidesBadConfigInLogs() throws Exception {
        assertThatThrownBy(() -> invoke(probeUri, "http://127.0.0.1:8080", false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(invoke(probeUri, "https://SAMPLE.onrender.com:443/", false))
                .isEqualTo(URI.create("https://sample.onrender.com/api/trend/readiness"));
        assertThat(invoke(probeUri, "http://[::1]:8080", true)).isEqualTo(URI.create("http://[::1]:8080/api/trend/readiness"));
        var logs = new ArrayList<String>();
        assertThat(run(env("https://private-key@a.onrender.com"), fixed(NOW), Duration.ofSeconds(2), Duration.ZERO,
                Duration.ofSeconds(5), logs::add, false)).isEqualTo(1);
        assertThat(logs).containsExactly("trend-wake: configuration-error");
    }

    @ResourceLock(value = "java.net.ProxySelector", mode = ResourceAccessMode.READ_WRITE)
    @Test void readyUsesOnlyFixedAnonymousGetAndIgnoresDefaultProxy() throws Exception {
        ProxySelector original = ProxySelector.getDefault();
        var proxyCalls = new java.util.concurrent.atomic.AtomicInteger();
        ProxySelector.setDefault(new ProxySelector() {
            /** 记录意外继承的默认代理调用，不连接额外目标。 */
            @Override public List<Proxy> select(URI uri) { proxyCalls.incrementAndGet(); return List.of(Proxy.NO_PROXY); }
            /** 连接失败也计入误用次数，避免无声放过代理路径。 */
            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                proxyCalls.incrementAndGet();
            }
        });
        try (var peer = new Peer(List.of(Response.immediate(200, READY)))) {
            var logs = new ArrayList<String>();
            assertThat(probe(peer, logs)).isZero();
            assertThat(peer.paths).containsExactly("GET /api/trend/readiness");
            assertThat(peer.authorization).containsExactly("");
            assertThat(peer.cookies).containsExactly("");
            assertThat(logs).contains("trend-wake: application-ready; dependencies-and-task-not-checked");
            assertThat(String.join("\n", logs)).doesNotContain("fixture-private", peer.origin());
            assertThat(proxyCalls.get()).isZero();
        } finally { ProxySelector.setDefault(original); }
    }

    @Test void cold503AndLoadingHtmlRecoverWithBoundedAttempts() throws Exception {
        try (var peer = new Peer(List.of(Response.immediate(503, bytes("warming")),
                Response.immediate(200, bytes("<html>Starting</html>")),
                Response.immediate(200, READY)))) {
            var logs = new ArrayList<String>();
            assertThat(probe(peer, logs)).isZero();
            assertThat(peer.paths).hasSize(3);
            assertThat(logs).contains("trend-wake: attempt=1 result=http-503", "trend-wake: attempt=2 result=not-ready");
        }
        try (var peer = new Peer(List.of(Response.immediate(503, bytes("private-body"))))) {
            var logs = new ArrayList<String>();
            assertThat(probe(peer, logs)).isEqualTo(1);
            assertThat(peer.paths).hasSize(3);
            assertThat(String.join("\n", logs)).doesNotContain("private-body");
        }
    }

    @ParameterizedTest(name = "状态码 {0} 不重试也不跟随重定向")
    @ValueSource(ints = {302, 401, 403, 404, 429})
    void redirectMissingUnauthorizedAnd429AreNeverRetriedOrFollowed(int status) throws Exception {
        try (var target = new Peer(List.of(Response.immediate(200, READY)));
             var peer = new Peer(List.of(new Response(status, bytes("private-body"), false, false, false, target.origin())))) {
            var logs = new ArrayList<String>();
            assertThat(probe(peer, logs)).isEqualTo(1);
            assertThat(peer.paths).hasSize(1);
            assertThat(String.join("\n", logs)).doesNotContain("private-body", target.origin());
            assertThat(target.paths).isEmpty();
        }
    }

    @ParameterizedTest(name = "超限响应体直接失败不重试（chunked={0}）")
    @ValueSource(booleans = {false, true})
    void knownAndChunkedOversizedBodiesFailWithoutRetry(boolean chunked) throws Exception {
        try (var peer = new Peer(List.of(new Response(200, new byte[8192], chunked, false, false, null)))) {
            var logs = new ArrayList<String>();
            assertThat(probe(peer, logs)).isEqualTo(1);
            assertThat(peer.paths).hasSize(1);
            assertThat(logs).contains("trend-wake: attempt=1 result=body-limit");
        }
    }

    @ParameterizedTest(name = "响应头与慢流响应体共用整次请求超时（slowBody={0}）")
    @ValueSource(booleans = {false, true})
    void responseHeaderAndSlowStreamingBodyShareWholeRequestTimeout(boolean slowBody) throws Exception {
        // 用服务端门闩阻塞代替固定 Thread.sleep，确保触发的是客户端整次超时而非与服务端休眠赛跑。
        try (var peer = new Peer(List.of(new Response(200, READY, false, !slowBody, slowBody, null)))) {
            var logs = new ArrayList<String>();
            assertThat(run(env(peer.origin()), fixed(NOW), Duration.ofMillis(150), Duration.ofSeconds(30),
                    Duration.ofSeconds(3), logs::add, true)).isEqualTo(1);
            assertThat(logs).hasSize(2);
            assertThat(logs.getFirst()).containsAnyOf("result=timeout", "result=transport");
            assertThat(peer.paths).hasSize(1);
            if (slowBody) assertThat(peer.startedBodies.get()).isEqualTo(1);
        }
    }

    @Test void dailyWindowAndTotalBudgetStopFurtherRequests() throws Exception {
        try (var peer = new Peer(List.of(Response.immediate(503, bytes("warming"))))) {
            var logs = new ArrayList<String>();
            assertThat(run(env(peer.origin()), fixed(Instant.parse("2026-11-15T23:59:50Z")), Duration.ofSeconds(2),
                    Duration.ofSeconds(30), Duration.ofSeconds(195), logs::add, true)).isEqualTo(1);
            assertThat(peer.paths).hasSize(1);
            var time = new AtomicReference<>(NOW);
            Clock clock = new Clock() {
                /** 保持UTC，仅测试时刻可推进，不依赖本机时区。 */
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                /** 调用方只读取instant，不另建可绕过窗口的测试时钟。 */
                @Override public Clock withZone(ZoneId zone) { return this; }
                /** 允许第一次失败后模拟跨过08:00。 */
                @Override public Instant instant() { return time.get(); }
            };
            assertThat(run(env(peer.origin()), clock, Duration.ofSeconds(2), Duration.ZERO, Duration.ofSeconds(5),
                    line -> time.set(Instant.parse("2026-11-16T00:00:00Z")), true)).isEqualTo(1);
            assertThat(peer.paths).hasSize(2);
            assertThat(run(env(peer.origin()), fixed(NOW), Duration.ofSeconds(2), Duration.ZERO,
                    Duration.ZERO, logs::add, true)).isEqualTo(1);
            assertThat(peer.paths).hasSize(2);
        }
    }

    @Test void strictJsonAcceptsOrderEscapesAndUnknownFields() throws Exception {
        assertThat(invoke(isReady, (Object) READY)).isEqualTo(true);
        assertThat(invoke(isReady, (Object) bytes(" {\"data\":{\"scope\":\"APPLICATION\",\"st\\u0061tus\":\"READY\"},"
                + "\"extra\":[true,false,null,1.25e+2,{\"x\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"}],\"code\":0} \n"))).isEqualTo(true);
    }

    @ParameterizedTest(name = "严格 JSON 拒绝畸形或伪造就绪载荷")
    @MethodSource("falseReadinessPayloads")
    void strictJsonRejectsFalseReadinessPayloads(byte[] payload) throws Exception {
        assertThat(invoke(isReady, (Object) payload)).isEqualTo(false);
    }

    private static Stream<byte[]> falseReadinessPayloads() {
        Stream<String> strings = Stream.of(
                "<html>Starting</html>", "[]", "{}", "null", "true", "01", "-", "0e", "[1,]",
                "{\"x\":1,}", "{\"x\":\"\\q\"}", "{\"x\":\"\\u000g\"}", "{\"x\":\"\n\"}", "{\"x\":\"unterminated}",
                new String(READY, StandardCharsets.UTF_8) + "{}", "[".repeat(20) + "0" + "]".repeat(20),
                "{\"code\":0,\"code\":0,\"data\":{\"status\":\"READY\",\"scope\":\"APPLICATION\"}}",
                "{\"other\":{\"code\":0,\"data\":{\"status\":\"READY\",\"scope\":\"APPLICATION\"}}}",
                "{\"code\":false,\"data\":{\"status\":\"READY\",\"scope\":\"APPLICATION\"}}",
                "{\"code\":\"0\",\"data\":{\"status\":\"READY\",\"scope\":\"APPLICATION\"}}",
                "{\"code\":0.0,\"data\":{\"status\":\"READY\",\"scope\":\"APPLICATION\"}}",
                "{\"code\":null,\"data\":{\"status\":\"READY\",\"scope\":\"APPLICATION\"}}",
                "{\"code\":1,\"data\":{\"status\":\"READY\",\"scope\":\"APPLICATION\"}}",
                "{\"code\":-0,\"data\":{\"status\":\"READY\",\"scope\":\"APPLICATION\"}}",
                "{\"code\":0,\"data\":{\"status\":\"READY\",\"scope\":\"DATABASE\"}}"
        );
        return Stream.concat(
                strings.map(TrendWakeTest::bytes),
                Stream.of(new byte[]{(byte) 0xff}, new byte[4097])
        );
    }

    @Test void singleSourceCliRunsWithOnlyJdkAndDisabledConfiguration() throws Exception {
        var output = classes.resolve("cli.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.language=en", "-Duser.country=US", "--source", "21", SOURCE.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        process.environment().put("TREND_WAKE_ENABLED", "false");
        process.environment().put("TREND_WAKE_BASE_URL", "https://fixture-private@invalid");
        var running = process.start();
        try {
            assertThat(running.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(running.exitValue()).isZero();
            assertThat(Files.readString(output)).contains("trend-wake: disabled")
                    .doesNotContain("fixture-private", "Spring", "curl", "Python");
        } finally { if (running.isAlive()) running.destroyForcibly(); }
    }

    @Test void workflowKeepsDefaultOffSecurityGuardrailsAndBoundedJdkOnlyInvocation() throws IOException {
        String text = Files.readString(Path.of("../.github/workflows/trend-wake.yml"));
        assertThat(text).contains("contents: read",
                "vars.TREND_WAKE_ENABLED == 'true' && github.ref_name == github.event.repository.default_branch",
                "persist-credentials: false", "timeout-minutes: 5", "cancel-in-progress: false", "java-version: '21'",
                "run: java --source 21 polyhouse/scripts/com/polyethene/polyhouse/ops/trend/TrendWake.java")
                .doesNotContain("secrets.", "--allow-local", "id-token:", "pages:", "mvn ");
    }

    private static int probe(Peer peer, List<String> logs) throws Exception {
        return run(env(peer.origin()), fixed(NOW), Duration.ofSeconds(2), Duration.ZERO, Duration.ofSeconds(8), logs::add, true);
    }
    private static int run(Map<String, String> env, Clock clock, Duration timeout, Duration backoff,
                           Duration total, Consumer<String> log, boolean local) throws Exception {
        return (Integer) invoke(execute, env, local, clock, timeout, backoff, total, log);
    }
    private static Object invoke(Method method, Object... args) throws Exception {
        try { return method.invoke(null, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw new AssertionError(failure.getCause());
        }
    }
    private static Map<String, String> env(String origin) {
        return Map.of("TREND_WAKE_ENABLED", "true", "TREND_WAKE_BASE_URL", origin,
                "DEEPSEEK_API_KEY", "fixture-private-key", "OWNER_TOKEN", "fixture-private-token");
    }
    private static Clock fixed(Instant now) { return Clock.fixed(now, ZoneOffset.UTC); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private record Response(int status, byte[] body, boolean chunked, boolean blockBeforeHeader,
                            boolean blockDuringBody, String location) {
        static Response immediate(int status, byte[] body) {
            return new Response(status, body, false, false, false, null);
        }
    }

    private static final class Peer implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<String> paths = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<String> authorization = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<String> cookies = java.util.Collections.synchronizedList(new ArrayList<>());
        private final java.util.concurrent.atomic.AtomicInteger disconnected = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger startedBodies = new java.util.concurrent.atomic.AtomicInteger();

        Peer(List<Response> responses) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                int index;
                synchronized (paths) { index = paths.size(); paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI()); }
                authorization.add(exchange.getRequestHeaders().getFirst("Authorization") == null ? "" : exchange.getRequestHeaders().getFirst("Authorization"));
                cookies.add(exchange.getRequestHeaders().getFirst("Cookie") == null ? "" : exchange.getRequestHeaders().getFirst("Cookie"));
                Response response = responses.get(Math.min(index, responses.size() - 1));
                serve(exchange, response);
            });
            server.start();
        }
        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        private void serve(HttpExchange exchange, Response response) {
            try (exchange) {
                if (response.blockBeforeHeader()) release.await(5, TimeUnit.SECONDS);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (response.location() != null) exchange.getResponseHeaders().set("Location", response.location());
                exchange.sendResponseHeaders(response.status(), response.chunked() ? 0 : response.body().length);
                int first = response.blockDuringBody() ? 1 : response.body().length;
                exchange.getResponseBody().write(response.body(), 0, first);
                exchange.getResponseBody().flush();
                if (response.blockDuringBody()) {
                    startedBodies.incrementAndGet();
                    release.await(5, TimeUnit.SECONDS);
                }
                exchange.getResponseBody().write(response.body(), first, response.body().length - first);
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            catch (IOException failure) { disconnected.incrementAndGet(); }
        }
        /** 释放门闩并关闭随机端口和测试线程，超时测试不会留下后台请求。 */
        @Override public void close() { release.countDown(); server.stop(0); workers.shutdownNow(); }
    }
}

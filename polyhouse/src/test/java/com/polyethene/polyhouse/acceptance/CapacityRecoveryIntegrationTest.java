package com.polyethene.polyhouse.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.PolyhouseApplication;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.service.impl.LocalAvatarStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式开启的有限负载与真实连接故障验收；只写随机临时库，不停本机数据库或 Redis。 */
@EnabledIfSystemProperty(named = "polyhouse.capacity.integration", matches = "true")
class CapacityRecoveryIntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(CapacityRecoveryIntegrationTest.class);
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private String base;

    @Test
    void acceptsBoundedLoadAndRecoversWithoutRestartingApplication() throws Exception {
        try (Sandbox sandbox = new Sandbox()) {
            try (var context = new SpringApplicationBuilder(PolyhouseApplication.class)
                    .initializers(application -> application.getEnvironment().getPropertySources()
                            .addFirst(new MapPropertySource("capacity-isolation", sandbox.properties())))
                    .run("--logging.level.com.zaxxer.hikari=OFF", "--logging.level.io.lettuce=OFF")) {
                base = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                var jdbc = context.getBean(JdbcTemplate.class);
                var redis = context.getBean(StringRedisTemplate.class);
                String username = "cap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
                String password = UUID.randomUUID().toString();
                JsonNode registered = post("/api/auth/register", Map.of("username", username, "password", password,
                        "nickname", "隔离验收"), null, 200);
                long userId = registered.path("data").path("id").asLong();
                String sessionKey = RedisConstants.buildAuthTokenKey(userId, "PC");
                assertThat(redis.hasKey(sessionKey)).as("随机测试 ID 不能复用已有会话").isFalse();
                // Redis 与开发实例共用服务，但只触碰本次随机雪花 ID 的精确键，绝不 FLUSH/SCAN。
                try {
                    String token = post("/api/auth/login", Map.of("username", username, "password", password), null, 200)
                            .path("data").path("accessToken").asText();
                    assertThat(token.isBlank()).isFalse();
                    get("/api/auth/me", token, 200);
                    var durations = new ArrayList<Long>();
                    long started = System.nanoTime();
                    // 四条并行请求链各十次，只测当前开发规模，不把本机结果宣称为容量上限。
                    try (var pool = Executors.newFixedThreadPool(4)) {
                        var futures = new ArrayList<java.util.concurrent.Future<List<Long>>>();
                        for (int client = 0; client < 4; client++) futures.add(pool.submit(() -> {
                            var values = new ArrayList<Long>();
                            for (int attempt = 0; attempt < 10; attempt++) {
                                long tick = System.nanoTime();
                                JsonNode response = get(attempt % 2 == 0 ? "/api/auth/me" : "/api/auth/growth", token, 200);
                                assertThat(response.path("data").path("experience").asText()).isEqualTo("10");
                                values.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tick));
                            }
                            return values;
                        }));
                        for (var future : futures) durations.addAll(future.get(45, TimeUnit.SECONDS));
                    }
                    durations.sort(Long::compareTo);
                    LOGGER.info("CAPACITY requests=40 concurrency=4 elapsedMs={} p50Ms={} p95Ms={} maxMs={}",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), durations.get(19),
                            durations.get(37), durations.get(39));
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_growth_reward WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);

                    sandbox.redis.block(true);
                    try {
                        get("/api/auth/me", token, 503);
                        post("/api/auth/deregister", Map.of("token", token), null, 503);
                        assertThat(jdbc.queryForObject("SELECT deleted FROM sys_user WHERE id = ?", Integer.class, userId)).isZero();
                    } finally {
                        sandbox.redis.block(false);
                    }
                    awaitRecovery(token, "Redis");

                    sandbox.mysql.block(true);
                    try {
                        get("/api/auth/me", token, 500);
                    } finally {
                        sandbox.mysql.block(false);
                    }
                    awaitRecovery(token, "MySQL");
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_growth_reward WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);

                    // 将本次私有头像目录变成同名普通文件，模拟实际不可写路径，不修改磁盘全局权限。
                    Path pendingDirectory=sandbox.avatars.resolveSibling("avatars-pending");
                    Files.createFile(pendingDirectory);
                    upload(token, 503);
                    assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(ext_json, '$.avatarUpload') FROM sys_user WHERE id = ?", String.class, userId)).isNull();
                    Files.delete(pendingDirectory);
                    JsonNode avatar = upload(token, 200).path("data");
                    assertThat(avatar.path("remainingToday").asInt()).isEqualTo(4);
                    HttpResponse<String> media = request("GET", "/api/profile-submissions/"+avatar.path("id").asText()+"/image", token, null);
                    assertThat(media.statusCode()).isEqualTo(200);
                    assertThat(media.headers().firstValue("Cache-Control").orElse("")).contains("no-store");

                    // 真文件删除失败生成持久标记，新实例读取标记后能重试，模拟重启后的清理入口。
                    String previousName = "b".repeat(32) + ".jpg";
                    Files.createDirectories(sandbox.avatars);
                    Path previous = sandbox.avatars.resolve(previousName);
                    Files.createDirectory(previous);
                    Path blocker = Files.writeString(previous.resolve("owned-fixture"), "fixture");
                    var storage = context.getBean(LocalAvatarStorage.class);
                    storage.deleteEventually("/api/media/avatars/" + previousName);
                    Path marker = sandbox.avatars.resolve(".cleanup").resolve(previousName);
                    assertThat(Files.exists(marker)).isTrue();
                    Files.delete(blocker);
                    new LocalAvatarStorage(sandbox.avatars.toString()).retryCleanup();
                    assertThat(Files.exists(previous)).isFalse();
                    assertThat(Files.exists(marker)).isFalse();

                    post("/api/auth/logout", Map.of("token", token), null, 200);
                    get("/api/auth/me", token, 401);
                    assertThat(redis.hasKey(sessionKey)).isFalse();
                    LOGGER.info("RECOVERY redis/mysql reconnect, failed deregister rollback, avatar pending retry/private preview/cleanup, logout: passed");
                } finally {
                    sandbox.redis.block(false);
                    redis.delete(sessionKey);
                    assertThat(redis.hasKey(sessionKey)).isFalse();
                }
            }
        }
    }

    private void awaitRecovery(String token, String dependency) throws Exception {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (request("GET", "/api/auth/me", null, token).statusCode() == 200) {
                LOGGER.info("RECOVERY dependency={} elapsedMs={}", dependency,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(dependency + " did not recover within bounded time");
    }

    private JsonNode get(String path, String token, int status) throws Exception {
        return decoded(request("GET", path, null, token), status);
    }

    private JsonNode post(String path, Map<String, String> body, String token, int status) throws Exception {
        return decoded(request("POST", path, json.writeValueAsString(body), token), status);
    }

    private JsonNode decoded(HttpResponse<String> response, int status) throws Exception {
        // 不在断言消息中附带响应全文，防止登录失败时输出凭据或 Token。
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asInt()).isEqualTo(status == 200 ? 0 : status * 100);
        if (status != 200) assertThat(body.path("data").isNull()).isTrue();
        return body;
    }

    private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode upload(String token, int status) throws Exception {
        String boundary = "fixture" + UUID.randomUUID().toString().replace("-", "");
        var bytes = new ByteArrayOutputStream();
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        ImageIO.write(new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB), "PNG", bytes);
        bytes.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"requestId\"\r\n\r\n" + UUID.randomUUID() + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(base + "/api/auth/avatar/submissions")).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
        return decoded(http.send(request, HttpResponse.BodyHandlers.ofString()), status);
    }

    /** 只有本进程明确创建成功的随机库允许删除，迁移脚本仅在这个库中执行。 */
    private static final class Sandbox implements AutoCloseable {
        private final String name = "polyhouse_acceptance_" + UUID.randomUUID().toString().replace("-", "");
        private final String user = System.getenv("DB_USERNAME");
        private final String password = System.getenv("DB_PASSWORD");
        private final String direct;
        private final Path root = Path.of("target", name).toAbsolutePath().normalize();
        private final Path avatars = root.resolve("avatars");
        private FaultProxy mysql;
        private FaultProxy redis;
        private boolean created;

        private Sandbox() throws Exception {
            String configured = System.getenv("DB_URL");
            URI database = URI.create((configured == null ? "jdbc:mysql://localhost:3306/polyhouse_db" : configured).substring(5));
            if (!List.of("localhost", "127.0.0.1").contains(database.getHost()) || !"/polyhouse_db".equals(database.getPath()))
                throw new IllegalArgumentException("Only the configured local development server is allowed");
            int port = database.getPort() < 0 ? 3306 : database.getPort();
            direct = "jdbc:mysql://" + database.getHost() + ":" + port + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            try {
                try (Connection connection = DriverManager.getConnection(direct, user, password); var sql = connection.createStatement()) {
                    // 标识符完全由内部随机十六进制生成，绝不拼接用户名或外部输入。
                    sql.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
                    created = true;
                    connection.setCatalog(name);
                    for (String migration : List.of("001_init.sql", "002_user_growth.sql", "003_daily_growth_rewards.sql", "004_owner_bootstrap.sql", "005_account_security.sql", "006_account_provisioning.sql", "007_account_management.sql", "008_profile_moderation.sql")) {
                        String source = Files.readString(Path.of("sql", migration));
                        if (migration.startsWith("001")) source = source.substring(source.indexOf("CREATE TABLE `"));
                        source = source.replace("USE `polyhouse_db`;", "");
                        ScriptUtils.executeSqlScript(connection, new ByteArrayResource(source.getBytes(StandardCharsets.UTF_8)));
                    }
                }
                String host = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
                if (!List.of("localhost", "127.0.0.1", "::1").contains(host)) throw new IllegalArgumentException("Only local Redis is allowed");
                mysql = new FaultProxy(database.getHost(), port);
                redis = new FaultProxy(host, Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
                Files.createDirectories(root);
            } catch (Exception exception) {
                try { close(); } catch (Exception cleanup) { exception.addSuppressed(cleanup); }
                throw exception;
            }
        }

        private Map<String, Object> properties() {
            return Map.ofEntries(Map.entry("server.port", 0),
                    Map.entry("spring.datasource.url", "jdbc:mysql://127.0.0.1:" + mysql.port() + "/" + name
                            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=1000&socketTimeout=1500"),
                    Map.entry("spring.datasource.hikari.maximum-pool-size", 4), Map.entry("spring.datasource.hikari.minimum-idle", 1),
                    Map.entry("spring.datasource.hikari.connection-timeout", 1000), Map.entry("spring.datasource.hikari.validation-timeout", 500),
                    Map.entry("spring.data.redis.host", "127.0.0.1"), Map.entry("spring.data.redis.port", redis.port()),
                    Map.entry("spring.data.redis.timeout", "500ms"), Map.entry("spring.data.redis.connect-timeout", "500ms"),
                    Map.entry("polyhouse.avatar.directory", avatars.toString()));
        }

        /** 先关闭专用连接，再精确删除本次创建的库和 target 子目录；已有开发库不在删除范围。 */
        @Override
        public void close() throws Exception {
            if (mysql != null) mysql.close();
            if (redis != null) redis.close();
            if (created && name.matches("polyhouse_acceptance_[0-9a-f]{32}")) {
                try (Connection connection = DriverManager.getConnection(direct, user, password); var sql = connection.createStatement()) {
                    sql.execute("DROP DATABASE `" + name + "`");
                    created = false;
                    try (var result = sql.executeQuery("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '" + name + "'")) {
                        assertThat(result.next()).isFalse();
                    }
                }
            }
            if (!root.startsWith(Path.of("target").toAbsolutePath().normalize())) throw new IllegalStateException("Invalid cleanup root");
            if (Files.exists(root)) try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            LOGGER.info("ISOLATION temporary schema/files removed, development database untouched");
        }
    }

    /** 只切断测试实例的本机转发连接，保持真实服务及其他客户端连续运行。 */
    private static final class FaultProxy implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        private final java.util.concurrent.ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean blocked = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private FaultProxy(String host, int port) throws IOException {
            pool.submit(() -> {
                while (!closed.get()) {
                    try {
                        Socket client = listener.accept();
                        sockets.add(client);
                        pool.submit(() -> bridge(client, host, port));
                    } catch (IOException exception) {
                        if (!closed.get()) LOGGER.warn("Fault proxy accept failed: type={}", exception.getClass().getSimpleName());
                    }
                }
            });
        }

        private int port() { return listener.getLocalPort(); }

        private void block(boolean value) {
            blocked.set(value);
            if (value) sockets.forEach(this::release);
        }

        private void bridge(Socket client, String host, int port) {
            Socket upstream = new Socket();
            sockets.add(upstream);
            try {
                if (blocked.get() || closed.get()) return;
                upstream.connect(new InetSocketAddress(host, port), 500);
                if (blocked.get() || closed.get()) return;
                pool.submit(() -> copy(upstream, client));
                copy(client, upstream);
            } catch (IOException exception) {
                LOGGER.debug("Fault proxy connection ended: type={}", exception.getClass().getSimpleName());
            } finally {
                release(client);
                release(upstream);
            }
        }

        private void copy(Socket from, Socket to) {
            try {
                from.getInputStream().transferTo(to.getOutputStream());
            } catch (IOException exception) {
                LOGGER.debug("Fault proxy stream ended: type={}", exception.getClass().getSimpleName());
            } finally {
                release(from);
                release(to);
            }
        }

        private void release(Socket socket) {
            try { socket.close(); }
            catch (IOException exception) { LOGGER.debug("Fault proxy close failed: type={}", exception.getClass().getSimpleName()); }
            sockets.remove(socket);
        }

        /** 关闭本测试创建的监听和转发套接字，阻塞的复制线程随套接字一起退出。 */
        @Override
        public void close() throws IOException {
            closed.set(true);
            listener.close();
            sockets.forEach(this::release);
            pool.shutdownNow();
        }
    }
}

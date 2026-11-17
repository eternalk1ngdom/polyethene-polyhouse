package com.polyethene.polyhouse.trend;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** G4专属环境：随机库用于免费集成，持久验收库保留真实费用，Redis必须是空的临时实例。 */
final class TrendAcceptanceSandbox implements AutoCloseable {
    private static final String LIVE_NAME = "polyhouse_trend_live_acceptance";
    private static final String MARKER = "polyhouse-trend-live-acceptance-v1";
    private final String name;
    private final String direct;
    private final String user = System.getenv("DB_USERNAME");
    private final String password = System.getenv("DB_PASSWORD");
    private final boolean live;
    private final int redisPort;
    final String url;

    TrendAcceptanceSandbox(boolean live) throws Exception {
        this.live = live;
        redisPort = Integer.parseInt(System.getProperty("polyhouse.trend.http.redis.port", "0"));
        if (redisPort < 1024 || redisPort > 65535 || redisPort == 6379) {
            throw new IllegalArgumentException("G4必须使用显式临时Redis端口，禁止使用正式6379端口");
        }
        requireEmptyRedis();
        String configured = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://localhost:3306/polyhouse_db");
        if (!configured.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("G4仅允许本机MySQL");
        URI parsed = URI.create(configured.substring(5));
        if (!List.of("localhost", "127.0.0.1").contains(parsed.getHost()) || !"/polyhouse_db".equals(parsed.getPath())
                || parsed.getUserInfo() != null || user == null || password == null) {
            throw new IllegalArgumentException("G4仅从既有本机预设连接服务器，不迁移开发库");
        }
        direct = "jdbc:mysql://" + parsed.getHost() + ":" + (parsed.getPort() < 0 ? 3306 : parsed.getPort())
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        name = live ? LIVE_NAME : "polyhouse_trend_http_" + UUID.randomUUID().toString().replace("-", "");
        url = direct.replace("/?", "/" + name + "?");
        boolean created = false;
        try (var connection = DriverManager.getConnection(direct, user, password)) {
            boolean exists;
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?")) {
                query.setString(1, name);
                try (var rows = query.executeQuery()) { rows.next(); exists = rows.getInt(1) != 0; }
            }
            if (exists && !live) throw new IllegalStateException("随机库意外重名，拒绝复用");
            if (!exists) {
                try (var command = connection.createStatement()) { command.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4"); }
                created = true;
                connection.setCatalog(name);
                for (String file : List.of("001_schema.sql", "010_trend_reports.sql", "011_trend_ai.sql", "012_trend_jobs.sql",
                        "013_trend_ai_resolution.sql", "014_trend_schedule.sql")) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource("sql/" + file), StandardCharsets.UTF_8));
                }
                if (live) {
                    try (var command = connection.createStatement()) {
                        command.execute("CREATE TABLE sys_trend_acceptance_marker (slot INT PRIMARY KEY, purpose VARCHAR(64) NOT NULL)");
                    }
                    try (var insert = connection.prepareStatement("INSERT INTO sys_trend_acceptance_marker VALUES (1,?)")) {
                        insert.setString(1, MARKER); insert.executeUpdate();
                    }
                }
            } else {
                connection.setCatalog(name);
                try (var query = connection.prepareStatement("SELECT purpose FROM sys_trend_acceptance_marker WHERE slot=1");
                     var rows = query.executeQuery()) {
                    if (!rows.next() || !MARKER.equals(rows.getString(1))) throw new IllegalStateException("验收库归属不匹配，拒绝连接");
                }
                try (var query = connection.prepareStatement("SELECT (SELECT COUNT(*) FROM tool_trend_job WHERE state IN ('QUEUED','RUNNING'))"
                        + "+(SELECT COUNT(*) FROM tool_trend_ai_request WHERE state IN ('RESERVED','UNKNOWN'))"); var rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) != 0) throw new IllegalStateException("上次真实验收仍有活动任务或待核对费用，禁止自动续跑/换号重试");
                }
            }
        } catch (Exception failure) {
            // 仅初始化尚无外部调用的新库可回收；已存在的真实费用库永不自动删除。
            if (created) {
                try { drop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    void configure(DynamicPropertyRegistry properties) {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        String signing = Base64.getEncoder().encodeToString(key);
        properties.add("server.address", () -> "127.0.0.1");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        properties.add("spring.datasource.hikari.minimum-idle", () -> 1);
        properties.add("spring.data.redis.host", () -> "127.0.0.1");
        properties.add("spring.data.redis.port", () -> redisPort);
        properties.add("spring.data.redis.database", () -> 0);
        properties.add("spring.data.redis.username", () -> "");
        properties.add("spring.data.redis.password", () -> "");
        properties.add("polyhouse.jwt.secret", () -> signing);
        properties.add("polyhouse.trend.enabled", () -> true);
        properties.add("polyhouse.trend.jobs.enabled", () -> true);
        properties.add("polyhouse.trend.ai.enabled", () -> true);
        properties.add("polyhouse.trend.ai.api-key", () -> live ? System.getenv("POLYHOUSE_DEEPSEEK_API_KEY") : TrendAiFixture.KEY);
        properties.add("polyhouse.trend.ai.monthly-budget-micros", () -> live ? 100_000L : 10_000_000L);
        properties.add("polyhouse.trend.schedule.enabled", () -> false);
        properties.add("polyhouse.trend.cache.enabled", () -> true);
    }

    private void requireEmptyRedis() throws Exception {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", redisPort), 2000);
            socket.setSoTimeout(2000);
            socket.getOutputStream().write("PING\r\nDBSIZE\r\n".getBytes(StandardCharsets.US_ASCII));
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            if (!"+PONG".equals(reader.readLine()) || !":0".equals(reader.readLine())) {
                throw new IllegalStateException("G4仅允许空的临时Redis，禁止连接已有会话或内容数据");
            }
        }
    }

    private void drop() throws Exception {
        // 库标识符不能参数绑定；这里只允许固定内部前缀和随机十六进制/固定验收库名。
        if (!name.matches("polyhouse_trend_http_[0-9a-f]{32}") && !LIVE_NAME.equals(name)) throw new IllegalStateException("隔离库名不合法");
        try (Connection connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
            command.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }
    }

    /** 免费随机库回收；真实验收库保留账本、未知费用及幂等事实，重跑不能获得新预算。 */
    @Override public void close() throws Exception { if (!live) drop(); }
}

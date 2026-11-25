package com.polyethene.polyhouse.user.fixture;

import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 供 user 域 L2 集成测试共用的本机随机隔离 MySQL 沙箱。
 * 严格限定仅连接本机开发实例与本地 Redis，按随机十六进制后缀建库并在退出时按前缀正则校验后删库，绝不触碰 polyhouse_db 正式屋主数据。
 */
public final class IsolatedMysqlSandbox implements AutoCloseable {

    private final String prefix;
    private final String name;
    private final String user;
    private final String password;
    private final String direct;
    private final String url;
    private final String tempDirPrefix;
    private final Path tempDir;

    private IsolatedMysqlSandbox(String prefix, boolean requireLocalRedis, String tempDirPrefix,
                                 List<String> extraSqlScripts) throws Exception {
        if (prefix == null || !prefix.matches("[a-z0-9_]{2,24}")) {
            throw new IllegalArgumentException("Invalid sandbox prefix");
        }
        if (tempDirPrefix != null && !tempDirPrefix.matches("[a-z0-9_-]{2,16}")) {
            throw new IllegalArgumentException("Invalid temp directory prefix");
        }
        this.prefix = prefix;
        this.name = "polyhouse_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        this.user = System.getenv("DB_USERNAME");
        this.password = System.getenv("DB_PASSWORD");
        this.tempDirPrefix = tempDirPrefix;
        this.tempDir = tempDirPrefix == null
                ? null
                : Path.of("target").toAbsolutePath().resolve(tempDirPrefix + "-" + UUID.randomUUID());

        String configured = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://localhost:3306/polyhouse_db");
        URI parsed = URI.create(configured.substring(5));
        if (!List.of("localhost", "127.0.0.1").contains(parsed.getHost()) || !"/polyhouse_db".equals(parsed.getPath())) {
            throw new IllegalArgumentException("Only local development server is allowed");
        }
        if (requireLocalRedis) {
            String redisHost = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
            if (!List.of("localhost", "127.0.0.1", "::1").contains(redisHost)) {
                throw new IllegalArgumentException("Only local Redis is allowed");
            }
        }
        if (tempDir != null) {
            Files.createDirectories(tempDir);
        }

        this.direct = "jdbc:mysql://" + parsed.getHost() + ":" + (parsed.getPort() < 0 ? 3306 : parsed.getPort())
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        this.url = direct.replace("/?", "/" + name + "?");

        try (Connection connection = DriverManager.getConnection(direct, user, password);
             var command = connection.createStatement()) {
            // JDBC 不支持参数化库名；名称由白名单前缀与随机 UUID 十六进制组成，删库时再次按正则核验。
            command.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            connection.setCatalog(name);
            try {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource("sql/001_schema.sql"), StandardCharsets.UTF_8));
                for (String script : extraSqlScripts) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource(script), StandardCharsets.UTF_8));
                }
            } catch (Exception error) {
                try {
                    close();
                } catch (Exception cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
        }
    }

    /** 创建仅加载基础 001 表结构且要求本地 Redis 的随机隔离库。 */
    public static IsolatedMysqlSandbox create(String prefix) throws Exception {
        return new IsolatedMysqlSandbox(prefix, true, null, List.of());
    }

    /** 创建无需 Redis 的随机隔离库（供纯 MySQL Mapper 验收使用）。 */
    public static IsolatedMysqlSandbox createMysqlOnly(String prefix) throws Exception {
        return new IsolatedMysqlSandbox(prefix, false, null, List.of());
    }

    /** 创建带附加迁移脚本与 target 下随机临时目录的隔离沙箱（供资料审核与卡面集成验收使用）。 */
    public static IsolatedMysqlSandbox create(String prefix, String tempDirPrefix, String... extraSqlScripts) throws Exception {
        return new IsolatedMysqlSandbox(prefix, true, tempDirPrefix, List.of(extraSqlScripts));
    }

    /** 返回当前随机隔离库的完整 JDBC 连接地址。 */
    public String url() {
        return url;
    }

    /** 以当前隔离库凭据直接打开独立 JDBC 连接，供迁库脚本或 DDL 验证使用。 */
    public Connection openConnection() throws Exception {
        return DriverManager.getConnection(url, user, password);
    }

    /** 返回 target 下创建的临时工作目录；未请求时返回 null。 */
    public Path tempDir() {
        return tempDir;
    }

    /** 将 Spring 数据源切换到本随机隔离库并限制连接池规模。 */
    public void registerDatasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        properties.add("spring.datasource.hikari.minimum-idle", () -> 1);
    }

    /** 在切换数据源的同时注入临时随机 HS256 密钥，避免依赖宿主机运行密钥。 */
    public void registerDatasourceAndEphemeralJwt(DynamicPropertyRegistry properties) {
        registerDatasource(properties);
        String temporaryKey = Base64.getEncoder().encodeToString(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded());
        properties.add("polyhouse.jwt.secret", () -> temporaryKey);
    }

    /** 只删除本次生成且格式严格匹配的隔离库与临时目录，绝不触碰已有开发数据。 */
    @Override
    public void close() throws Exception {
        if (!name.matches("polyhouse_" + prefix + "_[0-9a-f]{32}")) {
            throw new IllegalStateException("Invalid sandbox name");
        }
        try (Connection connection = DriverManager.getConnection(direct, user, password);
             var command = connection.createStatement()) {
            command.execute("DROP DATABASE IF EXISTS `" + name + "`");
        } finally {
            if (tempDir != null) {
                Path boundary = Path.of("target").toAbsolutePath().normalize();
                if (!tempDir.normalize().startsWith(boundary)
                        || !tempDir.getFileName().toString().startsWith(tempDirPrefix + "-")) {
                    throw new IllegalStateException("Unsafe fixture cleanup path");
                }
                if (Files.exists(tempDir)) {
                    try (var files = Files.walk(tempDir)) {
                        for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(path);
                        }
                    }
                }
            }
        }
    }
}

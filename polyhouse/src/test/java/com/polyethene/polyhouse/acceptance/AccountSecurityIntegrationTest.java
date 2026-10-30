package com.polyethene.polyhouse.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.AccountAuditMapper;
import com.polyethene.polyhouse.model.dto.LoginDTO;
import com.polyethene.polyhouse.model.dto.PasswordChangeDTO;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.service.AccountSecurityService;
import com.polyethene.polyhouse.service.AuthRedisStore;
import com.polyethene.polyhouse.service.AuthService;
import com.polyethene.polyhouse.utils.JwtUtils;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 在随机隔离 MySQL 库上验证真实 HTTP、提交/回滚与并发；只删除本测试创建的会话和随机库。 */
@EnabledIfSystemProperty(named = "polyhouse.security.integration", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountSecurityIntegrationTest {
    private static final String OLD = "P1-integration-old-fixture!";
    private static final String NEXT = "P1-integration-new-fixture!";
    private static Sandbox sandbox;
    @Autowired private AuthService auth;
    @Autowired private AccountSecurityService security;
    @Autowired private JwtProperties jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @SpyBean private AuthRedisStore sessions;
    @SpyBean private AccountAuditMapper audit;
    @LocalServerPort private int port;
    private final List<Long> ownedUsers = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry properties) throws Exception {
        sandbox = new Sandbox();
        String temporaryKey = Base64.getEncoder().encodeToString(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded());
        properties.add("spring.datasource.url", () -> sandbox.url);
        properties.add("polyhouse.jwt.secret", () -> temporaryKey);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        properties.add("spring.datasource.hikari.minimum-idle", () -> 1);
    }

    @AfterEach
    void removeOnlyOwnedSessionsAndRows() {
        reset(sessions, audit);
        for (long userId : ownedUsers) {
            redis.delete(List.of("PC", "H5", "PLUGIN").stream()
                    .map(client -> RedisConstants.buildAuthTokenKey(userId, client)).toList());
            jdbc.update("DELETE FROM sys_account_audit WHERE target_id=?", userId);
            jdbc.update("DELETE FROM sys_user WHERE id=?", userId);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_account_audit", Long.class)).isZero();
    }

    @AfterAll
    static void dropOnlyCreatedSandbox() throws Exception { if (sandbox != null) sandbox.close(); }

    @Test
    void shouldRepeatMigrationPreserveLegacyTokenThenPermanentlyInvalidateItOnChange() throws Exception {
        long id = fixture(false, UserRoleEnum.USER);
        String username = username(id);
        try (Connection connection = DriverManager.getConnection(sandbox.url, sandbox.user, sandbox.password)) {
            sandbox.migrate(connection, "005_account_security.sql");
            sandbox.migrate(connection, "005_account_security.sql");
        }
        assertThat(username(id)).isEqualTo(username);
        Instant now = Instant.now();
        String legacy = Jwts.builder().setHeaderParam(Header.TYPE, Header.JWT_TYPE).setIssuer("polyhouse")
                .setAudience("polyhouse-api").setSubject(Long.toString(id)).setId(UUID.randomUUID().toString())
                .setIssuedAt(Date.from(now)).setExpiration(Date.from(now.plusSeconds(3600)))
                .claim("tokenType", "ACCESS").claim("role", "USER").claim("clientType", "PC")
                .signWith(jwt.signingKey(), SignatureAlgorithm.HS256).compact();
        var claims = JwtUtils.parseAndValidate(legacy, jwt.signingKey(), Instant.now());
        sessions.saveSession(claims, Duration.ofMinutes(50));
        assertThat(call("GET", "/api/auth/me", legacy, null).statusCode()).isEqualTo(200);
        assertThat(change(legacy, OLD, NEXT).statusCode()).isEqualTo(200);
        assertThat(version(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_account_audit WHERE target_id=?", Long.class, id)).isEqualTo(1);
        // 模拟误恢复 Redis 或解封后旧白名单又存在，持久版本仍必须拒绝旧 Token。
        sessions.saveSession(claims, Duration.ofMinutes(50));
        assertThat(call("GET", "/api/auth/me", legacy, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/auth/growth", legacy, null).statusCode()).isEqualTo(401);
        assertThat(login(id, OLD, "PC").statusCode()).isEqualTo(401);
        assertThat(login(id, NEXT, "PC").statusCode()).isEqualTo(200);
    }

    @Test
    void shouldRestrictTemporaryCredentialsUntilPasswordIsChanged() throws Exception {
        long id = fixture(true, UserRoleEnum.ADMIN);
        var logged = login(id, OLD, "PC");
        assertThat(logged.statusCode()).isEqualTo(200);
        JsonNode data = json.readTree(logged.body()).get("data");
        assertThat(data.get("user").get("role").asText()).isEqualTo("ADMIN");
        assertThat(data.get("user").get("passwordChangeRequired").asBoolean()).isTrue();
        assertThat(data.get("user").get("experience").asText()).isEqualTo("0");
        String token = data.get("accessToken").asText();
        assertThat(call("GET", "/api/auth/me", token, null).statusCode()).isEqualTo(200);
        for (var response : List.of(call("GET", "/api/auth/growth", token, null), initialAvatar(token))) {
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(json.readTree(response.body()).get("code").asInt()).isEqualTo(40301);
        }
        assertThat(call("POST", "/api/auth/deregister", null, Map.of("token", token)).statusCode()).isEqualTo(403);
        String h5 = auth.login(new LoginDTO(username(id), OLD, "H5"), null).accessToken();
        assertThat(call("POST", "/api/auth/logout", null, Map.of("token", h5)).statusCode()).isEqualTo(200);
        assertThat(change(token, OLD, NEXT).statusCode()).isEqualTo(200);
        var normal = json.readTree(login(id, NEXT, "PC").body()).get("data");
        assertThat(normal.get("user").get("passwordChangeRequired").asBoolean()).isFalse();
        assertThat(normal.get("user").get("experience").asText()).isEqualTo("10");
    }

    @Test
    void shouldLeavePasswordAndAuditUntouchedForWrongCurrentOrUnchangedPassword() throws Exception {
        long id = fixture(false, UserRoleEnum.USER);
        String token = auth.login(new LoginDTO(username(id), OLD), null).accessToken();
        assertThat(change(token, "Wrong-fixture-password", NEXT).statusCode()).isEqualTo(400);
        assertThat(change(token, OLD, OLD).statusCode()).isEqualTo(400);
        assertThat(version(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_account_audit WHERE target_id=?", Long.class, id)).isZero();
        assertThat(call("GET", "/api/auth/me", token, null).statusCode()).isEqualTo(200);
    }

    @Test
    void shouldRollbackDatabaseAndAuditIfRedisCleanupFailsAfterDeletingSessions() {
        long id = fixture(false, UserRoleEnum.USER);
        String token = auth.login(new LoginDTO(username(id), OLD), null).accessToken();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }).when(sessions).removeAllSessions(id);
        assertCode(() -> security.changePassword(new PasswordChangeDTO(OLD, NEXT), token), ErrorCode.SERVICE_UNAVAILABLE);
        reset(sessions);
        assertThat(version(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_account_audit WHERE target_id=?", Long.class, id)).isZero();
        assertThat(redis.hasKey(RedisConstants.buildAuthTokenKey(id, "PC"))).isFalse();
        assertThat(auth.login(new LoginDTO(username(id), OLD), null).user().id()).isEqualTo(Long.toString(id));
        assertCode(() -> auth.login(new LoginDTO(username(id), NEXT), null), ErrorCode.UNAUTHORIZED);
    }

    @Test
    void shouldRollbackPasswordWhenAuditStorageFails() {
        long id = fixture(false, UserRoleEnum.USER);
        String token = auth.login(new LoginDTO(username(id), OLD), null).accessToken();
        doThrow(new DataAccessResourceFailureException("isolated-audit-fault"))
                .when(audit).recordPasswordChange(anyLong(), anyLong());
        assertCode(() -> security.changePassword(new PasswordChangeDTO(OLD, NEXT), token), ErrorCode.SERVICE_UNAVAILABLE);
        reset(audit);
        assertThat(version(id)).isZero();
        assertThat(auth.getCurrentUser(token).id()).isEqualTo(Long.toString(id));
        assertThat(auth.login(new LoginDTO(username(id), OLD), null).user().id()).isEqualTo(Long.toString(id));
        assertCode(() -> auth.login(new LoginDTO(username(id), NEXT), null), ErrorCode.UNAUTHORIZED);
    }

    @Test
    void shouldCommitOnlyOneOfTwoConcurrentPasswordChanges() throws Exception {
        long id = fixture(false, UserRoleEnum.USER);
        String token = auth.login(new LoginDTO(username(id), OLD), null).accessToken();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> attemptChange(start, token, NEXT));
            var second = pool.submit(() -> attemptChange(start, token, NEXT + "-other"));
            start.countDown();
            boolean wonFirst = first.get(15, TimeUnit.SECONDS);
            boolean wonSecond = second.get(15, TimeUnit.SECONDS);
            assertThat(wonFirst ^ wonSecond).isTrue();
            String accepted = wonFirst ? NEXT : NEXT + "-other";
            assertThat(auth.login(new LoginDTO(username(id), accepted), null).user().id()).isEqualTo(Long.toString(id));
        }
        assertThat(version(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_account_audit WHERE target_id=?", Long.class, id)).isEqualTo(1);
    }

    @Test
    void shouldRevokeAllClientsAndKeepAnotherAccountAndRoleUnchanged() {
        long id = fixture(false, UserRoleEnum.ADMIN);
        long other = fixture(false, UserRoleEnum.INSIDER);
        List<String> tokens = new ArrayList<>();
        for (String client : List.of("PC", "H5", "PLUGIN")) tokens.add(auth.login(new LoginDTO(username(id), OLD, client), null).accessToken());
        String unaffected = auth.login(new LoginDTO(username(other), OLD), null).accessToken();
        security.changePassword(new PasswordChangeDTO(OLD, NEXT), tokens.getFirst());
        tokens.forEach(token -> assertCode(() -> auth.getCurrentUser(token), ErrorCode.UNAUTHORIZED));
        assertThat(auth.getCurrentUser(unaffected).role()).isEqualTo(UserRoleEnum.INSIDER);
        assertThat(auth.login(new LoginDTO(username(id), NEXT), null).user().role()).isEqualTo(UserRoleEnum.ADMIN);
        assertThat(version(other)).isZero();
    }

    private boolean attemptChange(CountDownLatch start, String token, String next) throws Exception {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        try {
            security.changePassword(new PasswordChangeDTO(OLD, next), token);
            return true;
        } catch (BusinessException error) {
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
            return false;
        }
    }

    private long fixture(boolean required, UserRoleEnum role) {
        String name = "p1_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        var profile = auth.register(new RegisterDTO(name, OLD, "安全验收"));
        long id = Long.parseLong(profile.id());
        ownedUsers.add(id);
        jdbc.update("UPDATE sys_user SET password_change_required=?, role=? WHERE id=?", required, role.getCode(), id);
        return id;
    }

    private String username(long id) { return jdbc.queryForObject("SELECT username FROM sys_user WHERE id=?", String.class, id); }
    private long version(long id) { return jdbc.queryForObject("SELECT credential_version FROM sys_user WHERE id=?", Long.class, id); }
    private HttpResponse<String> login(long id, String password, String client) throws Exception {
        return call("POST", "/api/auth/login", null, Map.of("username", username(id), "password", password, "clientType", client));
    }
    private HttpResponse<String> change(String token, String current, String next) throws Exception {
        return call("POST", "/api/auth/password/change", token, Map.of("currentPassword", current, "newPassword", next));
    }
    private HttpResponse<String> initialAvatar(String token) throws Exception {
        // 使用实际上传协议才能进入已映射方法的认证拦截器；JSON 请求只会在路由阶段得到 415。
        String boundary = "p1-avatar-fixture";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\nfixture\r\n--" + boundary + "--\r\n";
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/avatar"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> call(String method, String path, String token, Map<String, String> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (method.equals("GET")) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }

    /** 只允许本机 MySQL，库名由内部随机十六进制构成；不会重放开发库迁移或创建 OWNER。 */
    private static final class Sandbox implements AutoCloseable {
        private final String name = "polyhouse_security_" + UUID.randomUUID().toString().replace("-", "");
        private final String user = System.getenv("DB_USERNAME");
        private final String password = System.getenv("DB_PASSWORD");
        private final String direct;
        private final String url;
        private Sandbox() throws Exception {
            String configured = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://localhost:3306/polyhouse_db");
            URI parsed = URI.create(configured.substring(5));
            if (!List.of("localhost", "127.0.0.1").contains(parsed.getHost()) || !"/polyhouse_db".equals(parsed.getPath())) {
                throw new IllegalArgumentException("Only local development server is allowed");
            }
            String redisHost = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
            if (!List.of("localhost", "127.0.0.1", "::1").contains(redisHost)) throw new IllegalArgumentException("Only local Redis is allowed");
            direct = "jdbc:mysql://" + parsed.getHost() + ":" + (parsed.getPort() < 0 ? 3306 : parsed.getPort())
                    + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            url = direct.replace("/?", "/" + name + "?");
            try (Connection connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                // JDBC 不支持绑定数据库标识符；名字不是用户输入，且删除时再次验证固定格式。
                command.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
                connection.setCatalog(name);
                try {
                    for (String migration : List.of("001_init.sql", "002_user_growth.sql", "003_daily_growth_rewards.sql", "004_owner_bootstrap.sql", "005_account_security.sql", "006_account_provisioning.sql", "007_account_management.sql", "008_profile_moderation.sql")) migrate(connection, migration);
                } catch (Exception error) {
                    try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                    throw error;
                }
            }
        }
        private void migrate(Connection connection, String migration) throws Exception {
            String source = Files.readString(Path.of("sql", migration));
            if (migration.startsWith("001")) source = source.substring(source.indexOf("CREATE TABLE `"));
            source = source.replace("USE `polyhouse_db`;", "");
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(source.getBytes(StandardCharsets.UTF_8)));
        }
        /** 只删除本次生成且格式严格匹配的隔离库，不包含任何已有开发数据。 */
        @Override
        public void close() throws Exception {
            if (!name.matches("polyhouse_security_[0-9a-f]{32}")) throw new IllegalStateException("Invalid sandbox name");
            try (Connection connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                command.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }
    }
}

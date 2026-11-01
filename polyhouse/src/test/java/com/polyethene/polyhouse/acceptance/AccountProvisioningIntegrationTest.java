package com.polyethene.polyhouse.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.AccountAuditMapper;
import com.polyethene.polyhouse.model.dto.AccountCreateDTO;
import com.polyethene.polyhouse.model.dto.LoginDTO;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.service.AccountProvisioningService;
import com.polyethene.polyhouse.service.AuthService;
import com.polyethene.polyhouse.utils.PasswordUtils;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 真实 HTTP/MySQL/Redis 的开户闭环；随机隔离库内自建 OWNER，绝不在现有开发库授权或清理。 */
@EnabledIfSystemProperty(named = "polyhouse.provisioning.integration", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountProvisioningIntegrationTest {
    private static final String OWNER_PASSWORD = "P2-isolated-owner-fixture!";
    private static Sandbox sandbox;
    @Autowired private AuthService auth;
    @Autowired private AccountProvisioningService provisioning;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @SpyBean private AccountAuditMapper audit;
    @LocalServerPort private int port;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private long ownerId;
    private String ownerToken;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) throws Exception {
        sandbox = new Sandbox();
        String key = Base64.getEncoder().encodeToString(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded());
        properties.add("spring.datasource.url", () -> sandbox.url);
        properties.add("polyhouse.jwt.secret", () -> key);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        properties.add("spring.datasource.hikari.minimum-idle", () -> 1);
    }

    @BeforeEach void isolatedOwner() {
        var profile = auth.register(new RegisterDTO("p2_owner", OWNER_PASSWORD, "隔离测试屋主"));
        ownerId = Long.parseLong(profile.id());
        jdbc.update("UPDATE sys_user SET role='OWNER' WHERE id=?", ownerId);
        ownerToken = auth.login(new LoginDTO("p2_owner", OWNER_PASSWORD), null).accessToken();
    }

    @AfterEach void cleanupOnlySandbox() {
        reset(audit);
        for (long id : jdbc.queryForList("SELECT id FROM sys_user", Long.class)) {
            redis.delete(List.of("PC", "H5", "PLUGIN").stream().map(client -> RedisConstants.buildAuthTokenKey(id, client)).toList());
        }
        for (String table : List.of("sys_account_audit", "sys_account_creation", "sys_user_growth_reward", "sys_user")) {
            // 表名来自固定测试白名单，不接受用户输入；仅作用于本测试随机数据库。
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.update("UPDATE sys_account_sequence SET next_value=1 WHERE slot=1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class)).isZero();
    }
    @AfterAll static void dropSandbox() throws Exception { if (sandbox != null) sandbox.close(); }

    @Test void publicRegistrationAndAllNonOwnerRolesCannotCreate() throws Exception {
        assertThat(call("POST", "/api/auth/register", null, Map.of("username", "rejected", "password", OWNER_PASSWORD, "nickname", "测试")).statusCode()).isEqualTo(403);
        assertThat(createHttp(null, UUID.randomUUID().toString(), "USER", null).statusCode()).isEqualTo(401);
        for (UserRoleEnum role : List.of(UserRoleEnum.USER, UserRoleEnum.ADMIN, UserRoleEnum.INSIDER)) {
            jdbc.update("UPDATE sys_user SET role=? WHERE id=?", role.getCode(), ownerId);
            assertThat(createHttp(ownerToken, UUID.randomUUID().toString(), "USER", null).statusCode()).isEqualTo(403);
            assertCode(() -> provisioning.create(request(UserRoleEnum.USER), ownerToken), ErrorCode.FORBIDDEN);
        }
        assertThat(count("sys_user")).isEqualTo(1);
        assertThat(count("sys_account_creation")).isZero();
    }

    @Test void onlyAllowedRolesAndCorrectAdminConfirmationCanCreate() throws Exception {
        for (String role : List.of("OWNER", "INSIDER", "UNKNOWN")) {
            assertThat(createHttp(ownerToken, UUID.randomUUID().toString(), role, OWNER_PASSWORD).statusCode()).isIn(400, 403);
        }
        assertThat(createHttp(ownerToken, UUID.randomUUID().toString(), "ADMIN", null).statusCode()).isEqualTo(400);
        assertThat(createHttp(ownerToken, UUID.randomUUID().toString(), "ADMIN", "wrong-fixture").statusCode()).isEqualTo(400);
        assertThat(sequence()).isEqualTo(1);
        var response = createHttp(ownerToken, UUID.randomUUID().toString(), "ADMIN", OWNER_PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var result = data(response);
        assertThat(result.get("role").asText()).isEqualTo("ADMIN");
        assertThat(result.get("initialPassword").asText()).matches("[A-Za-z2-9]{20}");
        assertThat(result.has("passwordHash")).isFalse();
    }

    @Test void concurrentIdenticalRequestsDeliverPasswordOnceAndRoleCannotBeChanged() throws Exception {
        String requestId = UUID.randomUUID().toString();
        var request = new AccountCreateDTO(requestId, UserRoleEnum.USER, null);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return provisioning.create(request, ownerToken); });
            var b = pool.submit(() -> { start.await(); return provisioning.create(request, ownerToken); });
            start.countDown();
            var first = a.get(20, TimeUnit.SECONDS); var second = b.get(20, TimeUnit.SECONDS);
            assertThat(first.id()).isEqualTo(second.id());
            assertThat(first.firstDelivery() ^ second.firstDelivery()).isTrue();
            assertThat(first.firstDelivery() ? second.initialPassword() : first.initialPassword()).isNull();
        }
        assertThat(count("sys_user")).isEqualTo(2);
        assertThat(count("sys_account_creation")).isEqualTo(1);
        assertThat(count("sys_account_audit")).isEqualTo(1);
        assertThat(sequence()).isEqualTo(2);
        assertThat(createHttp(ownerToken, requestId, "ADMIN", OWNER_PASSWORD).statusCode()).isEqualTo(409);
        var lookup = call("GET", "/api/manage/accounts/creation?requestId=" + requestId, ownerToken, null);
        assertThat(lookup.statusCode()).isEqualTo(200);
        assertThat(data(lookup).get("initialPassword").isNull()).isTrue();
        assertThat(data(lookup).get("firstDelivery").asBoolean()).isFalse();
        assertThat(call("GET", "/api/manage/accounts/creation?requestId=" + UUID.randomUUID(), ownerToken, null).statusCode()).isEqualTo(404);
    }

    @Test void distinctConcurrentRequestsUseUniqueNumbersAndIndependentCredentials() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<com.polyethene.polyhouse.model.vo.AccountCreationVO>> tasks = new ArrayList<>();
            for (int index = 0; index < 8; index++) tasks.add(pool.submit(() -> provisioning.create(request(UserRoleEnum.USER), ownerToken)));
            Set<String> names = new HashSet<>(), passwords = new HashSet<>();
            for (var task : tasks) {
                var created = task.get(30, TimeUnit.SECONDS);
                names.add(created.username()); passwords.add(created.initialPassword());
                String hash = jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, created.id());
                assertThat(PasswordUtils.matches(created.initialPassword(), hash)).isTrue();
                assertThat(jdbc.queryForObject("SELECT password_change_required FROM sys_user WHERE id=?", Integer.class, created.id())).isEqualTo(1);
            }
            assertThat(names).hasSize(8).contains("ph000001", "ph000008");
            assertThat(passwords).hasSize(8);
        }
        assertThat(sequence()).isEqualTo(9);
    }

    @Test void auditFailureRollsBackNumberUserAndRequestThenSameIntentCanRetry() {
        var request = request(UserRoleEnum.USER);
        doThrow(new DataAccessResourceFailureException("isolated-audit-fault")).when(audit).recordCreation(anyLong(), anyLong());
        assertCode(() -> provisioning.create(request, ownerToken), ErrorCode.SERVICE_UNAVAILABLE);
        reset(audit);
        assertThat(sequence()).isEqualTo(1);
        assertThat(count("sys_user")).isEqualTo(1);
        assertThat(count("sys_account_creation")).isZero();
        assertThat(provisioning.create(request, ownerToken).username()).isEqualTo("ph000001");
    }

    @Test void migrationRetainsLegacyNamesAndSkipsDeletedNamesWithoutTruncatingDigits() throws Exception {
        var legacy = auth.register(new RegisterDTO("ph000123", OWNER_PASSWORD, "历史账号"));
        jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=?", legacy.id());
        jdbc.update("DELETE FROM sys_account_sequence WHERE slot=1");
        try (Connection connection = DriverManager.getConnection(sandbox.url, sandbox.user, sandbox.password)) {
            sandbox.migrate(connection, "006_account_provisioning.sql");
            assertThat(sequence()).isEqualTo(124);
            provisioning.create(request(UserRoleEnum.USER), ownerToken);
            sandbox.migrate(connection, "006_account_provisioning.sql");
            assertThat(sequence()).isEqualTo(125);
        }
        // 迁移后独立导入的历史名也不能复用，数据库唯一索引覆盖软删除。
        var collided = auth.register(new RegisterDTO("ph000125", OWNER_PASSWORD, "保留原名"));
        jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=?", collided.id());
        assertThat(provisioning.create(request(UserRoleEnum.USER), ownerToken).username()).isEqualTo("ph000126");
        jdbc.update("UPDATE sys_account_sequence SET next_value=999999 WHERE slot=1");
        assertThat(provisioning.create(request(UserRoleEnum.USER), ownerToken).username()).isEqualTo("ph999999");
        assertThat(provisioning.create(request(UserRoleEnum.USER), ownerToken).username()).isEqualTo("ph1000000");
        assertThat(jdbc.queryForObject("SELECT nickname FROM sys_user WHERE id=?", String.class, collided.id())).isEqualTo("保留原名");
    }

    @Test void staleRoleVersionSessionAndFirstCredentialCannotBypassServiceLock() {
        jdbc.update("UPDATE sys_user SET credential_version=credential_version+1 WHERE id=?", ownerId);
        assertCode(() -> provisioning.create(request(UserRoleEnum.USER), ownerToken), ErrorCode.UNAUTHORIZED);
        jdbc.update("UPDATE sys_user SET credential_version=0, password_change_required=1 WHERE id=?", ownerId);
        assertCode(() -> provisioning.create(request(UserRoleEnum.USER), ownerToken), ErrorCode.PASSWORD_CHANGE_REQUIRED);
        jdbc.update("UPDATE sys_user SET password_change_required=0 WHERE id=?", ownerId);
        auth.logout(ownerToken);
        assertCode(() -> provisioning.create(request(UserRoleEnum.USER), ownerToken), ErrorCode.UNAUTHORIZED);
        assertThat(sequence()).isEqualTo(1);
    }

    @Test void newlyIssuedUserMustChangePasswordBeforeNormalUseAndDailyReward() throws Exception {
        var created = provisioning.create(request(UserRoleEnum.USER), ownerToken);
        List<String> tokens = new ArrayList<>();
        for (String client : List.of("PC", "H5", "PLUGIN")) {
            var login = auth.login(new LoginDTO(created.username(), created.initialPassword(), client), null);
            assertThat(login.user().passwordChangeRequired()).isTrue();
            assertThat(login.user().experience()).isEqualTo("0");
            tokens.add(login.accessToken());
        }
        assertThat(call("GET", "/api/auth/growth", tokens.getFirst(), null).statusCode()).isEqualTo(403);
        assertThat(createHttp(tokens.getFirst(), UUID.randomUUID().toString(), "USER", null).statusCode()).isEqualTo(403);
        String next = "P2-personal-next-fixture!";
        assertThat(call("POST", "/api/auth/password/change", tokens.getFirst(), Map.of("currentPassword", created.initialPassword(), "newPassword", next)).statusCode()).isEqualTo(200);
        for (String token : tokens) assertThat(call("GET", "/api/auth/me", token, null).statusCode()).isEqualTo(401);
        assertCode(() -> auth.login(new LoginDTO(created.username(), created.initialPassword()), null), ErrorCode.UNAUTHORIZED);
        var login = auth.login(new LoginDTO(created.username(), next), null);
        assertThat(login.user().passwordChangeRequired()).isFalse();
        assertThat(login.user().experience()).isEqualTo("10");
        assertThat(auth.getCurrentUser(login.accessToken()).experience()).isEqualTo("10");
        assertThat(auth.getCurrentUser(ownerToken).role()).isEqualTo(UserRoleEnum.OWNER);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POLYHOUSE_PROVISIONING_BROWSER", matches = "true")
    void shouldCompleteRealBrowserAccountHandoffInSandbox() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("node.exe", "scripts/verify-account-provisioning.cjs")
                .directory(Path.of("../polyhouse-web").toFile()).redirectErrorStream(true);
        Map<String, String> inherited = new HashMap<>(builder.environment());
        builder.environment().clear();
        // 浏览器子进程不继承真实数据库密码/JWT 预设，只接收操作系统路径和隔离测试凭据。
        for (String key : List.of("PATH", "SystemRoot", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)", "POLYHOUSE_PLAYWRIGHT_MODULE")) {
            inherited.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key)).findFirst()
                    .ifPresent(entry -> builder.environment().put(entry.getKey(), entry.getValue()));
        }
        builder.environment().put("POLYHOUSE_BROWSER_API_BASE_URL", "http://127.0.0.1:" + port);
        builder.environment().put("POLYHOUSE_BROWSER_OWNER_PASSWORD", OWNER_PASSWORD);
        Path output = Path.of("target/p2-browser-result.log");
        Process browser = builder.redirectOutput(output.toFile()).start();
        if (!browser.waitFor(90, TimeUnit.SECONDS)) { browser.destroyForcibly(); throw new IllegalStateException("Browser verification timed out"); }
        assertThat(browser.exitValue()).as("See masked local p2-browser-result.log").isZero();
        assertThat(count("sys_account_creation")).isEqualTo(2);
    }

    private AccountCreateDTO request(UserRoleEnum role) { return new AccountCreateDTO(UUID.randomUUID().toString(), role, role == UserRoleEnum.ADMIN ? OWNER_PASSWORD : null); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private long sequence() { return jdbc.queryForObject("SELECT next_value FROM sys_account_sequence WHERE slot=1", Long.class); }
    private JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).get("data"); }
    private HttpResponse<String> createHttp(String token, String requestId, String role, String password) throws Exception {
        Map<String, String> body = new HashMap<>(Map.of("requestId", requestId, "role", role));
        if (password != null) body.put("ownerPassword", password);
        return call("POST", "/api/manage/accounts", token, body);
    }
    private HttpResponse<String> call(String method, String path, String token, Map<String, String> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (method.equals("GET")) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
    }
    /** 只允许本机 MySQL，库名由内部随机十六进制构成；不会重放开发库迁移；OWNER 夹具只位于本测试随机库。 */
    private static final class Sandbox implements AutoCloseable {
        private final String name = "polyhouse_provisioning_" + UUID.randomUUID().toString().replace("-", "");
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
            if (!name.matches("polyhouse_provisioning_[0-9a-f]{32}")) throw new IllegalStateException("Invalid sandbox name");
            try (Connection connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                command.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }
    }
}

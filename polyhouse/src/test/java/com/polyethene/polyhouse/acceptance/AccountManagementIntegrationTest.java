package com.polyethene.polyhouse.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.AccountAuditMapper;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.vo.AccountOperationVO;
import com.polyethene.polyhouse.common.constant.AccountManagementAction;
import com.polyethene.polyhouse.mapper.AccountManagementMapper;
import com.polyethene.polyhouse.service.AccountManagementService;
import com.polyethene.polyhouse.service.AuthRedisStore;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 随机隔离库内验证管理权限、真实三端撤销、版本防重与故障回滚；绝不改已有开发账号。 */
@EnabledIfSystemProperty(named = "polyhouse.management.integration", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountManagementIntegrationTest {
    private static final String PASSWORD = "P3-isolated-fixture!";
    private static Sandbox sandbox;
    @Autowired private AuthService auth;
    @Autowired private AccountManagementService management;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @SpyBean private AccountManagementMapper mapper;
    @SpyBean private AuthRedisStore sessions;
    @LocalServerPort private int port;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private long owner, admin, user, insider;
    private String ownerToken, adminToken, userToken, insiderToken;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) throws Exception {
        sandbox = new Sandbox();
        String key = Base64.getEncoder().encodeToString(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded());
        p.add("spring.datasource.url", () -> sandbox.url); p.add("polyhouse.jwt.secret", () -> key);
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 4); p.add("spring.datasource.hikari.minimum-idle", () -> 1);
    }
    @BeforeEach void fixtures() {
        owner = create("p3_owner", UserRoleEnum.OWNER); admin = create("p3_admin", UserRoleEnum.ADMIN);
        user = create("p3_user", UserRoleEnum.USER); insider = create("p3_insider", UserRoleEnum.INSIDER);
        ownerToken = login("p3_owner", "PC"); adminToken = login("p3_admin", "PC");
        userToken = login("p3_user", "PC"); insiderToken = login("p3_insider", "PC");
    }
    @AfterEach void cleanupOnlySandbox() {
        reset(mapper, sessions);
        for (long id : jdbc.queryForList("SELECT id FROM sys_user", Long.class)) {
            redis.delete(List.of("PC", "H5", "PLUGIN").stream().map(client -> RedisConstants.buildAuthTokenKey(id, client)).toList());
        }
        for (String table : List.of("sys_account_management_operation", "sys_account_audit", "sys_account_creation", "sys_user_growth_reward", "sys_user")) {
            // 标识符来自固定内部白名单，隔离库名称在创建与删除时双重校验。
            jdbc.execute("DELETE FROM " + table);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class)).isZero();
    }
    @AfterAll static void drop() throws Exception { if (sandbox != null) sandbox.close(); }

    @Test void visibleScopePaginationAndEscapedPrefixAreEnforced() throws Exception {
        var ordinary = management.list(query(null, null, null), adminToken);
        assertThat(ordinary.total()).isEqualTo("1"); assertThat(ordinary.items()).extracting(row -> row.role()).containsExactly(UserRoleEnum.USER);
        var all = management.list(query(null, null, null), ownerToken);
        assertThat(all.total()).isEqualTo("4"); assertThat(all.items()).filteredOn(row -> row.id().equals(Long.toString(owner))).allMatch(row -> row.readOnly());
        create("p3xnoise", UserRoleEnum.USER);
        assertThat(management.list(query("p3_", UserRoleEnum.USER, null), ownerToken).total()).isEqualTo("1");
        assertThat(management.list(query("p3_", UserRoleEnum.ADMIN, 1), ownerToken).total()).isEqualTo("1");
        assertCode(() -> management.list(query(null, UserRoleEnum.ADMIN, null), adminToken), ErrorCode.FORBIDDEN);
        assertCode(() -> management.list(query(null, null, null), insiderToken), ErrorCode.FORBIDDEN);
        assertCode(() -> management.list(new AccountListDTO(1, 51, null, null, null), ownerToken), ErrorCode.INVALID_PARAMETER);
        var response = call("GET", "/api/manage/accounts?pageSize=1", ownerToken, null);
        assertThat(response.statusCode()).isEqualTo(200); assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(data(response).get("items")).hasSize(1);
        assertThat(response.body()).doesNotContain("passwordHash", "password_hash", "credentialVersion", "lastLoginIp", "extConfig");
    }
    @Test void permissionsCannotBeInferredFromWeightsOrClientRole() {
        for (String token : List.of(userToken, insiderToken)) assertCode(() -> act(user, request(user), AccountManagementAction.BAN, token), ErrorCode.FORBIDDEN);
        for (long target : List.of(owner, admin, insider)) assertCode(() -> act(target, request(target), AccountManagementAction.BAN, adminToken), ErrorCode.FORBIDDEN);
        assertCode(() -> act(owner, request(owner), AccountManagementAction.BAN, ownerToken), ErrorCode.FORBIDDEN);
        assertCode(() -> act(user, request(user), AccountManagementAction.PASSWORD_RESET, adminToken), ErrorCode.FORBIDDEN);
        jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=?", insider);
        assertCode(() -> act(insider, request(insider), AccountManagementAction.BAN, ownerToken), ErrorCode.NOT_FOUND);
        jdbc.update("UPDATE sys_user SET password_change_required=1 WHERE id=?", admin);
        assertCode(() -> act(user, request(user), AccountManagementAction.BAN, adminToken), ErrorCode.PASSWORD_CHANGE_REQUIRED);
        assertThat(count()).isZero();
    }
    @Test void changedActorRoleVersionOrRedisSessionCannotAuthorizeAWrite() {
        var req = request(user);
        jdbc.update("UPDATE sys_user SET role='INSIDER' WHERE id=?", admin);
        assertCode(() -> act(user, req, AccountManagementAction.BAN, adminToken), ErrorCode.FORBIDDEN);
        jdbc.update("UPDATE sys_user SET role='ADMIN',credential_version=1 WHERE id=?", admin);
        assertCode(() -> act(user, req, AccountManagementAction.BAN, adminToken), ErrorCode.UNAUTHORIZED);
        jdbc.update("UPDATE sys_user SET credential_version=0 WHERE id=?", admin);
        sessions.removeAllSessions(admin);
        assertCode(() -> act(user, req, AccountManagementAction.BAN, adminToken), ErrorCode.UNAUTHORIZED);
        assertThat(count()).isZero(); assertThat(status(user)).isEqualTo(1);
    }
    @Test void banUnbanRevokeAllClientsAndCannotReviveOldCredentials() throws Exception {
        var tokens = List.of(userToken, login("p3_user", "H5"), login("p3_user", "PLUGIN"));
        String oldSession = redis.opsForValue().get(RedisConstants.buildAuthTokenKey(user, "PC"));
        String preserved = fingerprint(user);
        act(user, request(user), AccountManagementAction.BAN, adminToken);
        assertThat(status(user)).isZero(); assertThat(fingerprint(user)).isEqualTo(preserved); assertSessionsRemoved(user);
        for (String token : tokens) assertThat(call("GET", "/api/auth/me", token, null).statusCode()).isEqualTo(401);
        assertCode(() -> act(user, request(user), AccountManagementAction.UNBAN, adminToken), ErrorCode.FORBIDDEN);
        act(user, request(user), AccountManagementAction.UNBAN, ownerToken);
        assertThat(status(user)).isEqualTo(1); assertSessionsRemoved(user);
        redis.opsForValue().set(RedisConstants.buildAuthTokenKey(user, "PC"), oldSession, Duration.ofMinutes(1));
        assertThat(call("GET", "/api/auth/me", userToken, null).statusCode()).isEqualTo(401);
        assertThat(auth.getCurrentUser(login("p3_user", "PC")).id()).isEqualTo(Long.toString(user));
        assertThat(version(user)).isEqualTo(2); assertThat(count()).isEqualTo(2);
    }
    @Test void sensitiveActionsReauthenticateOwnerAndResetRequiresFirstChange() throws Exception {
        var req = request(user);
        assertCode(() -> act(user, new AccountOperationDTO(req.requestId(), 0L, UserRoleEnum.USER, 1, req.reason(), null), AccountManagementAction.PASSWORD_RESET, ownerToken), ErrorCode.INVALID_PARAMETER);
        var adminRequest = request(admin);
        assertCode(() -> act(admin, new AccountOperationDTO(adminRequest.requestId(), 0L, UserRoleEnum.ADMIN, 1, adminRequest.reason(), "wrong-fixture"), AccountManagementAction.BAN, ownerToken), ErrorCode.INVALID_PARAMETER);
        login("p3_user", "H5"); login("p3_user", "PLUGIN");
        var reset = act(user, req, AccountManagementAction.PASSWORD_RESET, ownerToken);
        assertThat(reset.temporaryPassword()).matches("[A-Za-z2-9]{20}"); assertThat(reset.firstDelivery()).isTrue(); assertSessionsRemoved(user);
        String hash = jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, user);
        assertThat(PasswordUtils.matches(reset.temporaryPassword(), hash)).isTrue(); assertThat(hash).isNotEqualTo(reset.temporaryPassword());
        assertCode(() -> auth.login(new LoginDTO("p3_user", PASSWORD), null), ErrorCode.UNAUTHORIZED);
        var initial = auth.login(new LoginDTO("p3_user", reset.temporaryPassword()), null);
        assertThat(initial.user().passwordChangeRequired()).isTrue();
        assertThat(call("GET", "/api/auth/growth", initial.accessToken(), null).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/auth/password/change", initial.accessToken(), Map.of("currentPassword", reset.temporaryPassword(), "newPassword", "P3-personal-new-fixture!")).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/auth/me", initial.accessToken(), null).statusCode()).isEqualTo(401);
        assertThat(auth.login(new LoginDTO("p3_user", "P3-personal-new-fixture!"), null).user().passwordChangeRequired()).isFalse();
    }
    @Test void resettingBannedAccountDoesNotUnbanAndAuditHasNoPassword() {
        act(user, request(user), AccountManagementAction.BAN, ownerToken);
        var reset = act(user, request(user), AccountManagementAction.PASSWORD_RESET, ownerToken);
        assertThat(status(user)).isZero(); assertThat(reset.status()).isZero(); assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reason FROM sys_account_management_operation WHERE request_id=?", String.class, reset.requestId())).isEqualTo("隔离验收");
        assertCode(() -> auth.login(new LoginDTO("p3_user", reset.temporaryPassword()), null), ErrorCode.UNAUTHORIZED);
    }
    @Test void concurrentReplayDeliversOnceAndConfirmationIsActorScoped() throws Exception {
        var req = request(user);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> act(user, req, AccountManagementAction.PASSWORD_RESET, ownerToken));
            var b = pool.submit(() -> act(user, req, AccountManagementAction.PASSWORD_RESET, ownerToken));
            var first = a.get(20, TimeUnit.SECONDS); var second = b.get(20, TimeUnit.SECONDS);
            assertThat(first.firstDelivery() ^ second.firstDelivery()).isTrue();
            assertThat(first.firstDelivery() ? second.temporaryPassword() : first.temporaryPassword()).isNull();
        }
        assertThat(count()).isEqualTo(1); assertThat(version(user)).isEqualTo(1);
        var confirmed = management.confirm(new AccountCreationQueryDTO(req.requestId()), ownerToken);
        assertThat(confirmed.temporaryPassword()).isNull(); assertThat(confirmed.firstDelivery()).isFalse();
        assertCode(() -> management.confirm(new AccountCreationQueryDTO(req.requestId()), adminToken), ErrorCode.NOT_FOUND);
        assertCode(() -> act(user, new AccountOperationDTO(req.requestId(), 0L, UserRoleEnum.USER, 1, "不同意图", PASSWORD), AccountManagementAction.PASSWORD_RESET, ownerToken), ErrorCode.CONFLICT);
        assertCode(() -> act(user, new AccountOperationDTO(UUID.randomUUID().toString(), 0L, UserRoleEnum.USER, 1, "隔离验收", PASSWORD), AccountManagementAction.BAN, ownerToken), ErrorCode.CONFLICT);
    }
    @Test void distinctConcurrentActionsOnlyOneCanUseTheObservedVersion() throws Exception {
        var a = request(user); var b = request(user);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = pool.submit(() -> outcome(user, a, AccountManagementAction.BAN, ownerToken));
            var two = pool.submit(() -> outcome(user, b, AccountManagementAction.PASSWORD_RESET, ownerToken));
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder("success", "CONFLICT");
        }
        assertThat(version(user)).isEqualTo(1); assertThat(count()).isEqualTo(1);
    }
    @Test void ownerBanningAdminAndAdminBanningUserCannotDeadlock() throws Exception {
        var adminRequest = request(admin); var userRequest = request(user);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> outcome(admin, adminRequest, AccountManagementAction.BAN, ownerToken));
            var b = pool.submit(() -> outcome(user, userRequest, AccountManagementAction.BAN, adminToken));
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo("success");
            assertThat(b.get(20, TimeUnit.SECONDS)).isIn("success", "UNAUTHORIZED");
        }
        assertThat(status(admin)).isZero();
    }
    @Test void auditFaultRollsBackDatabaseAndDoesNotRemoveSessions() {
        var req = request(user);
        doThrow(new DataAccessResourceFailureException("isolated audit fault")).when(mapper).record(any());
        assertCode(() -> act(user, req, AccountManagementAction.BAN, ownerToken), ErrorCode.SERVICE_UNAVAILABLE);
        reset(mapper); assertThat(status(user)).isEqualTo(1); assertThat(version(user)).isZero(); assertThat(count()).isZero();
        assertThat(auth.getCurrentUser(userToken).id()).isEqualTo(Long.toString(user));
    }
    @Test void redisBeforeDeleteFaultRollsBackAndAllowsExplicitSameIntentRetry() {
        var req = request(user);
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(sessions).removeAllSessions(user);
        assertCode(() -> act(user, req, AccountManagementAction.BAN, ownerToken), ErrorCode.SERVICE_UNAVAILABLE);
        reset(sessions); assertThat(status(user)).isEqualTo(1); assertThat(version(user)).isZero(); assertThat(count()).isZero();
        assertThat(auth.getCurrentUser(userToken).id()).isEqualTo(Long.toString(user));
        act(user, req, AccountManagementAction.BAN, ownerToken); assertThat(count()).isEqualTo(1);
    }
    @Test void redisAfterDeleteFaultRollsBackWithoutRestoringRevokedKeys() {
        var req = request(user); String oldHash = jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, user);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }).when(sessions).removeAllSessions(user);
        assertCode(() -> act(user, req, AccountManagementAction.PASSWORD_RESET, ownerToken), ErrorCode.SERVICE_UNAVAILABLE);
        reset(sessions); assertThat(status(user)).isEqualTo(1); assertThat(version(user)).isZero(); assertThat(count()).isZero(); assertSessionsRemoved(user);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, user)).isEqualTo(oldHash);
        assertCode(() -> auth.getCurrentUser(userToken), ErrorCode.UNAUTHORIZED);
    }
    @Test void migrationIsRepeatableWithoutResettingManagedAccount() throws Exception {
        act(user, request(user), AccountManagementAction.BAN, ownerToken);
        try (Connection connection = DriverManager.getConnection(sandbox.url, sandbox.user, sandbox.password)) { sandbox.migrate(connection, "007_account_management.sql"); }
        assertThat(status(user)).isZero(); assertThat(version(user)).isEqualTo(1); assertThat(count()).isEqualTo(1);
    }
    @Test void httpRejectsInvalidTargetAndQueryOverrideCannotChangeTarget() throws Exception {
        assertThat(call("POST", "/api/manage/accounts/0/ban", ownerToken, body(request(user))).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/manage/accounts/" + user + "/ban?id=" + admin, ownerToken, body(request(user))).statusCode()).isEqualTo(200);
        assertThat(status(user)).isZero(); assertThat(status(admin)).isEqualTo(1);
        assertThat(call("GET", "/api/manage/accounts", userToken, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/manage/accounts", insiderToken, null).statusCode()).isEqualTo(403);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POLYHOUSE_MANAGEMENT_BROWSER", matches = "true")
    void realBrowserManagementJourney() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("node.exe", "scripts/verify-account-management.cjs")
                .directory(Path.of("../polyhouse-web").toFile()).redirectErrorStream(true);
        Map<String, String> inherited = new HashMap<>(builder.environment()); builder.environment().clear();
        // 白名单传递系统路径和隔离夹具，不把真实数据库/JWT 私有预设交给浏览器进程。
        for (String key : List.of("PATH", "SystemRoot", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)", "POLYHOUSE_PLAYWRIGHT_MODULE")) {
            inherited.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key)).findFirst()
                    .ifPresent(entry -> builder.environment().put(entry.getKey(), entry.getValue()));
        }
        builder.environment().put("POLYHOUSE_BROWSER_API_BASE_URL", "http://127.0.0.1:" + port);
        builder.environment().put("POLYHOUSE_BROWSER_OWNER_PASSWORD", PASSWORD);
        Process child = builder.redirectOutput(Path.of("target/p3-browser-result.log").toFile()).start();
        if (!child.waitFor(120, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new IllegalStateException("Browser verification timed out"); }
        assertThat(child.exitValue()).as("See masked p3-browser-result.log").isZero();
    }

    private long create(String name, UserRoleEnum role) {
        long id = Long.parseLong(auth.register(new RegisterDTO(name, PASSWORD, "隔离测试" )).id());
        jdbc.update("UPDATE sys_user SET role=? WHERE id=?", role.getCode(), id); return id;
    }
    private String login(String name, String client) { return auth.login(new LoginDTO(name, PASSWORD, client), null).accessToken(); }
    private AccountListDTO query(String username, UserRoleEnum role, Integer status) { return new AccountListDTO(1, 20, username, role, status); }
    private AccountOperationDTO request(long id) {
        String role = jdbc.queryForObject("SELECT role FROM sys_user WHERE id=?", String.class, id);
        return new AccountOperationDTO(UUID.randomUUID().toString(), version(id), UserRoleEnum.valueOf(role), status(id), "隔离验收", PASSWORD);
    }
    private AccountOperationVO act(long id, AccountOperationDTO req, AccountManagementAction action, String token) { return management.operate(new AccountTargetDTO(id), req, action, token); }
    private long version(long id) { return jdbc.queryForObject("SELECT management_version FROM sys_user WHERE id=?", Long.class, id); }
    private int status(long id) { return jdbc.queryForObject("SELECT status FROM sys_user WHERE id=?", Integer.class, id); }
    private long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM sys_account_management_operation", Long.class); }
    private String fingerprint(long id) { return jdbc.queryForObject("SELECT CONCAT(nickname,':',experience,':',coins,':',level,':',deleted) FROM sys_user WHERE id=?", String.class, id); }
    private String outcome(long id, AccountOperationDTO req, AccountManagementAction action, String token) { try { act(id, req, action, token); return "success"; } catch (BusinessException e) { return e.getErrorCode().name(); } }
    private void assertSessionsRemoved(long id) { for (String client : List.of("PC", "H5", "PLUGIN")) assertThat(redis.hasKey(RedisConstants.buildAuthTokenKey(id, client))).isFalse(); }
    private Map<String, String> body(AccountOperationDTO req) { return Map.of("requestId",req.requestId(),"expectedVersion",req.expectedVersion().toString(),"expectedRole",req.expectedRole().getCode(),"expectedStatus",req.expectedStatus().toString(),"reason",req.reason(),"ownerPassword",PASSWORD); }
    private JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).get("data"); }
    private HttpResponse<String> call(String method, String path, String token, Map<String, String> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (method.equals("GET")) request.GET(); else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void assertCode(Runnable action, ErrorCode expected) { assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(expected)); }
    /** 只允许本机 MySQL，库名由内部随机十六进制构成；不会重放开发库迁移；OWNER 夹具只位于本测试随机库。 */
    private static final class Sandbox implements AutoCloseable {
        private final String name = "polyhouse_management_" + UUID.randomUUID().toString().replace("-", "");
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
            if (!name.matches("polyhouse_management_[0-9a-f]{32}")) throw new IllegalStateException("Invalid sandbox name");
            try (Connection connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                command.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }
    }
}

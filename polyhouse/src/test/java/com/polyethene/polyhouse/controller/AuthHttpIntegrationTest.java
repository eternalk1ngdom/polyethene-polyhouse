package com.polyethene.polyhouse.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.context.UserContext;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import com.polyethene.polyhouse.service.AuthTransactionFixtureMapper;
import com.polyethene.polyhouse.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通过随机端口的真实 HTTP 调用生产 Controller、拦截器、MySQL 和 Redis，避免只用模拟业务验收。
 * 使用本机预设的真实运行密钥，不覆盖配置；测试账号独立提交并按精确 ID/随机账号清理。
 */
@EnabledIfSystemProperty(named = "polyhouse.auth.integration", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthHttpIntegrationTest.RoleProbeController.class)
class AuthHttpIntegrationTest {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private UserMapper users;
    @Autowired
    private AuthTransactionFixtureMapper fixtures;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JwtProperties jwt;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Fixture> ownedFixtures = new ArrayList<>();

    @BeforeEach
    void useHttpClientThatCanReadPostAuthenticationFailures() {
        // 默认 HttpURLConnection 在流式 POST 收到 401 时尝试认证重试并抛 I/O 错误；
        // 使用 JDK 自带 HttpClient 正常读取真实 401 响应，不新增依赖，也不绕过服务器认证。
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @AfterEach
    void cleanupOwnedRecordsAfterRealHttpTransactions() {
        List<Throwable> failures = new ArrayList<>();
        for (Fixture fixture : ownedFixtures) {
            try {
                try {
                    redis.delete(sessionKeys(fixture.id()));
                } finally {
                    assertThat(fixtures.removeOwnedFixture(fixture.id(), fixture.username())).isEqualTo(1);
                    assertThat(fixtures.deleted(fixture.id(), fixture.username())).isNull();
                }
            } catch (RuntimeException | AssertionError exception) {
                // 不因一条记录清理失败跳过其他测试记录，最后统一失败而非静默留存。
                failures.add(exception);
            }
        }
        assertThat(failures).as("HTTP 测试自己的账号和会话必须全部清理").isEmpty();
    }

    @Test
    void shouldRegisterLoginAndGetSafeProfileAfterRepeatedWrongPasswords() throws Exception {
        Fixture fixture = register();
        ResponseEntity<String> duplicate = http.postForEntity("/api/auth/register",
                Map.of("username", fixture.username(), "password", fixture.password(), "nickname", "重复注册"), String.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        for (int attempt = 0; attempt < 10; attempt++) {
            ResponseEntity<String> wrong = http.postForEntity("/api/auth/login", Map.of("username", fixture.username(),
                    "password", "wrong-" + UUID.randomUUID()), String.class);
            assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(json.readTree(wrong.getBody()).path("message").asText()).isEqualTo("账号或密码错误");
        }
        String token = login(fixture, "PC");
        ResponseEntity<String> current = get("/api/auth/me", token);
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode profile = json.readTree(current.getBody()).path("data");
        assertThat(profile.path("id").asText()).isEqualTo(Long.toString(fixture.id()));
        assertThat(profile.path("role").asText()).isEqualTo("USER");
        assertThat(profile.path("experience").asText()).isEqualTo("10");
        assertThat(profile.path("level").asInt()).isEqualTo(1);
        assertThat(profile.path("coins").asText()).isEqualTo("1");
        assertThat(profile.has("passwordHash")).isFalse();
        assertThat(profile.has("deleted")).isFalse();
        assertThat(profile.has("lastLoginIp")).isFalse();
        assertThat(get("/api/auth/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/auth/me", "malformed-fixture").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldExposeOnlyOwnGrowthWithoutDuplicatingDailyRewardsAcrossClients() throws Exception {
        Fixture fixture = register();
        Fixture other = register();
        assertThat(get("/api/auth/growth", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String pc = login(fixture, "PC");
        String h5 = login(fixture, "H5");
        for (String token : List.of(pc, h5, pc)) {
            ResponseEntity<String> response = get("/api/auth/growth?userId=" + other.id(), token);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode status = json.readTree(response.getBody()).path("data");
            assertThat(status.path("userId").asText()).isEqualTo(Long.toString(fixture.id()));
            assertThat(status.path("experience").asText()).isEqualTo("10");
            assertThat(status.path("coins").asText()).isEqualTo("1");
            assertThat(status.path("progressPercent").asInt()).isEqualTo(33);
            assertThat(status.path("experienceToNextLevel").asText()).isEqualTo("20");
            assertThat(status.path("dailyLoginRewardClaimed").asBoolean()).isTrue();
            assertThat(status.path("nextLevelExperience").asText()).isEqualTo("30");
            assertThat(status.has("passwordHash")).isFalse();
            assertThat(status.has("role")).isFalse();
            assertThat(response.getBody().contains(token)).isFalse();
            assertThat(response.getBody().contains(fixture.password())).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_growth_reward WHERE user_id = ?", Integer.class, fixture.id())).isEqualTo(1);
        assertThat(users.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, fixture.id())
                .set(User::getExperience, Long.MAX_VALUE).set(User::getCoins, Long.MAX_VALUE).set(User::getLevel, 6))).isEqualTo(1);
        JsonNode maximum = json.readTree(get("/api/auth/growth", pc).getBody()).path("data");
        assertThat(maximum.path("maxLevelReached").asBoolean()).isTrue();
        assertThat(maximum.path("nextLevelExperience").isNull()).isTrue();
        assertThat(maximum.path("experienceToNextLevel").asText()).isEqualTo("0");
        assertThat(maximum.path("experience").asText()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(maximum.path("progressPercent").asInt()).isEqualTo(100);
        assertThat(command("logout", pc).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/auth/growth", pc).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldRefreshMeFromLiveRoleAndStatusWithoutReusingPriorHttpIdentity() throws Exception {
        Fixture fixture = register();
        String token = login(fixture, "PC");
        assertThat(json.readTree(get("/api/auth/me", token).getBody()).path("data").path("role").asText())
                .isEqualTo("USER");
        changeRole(fixture, UserRoleEnum.INSIDER);
        assertThat(json.readTree(get("/api/auth/me", token).getBody()).path("data").path("role").asText())
                .isEqualTo("INSIDER");
        changeRole(fixture, UserRoleEnum.USER);
        assertThat(get("/api/__integration-auth/insider", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json.readTree(get("/api/auth/me?profile.role=OWNER&profile.id=99", token).getBody())
                .path("data").path("role").asText()).isEqualTo("USER");
        // 仅更新随机夹具；不占用全库 OWNER 唯一槽位，也不修改真实屋主的账号或会话。
        assertThat(users.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, fixture.id())
                .set(User::getStatus, 0))).isEqualTo(1);
        assertThat(get("/api/auth/me", token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(users.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, fixture.id())
                .set(User::getStatus, 1))).isEqualTo(1);
        assertThat(get("/api/auth/me", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_growth_reward WHERE user_id=?",
                Integer.class, fixture.id())).isEqualTo(1);
        assertThat(command("logout", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/auth/me", token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldUseLiveDatabaseRoleAndRejectDisabledAccountOverHttp() throws Exception {
        Fixture fixture = register();
        String token = login(fixture, "PC");
        assertThat(get("/api/__integration-auth/insider", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> forbidden = get("/api/__integration-auth/insider", token);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json.readTree(forbidden.getBody()).path("code").asInt()).isEqualTo(40300);
        changeRole(fixture, UserRoleEnum.INSIDER);
        assertThat(get("/api/__integration-auth/insider", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/__integration-auth/owner", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        changeRole(fixture, UserRoleEnum.OWNER);
        String ownerToken = login(fixture, "PC");
        assertThat(get("/api/__integration-auth/owner", ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/__integration-auth/insider", ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        changeRole(fixture, UserRoleEnum.USER);
        assertThat(get("/api/__integration-auth/owner", ownerToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, fixture.id())
                .set(User::getStatus, 0))).isEqualTo(1);
        assertThat(get("/api/auth/me", ownerToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldRejectExpiredTamperedAndUnlistedSignedTokensOverHttp() throws Exception {
        Fixture fixture = register();
        String token = login(fixture, "PC");
        String expired = JwtUtils.createToken(fixture.id(), UserRoleEnum.USER, "PC", jwt.signingKey(),
                Instant.now().minus(Duration.ofHours(2)), Duration.ofHours(1));
        String unlisted = JwtUtils.createToken(fixture.id(), UserRoleEnum.USER, "PC", jwt.signingKey(),
                Instant.now(), Duration.ofDays(7));
        // 改签名首字符，避免仅改末尾编码填充位而仍解码为同一签名的歧义。
        int signatureStart = token.lastIndexOf('.') + 1;
        char changed = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signatureStart) + changed + token.substring(signatureStart + 1);
        for (String invalid : List.of(expired, unlisted, tampered)) {
            assertThat(get("/api/auth/me", invalid).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(get("/api/auth/me", token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldKeepOldLogoutIdempotentAndDeregisterAllClientsOverHttp() throws Exception {
        Fixture fixture = register();
        String oldPc = login(fixture, "PC");
        String h5 = login(fixture, "H5");
        String plugin = login(fixture, "PLUGIN");
        String currentPc = login(fixture, "PC");
        assertThat(get("/api/auth/me", oldPc).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(command("logout", oldPc).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(command("logout", oldPc).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/auth/me", currentPc).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(command("logout", currentPc).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/auth/me", currentPc).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/auth/me", h5).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(command("deregister", h5).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fixtures.deleted(fixture.id(), fixture.username())).isEqualTo(1);
        assertThat(redis.countExistingKeys(sessionKeys(fixture.id()))).isZero();
        assertThat(get("/api/auth/me", h5).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/auth/me", plugin).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(command("deregister", h5).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> loginAfterDeletion = http.postForEntity("/api/auth/login", Map.of("username", fixture.username(),
                "password", fixture.password()), String.class);
        assertThat(loginAfterDeletion.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldRejectOwnerDeregistrationEvenWithAnOlderUserTokenAndKeepLogoutAvailable() throws Exception {
        Fixture fixture = register();
        String oldUserToken = login(fixture, "PC");
        // 仅精确修改本测试夹具，刻意保留旧 USER Token，证明决定来自锁定读取的数据库角色。
        assertThat(users.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, fixture.id())
                .set(User::getRole, UserRoleEnum.OWNER))).isEqualTo(1);
        ResponseEntity<String> rejected = command("deregister", oldUserToken);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json.readTree(rejected.getBody()).path("message").asText()).contains("屋主");
        assertThat(fixtures.deleted(fixture.id(), fixture.username())).isZero();
        assertThat(get("/api/auth/me", oldUserToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(command("logout", oldUserToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/auth/me", oldUserToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private Fixture register() throws Exception {
        String username = "http_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String password = UUID.randomUUID().toString();
        ResponseEntity<String> response = http.postForEntity("/api/auth/register",
                Map.of("username", username, "password", password, "nickname", "真实 HTTP 验收", "role", "OWNER",
                        "experience", "99999", "level", 99, "coins", "99999"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = json.readTree(response.getBody()).path("data");
        Fixture fixture = new Fixture(Long.parseLong(data.path("id").asText()), username, password);
        ownedFixtures.add(fixture);
        assertThat(data.path("role").asText()).isEqualTo("USER");
        assertThat(data.path("experience").asText()).isEqualTo("0");
        assertThat(data.path("level").asInt()).isEqualTo(1);
        assertThat(data.path("coins").asText()).isEqualTo("0");
        return fixture;
    }

    private String login(Fixture fixture, String client) throws Exception {
        ResponseEntity<String> response = http.postForEntity("/api/auth/login", Map.of("username", fixture.username(),
                "password", fixture.password(), "clientType", client), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = json.readTree(response.getBody()).path("data");
        assertThat(data.path("tokenType").asText()).isEqualTo("Bearer");
        String token = data.path("accessToken").asText();
        // 不断言完整令牌字符串，避免断言失败把可使用的测试 JWT 写入报告。
        assertThat(token.length()).isGreaterThan(0);
        return token;
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) { headers.setBearerAuth(token); }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> command(String command, String token) {
        return http.postForEntity("/api/auth/" + command, Map.of("token", token), String.class);
    }

    private void changeRole(Fixture fixture, UserRoleEnum role) {
        assertThat(users.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, fixture.id())
                .set(User::getRole, role))).isEqualTo(1);
    }

    private static List<String> sessionKeys(long userId) {
        return List.of("PC", "H5", "PLUGIN").stream()
                .map(client -> RedisConstants.buildAuthTokenKey(userId, client)).toList();
    }

    @Test
    void shouldReadLatestGrowthWithoutDuplicatingDailyRewardsOrGrantingRolePermissions() throws Exception {
        Fixture fixture = register();
        String oldToken = login(fixture, "PC");
        // 仅定向修改本测试的随机账号，模拟未来奖励业务已经提交，检验接口读取数据库而非旧 Token。
        assertThat(jdbc.update("UPDATE sys_user SET experience = ?, level = ?, coins = ? WHERE id = ? AND username = ?",
                Long.MAX_VALUE, 6, Long.MAX_VALUE, fixture.id(), fixture.username())).isEqualTo(1);
        JsonNode current = json.readTree(get("/api/auth/me", oldToken).getBody()).path("data");
        assertThat(current.path("experience").isTextual()).isTrue();
        assertThat(current.path("experience").asText()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(current.path("level").asInt()).isEqualTo(6);
        assertThat(current.path("coins").asText()).isEqualTo(Long.toString(Long.MAX_VALUE));
        ResponseEntity<String> login = http.postForEntity("/api/auth/login",
                Map.of("username", fixture.username(), "password", fixture.password(),
                        "experience", "1", "level", 99, "coins", "1"), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = json.readTree(login.getBody()).path("data");
        assertThat(data.path("user")).isEqualTo(current);
        String token = data.path("accessToken").asText();
        assertThat(get("/api/__integration-auth/insider", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.selectById(fixture.id()).getCoins()).isEqualTo(Long.MAX_VALUE);
        assertThat(users.selectById(fixture.id()).getExperience()).isEqualTo(Long.MAX_VALUE);
    }

    private record Fixture(long id, String username, String password) {
        /** 隐藏随机凭据，不能让测试夹具的默认文本泄露密码。 */
        @Override
        public String toString() { return "Fixture[credentials redacted]"; }
    }

    /** 真实 HTTP 中核验角色门槛；只在测试源码中存在，不提供实际收费或管理功能。 */
    @RestController
    @RequestMapping("/api/__integration-auth")
    public static class RoleProbeController {
        /** 验证普通账号不能通过 INSIDER 门槛。 */
        @RequireRole(UserRoleEnum.INSIDER)
        @GetMapping("/insider")
        public Result<UserProfileVO> insider() { return identity(); }

        /** 验证 OWNER 门槛以及数据库降权及时生效。 */
        @RequireRole(UserRoleEnum.OWNER)
        @GetMapping("/owner")
        public Result<UserProfileVO> owner() { return identity(); }

        private Result<UserProfileVO> identity() {
            return Result.success(new UserProfileVO(UserContext.getUserId().toString(), "fixture", "身份验证", null,
                    UserContext.getRole(), "0", 1, "0"));
        }
    }
}

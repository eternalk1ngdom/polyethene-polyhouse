package com.polyethene.polyhouse.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.LoginDTO;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.model.vo.LoginVO;
import com.polyethene.polyhouse.utils.PasswordUtils;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 显式启用的 MySQL + Redis 业务联调，数据库事务回滚，Redis 仅清理本次随机账号对应的精确键。
 * 使用内存中生成的测试 JWT 密钥，不替代用户待配置的日常运行密钥。
 */
@EnabledIfSystemProperty(named = "polyhouse.auth.integration", matches = "true")
@SpringBootTest
@Transactional
class AuthServiceIntegrationTest {

    private static final String PASSWORD = "Integration-fixture-password-2026!";
    private static final String TEST_KEY = Base64.getEncoder().encodeToString(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded());

    @Autowired
    private AuthService service;
    @Autowired
    private UserMapper mapper;
    @Autowired
    private StringRedisTemplate redis;

    private final Set<String> ownedKeys = new HashSet<>();

    @DynamicPropertySource
    static void configureEphemeralTestKey(DynamicPropertyRegistry properties) {
        properties.add("polyhouse.jwt.secret", () -> TEST_KEY);
    }

    @AfterEach
    void cleanupOwnRedisState() {
        if (!ownedKeys.isEmpty()) {
            redis.delete(ownedKeys);
        }
    }

    @Test
    void shouldRegisterHashLoginRestoreAndLogoutWithoutExposingPersistenceFields() {
        String username = newUsername();
        var profile = service.register(new RegisterDTO(username.toUpperCase(Locale.ROOT), PASSWORD, "鉴权事务验证"));
        long userId = Long.parseLong(profile.id());
        ownUserSessions(userId);
        var user = mapper.selectById(userId);
        assertThat(user.getUsername()).isEqualTo(username);
        assertThat(user.getRole()).isEqualTo(UserRoleEnum.USER);
        assertThat(PasswordUtils.matches(PASSWORD, user.getPasswordHash())).isTrue();
        LoginVO login = service.login(new LoginDTO(username, PASSWORD), "192.0.2.1");
        assertThat(service.getCurrentUser(login.accessToken()).id()).isEqualTo(profile.id());
        assertThat(mapper.selectById(userId).getLastLoginIp()).isEqualTo("192.0.2.1");
        assertThat(mapper.selectById(userId).getLastLoginTime()).isNotNull();
        String stored = redis.opsForValue().get(RedisConstants.buildAuthTokenKey(userId, "PC"));
        assertThat(stored).isNotNull().isNotEqualTo(login.accessToken());
        assertThat(UUID.fromString(stored).toString()).isEqualTo(stored);
        assertThat(redis.getExpire(RedisConstants.buildAuthTokenKey(userId, "PC"), TimeUnit.SECONDS))
                .isPositive().isLessThanOrEqualTo(604_800L);
        service.logout(login.accessToken());
        service.logout(login.accessToken());
        assertCode(() -> service.getCurrentUser(login.accessToken()), ErrorCode.UNAUTHORIZED);
    }

    @Test
    void shouldReplaceOnlySameClientAndKeepNewSessionWhenOldTokenLogsOut() {
        String username = newUsername();
        long userId = Long.parseLong(service.register(new RegisterDTO(username, PASSWORD, "多端事务验证")).id());
        ownUserSessions(userId);
        LoginVO firstPc = service.login(new LoginDTO(username, PASSWORD), null);
        LoginVO h5 = service.login(new LoginDTO(username, PASSWORD, "H5"), null);
        LoginVO secondPc = service.login(new LoginDTO(username, PASSWORD), null);
        assertCode(() -> service.getCurrentUser(firstPc.accessToken()), ErrorCode.UNAUTHORIZED);
        service.logout(firstPc.accessToken());
        assertThat(service.getCurrentUser(secondPc.accessToken()).id()).isEqualTo(Long.toString(userId));
        assertThat(service.getCurrentUser(h5.accessToken()).id()).isEqualTo(Long.toString(userId));
        service.logout(secondPc.accessToken());
        assertCode(() -> service.getCurrentUser(secondPc.accessToken()), ErrorCode.UNAUTHORIZED);
        assertThat(service.getCurrentUser(h5.accessToken()).id()).isEqualTo(Long.toString(userId));
    }

    @Test
    void shouldAllowImmediateRetryAfterRepeatedFailuresAndPreserveExistingSession() {
        String missing = newUsername();
        for (int attempt = 1; attempt <= 10; attempt++) {
            String input = attempt % 2 == 0 ? missing.toUpperCase(Locale.ROOT) : missing;
            assertCode(() -> service.login(new LoginDTO(input, PASSWORD), null), ErrorCode.UNAUTHORIZED);
        }
        String username = newUsername();
        long userId = Long.parseLong(service.register(new RegisterDTO(username, PASSWORD, "重试事务验证")).id());
        ownUserSessions(userId);
        // 模拟旧版本尚未到期的失败计数键；新版本必须忽略它，不能让历史状态继续阻止登录。
        String legacyFailureKey = "polyhouse:auth:fail:" + username;
        assertThat(redis.opsForValue().setIfAbsent(legacyFailureKey, "5", java.time.Duration.ofSeconds(60))).isTrue();
        ownedKeys.add(legacyFailureKey);
        LoginVO existing = service.login(new LoginDTO(username, PASSWORD), null);
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertCode(() -> service.login(new LoginDTO(username, "Wrong-fixture-password"), null), ErrorCode.UNAUTHORIZED);
        }
        assertThat(service.getCurrentUser(existing.accessToken()).id()).isEqualTo(Long.toString(userId));
        LoginVO retry = service.login(new LoginDTO(username, PASSWORD), null);
        assertThat(service.getCurrentUser(retry.accessToken()).id()).isEqualTo(Long.toString(userId));
        assertCode(() -> service.getCurrentUser(existing.accessToken()), ErrorCode.UNAUTHORIZED);
    }

    @Test
    void shouldUseCurrentRoleAndRejectDisabledDeletedAndDuplicateAccounts() {
        String username = newUsername();
        long userId = Long.parseLong(service.register(new RegisterDTO(username, PASSWORD, "权限事务验证")).id());
        ownUserSessions(userId);
        LoginVO login = service.login(new LoginDTO(username, PASSWORD), null);
        mapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId).set(User::getRole, UserRoleEnum.INSIDER));
        assertThat(service.getCurrentUser(login.accessToken()).role()).isEqualTo(UserRoleEnum.INSIDER);
        mapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId).set(User::getStatus, 0));
        assertCode(() -> service.getCurrentUser(login.accessToken()), ErrorCode.UNAUTHORIZED);
        mapper.deleteById(userId);
        assertCode(() -> service.getCurrentUser(login.accessToken()), ErrorCode.UNAUTHORIZED);
        // 软删除后唯一索引仍保留账号；服务应翻译冲突，而不是泄露数据库异常。
        assertCode(() -> service.register(new RegisterDTO(username, PASSWORD, "重复账号")), ErrorCode.CONFLICT);
    }

    @Test
    void shouldSoftDeleteCurrentAccountRevokeAllClientsAndRefuseFutureLogin() {
        String username = newUsername();
        long userId = Long.parseLong(service.register(new RegisterDTO(username, PASSWORD, "注销事务验证")).id());
        ownUserSessions(userId);
        LoginVO pc = service.login(new LoginDTO(username, PASSWORD), null);
        LoginVO h5 = service.login(new LoginDTO(username, PASSWORD, "H5"), null);
        LoginVO plugin = service.login(new LoginDTO(username, PASSWORD, "PLUGIN"), null);
        service.deregister(pc.accessToken());
        assertThat(mapper.selectById(userId)).isNull();
        for (String client : new String[]{"PC", "H5", "PLUGIN"}) {
            assertThat(redis.hasKey(RedisConstants.buildAuthTokenKey(userId, client))).isFalse();
        }
        for (LoginVO session : new LoginVO[]{pc, h5, plugin}) {
            assertCode(() -> service.getCurrentUser(session.accessToken()), ErrorCode.UNAUTHORIZED);
        }
        assertCode(() -> service.login(new LoginDTO(username, PASSWORD), null), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.register(new RegisterDTO(username, PASSWORD, "不可复用")), ErrorCode.CONFLICT);
    }

    private String newUsername() {
        return "auth_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private void ownUserSessions(long userId) {
        // 用户 ID 由本测试事务内的新记录分配，绝不枚举或清理其他用户的会话。
        for (String client : new String[]{"PC", "H5", "PLUGIN"}) {
            ownedKeys.add(RedisConstants.buildAuthTokenKey(userId, client));
        }
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}

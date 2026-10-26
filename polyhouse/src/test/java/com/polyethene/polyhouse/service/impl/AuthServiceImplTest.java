package com.polyethene.polyhouse.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.LoginDTO;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.model.vo.LoginVO;
import com.polyethene.polyhouse.service.AuthRedisStore;
import com.polyethene.polyhouse.utils.JwtUtils;
import com.polyethene.polyhouse.utils.PasswordUtils;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 以真实密码/JWT 工具和固定时钟检查业务编排，外部存储使用可控替身验证失败边界。 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PASSWORD = "Service-fixture-password-2026!";
    private static final Instant NOW = Instant.parse("2026-10-26T08:00:00Z");
    private static ValidatorFactory validatorFactory;
    private static String passwordHash;

    @Mock
    private UserMapper mapper;
    @Mock
    private AuthRedisStore sessions;

    private JwtProperties properties;
    private AuthServiceImpl service;

    @BeforeAll
    static void prepareValidationAndFixtureHash() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        passwordHash = PasswordUtils.hashPassword(PASSWORD);
        // 纯单元测试没有 SqlSessionFactory，但 Lambda 更新仍需要实体列元数据；只初始化元数据，不连接数据库。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "auth-unit-test");
        assistant.setCurrentNamespace(UserMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @BeforeEach
    void prepareService() {
        properties = new JwtProperties();
        properties.setExpirationDays(7);
        properties.setSecret(Base64.getEncoder().encodeToString(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded()));
        service = new AuthServiceImpl(mapper, sessions, properties, validatorFactory.getValidator(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldRegisterCanonicalUserWithHashAndSafeProfile() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(42L);
            return 1;
        });
        when(mapper.selectById(42L)).thenReturn(activeUser());
        var profile = service.register(new RegisterDTO("ALICE", PASSWORD, "居民"));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("alice");
        assertThat(saved.getValue().getRole()).isEqualTo(UserRoleEnum.USER);
        assertThat(saved.getValue().getStatus()).isEqualTo(1);
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(PasswordUtils.matches(PASSWORD, saved.getValue().getPasswordHash())).isTrue();
        assertThat(profile.id()).isEqualTo("42");
        verifyNoInteractions(sessions);
    }

    @Test
    void shouldHandlePrecheckAndDatabaseUniqueConflictsWithSameSafeMessage() {
        when(mapper.selectCount(any())).thenReturn(1L);
        assertBusiness(() -> service.register(new RegisterDTO("alice", PASSWORD, "居民")), ErrorCode.CONFLICT, "账号已被使用");
        verify(mapper, never()).insert(any(User.class));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(User.class))).thenThrow(new DuplicateKeyException("sensitive-sql-fixture"));
        assertBusiness(() -> service.register(new RegisterDTO("alice", PASSWORD, "居民")), ErrorCode.CONFLICT, "账号已被使用");
    }

    @Test
    void shouldRejectInvalidDtoAndMalformedUnicodeWithoutPersistingIt() {
        assertBusiness(() -> service.register(null), ErrorCode.INVALID_PARAMETER, "请求参数不合法");
        assertBusiness(() -> service.login(new LoginDTO("a", PASSWORD), null), ErrorCode.INVALID_PARAMETER, "请求参数不合法");
        assertBusiness(() -> service.login(new LoginDTO("alice", PASSWORD, "MOBILE"), null), ErrorCode.INVALID_PARAMETER, "请求参数不合法");
        verifyNoInteractions(mapper, sessions);
        when(mapper.selectCount(any())).thenReturn(0L);
        assertBusiness(() -> service.register(new RegisterDTO("alice", "aaaaaaa\uD800", "居民")),
                ErrorCode.INVALID_PARAMETER, "请求参数不合法");
        verify(mapper, never()).insert(any(User.class));
    }

    @Test
    void shouldAllowCorrectLoginImmediatelyAfterRepeatedFailures() {
        when(mapper.selectOne(any())).thenReturn(activeUser());
        for (int attempt = 0; attempt < 10; attempt++) {
            assertBusiness(() -> service.login(new LoginDTO("ALICE", "Wrong-fixture-password"), null),
                    ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        // 凭据错误不访问 Redis，也不清除已经存在的登录会话。
        verifyNoInteractions(sessions);
        when(mapper.update(isNull(), any())).thenReturn(1);
        assertThat(service.login(new LoginDTO("alice", PASSWORD), null).user().id()).isEqualTo("42");
        verify(sessions).saveSession(any(), eq(Duration.ofDays(7)));
    }

    @Test
    void shouldHideWhetherAccountExistsWhenPasswordIsWrong() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertBusiness(() -> service.login(new LoginDTO("ALICE", PASSWORD), null), ErrorCode.UNAUTHORIZED, "账号或密码错误");
        when(mapper.selectOne(any())).thenReturn(activeUser());
        assertBusiness(() -> service.login(new LoginDTO("alice", "Wrong-fixture-password"), null),
                ErrorCode.UNAUTHORIZED, "账号或密码错误");
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldRejectNonActiveAccountsEvenWithCorrectPassword(int status) {
        User user = activeUser();
        user.setStatus(status);
        when(mapper.selectOne(any())).thenReturn(user);
        assertBusiness(() -> service.login(new LoginDTO("alice", PASSWORD), null), ErrorCode.UNAUTHORIZED, "账号或密码错误");
        verifyNoInteractions(sessions);
    }

    @Test
    void shouldOnlyReturnTokenAfterWhitelistWasStoredAndHideItInDiagnosticText() throws Exception {
        when(mapper.selectOne(any())).thenReturn(activeUser());
        when(mapper.update(isNull(), any())).thenReturn(1);
        LoginVO result = service.login(new LoginDTO("ALICE", PASSWORD, "H5"), "192.0.2.1");
        var claims = JwtUtils.parseAndValidate(result.accessToken(), properties.signingKey(), NOW);
        assertThat(claims.clientType()).isEqualTo("H5");
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.user().id()).isEqualTo("42");
        assertThat(result.toString()).doesNotContain(result.accessToken());
        assertThat(new ObjectMapper().findAndRegisterModules().readTree(
                new ObjectMapper().findAndRegisterModules().writeValueAsString(result)).get("accessToken").asText())
                .isEqualTo(result.accessToken());
        verify(sessions).saveSession(any(), eq(Duration.ofDays(7)));
    }

    @Test
    void shouldNotPublishSessionWhenAuditWriteOrConcurrentStatusCheckFails() {
        when(mapper.selectOne(any())).thenReturn(activeUser());
        when(mapper.update(isNull(), any())).thenReturn(0);
        assertBusiness(() -> service.login(new LoginDTO("alice", PASSWORD), null), ErrorCode.UNAUTHORIZED, "账号或密码错误");
        verify(sessions, never()).saveSession(any(), any());
        when(mapper.update(isNull(), any())).thenThrow(new DataAccessResourceFailureException("sensitive-database-fixture"));
        assertBusiness(() -> service.login(new LoginDTO("alice", PASSWORD), null), ErrorCode.SERVICE_UNAVAILABLE,
                "认证服务暂时不可用，请稍后重试");
    }

    @Test
    void shouldNotReturnTokenWhenWhitelistStorageFails() {
        when(mapper.selectOne(any())).thenReturn(activeUser());
        when(mapper.update(isNull(), any())).thenReturn(1);
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "认证服务暂时不可用，请稍后重试"))
                .when(sessions).saveSession(any(), any());
        assertBusiness(() -> service.login(new LoginDTO("alice", PASSWORD), null),
                ErrorCode.SERVICE_UNAVAILABLE, "认证服务暂时不可用，请稍后重试");
    }

    @Test
    void shouldRefuseMissingSigningKeyWithoutCreatingWhitelistEntry() {
        properties.setSecret("");
        when(mapper.selectOne(any())).thenReturn(activeUser());
        assertBusiness(() -> service.login(new LoginDTO("alice", PASSWORD), null), ErrorCode.SERVICE_UNAVAILABLE,
                "认证服务暂时不可用，请稍后重试");
        verify(mapper, never()).update(isNull(), any());
        verify(sessions, never()).saveSession(any(), any());
    }

    @Test
    void shouldUseCurrentDatabaseRoleAndRefuseRevokedOrDisabledSessions() {
        String token = JwtUtils.createToken(42L, UserRoleEnum.OWNER, "PC", properties.signingKey(), NOW, Duration.ofDays(7));
        when(sessions.isCurrentSession(any())).thenReturn(true);
        User current = activeUser();
        current.setRole(UserRoleEnum.USER);
        when(mapper.selectById(42L)).thenReturn(current);
        assertThat(service.getCurrentUser(token).role()).isEqualTo(UserRoleEnum.USER);
        current.setStatus(0);
        assertBusiness(() -> service.getCurrentUser(token), ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        current.setStatus(1);
        current.setDeleted(1);
        assertBusiness(() -> service.getCurrentUser(token), ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        when(sessions.isCurrentSession(any())).thenReturn(false);
        assertBusiness(() -> service.getCurrentUser(token), ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
    }

    @Test
    void shouldRejectMalformedTokenAndAllowCompareAndDeleteLogoutWithoutDatabaseQuery() {
        assertBusiness(() -> service.getCurrentUser("invalid-fixture-token"), ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        verifyNoInteractions(mapper, sessions);
        String token = JwtUtils.createToken(42L, UserRoleEnum.USER, "PC", properties.signingKey(), NOW, Duration.ofDays(7));
        service.logout(token);
        ArgumentCaptor<JwtUtils.TokenClaims> claims = ArgumentCaptor.forClass(JwtUtils.TokenClaims.class);
        verify(sessions).removeSession(claims.capture());
        assertThat(claims.getValue().userId()).isEqualTo(42L);
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldSoftDeleteOnlyCurrentAccountThenRemoveEveryClientSession() {
        String token = currentToken();
        when(mapper.selectOne(any())).thenReturn(activeUser());
        when(sessions.isCurrentSession(any())).thenReturn(true);
        when(mapper.deleteById(42L)).thenReturn(1);
        service.deregister(token);
        var order = inOrder(mapper, sessions);
        order.verify(mapper).selectOne(any());
        order.verify(sessions).isCurrentSession(any());
        order.verify(mapper).deleteById(42L);
        order.verify(sessions).removeAllSessions(42L);
    }

    @Test
    void shouldRejectReplacedSessionWithoutDeletingAccountOrOtherSessions() {
        when(mapper.selectOne(any())).thenReturn(activeUser());
        when(sessions.isCurrentSession(any())).thenReturn(false);
        assertBusiness(() -> service.deregister(currentToken()), ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        verify(mapper, never()).deleteById(any(Long.class));
        verify(sessions, never()).removeAllSessions(42L);
    }

    @Test
    void shouldRejectDeletedUserWithoutTouchingRedisSessions() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertBusiness(() -> service.deregister(currentToken()), ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        verifyNoInteractions(sessions);
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    void shouldFailDeregistrationIfDeletionOrRedisCleanupFails() {
        when(mapper.selectOne(any())).thenReturn(activeUser());
        when(sessions.isCurrentSession(any())).thenReturn(true);
        when(mapper.deleteById(42L)).thenReturn(0);
        assertBusiness(() -> service.deregister(currentToken()), ErrorCode.SERVICE_UNAVAILABLE,
                "认证服务暂时不可用，请稍后重试");
        verify(sessions, never()).removeAllSessions(42L);
        when(mapper.deleteById(42L)).thenReturn(1);
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "认证服务暂时不可用，请稍后重试"))
                .when(sessions).removeAllSessions(42L);
        assertBusiness(() -> service.deregister(currentToken()), ErrorCode.SERVICE_UNAVAILABLE,
                "认证服务暂时不可用，请稍后重试");
    }

    private String currentToken() {
        return JwtUtils.createToken(42L, UserRoleEnum.USER, "PC", properties.signingKey(), NOW, Duration.ofDays(7));
    }

    private static User activeUser() {
        User user = new User();
        user.setId(42L);
        user.setUsername("alice");
        user.setPasswordHash(passwordHash);
        user.setNickname("居民");
        user.setRole(UserRoleEnum.USER);
        user.setStatus(1);
        user.setDeleted(0);
        user.setExperience(0L);
        user.setLevel(1);
        user.setCoins(0L);
        return user;
    }

    private static void assertBusiness(Runnable action, ErrorCode code, String message) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(code);
            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getCause()).isNull();
        });
    }
}

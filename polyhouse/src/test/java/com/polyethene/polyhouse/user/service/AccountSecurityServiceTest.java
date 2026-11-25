package com.polyethene.polyhouse.user.service;

import com.polyethene.polyhouse.service.AccountSecurityService;
import com.polyethene.polyhouse.service.AuthRedisStore;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.AccountAuditMapper;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.PasswordChangeDTO;
import com.polyethene.polyhouse.model.entity.User;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** 用真实密码哈希/JWT 验证改密边界；事务回滚和真实锁竞争由隔离实例测试另行证明。 */
@ExtendWith(MockitoExtension.class)
class AccountSecurityServiceTest {
    private static final String OLD = "Security-fixture-old-2026!";
    private static final String NEXT = "Security-fixture-new-2026!";
    private static ValidatorFactory validation;
    private static String hash;
    @Mock private UserMapper users;
    @Mock private AccountAuditMapper audit;
    @Mock private AuthRedisStore sessions;
    private AccountSecurityService service;
    private JwtProperties jwt;

    @BeforeAll
    static void prepare() {
        validation = Validation.buildDefaultValidatorFactory();
        hash = PasswordUtils.hashPassword(OLD);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "security-test"), User.class);
    }

    @AfterAll
    static void close() { validation.close(); }

    @BeforeEach
    void setup() {
        jwt = new JwtProperties();
        jwt.setSecret(Base64.getEncoder().encodeToString(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded()));
        service = new AccountSecurityService(users, audit, sessions, jwt, validation.getValidator());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldChangeOnlySecurityColumnsAndRevokeAllClientsAfterAudit() {
        User user = current();
        user.setPasswordChangeRequired(true);
        when(users.selectOne(any())).thenReturn(user);
        when(sessions.isCurrentSession(any())).thenReturn(true);
        when(users.update(isNull(), any())).thenReturn(1);
        when(audit.recordPasswordChange(42, 1)).thenReturn(1);

        service.changePassword(new PasswordChangeDTO(OLD, NEXT), token(0));

        // 校验定向更新只修改安全列，且新密码按 BCrypt 哈希写入、版本号递增并解除首次改密标记。
        ArgumentCaptor<LambdaUpdateWrapper<User>> updateCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(users).update(isNull(), updateCaptor.capture());
        LambdaUpdateWrapper<User> wrapper = updateCaptor.getValue();
        assertThat(wrapper.getSqlSet())
                .contains("password_hash", "credential_version", "password_change_required")
                .doesNotContain("role", "coins", "experience", "level");
        var boundValues = wrapper.getParamNameValuePairs().values();
        assertThat(boundValues).contains(1L, false).doesNotContain(NEXT);
        assertThat(boundValues).anyMatch(value -> value instanceof String s && PasswordUtils.matches(NEXT, s));

        verify(audit).recordPasswordChange(42, 1);
        verify(sessions).removeAllSessions(42);
    }

    @ParameterizedTest(name = "改密入参在触库前拒绝：{0}")
    @MethodSource("invalidPasswordChangeRequests")
    void shouldRejectBadDtosBeforeAnyStorage(String label, PasswordChangeDTO request) {
        code(() -> service.changePassword(request, "invalid"), ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(users, audit, sessions);
    }

    private static Stream<Arguments> invalidPasswordChangeRequests() {
        return Stream.of(
                Arguments.of("null 请求", null),
                Arguments.of("当前密码过短", new PasswordChangeDTO("short", NEXT)),
                Arguments.of("新密码超 72 UTF-8 字节", new PasswordChangeDTO(OLD, "密".repeat(25))),
                Arguments.of("新密码全空白", new PasswordChangeDTO(OLD, " ".repeat(8)))
        );
    }

    @Test
    void shouldHidePasswordsInDtoToStringAndJson() throws Exception {
        var request = new PasswordChangeDTO(OLD, NEXT);
        assertThat(request.toString()).doesNotContain(OLD, NEXT);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request)).doesNotContain(OLD, NEXT);
    }

    @Test
    void shouldRejectWrongCurrentOrUnchangedPasswordWithoutRevokingAnything() {
        when(users.selectOne(any())).thenReturn(current());
        when(sessions.isCurrentSession(any())).thenReturn(true);
        code(() -> service.changePassword(new PasswordChangeDTO("Wrong-fixture", NEXT), token(0)), ErrorCode.INVALID_PARAMETER);
        code(() -> service.changePassword(new PasswordChangeDTO(OLD, OLD), token(0)), ErrorCode.INVALID_PARAMETER);
        verify(users, never()).update(isNull(), any());
        verifyNoInteractions(audit);
        verify(sessions, never()).removeAllSessions(42);
    }

    @Test
    void shouldRejectOldVersionEvenIfRedisWasRestoredAndOldPasswordIsKnown() {
        User user = current();
        user.setCredentialVersion(1L);
        when(users.selectOne(any())).thenReturn(user);
        code(() -> service.changePassword(new PasswordChangeDTO(OLD, NEXT), token(0)), ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(audit, sessions);
        verify(users, never()).update(isNull(), any());
    }

    @Test
    void shouldRejectReplacedOrInactiveSessionBeforeChangingCredentials() {
        User user = current();
        when(users.selectOne(any())).thenReturn(user);
        code(() -> service.changePassword(new PasswordChangeDTO(OLD, NEXT), token(0)), ErrorCode.UNAUTHORIZED);
        user.setStatus(0);
        code(() -> service.changePassword(new PasswordChangeDTO(OLD, NEXT), token(0)), ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(audit);
        verify(users, never()).update(isNull(), any());
    }

    @Test
    void shouldPropagateDependencyFailureInsteadOfClaimingSuccess() {
        when(users.selectOne(any())).thenReturn(current());
        when(sessions.isCurrentSession(any())).thenReturn(true);
        when(users.update(isNull(), any())).thenReturn(1);
        when(audit.recordPasswordChange(42, 1)).thenThrow(new DataAccessResourceFailureException("private-sql-fixture"));
        code(() -> service.changePassword(new PasswordChangeDTO(OLD, NEXT), token(0)), ErrorCode.SERVICE_UNAVAILABLE);
        verify(sessions, never()).removeAllSessions(42);
    }

    @Test
    void shouldRejectExhaustedVersionWithoutWrappingToZero() {
        User user = current();
        user.setCredentialVersion(Long.MAX_VALUE);
        when(users.selectOne(any())).thenReturn(user);
        when(sessions.isCurrentSession(any())).thenReturn(true);
        code(() -> service.changePassword(new PasswordChangeDTO(OLD, NEXT), token(Long.MAX_VALUE)), ErrorCode.SERVICE_UNAVAILABLE);
        verify(users, never()).update(isNull(), any());
    }

    private String token(long version) {
        return JwtUtils.createToken(42, UserRoleEnum.USER, "PC", jwt.signingKey(), Instant.now(), Duration.ofDays(1), version);
    }

    private static User current() {
        User user = new User();
        user.setId(42L);
        user.setRole(UserRoleEnum.USER);
        user.setStatus(1);
        user.setDeleted(0);
        user.setPasswordHash(hash);
        return user;
    }

    private static void code(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(code);
            assertThat(error.getCause()).isNull();
        });
    }
}

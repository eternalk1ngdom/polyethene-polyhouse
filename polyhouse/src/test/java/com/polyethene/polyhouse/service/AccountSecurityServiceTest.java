package com.polyethene.polyhouse.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

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
    void shouldChangeOnlySecurityColumnsAndRevokeAllClientsAfterAudit() {
        User user = current();
        user.setPasswordChangeRequired(true);
        when(users.selectOne(any())).thenReturn(user);
        when(sessions.isCurrentSession(any())).thenReturn(true);
        when(users.update(isNull(), any())).thenReturn(1);
        when(audit.recordPasswordChange(42, 1)).thenReturn(1);
        service.changePassword(new PasswordChangeDTO(OLD, NEXT), token(0));
        var order = inOrder(users, sessions, audit);
        order.verify(users).selectOne(any());
        order.verify(sessions).isCurrentSession(any());
        order.verify(users).update(isNull(), any());
        order.verify(audit).recordPasswordChange(42, 1);
        order.verify(sessions).removeAllSessions(42);
        verifyNoMoreInteractions(audit, sessions);
    }

    @Test
    void shouldRejectBadDtosBeforeAnyStorageAndHidePasswords() throws Exception {
        for (PasswordChangeDTO request : new PasswordChangeDTO[]{null, new PasswordChangeDTO("short", NEXT),
                new PasswordChangeDTO(OLD, "密".repeat(25)), new PasswordChangeDTO(OLD, " ".repeat(8))}) {
            code(() -> service.changePassword(request, "invalid"), ErrorCode.INVALID_PARAMETER);
        }
        verifyNoInteractions(users, audit, sessions);
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

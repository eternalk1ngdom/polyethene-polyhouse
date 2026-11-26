package com.polyethene.polyhouse.user.service;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.AccountAuditMapper;
import com.polyethene.polyhouse.mapper.AccountManagementMapper;
import com.polyethene.polyhouse.mapper.AccountProvisioningMapper;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.AccountCreationQueryDTO;
import com.polyethene.polyhouse.model.dto.AccountListDTO;
import com.polyethene.polyhouse.model.dto.PasswordChangeDTO;
import com.polyethene.polyhouse.service.AccountManagementService;
import com.polyethene.polyhouse.service.AccountProvisioningService;
import com.polyethene.polyhouse.service.AccountSecurityService;
import com.polyethene.polyhouse.service.AuthRedisStore;
import com.polyethene.polyhouse.utils.JwtUtils;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/** 验证三个账号核心服务均以注入的 Clock 判定令牌过期，避免硬编码系统墙钟导致时间临界不可测。 */
@ExtendWith(MockitoExtension.class)
class AccountServiceClockTest {
    private static ValidatorFactory validation;
    @Mock private UserMapper users;
    @Mock private AccountManagementMapper management;
    @Mock private AccountProvisioningMapper provisioning;
    @Mock private AccountAuditMapper audit;
    @Mock private AuthRedisStore sessions;
    private JwtProperties jwt;

    @BeforeAll
    static void prepare() {
        validation = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void close() {
        validation.close();
    }

    @BeforeEach
    void setup() {
        jwt = new JwtProperties();
        jwt.setSecret(Base64.getEncoder().encodeToString(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded()));
    }

    @ParameterizedTest(name = "{0} 应依据注入的固定 Clock 拒绝已过期令牌")
    @ValueSource(strings = {"management", "provisioning", "security"})
    void shouldUseInjectedClockForJwtExpiry(String target) {
        // 签发远未来窗口内的令牌：若服务误用真实墙钟 Instant.now()，2099 年令牌不会被判过期；
        // 仅当使用注入的 2099-01-01T00:11:00Z 固定时钟时，才会在触库前以 UNAUTHORIZED 拒绝。
        Instant issuedAt = Instant.parse("2099-01-01T00:00:00Z");
        Clock expiredClock = Clock.fixed(issuedAt.plus(Duration.ofMinutes(11)), ZoneOffset.UTC);
        String token = JwtUtils.createToken(
                42L, UserRoleEnum.OWNER, "PC", jwt.signingKey(), issuedAt, Duration.ofMinutes(10), 0L);

        Runnable invoke = switch (target) {
            case "management" -> {
                var service = new AccountManagementService(
                        users, management, sessions, jwt, validation.getValidator(), expiredClock);
                yield () -> service.list(new AccountListDTO(1, 20, null, null, null), token);
            }
            case "provisioning" -> {
                var service = new AccountProvisioningService(
                        provisioning, users, audit, sessions, jwt, validation.getValidator(), expiredClock);
                yield () -> service.confirm(new AccountCreationQueryDTO(UUID.randomUUID().toString()), token);
            }
            case "security" -> {
                var service = new AccountSecurityService(
                        users, audit, sessions, jwt, validation.getValidator(), expiredClock);
                yield () -> service.changePassword(new PasswordChangeDTO("Old-pass-2026!", "New-pass-2026!"), token);
            }
            default -> throw new IllegalArgumentException(target);
        };

        assertThatThrownBy(invoke::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
            assertThat(error.getCause()).isNull();
        });
        verifyNoInteractions(users, management, provisioning, audit, sessions);
    }
}

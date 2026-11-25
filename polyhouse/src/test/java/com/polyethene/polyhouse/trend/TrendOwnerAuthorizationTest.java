package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.service.AuthRedisStore;
import com.polyethene.polyhouse.service.trend.TrendOwnerAuthorization;
import com.polyethene.polyhouse.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.*;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrendOwnerAuthorizationTest {
    private final UserMapper users = mock(UserMapper.class);
    private final AuthRedisStore sessions = mock(AuthRedisStore.class);

    @Test void validOwnerSessionPassesAuthorization() {
        var user = TrendFixture.owner();
        var jwt = jwt();
        var authorization = new TrendOwnerAuthorization(users, sessions, jwt, Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC));
        when(users.selectOne(any())).thenReturn(user);
        when(sessions.isCurrentSession(any())).thenReturn(true);
        String token = JwtUtils.createToken(42, UserRoleEnum.OWNER, "PC", jwt.signingKey(), TrendAiFixture.NOW, Duration.ofDays(1), 0);
        assertThat(authorization.lockSession(token)).isSameAs(user);
    }

    @ParameterizedTest(name = "拒绝越权或失效屋主会话：{0} -> {3}")
    @MethodSource("rejectedOwnerStates")
    void signedOwnerClaimCannotOverrideLatestRoleStatusCredentialOrSession(
            String scenario, Consumer<User> mutateUser, boolean sessionActive, ErrorCode expectedCode) {
        var user = TrendFixture.owner();
        mutateUser.accept(user);
        var jwt = jwt();
        var authorization = new TrendOwnerAuthorization(users, sessions, jwt, Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC));
        when(users.selectOne(any())).thenReturn(user);
        when(sessions.isCurrentSession(any())).thenReturn(sessionActive);
        String token = JwtUtils.createToken(42, UserRoleEnum.OWNER, "PC", jwt.signingKey(), TrendAiFixture.NOW, Duration.ofDays(1), 0);
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authorization.lockSession(token))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(expectedCode);
    }

    private static Stream<Arguments> rejectedOwnerStates() {
        return Stream.of(
                Arguments.of("角色已降级为管理员", (Consumer<User>) user -> user.setRole(UserRoleEnum.ADMIN), true, ErrorCode.FORBIDDEN),
                Arguments.of("凭据版本已递增", (Consumer<User>) user -> user.setCredentialVersion(1L), true, ErrorCode.UNAUTHORIZED),
                Arguments.of("账号已停用", (Consumer<User>) user -> user.setStatus(0), true, ErrorCode.UNAUTHORIZED),
                Arguments.of("Redis 会话已撤销", (Consumer<User>) user -> { }, false, ErrorCode.UNAUTHORIZED),
                Arguments.of("仍需修改初始密码", (Consumer<User>) user -> user.setPasswordChangeRequired(true), true, ErrorCode.PASSWORD_CHANGE_REQUIRED));
    }

    @Test void workerRespectsInitialPasswordRestrictionAndSessionRejectsMalformedToken() {
        var user = TrendFixture.owner();
        user.setPasswordChangeRequired(true);
        var jwt = jwt();
        var authorization = new TrendOwnerAuthorization(users, sessions, jwt, Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC));
        when(users.selectOne(any())).thenReturn(user);
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authorization.lockActor(42, 0))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.PASSWORD_CHANGE_REQUIRED);
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authorization.lockSession("bad"))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    private static JwtProperties jwt() {
        var jwt = new JwtProperties(); jwt.setSecret(Base64.getEncoder().encodeToString(new byte[32])); return jwt;
    }
}

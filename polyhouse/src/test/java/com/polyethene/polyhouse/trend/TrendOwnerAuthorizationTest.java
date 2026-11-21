package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.service.AuthRedisStore;
import com.polyethene.polyhouse.service.trend.TrendOwnerAuthorization;
import com.polyethene.polyhouse.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrendOwnerAuthorizationTest {
    private final UserMapper users = mock(UserMapper.class);
    private final AuthRedisStore sessions = mock(AuthRedisStore.class);

    @Test void signedOwnerClaimCannotOverrideLatestRoleStatusOrCredentialVersion() {
        var user = TrendFixture.owner();
        var jwt = jwt();
        var authorization = new TrendOwnerAuthorization(users, sessions, jwt, Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC));
        when(users.selectOne(any())).thenReturn(user);
        when(sessions.isCurrentSession(any())).thenReturn(true);
        String token = JwtUtils.createToken(42, UserRoleEnum.OWNER, "PC", jwt.signingKey(), TrendAiFixture.NOW, Duration.ofDays(1), 0);
        assertThat(authorization.lockSession(token)).isSameAs(user);
        user.setRole(UserRoleEnum.ADMIN);
        assertThatThrownBy(() -> authorization.lockSession(token)).isInstanceOf(BusinessException.class);
        user.setRole(UserRoleEnum.OWNER); user.setCredentialVersion(1L);
        assertThatThrownBy(() -> authorization.lockSession(token)).isInstanceOf(BusinessException.class);
        user.setCredentialVersion(0L); user.setStatus(0);
        assertThatThrownBy(() -> authorization.lockSession(token)).isInstanceOf(BusinessException.class);
    }

    @Test void revokedSessionCannotReadAndWorkerMustRespectInitialPasswordRestriction() {
        var user = TrendFixture.owner();
        var jwt = jwt();
        var authorization = new TrendOwnerAuthorization(users, sessions, jwt, Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC));
        when(users.selectOne(any())).thenReturn(user);
        String token = JwtUtils.createToken(42, UserRoleEnum.OWNER, "PC", jwt.signingKey(), TrendAiFixture.NOW, Duration.ofDays(1), 0);
        assertThatThrownBy(() -> authorization.lockSession(token)).isInstanceOf(BusinessException.class);
        user.setPasswordChangeRequired(true);
        assertThatThrownBy(() -> authorization.lockActor(42, 0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> authorization.lockSession("bad")).isInstanceOf(BusinessException.class);
    }

    private static JwtProperties jwt() {
        var jwt = new JwtProperties(); jwt.setSecret(Base64.getEncoder().encodeToString(new byte[32])); return jwt;
    }
}

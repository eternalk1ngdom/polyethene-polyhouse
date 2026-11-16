package com.polyethene.polyhouse.service.trend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.common.security.SessionSecurity;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.service.AuthRedisStore;
import com.polyethene.polyhouse.utils.JwtUtils;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Component;
import java.time.Clock;

/** 复用现有JWT/会话事实，在模块内明确限制屋主；不改变公共认证或伪造后台身份。 */
@Component
public class TrendOwnerAuthorization {
    private final UserMapper users;
    private final AuthRedisStore sessions;
    private final JwtProperties jwt;
    private final Clock clock;

    /** 管理事务先锁操作者行，再锁任务槽；没有跨模块的新认证体系。 */
    public TrendOwnerAuthorization(UserMapper users, AuthRedisStore sessions, JwtProperties jwt, Clock clock) {
        this.users = users; this.sessions = sessions; this.jwt = jwt; this.clock = clock;
    }

    /** 仅在短事务内调用；会话撤销后不再受理或读取管理数据。 */
    public User lockSession(String token) {
        final JwtUtils.TokenClaims claims;
        try { claims = JwtUtils.parseAndValidate(token, jwt.signingKey(), clock.instant()); }
        catch (JwtException failure) { throw new BusinessException(ErrorCode.UNAUTHORIZED); }
        catch (IllegalArgumentException failure) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
        var actor = lockActor(claims.userId(), claims.credentialVersion());
        if (!sessions.isCurrentSession(claims)) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return actor;
    }

    /** 已接受任务不保留令牌；执行时复核账号状态、屋主角色及凭据版本，退出登录不撤销已接受意图。 */
    public User lockActor(long id, long version) {
        var user = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, id).last("FOR UPDATE"));
        SessionSecurity.requireCurrent(user, version);
        SessionSecurity.requireFullAccess(user);
        if (user.getRole() != UserRoleEnum.OWNER) throw new BusinessException(ErrorCode.FORBIDDEN);
        return user;
    }
}

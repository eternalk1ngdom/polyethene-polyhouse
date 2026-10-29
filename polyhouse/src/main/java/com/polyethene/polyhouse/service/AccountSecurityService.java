package com.polyethene.polyhouse.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.common.security.SessionSecurity;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.AccountAuditMapper;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.PasswordChangeDTO;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.utils.JwtUtils;
import com.polyethene.polyhouse.utils.PasswordUtils;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Validator;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 隔离密码、凭据版本和会话撤销业务，避免将安全事务堆入登录或资料服务。 */
@Service
public class AccountSecurityService {
    private final UserMapper users;
    private final AccountAuditMapper audit;
    private final AuthRedisStore sessions;
    private final JwtProperties jwt;
    private final Validator validator;

    /** 使用项目已有密钥与会话存储，不生成第二套认证凭据或 Redis 客户端。 */
    public AccountSecurityService(UserMapper users, AccountAuditMapper audit, AuthRedisStore sessions,
                                  JwtProperties jwt, Validator validator) {
        this.users = users;
        this.audit = audit;
        this.sessions = sessions;
        this.jwt = jwt;
        this.validator = validator;
    }

    /**
     * 同一用户行锁串行化登录/改密/注销；失败回滚数据库，Redis 已撤销的会话不尝试恢复。
     * 密码、版本、首次改密状态和审计原子提交；成功不签发替代 Token，所有终端必须重新登录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(PasswordChangeDTO request, String token) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        JwtUtils.TokenClaims claims;
        try {
            claims = JwtUtils.parseAndValidate(token, jwt.signingKey(), Instant.now());
        } catch (JwtException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            User user = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, claims.userId()).last("FOR UPDATE"));
            SessionSecurity.requireCurrent(user, claims.credentialVersion());
            if (!sessions.isCurrentSession(claims)) throw new BusinessException(ErrorCode.UNAUTHORIZED);
            if (!PasswordUtils.matches(request.currentPassword(), user.getPasswordHash())) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "当前密码不正确");
            }
            if (PasswordUtils.matches(request.newPassword(), user.getPasswordHash())) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "新密码不能与当前密码相同");
            }
            long version = Math.addExact(user.getCredentialVersion(), 1);
            String nextHash;
            try {
                nextHash = PasswordUtils.hashPassword(request.newPassword());
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER);
            }
            // 定向列更新保留角色、成长和其他资料；版本条件在未来调用变更时仍能防止旧写覆盖。
            if (users.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, user.getId())
                    .eq(User::getStatus, 1).eq(User::getCredentialVersion, user.getCredentialVersion())
                    .set(User::getPasswordHash, nextHash).set(User::getCredentialVersion, version)
                    .set(User::getPasswordChangeRequired, false)) != 1
                    || audit.recordPasswordChange(user.getId(), version) != 1) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }
            // Redis 不属于 MySQL 事务：删除失败不伪报成功，数据库版本也是旧 Token 的持久拒绝边界。
            sessions.removeAllSessions(user.getId());
        } catch (DataAccessException | ArithmeticException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}

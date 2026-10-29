package com.polyethene.polyhouse.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.common.security.AccountAuthorizationPolicy;
import com.polyethene.polyhouse.common.security.SessionSecurity;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.AccountManagementMapper;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.entity.*;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.utils.JwtUtils;
import com.polyethene.polyhouse.utils.PasswordUtils;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Validator;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.Instant;

/** 仅处理受控账号列表、封禁/解封和代重置；不介入登录、成长、发号或资料审核。 */
@Service
public class AccountManagementService {
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();
    private final UserMapper users;
    private final AccountManagementMapper management;
    private final AuthRedisStore sessions;
    private final JwtProperties jwt;
    private final Validator validator;

    /** 复用既有认证和数据库事务，不引入第二套登录或跨库事务机制。 */
    public AccountManagementService(UserMapper users, AccountManagementMapper management, AuthRedisStore sessions,
                                     JwtProperties jwt, Validator validator) {
        this.users = users;
        this.management = management;
        this.sessions = sessions;
        this.jwt = jwt;
        this.validator = validator;
    }

    /** 最新角色在锁内验证，SQL 同时约束行和计数；只返回最小投影及有限页大小。 */
    @Transactional(rollbackFor = Exception.class)
    public AccountPageVO list(AccountListDTO request, String token) {
        validate(request);
        try {
            var claims = claims(token);
            User actor = lockUser(claims.userId());
            requireManager(actor, claims);
            boolean owner = actor.getRole() == UserRoleEnum.OWNER;
            if (!owner && request.role() != null && request.role() != UserRoleEnum.USER) forbidden();
            long total = management.count(request, owner, actor.getId());
            var rows = management.list(request, owner, actor.getId(), (request.page() - 1) * request.pageSize());
            return new AccountPageVO(rows.stream().map(ManagedAccountVO::from).toList(), Long.toString(total),
                    request.page(), request.pageSize());
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * 对操作者/目标按编号升序加锁，避免 OWNER 管理 ADMIN 与 ADMIN 管理 USER 时形成反向锁环。
     * 同一意图成功后只返回事实；凭据、版本和审计同库提交，Redis 撤销后失败不恢复旧会话。
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountOperationVO operate(AccountTargetDTO target, AccountOperationDTO request,
                                       AccountManagementAction action, String token) {
        validate(target);
        validate(request);
        if (action == null) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        try {
            var claims = claims(token);
            long actorId = claims.userId();
            User first = lockUser(Math.min(actorId, target.id()));
            User second = actorId == target.id() ? first : lockUser(Math.max(actorId, target.id()));
            User actor = actorId <= target.id() ? first : second;
            User subject = actorId <= target.id() ? second : first;
            requireManager(actor, claims);
            if (subject == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            if (actorId == subject.getId() || !(action == AccountManagementAction.BAN
                    ? AccountAuthorizationPolicy.canBan(actor.getRole(), subject.getRole())
                    : AccountAuthorizationPolicy.canRecoverAccount(actor.getRole(), subject.getRole()))) forbidden();
            ManagementOperation previous = management.find(actorId, request.requestId());
            if (previous != null) {
                if (previous.targetId() != target.id() || previous.action() != action
                        || previous.beforeVersion() != request.expectedVersion()
                        || previous.targetRole() != request.expectedRole() || previous.beforeStatus() != request.expectedStatus()
                        || !previous.reason().equals(request.reason())) conflict();
                return AccountOperationVO.confirmed(previous);
            }
            Long version = management.version(subject.getId());
            if (version == null) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            if (!version.equals(request.expectedVersion()) || subject.getRole() != request.expectedRole()
                    || !subject.getStatus().equals(request.expectedStatus())) conflict();
            if (action == AccountManagementAction.BAN && subject.getStatus() != 1
                    || action == AccountManagementAction.UNBAN && subject.getStatus() != 0) conflict();
            boolean reset = action == AccountManagementAction.PASSWORD_RESET;
            if (reset || action == AccountManagementAction.BAN && subject.getRole() == UserRoleEnum.ADMIN) {
                if (request.ownerPassword() == null || request.ownerPassword().length() < 8
                        || !PasswordUtils.matches(request.ownerPassword(), actor.getPasswordHash())) {
                    throw new BusinessException(ErrorCode.INVALID_PARAMETER, "请核对屋主当前密码");
                }
            }
            long nextVersion = Math.addExact(version, 1);
            long nextCredential = Math.addExact(subject.getCredentialVersion(), 1);
            int nextStatus = action == AccountManagementAction.BAN ? 0 : action == AccountManagementAction.UNBAN ? 1 : subject.getStatus();
            String password = reset ? randomPassword() : null;
            if (management.apply(subject.getId(), subject.getRole(), version, nextVersion, nextCredential, nextStatus,
                    reset, reset ? PasswordUtils.hashPassword(password) : null) != 1) conflict();
            var operation = new ManagementOperation(actorId, request.requestId(), subject.getId(), subject.getUsername(),
                    action, actor.getRole(), subject.getRole(), subject.getStatus(), nextStatus, version, nextVersion, request.reason());
            if (management.record(operation) != 1) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            sessions.removeAllSessions(subject.getId());
            return new AccountOperationVO(request.requestId(), Long.toString(subject.getId()), subject.getUsername(), action,
                    Long.toString(nextVersion), nextStatus, reset, password);
        } catch (DataAccessException | ArithmeticException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** 响应未知时只确认本人请求成功事实，绝不再交付明文或重新执行；404 也可能原请求尚未提交。 */
    @Transactional(rollbackFor = Exception.class)
    public AccountOperationVO confirm(AccountCreationQueryDTO request, String token) {
        validate(request);
        try {
            var claims = claims(token);
            User actor = lockUser(claims.userId());
            requireManager(actor, claims);
            var previous = management.find(actor.getId(), request.requestId());
            if (previous == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            if (actor.getRole() == UserRoleEnum.ADMIN && (previous.targetRole() != UserRoleEnum.USER
                    || previous.action() != AccountManagementAction.BAN)) forbidden();
            return AccountOperationVO.confirmed(previous);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private User lockUser(long id) {
        return users.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, id).last("FOR UPDATE"));
    }

    private void requireManager(User actor, JwtUtils.TokenClaims claims) {
        SessionSecurity.requireCurrent(actor, claims.credentialVersion());
        SessionSecurity.requireFullAccess(actor);
        if (!sessions.isCurrentSession(claims)) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (!AccountAuthorizationPolicy.canManageAccounts(actor.getRole())) forbidden();
    }

    private JwtUtils.TokenClaims claims(String token) {
        try {
            return JwtUtils.parseAndValidate(token, jwt.signingKey(), Instant.now());
        } catch (JwtException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private String randomPassword() {
        char[] password = new char[20];
        // 有界均匀采样，不从账号或日期推导，不截断 BCrypt 密码。
        for (int index = 0; index < password.length; index++) {
            password[index] = PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length()));
        }
        return new String(password);
    }

    private void validate(Object value) {
        if (value == null || !validator.validate(value).isEmpty()) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
    }

    private void forbidden() { throw new BusinessException(ErrorCode.FORBIDDEN); }
    private void conflict() { throw new BusinessException(ErrorCode.CONFLICT); }
}

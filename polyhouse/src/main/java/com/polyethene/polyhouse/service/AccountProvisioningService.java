package com.polyethene.polyhouse.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.common.security.AccountAuthorizationPolicy;
import com.polyethene.polyhouse.common.security.SessionSecurity;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.AccountAuditMapper;
import com.polyethene.polyhouse.mapper.AccountProvisioningMapper;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.AccountCreateDTO;
import com.polyethene.polyhouse.model.dto.AccountCreationQueryDTO;
import com.polyethene.polyhouse.model.entity.AccountCreation;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.model.vo.AccountCreationVO;
import com.polyethene.polyhouse.utils.JwtUtils;
import com.polyethene.polyhouse.utils.PasswordUtils;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Validator;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/** 只负责屋主发放账号；发号、幂等事实、用户与审计同事务，登录、封禁和审核各自隔离。 */
@Service
public class AccountProvisioningService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final long LIMIT = 1_000_000_000_000_000_000L;
    private final SecureRandom random = new SecureRandom();
    private final AccountProvisioningMapper provisioning;
    private final UserMapper users;
    private final AccountAuditMapper audit;
    private final AuthRedisStore sessions;
    private final JwtProperties jwt;
    private final Validator validator;

    /** 复用现有数据源、会话和凭据算法，避免出现第二套认证体系。 */
    public AccountProvisioningService(AccountProvisioningMapper provisioning, UserMapper users, AccountAuditMapper audit,
                                      AuthRedisStore sessions, JwtProperties jwt, Validator validator) {
        this.provisioning = provisioning;
        this.users = users;
        this.audit = audit;
        this.sessions = sessions;
        this.jwt = jwt;
        this.validator = validator;
    }

    /** 锁顺序固定为操作者用户行→发号行；不触及其他既有用户，重复请求只返回无密码的创建事实。 */
    @Transactional(rollbackFor = Exception.class)
    public AccountCreationVO create(AccountCreateDTO request, String token) {
        validate(request);
        try {
            User actor = lockOwner(token);
            if (!AccountAuthorizationPolicy.canCreate(actor.getRole(), request.role())) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            AccountCreation previous = provisioning.find(actor.getId(), request.requestId());
            if (previous != null) {
                if (previous.role() != request.role()) {
                    throw new BusinessException(ErrorCode.CONFLICT);
                }
                return AccountCreationVO.confirmed(previous);
            }
            if (request.role() == UserRoleEnum.ADMIN && !PasswordUtils.matches(request.ownerPassword(), actor.getPasswordHash())) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "屋主当前密码不正确");
            }
            Long next = provisioning.lockSequence();
            if (next == null || next < 1 || next >= LIMIT) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }
            String suffix;
            String username;
            // 迁移已跳过历史最大号；仍核对全部旧名，兼容迁移后受控导入或大小写等价碰撞。
            do {
                suffix = String.format(Locale.ROOT, "%06d", next++);
                username = "ph" + suffix;
                if (next > LIMIT) {
                    throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
                }
            } while (provisioning.occupied(username) != 0);
            if (provisioning.advance(next) != 1) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }
            String nickname = "小屋住户" + suffix;
            String password = randomPassword();
            long id = IdWorker.getId();
            if (provisioning.create(id, username, nickname, PasswordUtils.hashPassword(password), request.role()) != 1
                    || provisioning.record(actor.getId(), request.requestId(), id, username, nickname, request.role()) != 1
                    || audit.recordCreation(actor.getId(), id) != 1) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }
            return new AccountCreationVO(Long.toString(id), username, nickname, request.role(), true, password);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** 仅确认当前屋主同一请求是否提交，不触发创建、不签发凭据或泄露其他操作者的记录。 */
    @Transactional(rollbackFor = Exception.class)
    public AccountCreationVO confirm(AccountCreationQueryDTO request, String token) {
        validate(request);
        try {
            User actor = lockOwner(token);
            AccountCreation record = provisioning.find(actor.getId(), request.requestId());
            if (record == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            return AccountCreationVO.confirmed(record);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private User lockOwner(String token) {
        JwtUtils.TokenClaims claims;
        try {
            claims = JwtUtils.parseAndValidate(token, jwt.signingKey(), Instant.now());
        } catch (JwtException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        User actor = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, claims.userId()).last("FOR UPDATE"));
        SessionSecurity.requireCurrent(actor, claims.credentialVersion());
        SessionSecurity.requireFullAccess(actor);
        if (!sessions.isCurrentSession(claims)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (actor.getRole() != UserRoleEnum.OWNER) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return actor;
    }

    private String randomPassword() {
        char[] value = new char[20];
        // SecureRandom.nextInt(bound) 无取模偏差；二十个 ASCII 字符远低于 BCrypt 的七十二字节上限。
        for (int index = 0; index < value.length; index++) {
            value[index] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(value);
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
    }
}

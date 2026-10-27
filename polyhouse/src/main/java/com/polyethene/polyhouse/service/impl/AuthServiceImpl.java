package com.polyethene.polyhouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.LoginDTO;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.model.vo.LoginVO;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import com.polyethene.polyhouse.model.vo.UserGrowthVO;
import com.polyethene.polyhouse.service.AuthRedisStore;
import com.polyethene.polyhouse.service.AuthService;
import com.polyethene.polyhouse.service.UserGrowthService;
import com.polyethene.polyhouse.utils.JwtUtils;
import com.polyethene.polyhouse.utils.PasswordUtils;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 组织用户持久化、密码核验和 Redis 会话；Controller 与纯工具类不承担这些有状态业务。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final int ACTIVE_STATUS = 1;
    private static final ZoneId AUDIT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 不存在账号也做一次相同成本的 BCrypt 验证，减少通过响应耗时枚举账号的差异。 */
    private static final String DUMMY_PASSWORD_HASH = PasswordUtils.hashPassword(UUID.randomUUID().toString());

    private final UserMapper mapper;
    private final AuthRedisStore sessions;
    private final JwtProperties jwt;
    private final Validator validator;
    private final Clock clock;
    private final UserGrowthService growth;

    /** 注入有状态依赖，真实运行使用 UTC 时钟，数据库审计时间显式转换到既有上海时区约定。 */
    @Autowired
    public AuthServiceImpl(UserMapper mapper, AuthRedisStore sessions, JwtProperties jwt, Validator validator,
                           UserGrowthService growth) {
        this(mapper, sessions, jwt, validator, growth, Clock.systemUTC());
    }

    /** 包内构造入口允许测试提供固定时钟，不修改系统时间或在工具类中缓存时间。 */
    AuthServiceImpl(UserMapper mapper, AuthRedisStore sessions, JwtProperties jwt, Validator validator,
                    UserGrowthService growth, Clock clock) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.jwt = jwt;
        this.validator = validator;
        this.clock = clock;
        this.growth = growth;
    }

    /** 注册只创建 USER，数据库插入与安全资料读取在同一事务内完成，冲突时回滚。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileVO register(RegisterDTO request) {
        validate(request);
        String username = canonicalUsername(request.username());
        try {
            if (mapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
                throw accountConflict();
            }
            User user = new User();
            user.setUsername(username);
            try {
                user.setPasswordHash(PasswordUtils.hashPassword(request.password()));
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER);
            }
            user.setNickname(request.nickname());
            user.setRole(UserRoleEnum.USER);
            user.setStatus(ACTIVE_STATUS);
            user.setDeleted(0);
            if (mapper.insert(user) != 1) {
                throw unavailable();
            }
            User persisted = mapper.selectById(user.getId());
            if (persisted == null) {
                throw unavailable();
            }
            return UserProfileVO.from(persisted);
        } catch (DuplicateKeyException exception) {
            // 预查询无法阻止并发注册；唯一索引同时保护并发和已软删除账号，不回传 SQL 异常链。
            throw accountConflict();
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    /** 密码核验通过后才签发；审计更新持有的用户行锁覆盖会话发布，与注销串行化。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO login(LoginDTO request, String clientIp) {
        validate(request);
        if (clientIp != null && (clientIp.isBlank() || clientIp.length() > 64)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        String username = canonicalUsername(request.username());
        User user;
        try {
            user = mapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        } catch (DataAccessException exception) {
            throw unavailable();
        }
        boolean matched = PasswordUtils.matches(request.password(), user == null ? DUMMY_PASSWORD_HASH : user.getPasswordHash());
        if (user == null || !matched || !isActive(user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }

        SecretKey key = signingKey();
        Instant now = clock.instant();
        String token = JwtUtils.createToken(user.getId(), user.getRole(), request.clientType(), key, now, jwt.tokenTtl());
        JwtUtils.TokenClaims claims = JwtUtils.parseAndValidate(token, key, now);
        try {
            // 只更新审计列；同时复核状态与原密码哈希，避免覆盖管理员刚修改的角色或放行已换密码的账号。
            int updated = mapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, user.getId()).eq(User::getStatus, ACTIVE_STATUS)
                    .eq(User::getPasswordHash, user.getPasswordHash())
                    .set(User::getLastLoginIp, clientIp)
                    .set(User::getLastLoginTime, LocalDateTime.ofInstant(now, AUDIT_ZONE)));
            if (updated != 1) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
            }
        } catch (DataAccessException exception) {
            throw unavailable();
        }
        // 更新的行锁保持到事务结束，注销必须等待本次会话发布，或先删除账号使本次更新失败。
        // Redis 不参与数据库事务；提交失败即使留下 jti，身份检查仍会复核数据库状态。
        User rewarded = growth.rewardDailyLogin(user.getId());
        sessions.saveSession(claims, Duration.between(clock.instant(), claims.expiresAt()));
        return new LoginVO(token, "Bearer", claims.expiresAt(), UserProfileVO.from(rewarded));
    }

    /** 从数据库读取当前角色而非盲信 Token 中的旧角色快照，确保降权、禁用和软删除及时生效。 */
    @Override
    public UserProfileVO getCurrentUser(String token) {
        JwtUtils.TokenClaims claims = parseToken(token);
        if (!sessions.isCurrentSession(claims)) {
            throw unauthorized();
        }
        // 已通过签名和白名单校验的活跃访问也算每日登录；成长服务锁定读复核最新角色和账号状态。
        return UserProfileVO.from(growth.rewardDailyLogin(claims.userId()));
    }

    /** 与资料查询一样独立核对会话，成长服务再锁定读复核账号状态，不依赖客户端资料。 */
    @Override
    public UserGrowthVO getGrowthStatus(String token) {
        JwtUtils.TokenClaims claims = parseToken(token);
        if (!sessions.isCurrentSession(claims)) {
            throw unauthorized();
        }
        return growth.getStatus(claims.userId());
    }

    /** 退出不要求账号仍启用，允许撤销自己的旧会话；Lua 确保不会误删新登录。 */
    @Override
    public void logout(String token) {
        sessions.removeSession(parseToken(token));
    }

    /** 先锁定用户并验证当前会话，再软删除及清理全部终端；Redis 失败会回滚数据库删除。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deregister(String token) {
        JwtUtils.TokenClaims claims = parseToken(token);
        try {
            // 固定 SQL 后缀无用户输入；锁定读读取最新状态，并与登录的审计更新共用同一用户行锁。
            User user = mapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getId, claims.userId()).last("FOR UPDATE"));
            if (user == null || !isActive(user) || !sessions.isCurrentSession(claims)) {
                throw unauthorized();
            }
            // @TableLogic 将 deleteById 转为 deleted=1，保留用户名唯一索引和原始业务关联。
            if (mapper.deleteById(claims.userId()) != 1) {
                throw unavailable();
            }
            sessions.removeAllSessions(claims.userId());
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private JwtUtils.TokenClaims parseToken(String token) {
        SecretKey key = signingKey();
        try {
            return JwtUtils.parseAndValidate(token, key, clock.instant());
        } catch (JwtException exception) {
            throw unauthorized();
        }
    }

    private SecretKey signingKey() {
        try {
            return jwt.signingKey();
        } catch (IllegalArgumentException exception) {
            // 密钥仍是本地待办；缺配置时拒绝签发，不自动产生替代密钥，也不泄露配置值。
            throw unavailable();
        }
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
    }

    private static String canonicalUsername(String username) {
        // DTO 已限定 ASCII 账号；小写规范化与现有 MySQL 不区分大小写的唯一索引一致，保持查找和判重规则统一。
        return username.toLowerCase(Locale.ROOT);
    }

    private static boolean isActive(User user) {
        return Objects.equals(user.getStatus(), ACTIVE_STATUS) && Objects.equals(user.getDeleted(), 0)
                && user.getRole() != null;
    }

    private static BusinessException accountConflict() {
        return new BusinessException(ErrorCode.CONFLICT, "账号已被使用");
    }

    private static BusinessException unauthorized() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "认证服务暂时不可用，请稍后重试");
    }
}

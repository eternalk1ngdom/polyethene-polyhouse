package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.utils.JwtUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 集中处理有状态的 Redis 鉴权操作，避免将缓存访问放进纯工具类。
 * 保存会话通过单条 SET 同时设置值和 TTL，退出通过单键 Lua 比较后删除。
 */
@Service
public class AuthRedisStore {

    private static final DefaultRedisScript<Long> LOGOUT_SCRIPT = script("redis/auth_remove_session.lua");

    private final StringRedisTemplate redis;

    /** 通过构造注入复用 Boot 管理的连接和字符串序列化配置，不自建 Redis 客户端。 */
    public AuthRedisStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 通过单条 SET 保存会话及剩余 TTL，不维护失败计数或锁定状态。
     * Redis 只保存随机 jti，不保存可以直接使用的完整 Bearer Token。
     *
     * @param claims 刚签发并验证过的令牌声明
     * @param remainingTtl 从 expiresAt 减当前时间得到的剩余时长，禁止重新延长到完整七天
     */
    public void saveSession(JwtUtils.TokenClaims claims, Duration remainingTtl) {
        if (remainingTtl == null || remainingTtl.toMillis() <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        try {
            redis.opsForValue().set(sessionKey(claims), claims.tokenId(), remainingTtl);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    /** 检查当前终端仍保留同一 jti；Redis 不可用时明确失败，不退化为仅验 JWT。 */
    public boolean isCurrentSession(JwtUtils.TokenClaims claims) {
        try {
            return claims.tokenId().equals(redis.opsForValue().get(sessionKey(claims)));
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    /** 原子比较后删除当前 jti，重复退出或旧 Token 退出不会误删新会话。 */
    public void removeSession(JwtUtils.TokenClaims claims) {
        execute(LOGOUT_SCRIPT, List.of(sessionKey(claims)), claims.tokenId());
    }

    /**
     * 清理 JWT 当前支持的全部终端，仅删除精确用户键，避免使用 KEYS 扫描或误删其他用户。
     * 调用者必须持有用户行锁，防止并发登录在删除后重新发布会话；新增终端时须同步扩展此清单。
     *
     * @param userId 从有效 Token 解析的当前用户主键
     */
    public void removeAllSessions(long userId) {
        List<String> keys = List.of("PC", "H5", "PLUGIN").stream()
                .map(client -> RedisConstants.buildAuthTokenKey(userId, client)).toList();
        try {
            // 单条 DEL 清理所有支持终端；0 表示键已不存在，仍是成功，不要求恰好删除三个键。
            if (redis.delete(keys) == null) {
                throw unavailable();
            }
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private long execute(DefaultRedisScript<Long> script, List<String> keys, String... arguments) {
        try {
            Long result = redis.execute(script, keys, (Object[]) arguments);
            if (result == null) {
                throw unavailable();
            }
            return result;
        } catch (DataAccessException exception) {
            // 不保存原异常链，底层错误可能包含键、脚本参数或连接信息。
            throw unavailable();
        }
    }

    private static String sessionKey(JwtUtils.TokenClaims claims) {
        return RedisConstants.buildAuthTokenKey(claims.userId(), claims.clientType());
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "认证服务暂时不可用，请稍后重试");
    }
}

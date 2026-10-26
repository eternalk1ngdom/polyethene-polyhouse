package com.polyethene.polyhouse.utils;

import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.SigningKeyResolverAdapter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * 提供本项目访问令牌的 HS256 签发和验证；密钥、时间和有效期均由调用方传入。
 * 不注入 Bean、不读取环境、不访问数据库或 Redis，也不把原始 Token 写入日志或异常。
 */
public final class JwtUtils {

    private static final String ISSUER = "polyhouse";
    private static final String AUDIENCE = "polyhouse-api";
    private static final String TOKEN_TYPE = "ACCESS";
    private static final String KEY_ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final String INVALID_TOKEN_MESSAGE = "invalid access token";
    private static final String INVALID_KEY_MESSAGE = "JWT secret must be standard Base64 of exactly 32 random bytes";
    private static final Set<String> CLIENT_TYPES = Set.of("PC", "H5", "PLUGIN");

    private JwtUtils() {
    }

    /**
     * 将外部配置的标准 Base64 密钥还原为 HS256 密钥，不把人类密码当作签名密钥。
     * 配置方必须用安全随机数生成器生成 32 字节；长度检查不能判断输入是否真正随机。
     *
     * @param base64Secret 标准 Base64 编码的 32 字节随机密钥，不接受缺失或短密钥
     * @return 可用于本项目 HS256 运算的密钥，不缓存到工具类静态字段
     * @throws IllegalArgumentException 配置不满足编码或长度要求时抛出，消息不回显密钥
     */
    public static SecretKey keyFromBase64(String base64Secret) {
        if (base64Secret == null || base64Secret.length() != 44) {
            throw new IllegalArgumentException(INVALID_KEY_MESSAGE);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Secret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(INVALID_KEY_MESSAGE);
        }
        try {
            if (decoded.length != KEY_BYTES) {
                throw new IllegalArgumentException(INVALID_KEY_MESSAGE);
            }
            return new SecretKeySpec(decoded, KEY_ALGORITHM);
        } finally {
            // SecretKeySpec 已复制数据，清理临时解码数组，减少额外的密钥副本存留。
            Arrays.fill(decoded, (byte) 0);
        }
    }

    /**
     * 为已经完成身份验证的用户签发访问令牌，只携带最小身份及会话信息，不携带凭据。
     * 独立的随机 jti 使同一用户同一秒的两次登录仍能区分，便于后续白名单替换与撤销。
     *
     * @param userId 来自服务端用户记录的正整数主键，作为字符串 subject 保存
     * @param role 来自服务端的语义角色，禁止直接采用客户端传入的角色
     * @param clientType PC/H5/PLUGIN；null 使用现有默认 PC，其他无效值直接拒绝
     * @param signingKey HS256 的 32 字节密钥，由配置层提供
     * @param now 调用方提供的签发时间，统一截断到 JWT 使用的秒精度
     * @param ttl 来自统一配置的有效期，必须是正的整秒时长
     * @return 完整签名 Token，只能交给业务层传递和保存，禁止日志输出
     */
    public static String createToken(long userId, UserRoleEnum role, String clientType,
                                     SecretKey signingKey, Instant now, Duration ttl) {
        SecretKey key = checkedKey(signingKey);
        String client = clientType == null ? RedisConstants.DEFAULT_CLIENT_TYPE : clientType;
        if (userId <= 0 || role == null || !CLIENT_TYPES.contains(client)
                || now == null || ttl == null || ttl.isNegative() || ttl.isZero() || ttl.getNano() != 0) {
            throw new IllegalArgumentException("invalid token issuance parameters");
        }
        Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
        Date issued;
        Date expires;
        try {
            issued = Date.from(issuedAt);
            expires = Date.from(issuedAt.plus(ttl));
        } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException exception) {
            throw new IllegalArgumentException("token time is outside the supported range");
        }
        return Jwts.builder()
                .setHeaderParam(Header.TYPE, Header.JWT_TYPE)
                .setIssuer(ISSUER)
                .setAudience(AUDIENCE)
                .setSubject(Long.toString(userId))
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(issued)
                .setExpiration(expires)
                .claim("tokenType", TOKEN_TYPE)
                .claim("role", role.getCode())
                .claim("clientType", client)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 验证签名、算法、应用归属、用途、身份字段和有效期后，才返回不可变的类型化身份。
     * 此方法只证明 Token 自身有效，不证明账号仍启用或仍在 Redis 白名单内。
     *
     * @param token 原始紧凑 Token，不包含 Bearer 前缀，不自动裁剪空白
     * @param signingKey 配置层提供的 HS256 密钥，配置错误与无效 Token 分开处理
     * @param now 调用方提供的验证时间，不容忍过期或未来签发时间
     * @return 已核验的身份字段，不能用原始未验签载荷代替
     * @throws JwtException Token 无效时抛出固定消息，不保留可能携带原始载荷的异常链
     */
    public static TokenClaims parseAndValidate(String token, SecretKey signingKey, Instant now) {
        SecretKey key = checkedKey(signingKey);
        if (now == null) {
            throw new IllegalArgumentException("verification time must not be null");
        }
        if (token == null || token.length() > MAX_TOKEN_LENGTH || token.isBlank()
                || !token.equals(token.strip())) {
            throw invalidToken();
        }
        try {
            Jws<Claims> parsed = Jwts.parserBuilder()
                    .setSigningKeyResolver(new Hs256KeyResolver(key))
                    .setClock(() -> Date.from(now))
                    .setAllowedClockSkewSeconds(0)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .require("tokenType", TOKEN_TYPE)
                    .setCompressionCodecResolver(header -> {
                        // 在库解压载荷前拒绝 zip，避免小型输入展开为超大 JSON。
                        if (header.containsKey(Header.COMPRESSION_ALGORITHM)
                                || header.containsKey(Header.DEPRECATED_COMPRESSION_ALGORITHM)) {
                            throw invalidToken();
                        }
                        return null;
                    })
                    .build().parseClaimsJws(token);
            if (!Header.JWT_TYPE.equals(parsed.getHeader().get(Header.TYPE))) {
                throw invalidToken();
            }
            Claims claims = parsed.getBody();
            String subject = claims.get(Claims.SUBJECT, String.class);
            long userId = Long.parseLong(subject);
            if (userId <= 0 || !Long.toString(userId).equals(subject)) {
                throw invalidToken();
            }
            String roleCode = claims.get("role", String.class);
            if (roleCode == null) {
                throw invalidToken();
            }
            UserRoleEnum role = UserRoleEnum.valueOf(roleCode);
            String client = claims.get("clientType", String.class);
            String tokenId = claims.get(Claims.ID, String.class);
            if (client == null || !CLIENT_TYPES.contains(client) || tokenId == null
                    || !UUID.fromString(tokenId).toString().equals(tokenId)) {
                throw invalidToken();
            }
            // 本项目签发整秒 NumericDate；拒绝字符串或浮点时间，避免库的宽松类型转换产生歧义。
            if (!isWholeSecondTimestamp(claims.get(Claims.ISSUED_AT))
                    || !isWholeSecondTimestamp(claims.get(Claims.EXPIRATION))) {
                throw invalidToken();
            }
            Date issued = claims.getIssuedAt();
            Date expires = claims.getExpiration();
            if (issued == null || expires == null || issued.toInstant().isAfter(now)
                    || !expires.after(issued) || !now.isBefore(expires.toInstant())) {
                // 显式拒绝 now == exp，不能依赖旧库只比较 now > exp 的边界行为。
                throw invalidToken();
            }
            return new TokenClaims(userId, role, client, tokenId, issued.toInstant(), expires.toInstant());
        } catch (JwtException | IllegalArgumentException | IllegalStateException | ClassCastException exception) {
            // 旧库将部分错误 NumericDate 抛为 IllegalStateException；统一替换，不能泄露声明值或异常链。
            throw invalidToken();
        }
    }

    private static SecretKey checkedKey(SecretKey key) {
        if (key == null || !KEY_ALGORITHM.equalsIgnoreCase(key.getAlgorithm())) {
            throw new IllegalArgumentException(INVALID_KEY_MESSAGE);
        }
        byte[] encoded = key.getEncoded();
        if (encoded == null || encoded.length != KEY_BYTES) {
            throw new IllegalArgumentException(INVALID_KEY_MESSAGE);
        }
        return new SecretKeySpec(encoded, KEY_ALGORITHM);
    }

    private static JwtException invalidToken() {
        return new JwtException(INVALID_TOKEN_MESSAGE);
    }

    private static boolean isWholeSecondTimestamp(Object value) {
        return value instanceof Integer || value instanceof Long;
    }

    /**
     * 算法白名单在选取验签密钥时执行，不能根据外部 alg 自由切换算法或读取外部密钥地址。
     */
    private static final class Hs256KeyResolver extends SigningKeyResolverAdapter {

        private final SecretKey key;

        private Hs256KeyResolver(SecretKey key) {
            this.key = key;
        }

        /** 未验签时只检查算法，不使用库传入的声明做身份决策。 */
        @Override
        @SuppressWarnings("rawtypes") // JJWT 0.11.5 的重写接口使用原始 JwsHeader 类型。
        public Key resolveSigningKey(JwsHeader header, Claims claims) {
            if (!SignatureAlgorithm.HS256.getValue().equals(header.get(JwsHeader.ALGORITHM))
                    || header.containsKey(JwsHeader.CRITICAL)) {
                throw invalidToken();
            }
            return key;
        }

        /** 访问令牌必须携带 JSON 声明，拒绝普通文本签名载荷。 */
        @Override
        @SuppressWarnings("rawtypes") // 与固定版本库的接口签名保持一致，不传播到业务模型。
        public Key resolveSigningKey(JwsHeader header, String plaintext) {
            throw invalidToken();
        }
    }

    /**
     * 仅用于内部鉴权的解析结果，不是 Controller 出参，也不携带原始 Token 或密钥。
     *
     * @param userId 经过签名及正整数校验的用户 ID
     * @param role 语义角色快照，角色调整后的即时生效仍依赖后续会话撤销策略
     * @param clientType 经过白名单校验的终端类型
     * @param tokenId 用于区分同一用户多次签发的随机会话标识
     * @param issuedAt 秒精度的签发时间
     * @param expiresAt 过期时间，后续 Redis TTL 应由此减去当前时间得到
     */
    public record TokenClaims(long userId, UserRoleEnum role, String clientType, String tokenId,
                              Instant issuedAt, Instant expiresAt) {
    }
}

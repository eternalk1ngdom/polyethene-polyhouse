package com.polyethene.polyhouse.config;

import com.polyethene.polyhouse.utils.JwtUtils;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.crypto.SecretKey;
import java.time.Duration;

/**
 * 由 Spring 绑定外部 JWT 配置；工具类只接收参数，不负责环境读取或 Bean 管理。
 * 允许未配置密钥时启动其他模块；退出、注销和其他认证 Service 取用密钥时必须验证且不提供默认密钥。
 */
@Component
@Validated
@ConfigurationProperties(prefix = "polyhouse.jwt")
public class JwtProperties {

    private String secret;

    @Min(value = 1, message = "JWT expiration-days must be positive")
    private int expirationDays;

    /** 接收环境变量绑定的密钥，仅写入配置对象，不提供明文 getter 或日志输出。 */
    public void setSecret(String secret) {
        this.secret = secret;
    }

    /** 返回唯一有效期配置，供运行配置检查使用。 */
    public int getExpirationDays() {
        return expirationDays;
    }

    /** 接收配置中的有效天数；Spring 启动绑定后会执行正数校验。 */
    public void setExpirationDays(int expirationDays) {
        this.expirationDays = expirationDays;
    }

    /** 按需校验并生成密钥；缺失、编码错误或长度不合格时明确失败，不自动生成临时替代密钥。 */
    public SecretKey signingKey() {
        return JwtUtils.keyFromBase64(secret);
    }

    /**
     * 将唯一的 expiration-days 转为签发时长，Redis 业务应使用签发结果的剩余时间。
     *
     * @return 正的有效时长，默认七天来自 application.yml，不在代码中维护第二个七天常量
     */
    public Duration tokenTtl() {
        if (expirationDays <= 0) {
            throw new IllegalStateException("JWT expiration-days must be positive");
        }
        return Duration.ofDays(expirationDays);
    }

    /** 提供安全的配置诊断文本，禁止将明文密钥包含在对象文本表示中。 */
    @Override
    public String toString() {
        return "JwtProperties[secret=redacted, expirationDays=" + expirationDays + "]";
    }
}

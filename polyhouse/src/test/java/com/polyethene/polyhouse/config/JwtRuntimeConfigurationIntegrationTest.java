package com.polyethene.polyhouse.config;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 显式核对真实 application.yml 与运行环境的密钥绑定，不用测试随机密钥覆盖配置。
 * 令牌只在内存签发/验签，不访问数据库、Redis，不输出密钥、令牌或载荷。
 */
@EnabledIfSystemProperty(named = "polyhouse.jwt.runtime", matches = "true")
@SpringJUnitConfig(classes = JwtRuntimeConfigurationIntegrationTest.RuntimeConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
class JwtRuntimeConfigurationIntegrationTest {

    @Autowired
    private JwtProperties properties;

    @Test
    void shouldBindActualRuntimeKeyAndVerifyItsConfiguredLifetime() {
        var key = properties.signingKey();
        assertThat(key.getEncoded().length).isEqualTo(32);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String token = JwtUtils.createToken(1L, UserRoleEnum.USER, "PC", key, now, properties.tokenTtl());
        var claims = JwtUtils.parseAndValidate(token, key, now);
        assertThat(claims.expiresAt()).isEqualTo(now.plus(properties.tokenTtl()));
        assertThat(properties.toString()).contains("secret=redacted");
    }

    /** 只加载真实配置绑定；避免用完整应用启动或连接成功代替密钥可用性验证。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class RuntimeConfiguration {
    }
}

package com.polyethene.polyhouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.utils.JwtUtils;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 独立核对实际 YAML 配置绑定和密钥保护，不为配置测试加载数据库或 Web。 */
class JwtPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void shouldReadSevenDaysFromActualApplicationConfigurationWithoutRequiringAKeyYet() {
        runner.withPropertyValues("polyhouse.jwt.secret=").run(context -> {
            assertThat(context).hasNotFailed();
            JwtProperties properties = context.getBean(JwtProperties.class);
            assertThat(properties.getExpirationDays()).isEqualTo(7);
            assertThat(properties.tokenTtl()).isEqualTo(Duration.ofDays(7));
            assertThatIllegalArgumentException().isThrownBy(properties::signingKey);
        });
    }

    @Test
    void shouldUseOverriddenLifetimeForTokenAndDerivedRemainingWhitelistTime() {
        String secret = Base64.getEncoder().encodeToString(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded());
        runner.withPropertyValues("polyhouse.jwt.secret=" + secret, "polyhouse.jwt.expiration-days=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtProperties properties = context.getBean(JwtProperties.class);
                    Instant now = Instant.parse("2026-10-26T00:00:00Z");
                    String token = JwtUtils.createToken(42L, UserRoleEnum.USER, "PC", properties.signingKey(),
                            now, properties.tokenTtl());
                    var claims = JwtUtils.parseAndValidate(token, properties.signingKey(), now);
                    assertThat(Duration.between(now, claims.expiresAt())).isEqualTo(Duration.ofDays(2));
                    assertThat(Duration.between(now.plusSeconds(5), claims.expiresAt()))
                            .isEqualTo(Duration.ofDays(2).minusSeconds(5));
                    assertThat(properties.toString()).doesNotContain(secret);
                    assertThat(new ObjectMapper().writeValueAsString(properties)).doesNotContain(secret, "secret");
                });
    }

    @Test
    void shouldRejectNonpositiveConfiguredLifetime() {
        runner.withPropertyValues("polyhouse.jwt.expiration-days=0").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("polyhouse.jwt.expiration-days=-1").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldNotProvideAFallbackForMalformedConfiguredSecret() {
        runner.withPropertyValues("polyhouse.jwt.secret=not-a-valid-signing-key").run(context -> {
            assertThat(context).hasNotFailed();
            assertThatIllegalArgumentException().isThrownBy(context.getBean(JwtProperties.class)::signingKey)
                    .withMessage("JWT secret must be standard Base64 of exactly 32 random bytes");
        });
    }

    /** 只注册配置绑定基础设施；生产环境由 JwtProperties 的 Component 注册。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropertiesConfiguration {
    }
}

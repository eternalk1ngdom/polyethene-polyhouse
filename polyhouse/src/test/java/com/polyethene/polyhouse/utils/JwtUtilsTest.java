package com.polyethene.polyhouse.utils;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import io.jsonwebtoken.CompressionCodecs;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用进程内随机测试密钥和固定时间验证 JWT 协议及拒绝路径，不读取真实环境凭据。 */
class JwtUtilsTest {

    private static final SecretKey KEY = Keys.secretKeyFor(SignatureAlgorithm.HS256);
    private static final Instant NOW = Instant.parse("2026-10-26T06:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);

    @ParameterizedTest
    @EnumSource(UserRoleEnum.class)
    void shouldRoundTripRolesAndPreserveBigintSubject(UserRoleEnum role) {
        String token = JwtUtils.createToken(Long.MAX_VALUE, role, null, KEY, NOW, TTL);
        JwtUtils.TokenClaims claims = JwtUtils.parseAndValidate(token, KEY, NOW);
        assertThat(claims.userId()).isEqualTo(Long.MAX_VALUE);
        assertThat(claims.role()).isEqualTo(role);
        assertThat(claims.clientType()).isEqualTo("PC");
        assertThat(claims.issuedAt()).isEqualTo(NOW);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(TTL));

        var verified = Jwts.parserBuilder().setSigningKey(KEY).setClock(() -> Date.from(NOW))
                .build().parseClaimsJws(token);
        assertThat(verified.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(verified.getBody().getSubject()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(verified.getBody().keySet()).containsExactlyInAnyOrder(
                "iss", "aud", "sub", "jti", "iat", "exp", "tokenType", "role", "clientType");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PC", "H5", "PLUGIN"})
    void shouldKeepClientsIsolatedAndUseUniqueIdsEvenWithinOneSecond(String client) {
        String first = JwtUtils.createToken(42L, UserRoleEnum.USER, client, KEY, NOW, TTL);
        String second = JwtUtils.createToken(42L, UserRoleEnum.USER, client, KEY, NOW, TTL);
        assertThat(first).isNotEqualTo(second);
        var firstClaims = JwtUtils.parseAndValidate(first, KEY, NOW);
        var secondClaims = JwtUtils.parseAndValidate(second, KEY, NOW);
        assertThat(firstClaims.clientType()).isEqualTo(client);
        assertThat(firstClaims.tokenId()).isNotEqualTo(secondClaims.tokenId());
    }

    @Test
    void shouldRejectTamperingAndWrongKeysWithoutEchoingToken() {
        String token = JwtUtils.createToken(42L, UserRoleEnum.USER, "PC", KEY, NOW, TTL);
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.replace("USER", "OWNER").getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertInvalid(tampered, KEY, NOW);
        assertInvalid(token, Keys.secretKeyFor(SignatureAlgorithm.HS256), NOW);
    }

    @Test
    void shouldUseSecondPrecisionAndRejectExactExpirationBoundary() {
        String token = JwtUtils.createToken(42L, UserRoleEnum.USER, "PC", KEY,
                NOW.plusNanos(999_000_000), Duration.ofSeconds(10));
        var claims = JwtUtils.parseAndValidate(token, KEY, NOW);
        assertThat(claims.issuedAt()).isEqualTo(NOW);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(JwtUtils.parseAndValidate(token, KEY, NOW.plusSeconds(9)).userId()).isEqualTo(42L);
        assertInvalid(token, KEY, NOW.plusSeconds(10));
        assertInvalid(token, KEY, NOW.plusSeconds(11));
        assertInvalid(token, KEY, NOW.minusSeconds(1));
    }

    @Test
    void shouldRejectFutureNotBeforeAndInvalidTimeOrder() {
        Map<String, Object> claims = validClaims();
        claims.put("nbf", Date.from(NOW.plusSeconds(1)));
        assertInvalid(signed(claims), KEY, NOW);
        claims = validClaims();
        claims.put("exp", Date.from(NOW.minusSeconds(1)));
        assertInvalid(signed(claims), KEY, NOW);
        claims = validClaims();
        claims.put("iat", Date.from(NOW.plusSeconds(1)));
        assertInvalid(signed(claims), KEY, NOW);
    }

    @ParameterizedTest
    @MethodSource("invalidClaimValues")
    void shouldRejectInvalidSignedIdentityFields(String field, Object value) {
        Map<String, Object> claims = validClaims();
        claims.put(field, value);
        assertInvalid(signed(claims), KEY, NOW);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-token", "a.b.c", "Bearer a.b.c"})
    void shouldRejectMalformedTokens(String token) {
        assertInvalid(token, KEY, NOW);
    }

    @Test
    void shouldRejectUnsignedTokensWhitespaceAndOversizedInput() {
        String signed = signed(validClaims());
        assertInvalid(Jwts.builder().setClaims(validClaims()).compact(), KEY, NOW);
        assertInvalid(" " + signed, KEY, NOW);
        assertInvalid(signed + "\n", KEY, NOW);
        assertInvalid("x".repeat(4097), KEY, NOW);
    }

    @Test
    void shouldRejectOtherAlgorithmsCompressedPayloadsAndUnknownCriticalHeaders() {
        String otherAlgorithm = Jwts.builder().setHeaderParam(Header.TYPE, Header.JWT_TYPE)
                .setClaims(validClaims()).signWith(Keys.secretKeyFor(SignatureAlgorithm.HS512), SignatureAlgorithm.HS512)
                .compact();
        assertInvalid(otherAlgorithm, KEY, NOW);
        String compressed = Jwts.builder().setHeaderParam(Header.TYPE, Header.JWT_TYPE).setClaims(validClaims())
                .compressWith(CompressionCodecs.DEFLATE).signWith(KEY, SignatureAlgorithm.HS256).compact();
        assertInvalid(compressed, KEY, NOW);
        String critical = Jwts.builder().setHeaderParam(Header.TYPE, Header.JWT_TYPE)
                .setHeaderParam("crit", List.of("unrecognized")).setClaims(validClaims())
                .signWith(KEY, SignatureAlgorithm.HS256).compact();
        assertInvalid(critical, KEY, NOW);
        String wrongType = Jwts.builder().setHeaderParam(Header.TYPE, "OTHER").setClaims(validClaims())
                .signWith(KEY, SignatureAlgorithm.HS256).compact();
        assertInvalid(wrongType, KEY, NOW);
        String textPayload = Jwts.builder().setHeaderParam(Header.TYPE, Header.JWT_TYPE).setPayload("plain-text")
                .signWith(KEY, SignatureAlgorithm.HS256).compact();
        assertInvalid(textPayload, KEY, NOW);
    }

    @Test
    void shouldDecodeRandomBase64KeyAndRejectAbsentMalformedOrWrongSizedKeys() {
        String encoded = Base64.getEncoder().encodeToString(KEY.getEncoded());
        assertThat(JwtUtils.keyFromBase64(encoded).getEncoded()).isEqualTo(KEY.getEncoded());
        for (String invalid : new String[]{null, "", "not-a-base64-secret", "!".repeat(44),
                Base64.getEncoder().encodeToString(new byte[31]), Base64.getEncoder().encodeToString(new byte[33])}) {
            assertThatIllegalArgumentException().isThrownBy(() -> JwtUtils.keyFromBase64(invalid))
                    .withMessage("JWT secret must be standard Base64 of exactly 32 random bytes");
        }
        SecretKey shortKey = new SecretKeySpec(new byte[16], "HmacSHA256");
        SecretKey otherAlgorithm = new SecretKeySpec(KEY.getEncoded(), "AES");
        for (SecretKey invalid : new SecretKey[]{null, shortKey, otherAlgorithm}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    JwtUtils.createToken(42L, UserRoleEnum.USER, "PC", invalid, NOW, TTL));
            assertThatIllegalArgumentException().isThrownBy(() -> JwtUtils.parseAndValidate("invalid", invalid, NOW));
        }
    }

    @Test
    void shouldRejectInvalidIssuanceArgumentsBeforeCreatingToken() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                JwtUtils.createToken(0L, UserRoleEnum.USER, "PC", KEY, NOW, TTL));
        assertThatIllegalArgumentException().isThrownBy(() ->
                JwtUtils.createToken(42L, null, "PC", KEY, NOW, TTL));
        assertThatIllegalArgumentException().isThrownBy(() ->
                JwtUtils.createToken(42L, UserRoleEnum.USER, "MOBILE", KEY, NOW, TTL));
        assertThatIllegalArgumentException().isThrownBy(() ->
                JwtUtils.createToken(42L, UserRoleEnum.USER, "PC", KEY, null, TTL));
        for (Duration invalid : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(1500)}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    JwtUtils.createToken(42L, UserRoleEnum.USER, "PC", KEY, NOW, invalid));
        }
        assertThatIllegalArgumentException().isThrownBy(() ->
                JwtUtils.createToken(42L, UserRoleEnum.USER, "PC", KEY, Instant.MAX, TTL));
        assertThatIllegalArgumentException().isThrownBy(() -> JwtUtils.parseAndValidate("invalid", KEY, null));
    }

    private static Stream<Arguments> invalidClaimValues() {
        return Stream.of(
                Arguments.of("sub", (Object) null), Arguments.of("sub", "0"), Arguments.of("sub", "-1"),
                Arguments.of("sub", "01"), Arguments.of("sub", "9223372036854775808"), Arguments.of("sub", 42),
                Arguments.of("role", (Object) null), Arguments.of("role", "ADMIN"), Arguments.of("role", 10),
                Arguments.of("role", List.of("USER")), Arguments.of("clientType", (Object) null),
                Arguments.of("clientType", "MOBILE"), Arguments.of("clientType", "pc"),
                Arguments.of("jti", (Object) null), Arguments.of("jti", "not-a-uuid"), Arguments.of("jti", "1-1-1-1-1"),
                Arguments.of("iat", (Object) null), Arguments.of("exp", (Object) null),
                Arguments.of("iat", "2026-10-26T06:00:00Z"), Arguments.of("exp", "2026-11-02T06:00:00Z"),
                Arguments.of("iat", NOW.getEpochSecond() + 0.5), Arguments.of("exp", NOW.plus(TTL).getEpochSecond() + 0.5),
                Arguments.of("iat", List.of(1)), Arguments.of("exp", List.of(1)),
                Arguments.of("iss", "another-app"), Arguments.of("aud", "another-api"),
                Arguments.of("tokenType", (Object) null), Arguments.of("tokenType", "REFRESH"));
    }

    private static Map<String, Object> validClaims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "polyhouse");
        claims.put("aud", "polyhouse-api");
        claims.put("sub", "42");
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", Date.from(NOW));
        claims.put("exp", Date.from(NOW.plus(TTL)));
        claims.put("tokenType", "ACCESS");
        claims.put("role", "USER");
        claims.put("clientType", "PC");
        return claims;
    }

    private static String signed(Map<String, Object> claims) {
        return Jwts.builder().setHeaderParam(Header.TYPE, Header.JWT_TYPE).setClaims(claims)
                .signWith(KEY, SignatureAlgorithm.HS256).compact();
    }

    private static void assertInvalid(String token, SecretKey key, Instant now) {
        assertThatThrownBy(() -> JwtUtils.parseAndValidate(token, key, now))
                .isInstanceOf(JwtException.class).hasMessage("invalid access token").hasNoCause();
    }
}

package com.polyethene.polyhouse.utils;

import cn.hutool.crypto.digest.BCrypt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 验证随机盐、第三方兼容、密码原样比较及边界拒绝；不启动 Spring 或外部服务。 */
class PasswordUtilsTest {

    /** 仅为测试构造的输入，不是项目账号或运行凭据。 */
    private static final String TEST_PASSWORD = "Fixture-only-password-2026!";
    private static String libraryHash;

    @BeforeAll
    static void prepareIndependentLibraryHash() {
        libraryHash = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(10));
    }

    @Test
    void shouldUseDifferentRandomSaltsAndProduceLibraryCompatibleHashes() {
        String first = PasswordUtils.hashPassword(TEST_PASSWORD);
        String second = PasswordUtils.hashPassword(TEST_PASSWORD);
        assertThat(first).hasSize(60).startsWith("$2a$10$").isNotEqualTo(second);
        assertThat(first.substring(7, 29)).isNotEqualTo(second.substring(7, 29));
        assertThat(BCrypt.checkpw(TEST_PASSWORD, first)).isTrue();
        assertThat(BCrypt.checkpw(TEST_PASSWORD, second)).isTrue();
        assertThat(PasswordUtils.matches(TEST_PASSWORD, first)).isTrue();
        assertThat(PasswordUtils.matches(TEST_PASSWORD, second)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2a", "2b", "2y"})
    void shouldVerifySupportedVersionsUsingAnIndependentLibraryHash(String version) {
        String stored = "$" + version + libraryHash.substring(3);
        assertThat(PasswordUtils.matches(TEST_PASSWORD, stored)).isTrue();
        assertThat(PasswordUtils.matches("Wrong-fixture-password", stored)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("invalidPasswords")
    void shouldRejectInvalidInputWithoutEchoingIt(String rawPassword) {
        assertThatIllegalArgumentException().isThrownBy(() -> PasswordUtils.hashPassword(rawPassword))
                .withMessage("password must be non-blank, at least 8 characters, "
                        + "and valid UTF-8 of at most 72 bytes");
        assertThat(PasswordUtils.matches(rawPassword, libraryHash)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("invalidHashes")
    void shouldReturnFalseForMissingDamagedOrUnsupportedHashes(String storedHash) {
        assertThat(PasswordUtils.matches(TEST_PASSWORD, storedHash)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("exactly72BytePasswords")
    void shouldAcceptByteBoundaryAndRejectSuffixInsteadOfSilentlyTruncating(String password) {
        assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        String hash = PasswordUtils.hashPassword(password);
        assertThat(PasswordUtils.matches(password, hash)).isTrue();
        // BCrypt 可能忽略 72 字节之后的内容；包装层必须先拒绝，不能把不同密码判为相同。
        assertThat(PasswordUtils.matches(password + "x", hash)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> PasswordUtils.hashPassword(password + "x"));
    }

    @Test
    void shouldAcceptMinimumLengthAndRejectACaseChange() {
        String password = "Abcdef12";
        String hash = PasswordUtils.hashPassword(password);
        assertThat(PasswordUtils.matches(password, hash)).isTrue();
        assertThat(PasswordUtils.matches("abcdef12", hash)).isFalse();
    }

    @Test
    void shouldPreserveLeadingAndTrailingSpaces() {
        String password = " " + TEST_PASSWORD + " ";
        String hash = PasswordUtils.hashPassword(password);
        assertThat(PasswordUtils.matches(password, hash)).isTrue();
        assertThat(PasswordUtils.matches(TEST_PASSWORD, hash)).isFalse();
    }

    @Test
    void shouldNotNormalizeDistinctUnicodeSequences() {
        String composed = "\u00e9-Fixture-only";
        String decomposed = "e\u0301-Fixture-only";
        String hash = PasswordUtils.hashPassword(composed);
        assertThat(PasswordUtils.matches(composed, hash)).isTrue();
        assertThat(PasswordUtils.matches(decomposed, hash)).isFalse();
    }

    private static Stream<String> invalidPasswords() {
        return Stream.of(null, "", "short", "        ", "\t".repeat(8), "a".repeat(73),
                "密".repeat(25), "🙂".repeat(19), "\uD800aaaaaaa", "aaaaaaa\uDC00");
    }

    private static Stream<String> invalidHashes() {
        return Stream.of(null, "", "plain-text-is-not-a-hash", "0".repeat(64),
                libraryHash.substring(0, 59), libraryHash + "\n",
                libraryHash.substring(0, 59) + "!",
                libraryHash.substring(0, 4) + "04" + libraryHash.substring(6),
                libraryHash.substring(0, 4) + "30" + libraryHash.substring(6),
                libraryHash.substring(0, 4) + "31" + libraryHash.substring(6),
                "$2x" + libraryHash.substring(3), "$2z" + libraryHash.substring(3));
    }

    private static Stream<String> exactly72BytePasswords() {
        return Stream.of("a".repeat(72), "密".repeat(24), "🙂".repeat(18));
    }
}

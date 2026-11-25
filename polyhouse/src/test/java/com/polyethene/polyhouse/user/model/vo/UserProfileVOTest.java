package com.polyethene.polyhouse.user.model.vo;

import com.polyethene.polyhouse.model.vo.UserProfileVO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 检查资料输出的字段白名单和大整数精度，防止实体扩展后意外增加响应字段。 */
class UserProfileVOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeOnlySafeFieldsAndPreserveSnowflakePrecision() throws Exception {
        User user = persistedUser();
        user.setPasswordHash("test-only-sensitive-hash");
        user.setDeleted(0);
        user.setStatus(1);
        user.setLastLoginIp("192.0.2.1");
        user.setExtJson(Map.of("internal", "not-public"));

        JsonNode response = objectMapper.readTree(objectMapper.writeValueAsString(Result.success(UserProfileVO.from(user))));
        JsonNode profile = response.get("data");
        assertThat(profile.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "username", "nickname", "avatar", "role", "experience", "level", "coins", "passwordChangeRequired");
        assertThat(profile.get("passwordChangeRequired").asBoolean()).isFalse();
        assertThat(profile.has("credentialVersion")).isFalse();
        assertThat(profile.get("id").isTextual()).isTrue();
        assertThat(profile.get("id").asText()).isEqualTo("9223372036854775807");
        assertThat(profile.get("role").asText()).isEqualTo("INSIDER");
        assertThat(profile.get("experience").isTextual()).isTrue();
        assertThat(profile.get("experience").asText()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(profile.get("coins").isTextual()).isTrue();
        assertThat(profile.get("coins").asText()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(profile.get("level").asInt()).isEqualTo(1);
        assertThat(response.toString()).doesNotContain("test-only-sensitive-hash", "192.0.2.1", "not-public");
    }

    @Test
    void shouldAllowAbsentAvatarWithoutInventingAnotherDefault() {
        User user = persistedUser();
        user.setAvatar(null);
        assertThat(UserProfileVO.from(user).avatar()).isNull();
    }

    @ParameterizedTest(name = "拒绝残缺或非法用户实体：{0}")
    @MethodSource("invalidUsers")
    void shouldRejectIncompleteIdentityOrInvalidGrowthData(String label, Consumer<User> mutator,
                                                           Class<? extends RuntimeException> expectedType) {
        User candidate = mutator == null ? null : persistedUser();
        if (mutator != null) mutator.accept(candidate);
        assertThatThrownBy(() -> UserProfileVO.from(candidate)).isInstanceOf(expectedType);
    }

    private static Stream<Arguments> invalidUsers() {
        return Stream.of(
                Arguments.of("null 用户实体", null, NullPointerException.class),
                Arguments.of("id 为 null", (Consumer<User>) u -> u.setId(null), NullPointerException.class),
                Arguments.of("id 为 0", (Consumer<User>) u -> u.setId(0L), IllegalArgumentException.class),
                Arguments.of("role 为 null", (Consumer<User>) u -> u.setRole(null), NullPointerException.class),
                Arguments.of("experience 为 null", (Consumer<User>) u -> u.setExperience(null), NullPointerException.class),
                Arguments.of("experience 为负数", (Consumer<User>) u -> u.setExperience(-1L), IllegalArgumentException.class),
                Arguments.of("level 为 null", (Consumer<User>) u -> u.setLevel(null), NullPointerException.class),
                Arguments.of("level 为 0", (Consumer<User>) u -> u.setLevel(0), IllegalArgumentException.class),
                Arguments.of("coins 为 null", (Consumer<User>) u -> u.setCoins(null), NullPointerException.class),
                Arguments.of("coins 为负数", (Consumer<User>) u -> u.setCoins(-1L), IllegalArgumentException.class)
        );
    }

    private User persistedUser() {
        User user = new User();
        user.setId(Long.MAX_VALUE);
        user.setUsername("profile_user");
        user.setNickname("聚妙屋居民");
        user.setAvatar("https://example.invalid/avatar.png");
        user.setRole(UserRoleEnum.INSIDER);
        user.setExperience(Long.MAX_VALUE);
        user.setLevel(1);
        user.setCoins(Long.MAX_VALUE);
        return user;
    }
}

package com.polyethene.polyhouse.model.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.entity.User;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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

    @Test
    void shouldRejectIncompleteIdentityRatherThanExposeAmbiguousProfile() {
        assertThatNullPointerException().isThrownBy(() -> UserProfileVO.from(null));
        User user = persistedUser();
        user.setId(null);
        assertThatNullPointerException().isThrownBy(() -> UserProfileVO.from(user));
        user.setId(0L);
        assertThatIllegalArgumentException().isThrownBy(() -> UserProfileVO.from(user));
        user.setId(1L);
        user.setRole(null);
        assertThatNullPointerException().isThrownBy(() -> UserProfileVO.from(user));
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

    @Test
    void shouldRejectMissingOrInvalidGrowthDataInsteadOfInventingBalances() {
        User user = persistedUser();
        user.setExperience(null);
        assertThatNullPointerException().isThrownBy(() -> UserProfileVO.from(user));
        user.setExperience(-1L);
        assertThatIllegalArgumentException().isThrownBy(() -> UserProfileVO.from(user));
        user.setExperience(0L);
        user.setLevel(null);
        assertThatNullPointerException().isThrownBy(() -> UserProfileVO.from(user));
        user.setLevel(0);
        assertThatIllegalArgumentException().isThrownBy(() -> UserProfileVO.from(user));
        user.setLevel(1);
        user.setCoins(null);
        assertThatNullPointerException().isThrownBy(() -> UserProfileVO.from(user));
        user.setCoins(-1L);
        assertThatIllegalArgumentException().isThrownBy(() -> UserProfileVO.from(user));
    }
}

package com.polyethene.polyhouse.common.constant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 校验角色编码/权重稳定性、完整 4×4 非单调权限矩阵及 JSON 语义编解码契约。 */
class UserRoleEnumTest {

    @ParameterizedTest(name = "稳定角色编码与权重：{0} -> code={1}, level={2}")
    @CsvSource({
            "USER, USER, 10",
            "ADMIN, ADMIN, 30",
            "INSIDER, INSIDER, 50",
            "OWNER, OWNER, 99"
    })
    void shouldKeepStableCodesAndLevels(UserRoleEnum role, String code, int level) {
        assertEquals(code, role.getCode());
        assertEquals(level, role.getLevel());
    }

    @ParameterizedTest(name = "角色权限矩阵：{0} 访问 {1} -> {2}")
    @CsvSource({
            "USER, USER, true",
            "USER, ADMIN, false",
            "USER, INSIDER, false",
            "USER, OWNER, false",
            "ADMIN, USER, true",
            "ADMIN, ADMIN, true",
            "ADMIN, INSIDER, false",
            "ADMIN, OWNER, false",
            "INSIDER, USER, true",
            "INSIDER, ADMIN, false",
            "INSIDER, INSIDER, true",
            "INSIDER, OWNER, false",
            "OWNER, USER, true",
            "OWNER, ADMIN, true",
            "OWNER, INSIDER, true",
            "OWNER, OWNER, true"
    })
    void shouldEnforceNonMonotonicRolePermissionMatrix(UserRoleEnum actor, UserRoleEnum required, boolean allowed) {
        // INSIDER 权重高于 ADMIN 但不得自动获得管理权，ADMIN 亦不得自动获得高开销 INSIDER 资格。
        assertEquals(allowed, actor.hasPermission(required));
    }

    @Test
    void shouldRejectNullRequiredRole() {
        assertThrows(NullPointerException.class, () -> UserRoleEnum.OWNER.hasPermission(null));
    }

    @ParameterizedTest
    @EnumSource(UserRoleEnum.class)
    void shouldSerializeAndDeserializeSemanticRoleCode(UserRoleEnum role) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(role);

        assertEquals("\"" + role.getCode() + "\"", json);
        assertEquals(role, mapper.readValue(json, UserRoleEnum.class));
    }
}

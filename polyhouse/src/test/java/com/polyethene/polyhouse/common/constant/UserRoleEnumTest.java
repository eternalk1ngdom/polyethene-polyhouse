package com.polyethene.polyhouse.common.constant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRoleEnumTest {

    @Test
    void shouldKeepStableCodesAndLevels() {
        assertEquals("USER", UserRoleEnum.USER.getCode());
        assertEquals(10, UserRoleEnum.USER.getLevel());
        assertEquals("INSIDER", UserRoleEnum.INSIDER.getCode());
        assertEquals(50, UserRoleEnum.INSIDER.getLevel());
        assertEquals("OWNER", UserRoleEnum.OWNER.getCode());
        assertEquals(99, UserRoleEnum.OWNER.getLevel());
    }

    @Test
    void shouldAuthorizeByRoleWeight() {
        assertTrue(UserRoleEnum.OWNER.hasPermission(UserRoleEnum.INSIDER));
        assertTrue(UserRoleEnum.INSIDER.hasPermission(UserRoleEnum.USER));
        assertFalse(UserRoleEnum.USER.hasPermission(UserRoleEnum.INSIDER));
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

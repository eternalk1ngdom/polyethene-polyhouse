package com.polyethene.polyhouse.common.annotation;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RequireRoleTest {

    @Test
    void shouldExposeRequiredRoleAtRuntime() throws NoSuchMethodException {
        Method method = ProtectedResource.class.getDeclaredMethod("execute");
        RequireRole requireRole = method.getAnnotation(RequireRole.class);

        assertNotNull(requireRole);
        assertEquals(UserRoleEnum.INSIDER, requireRole.value());
    }

    private static final class ProtectedResource {

        @RequireRole(UserRoleEnum.INSIDER)
        private void execute() {
        }
    }
}

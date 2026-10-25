package com.polyethene.polyhouse.common.exception;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessExceptionTest {

    @Test
    void shouldKeepBusinessCodeMessageAndCause() {
        IllegalStateException cause = new IllegalStateException("database conflict");
        BusinessException exception = new BusinessException(ErrorCode.CONFLICT, "数据状态冲突", cause);

        assertEquals(40900, exception.getCode());
        assertEquals("数据状态冲突", exception.getMessage());
        assertSame(cause, exception.getCause());
        assertEquals(ErrorCode.CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldRejectMissingErrorCodeAndBlankMessage() {
        assertThrows(NullPointerException.class, () -> new BusinessException(null));
        assertThrows(NullPointerException.class, () -> new BusinessException(ErrorCode.INVALID_PARAMETER, null));
        assertThrows(IllegalArgumentException.class,
                () -> new BusinessException(ErrorCode.INVALID_PARAMETER, " "));
    }
}

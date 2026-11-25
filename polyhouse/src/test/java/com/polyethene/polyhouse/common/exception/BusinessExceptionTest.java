package com.polyethene.polyhouse.common.exception;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 校验业务异常防误用契约：不允许构造空错误码或空白客户端提示。 */
class BusinessExceptionTest {

    @Test
    void shouldRejectNullErrorCodeOrNullMessage() {
        assertThrows(NullPointerException.class, () -> new BusinessException(null));
        assertThrows(NullPointerException.class, () -> new BusinessException(ErrorCode.INVALID_PARAMETER, null));
        assertThrows(NullPointerException.class, () -> new BusinessException(null, "数据状态冲突", null));
    }

    @ParameterizedTest(name = "拒绝空白提示：''{0}''")
    @ValueSource(strings = {"", " ", "\t\n"})
    void shouldRejectBlankClientMessage(String message) {
        assertThrows(IllegalArgumentException.class,
                () -> new BusinessException(ErrorCode.INVALID_PARAMETER, message));
    }
}

package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.OwnerBootstrapMapper;
import com.polyethene.polyhouse.model.entity.OwnerBootstrapRecord;
import com.polyethene.polyhouse.model.entity.OwnerBootstrapTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 存储结构、输入和状态异常必须明确拒绝，不以“成功启动命令”代替成功授权。 */
@ExtendWith(MockitoExtension.class)
class OwnerBootstrapServiceTest {
    @Mock private OwnerBootstrapMapper mapper;
    @Mock private AuthRedisStore sessions;
    private OwnerBootstrapService service;

    @BeforeEach
    void setup() {
        service = new OwnerBootstrapService(mapper, sessions);
        lenient().when(mapper.uniqueGuardCount()).thenReturn(1);
        lenient().when(mapper.guardExpression()).thenReturn("(case when (`role` = _utf8mb4\\'OWNER\\') then 1 else NULL end)");
        // SQL 无行必须是 null；Mockito 对包装 Long 的默认 0 不是“没有屋主”。
        lenient().when(mapper.ownerId()).thenReturn(null);
        lenient().when(mapper.lockOwnerId()).thenReturn(null);
        lenient().when(mapper.record()).thenReturn(new OwnerBootstrapRecord(1, null));
        lenient().when(mapper.target(42L)).thenReturn(target(UserRoleEnum.USER, 1, 0));
    }

    @Test
    void shouldRejectInvalidIdAndNonCanonicalUsernameBeforeStorage() {
        for (String name : new String[]{"OWNER", " owner ", "bad;input", "a"}) {
            assertCode(() -> service.inspect(42L, name), ErrorCode.INVALID_PARAMETER);
        }
        assertCode(() -> service.inspect(0L, "owner_account"), ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(mapper, sessions);
    }

    @Test
    void shouldRejectMalformedOperatorBeforeAnyWrite() {
        for (String operator : new String[]{"", "\nactor", "x".repeat(129)}) {
            assertCode(() -> service.initialize(42L, "owner_account", operator), ErrorCode.INVALID_PARAMETER);
        }
        verifyNoInteractions(mapper, sessions);
    }

    @Test
    void shouldRefuseMissingOrForgedDatabaseGuard() {
        when(mapper.uniqueGuardCount()).thenReturn(0);
        assertCode(() -> service.inspect(42L, "owner_account"), ErrorCode.SERVICE_UNAVAILABLE);
        when(mapper.uniqueGuardCount()).thenReturn(1);
        when(mapper.guardExpression()).thenReturn("case when deleted = 0 then 1 else null end");
        assertCode(() -> service.inspect(42L, "owner_account"), ErrorCode.SERVICE_UNAVAILABLE);
        when(mapper.guardExpression()).thenReturn(null);
        assertCode(() -> service.inspect(42L, "owner_account"), ErrorCode.SERVICE_UNAVAILABLE);
        verifyNoInteractions(sessions);
    }

    @Test
    void shouldRequireExactIdAndUsernameAndActiveAccount() {
        when(mapper.target(42L)).thenReturn(null);
        assertCode(() -> service.inspect(42L, "owner_account"), ErrorCode.INVALID_PARAMETER);
        when(mapper.target(42L)).thenReturn(target(UserRoleEnum.USER, 1, 0));
        assertCode(() -> service.inspect(42L, "another_account"), ErrorCode.INVALID_PARAMETER);
        when(mapper.target(42L)).thenReturn(target(UserRoleEnum.USER, 0, 0));
        assertCode(() -> service.inspect(42L, "owner_account"), ErrorCode.CONFLICT);
        when(mapper.target(42L)).thenReturn(target(UserRoleEnum.USER, 1, 1));
        assertCode(() -> service.inspect(42L, "owner_account"), ErrorCode.CONFLICT);
        verifyNoInteractions(sessions);
    }

    @Test
    void shouldRejectOtherOrInconsistentHistoricalOwner() {
        when(mapper.ownerId()).thenReturn(99L);
        assertCode(() -> service.inspect(42L, "owner_account"), ErrorCode.CONFLICT);
        when(mapper.ownerId()).thenReturn(null);
        when(mapper.record()).thenReturn(new OwnerBootstrapRecord(1, 99L));
        assertCode(() -> service.inspect(42L, "owner_account"), ErrorCode.CONFLICT);
        when(mapper.record()).thenReturn(new OwnerBootstrapRecord(1, 42L));
        assertCode(() -> service.inspect(42L, "owner_account"), ErrorCode.CONFLICT);
        when(mapper.record()).thenReturn(null);
        assertCode(() -> service.inspect(42L, "owner_account"), ErrorCode.SERVICE_UNAVAILABLE);
        verifyNoInteractions(sessions);
    }

    @Test
    void shouldReturnOnlyPreviewWithoutRevokingSessionsOrWriting() {
        assertThat(service.inspect(42L, "owner_account").role()).isEqualTo(UserRoleEnum.USER);
        verifyNoInteractions(sessions);
        verify(mapper, never()).lockRecord();
        verify(mapper, never()).promote(42L, UserRoleEnum.USER, UserRoleEnum.OWNER);
    }

    @Test
    void shouldTranslateUnexpectedCompetingOwnerWithoutLeakingSql() {
        when(mapper.lockRecord()).thenReturn(new OwnerBootstrapRecord(1, null));
        when(mapper.lockTarget(42L)).thenReturn(target(UserRoleEnum.USER, 1, 0));
        when(mapper.promote(42L, UserRoleEnum.USER, UserRoleEnum.OWNER)).thenThrow(new DuplicateKeyException("private-sql"));
        assertCode(() -> service.initialize(42L, "owner_account", "local-operator"), ErrorCode.CONFLICT);
        verifyNoInteractions(sessions);
    }

    private static OwnerBootstrapTarget target(UserRoleEnum role, int status, int deleted) {
        return new OwnerBootstrapTarget(42L, "owner_account", role, status, deleted);
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(code);
            assertThat(error.getMessage()).doesNotContain("private-sql");
            assertThat(error.getCause()).isNull();
        });
    }
}

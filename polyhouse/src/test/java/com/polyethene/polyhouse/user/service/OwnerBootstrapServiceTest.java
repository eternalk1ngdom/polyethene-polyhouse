package com.polyethene.polyhouse.user.service;

import com.polyethene.polyhouse.service.AuthRedisStore;
import com.polyethene.polyhouse.service.OwnerBootstrapService;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.OwnerBootstrapMapper;
import com.polyethene.polyhouse.model.entity.OwnerBootstrapRecord;
import com.polyethene.polyhouse.model.entity.OwnerBootstrapTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    }

    private void stubHealthyGuardAndEmptyOwner() {
        when(mapper.uniqueGuardCount()).thenReturn(1);
        when(mapper.guardExpression()).thenReturn("(case when (`role` = _utf8mb4\\'OWNER\\') then 1 else NULL end)");
        // SQL 无行必须是 null；Mockito 对包装 Long 的默认 0 不是“没有屋主”。
        when(mapper.ownerId()).thenReturn(null);
        when(mapper.record()).thenReturn(new OwnerBootstrapRecord(1, null));
    }

    @ParameterizedTest(name = "拒绝非法目标编号或非规范账号：{0}")
    @MethodSource("invalidInspectTargets")
    void shouldRejectInvalidIdAndNonCanonicalUsernameBeforeStorage(String label, long userId, String username) {
        assertCode(() -> service.inspect(userId, username), ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(mapper, sessions);
    }

    private static Stream<Arguments> invalidInspectTargets() {
        return Stream.of(
                Arguments.of("大写用户名", 42L, "OWNER"),
                Arguments.of("前后带空格", 42L, " owner "),
                Arguments.of("含非法分号", 42L, "bad;input"),
                Arguments.of("用户名过短", 42L, "a"),
                Arguments.of("userId 为 0", 0L, "owner_account")
        );
    }

    @ParameterizedTest(name = "拒绝非法操作人标识：''{0}''")
    @MethodSource("malformedOperators")
    void shouldRejectMalformedOperatorBeforeAnyWrite(String operator) {
        assertCode(() -> service.initialize(42L, "owner_account", operator), ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(mapper, sessions);
    }

    private static Stream<String> malformedOperators() {
        return Stream.of("", "\nactor", "x".repeat(129));
    }

    @ParameterizedTest(name = "缺失或伪造唯一屋主数据库约束时拒绝：{0}")
    @MethodSource("brokenGuards")
    void shouldRefuseMissingOrForgedDatabaseGuard(String label, int guardCount, String expression) {
        when(mapper.uniqueGuardCount()).thenReturn(guardCount);
        if (guardCount == 1) {
            when(mapper.guardExpression()).thenReturn(expression);
        }
        assertCode(() -> service.inspect(42L, "owner_account"), ErrorCode.SERVICE_UNAVAILABLE);
        verifyNoInteractions(sessions);
    }

    private static Stream<Arguments> brokenGuards() {
        return Stream.of(
                Arguments.of("唯一索引缺失", 0, null),
                Arguments.of("表达式被篡改", 1, "case when deleted = 0 then 1 else null end"),
                Arguments.of("表达式为 null", 1, null)
        );
    }

    @Test
    void shouldRequireExactIdAndUsernameAndActiveAccount() {
        stubHealthyGuardAndEmptyOwner();
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
        stubHealthyGuardAndEmptyOwner();
        when(mapper.target(42L)).thenReturn(target(UserRoleEnum.USER, 1, 0));
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
        stubHealthyGuardAndEmptyOwner();
        when(mapper.target(42L)).thenReturn(target(UserRoleEnum.USER, 1, 0));
        assertThat(service.inspect(42L, "owner_account").role()).isEqualTo(UserRoleEnum.USER);
        verifyNoInteractions(sessions);
        verify(mapper, never()).lockRecord();
        verify(mapper, never()).promote(42L, UserRoleEnum.USER, UserRoleEnum.OWNER);
    }

    @Test
    void shouldTranslateUnexpectedCompetingOwnerWithoutLeakingSql() {
        when(mapper.uniqueGuardCount()).thenReturn(1);
        when(mapper.guardExpression()).thenReturn("(case when (`role` = _utf8mb4\\'OWNER\\') then 1 else NULL end)");
        when(mapper.lockRecord()).thenReturn(new OwnerBootstrapRecord(1, null));
        when(mapper.lockOwnerId()).thenReturn(null);
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

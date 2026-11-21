package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.vo.TrendJobSubmissionVO;
import com.polyethene.polyhouse.service.*;
import com.polyethene.polyhouse.service.trend.TrendOwnerAuthorization;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 提交不确定时不提前发起外部工作；管理事务在存储故障时返回可控503。 */
class TrendJobManagementServiceTest {
    private final TrendOwnerAuthorization owners = mock(TrendOwnerAuthorization.class);
    private final TrendJobStore store = mock(TrendJobStore.class);
    private final TrendJobRunner runner = mock(TrendJobRunner.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    @Test void workerIsWokenOnlyAfterSuccessfulCommit() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var actor = TrendFixture.owner();
        when(owners.lockSession("fixture")).thenReturn(actor);
        var request = new TrendJobSubmitDTO(UUID.randomUUID().toString());
        when(store.submit(request.requestId(), actor)).thenReturn(new TrendJobSubmissionVO(true, false, null));
        withService(service -> assertThat(service.submit(request, "fixture").accepted()).isTrue());
        var order = inOrder(manager, owners, store, runner);
        order.verify(manager).getTransaction(any()); order.verify(owners).lockSession("fixture");
        order.verify(store).submit(request.requestId(), actor); order.verify(manager).commit(any()); order.verify(runner).wake();
    }

    @Test void uncertainCommitNeverWakesWorkerAndDoesNotExposeStorageError() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(owners.lockSession("fixture")).thenReturn(TrendFixture.owner());
        doThrow(new TransactionSystemException("private storage diagnostic")).when(manager).commit(any());
        withService(service -> assertThatThrownBy(() -> service.submit(new TrendJobSubmitDTO(UUID.randomUUID().toString()), "fixture"))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(failure.getMessage()).doesNotContain("private");
                }));
        verifyNoInteractions(runner);
    }

    @Test void unavailableTransactionAndInvalidDtoCannotReachProtectedStore() {
        when(manager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("private connection detail"));
        withService(service -> {
            assertThatThrownBy(() -> service.query(new TrendJobQueryDTO(UUID.randomUUID().toString()), "fixture"))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
            assertThatThrownBy(() -> service.submit(new TrendJobSubmitDTO("bad"), "fixture")).isInstanceOf(BusinessException.class);
        });
        verifyNoInteractions(owners, store, runner);
    }

    private void withService(java.util.function.Consumer<TrendJobManagementService> assertion) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertion.accept(new TrendJobManagementService(owners, store, runner, factory.getValidator(), manager));
        }
    }
}

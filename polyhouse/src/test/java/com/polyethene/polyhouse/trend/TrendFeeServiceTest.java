package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.*;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.service.*;
import com.polyethene.polyhouse.service.trend.TrendOwnerAuthorization;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrendFeeServiceTest {
    private final TrendOwnerAuthorization owners = mock(TrendOwnerAuthorization.class);
    private final TrendFeeMapper fees = mock(TrendFeeMapper.class);
    private final TrendJobMapper jobMapper = mock(TrendJobMapper.class);
    private final TrendJobStore jobs = mock(TrendJobStore.class);
    private final TrendAiLedger ledger = mock(TrendAiLedger.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    @Test void revokedOwnerCannotReadOrModifyAccountingEvenThroughDirectServiceCall() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(owners.lockSession("fixture")).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        withService(service -> {
            assertThatThrownBy(() -> service.query(new TrendFeeQueryDTO(null, null, null), "fixture")).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.resolve(request(), "fixture")).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.confirm(new TrendFeeResolutionQueryDTO(UUID.randomUUID().toString()), "fixture")).isInstanceOf(BusinessException.class);
        });
        verifyNoInteractions(fees, jobMapper, jobs, ledger);
    }

    @Test void defaultMonthUsesBeijingBoundaryAndEmptyReadDoesNotCreateMonthOrCharge() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(owners.lockSession("fixture")).thenReturn(TrendFixture.owner());
        when(jobMapper.now()).thenReturn(LocalDateTime.parse("2026-10-31T16:00:00"));
        when(fees.pending("2026-11", null, 21)).thenReturn(List.of());
        withService(service -> {
            var view = service.query(new TrendFeeQueryDTO(null, null, null), "fixture");
            assertThat(view.month()).isEqualTo("2026-11"); assertThat(view.accountedMicros()).isZero();
            assertThat(view.remainingMicros()).isEqualTo(10_000_000); assertThat(view.pending()).isEmpty();
        });
        verify(fees).month("2026-11"); verifyNoInteractions(jobs, ledger);
        verify(fees, never()).insert(any());
    }

    @Test void invalidRequestAndUnavailableTransactionAreSafeAndNeverReachLedger() {
        when(manager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("private database diagnostic"));
        withService(service -> {
            assertThatThrownBy(() -> service.query(new TrendFeeQueryDTO("2026-11", null, 20), "fixture"))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> {
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(failure.getMessage()).doesNotContain("private");
                    });
            assertThatThrownBy(() -> service.resolve(null, "fixture")).isInstanceOf(BusinessException.class);
        });
        verifyNoInteractions(owners, fees, jobs, ledger);
    }

    private void withService(java.util.function.Consumer<TrendFeeService> assertion) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertion.accept(new TrendFeeService(owners, fees, jobMapper, jobs, ledger, TrendAiFixture.settings(), factory.getValidator(), manager));
        }
    }
    private static TrendFeeResolveDTO request() {
        return new TrendFeeResolveDTO(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 2800L, "COUNT_RESERVED", "RESULT_UNRECOVERABLE");
    }
}

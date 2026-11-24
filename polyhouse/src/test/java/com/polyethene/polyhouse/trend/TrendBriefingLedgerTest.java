package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.TrendAiMapper;
import com.polyethene.polyhouse.mapper.TrendBriefingMapper;
import com.polyethene.polyhouse.model.entity.TrendBriefingRecord;
import com.polyethene.polyhouse.model.entity.TrendBriefingRequest;
import com.polyethene.polyhouse.service.TrendBriefingLedger;
import com.polyethene.polyhouse.service.trend.TrendBriefingCodec;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证简报账本参数守卫、同指纹零费用复用、预算拒绝、未知态熔断与五分钟保守关闭门槛（离线单测）。 */
class TrendBriefingLedgerTest {
    private static final LocalDate ANCHOR = LocalDate.of(2026, 11, 23);
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String OTHER_FINGERPRINT = "b".repeat(64);

    private final TrendBriefingMapper mapper = mock(TrendBriefingMapper.class);
    private final TrendAiMapper aiMapper = mock(TrendAiMapper.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    @Test void rejectsInvalidParametersAndReusesSameFingerprintWithZeroCost() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var ledger = ledger();

        assertThatThrownBy(() -> ledger.reserve("not-a-uuid", ANCHOR, FINGERPRINT, 2000, 1500, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));
        assertThatThrownBy(() -> ledger.reserve(UUID.randomUUID().toString(), ANCHOR, "short", 2000, 1500, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));
        assertThatThrownBy(() -> ledger.reserve(UUID.randomUUID().toString(), ANCHOR, FINGERPRINT, 32_001, 1500, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));

        var existing = new TrendBriefingRecord(ANCHOR, ANCHOR.minusDays(6), ANCHOR, 5, "SUFFICIENT",
                FINGERPRINT, "deepseek-flash", TrendBriefingCodec.RULE, null, "{\"headline\":\"ok\"}",
                LocalDateTime.ofInstant(TrendAiFixture.NOW, ZoneOffset.UTC));
        when(mapper.lockBriefing(ANCHOR)).thenReturn(existing);
        String id = UUID.randomUUID().toString();
        var decision = ledger.reserve(id, ANCHOR, FINGERPRINT, 2000, 1500, FINGERPRINT);

        assertThat(decision.status()).isEqualTo("REUSED");
        assertThat(decision.request()).isNull();
        assertThat(decision.briefing()).isEqualTo(existing);
        verifyNoInteractions(aiMapper);
    }

    @Test void blocksWhenActiveRequestExistsOrBudgetHoldFails() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var ledger = ledger();

        var busy = new TrendBriefingRecord(ANCHOR, null, null, null, null, null, null, null,
                UUID.randomUUID().toString(), null, null);
        when(mapper.lockBriefing(ANCHOR)).thenReturn(busy);
        assertThat(ledger.reserve(UUID.randomUUID().toString(), ANCHOR, FINGERPRINT, 2000, 1500, null).status())
                .isEqualTo("PENDING");
        verifyNoInteractions(aiMapper);

        var idle = new TrendBriefingRecord(ANCHOR, null, null, null, null, null, null, null, null, null, null);
        when(mapper.lockBriefing(ANCHOR)).thenReturn(idle);
        when(aiMapper.hold(eq("2026-11"), anyLong(), eq(10_000_000L))).thenReturn(0);
        assertThat(ledger.reserve(UUID.randomUUID().toString(), ANCHOR, OTHER_FINGERPRINT, 2000, 1500, null).status())
                .isEqualTo("BUDGET_LIMIT");
    }

    @Test void unknownUsageViolationSuspendsMonthAndConservativeCloseEnforcesFiveMinuteWait() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var ledger = ledger();
        String id = UUID.randomUUID().toString();
        var req = new TrendBriefingRequest(id, "2026-11", ANCHOR, FINGERPRINT, "deepseek-flash",
                TrendBriefingCodec.RULE, "RESERVED", 16000L, null, 2000, 1500,
                new BigDecimal("2"), new BigDecimal("8"));
        var slot = new TrendBriefingRecord(ANCHOR, null, null, null, null, null, null, null, id, null, null);

        when(mapper.request(id)).thenReturn(req);
        when(mapper.lockBriefing(ANCHOR)).thenReturn(slot);
        when(mapper.lockRequest(id)).thenReturn(req);

        ledger.unknown(id, true);
        verify(mapper).unknown(id, "USAGE_OUT_OF_BOUNDS");
        verify(aiMapper).suspend("2026-11");

        // 预留不满五分钟时保守结算必须拒绝。
        LocalDateTime created = LocalDateTime.of(2026, 11, 23, 2, 0, 0);
        when(mapper.createdAt(id)).thenReturn(created);
        assertThatThrownBy(() -> ledger.closeConservatively(id, 16000L, created.plusMinutes(4).plusSeconds(59)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(e.getMessage()).contains("五分钟");
                });
    }

    private TrendBriefingLedger ledger() {
        return new TrendBriefingLedger(mapper, aiMapper, TrendAiFixture.settings(),
                Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC), manager);
    }
}

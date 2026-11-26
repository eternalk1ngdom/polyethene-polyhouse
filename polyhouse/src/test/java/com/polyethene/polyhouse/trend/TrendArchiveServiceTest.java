package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.mapper.TrendMapper;
import com.polyethene.polyhouse.model.entity.TrendCollection;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.service.TrendArchiveService;
import com.polyethene.polyhouse.service.TrendReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 验证星潮归档服务使用注入的 Clock 生成发布时间，不依赖宿主机墙钟。 */
@ExtendWith(MockitoExtension.class)
class TrendArchiveServiceTest {
    @Mock private TrendMapper mapper;
    @Mock private TrendReportService reports;

    @Test
    void shouldStampPublishedAtFromInjectedClock() {
        Instant capturedAt = Instant.parse("2030-05-01T01:00:00Z");
        Instant publishedAt = Instant.parse("2030-05-01T12:34:56Z");
        Clock fixedClock = Clock.fixed(publishedAt, ZoneOffset.UTC);

        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };

        String publicationId = UUID.randomUUID().toString();
        when(mapper.lockDay(any())).thenReturn(null);
        when(mapper.findVersion(publicationId)).thenReturn(null);
        when(mapper.publish(any(), eq(publicationId))).thenReturn(1);

        var service = new TrendArchiveService(mapper, reports, TrendFixture.json(), txManager, fixedClock);
        var project = new TrendProjectVO(
                1, "101", "sample/engine", "https://github.com/sample/engine",
                "desc", "Java", 1000L, 120L, 50L, "MIT", false,
                "SOFTWARE", "OK", "OK", "OK", "NOT_REQUIRED", null, null);
        var collection = new TrendCollection(capturedAt, List.of(new TrendCollection.Project(project, null, "")));

        var result = service.publish(publicationId, collection);

        assertThat(result.publishedAt()).isEqualTo(publishedAt);
        assertThat(result.capturedAt()).isEqualTo(capturedAt);
    }
}

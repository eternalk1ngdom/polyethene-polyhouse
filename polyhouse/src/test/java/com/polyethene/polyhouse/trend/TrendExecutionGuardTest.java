package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.service.trend.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrendExecutionGuardTest {
    @Test void collectionCannotStartAfterExecutionIsRevoked() {
        var transport = mock(TrendSourceTransport.class);
        var source = new GithubTrendClient(transport, new GithubTrendingParser(), TrendFixture.json(), new TrendProperties(true, null));
        assertThatThrownBy(() -> source.collect(() -> { throw new TrendExecutionLost(); })).isInstanceOf(TrendExecutionLost.class);
        verifyNoInteractions(transport);
    }

    @Test void redirectRequiresAnotherAuthorityCheckAndDoesNotConsumeAnotherRequestOnRevocation() {
        var transport = mock(TrendSourceTransport.class);
        var redirected = new AtomicBoolean();
        when(transport.get(any(), any())).thenAnswer(invocation -> {
            // 首次请求返回 302 后再模拟执行权被撤销，不耦合首次请求前的守卫调用次数。
            redirected.set(true);
            return new TrendSourceTransport.Reply(302, "", "/trending?since=daily");
        });
        var source = new GithubTrendClient(transport, new GithubTrendingParser(), TrendFixture.json(), new TrendProperties(true, null));
        assertThatThrownBy(() -> source.collect(() -> { if (redirected.get()) throw new TrendExecutionLost(); }))
                .isInstanceOf(TrendExecutionLost.class);
        verify(transport, times(1)).get(URI.create(GithubTrendingParser.SOURCE), null);
    }
}

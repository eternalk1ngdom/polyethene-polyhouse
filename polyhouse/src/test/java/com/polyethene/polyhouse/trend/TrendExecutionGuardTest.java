package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.service.trend.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
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
        when(transport.get(any(), any())).thenReturn(new TrendSourceTransport.Reply(302, "", "/trending?since=daily"));
        var source = new GithubTrendClient(transport, new GithubTrendingParser(), TrendFixture.json(), new TrendProperties(true, null));
        var checks = new AtomicInteger();
        assertThatThrownBy(() -> source.collect(() -> { if (checks.incrementAndGet() == 2) throw new TrendExecutionLost(); }))
                .isInstanceOf(TrendExecutionLost.class);
        verify(transport, times(1)).get(URI.create(GithubTrendingParser.SOURCE), null);
    }
}

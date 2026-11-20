package com.polyethene.polyhouse.service.trend;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** 来源和 AI 共用有界正文订阅；先累计再交给聚合器，超限立即取消，不能先读完整字节数组。 */
final class LimitedResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int limit;
    private long received;
    private Flow.Subscription subscription;
    private boolean stopped;

    LimitedResponseBody(int limit) { this.limit = limit; }
    @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    @Override public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
    @Override public void onNext(List<ByteBuffer> buffers) {
        if (stopped) return;
        for (ByteBuffer buffer : buffers) received += buffer.remaining();
        if (received > limit) {
            stopped = true;
            subscription.cancel();
            delegate.onError(new TooLarge());
        } else delegate.onNext(buffers);
    }
    @Override public void onError(Throwable failure) { if (!stopped) { stopped = true; delegate.onError(failure); } }
    @Override public void onComplete() { if (!stopped) { stopped = true; delegate.onComplete(); } }

    /** 将单份响应体超限与传输中断分开，采集器仍可处理后续仓库。 */
    static final class TooLarge extends IllegalStateException {
        TooLarge() { super("Response body limit exceeded"); }
    }
}

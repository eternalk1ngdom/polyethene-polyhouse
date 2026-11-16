package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.config.TrendCacheProperties;
import com.polyethene.polyhouse.model.entity.TrendReportRecord;
import org.slf4j.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 单条有界内容缓存，不把Redis指针当发布权威；失败短暂停用，数据库读取始终可独立完成。 */
@Component
public class TrendReportCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrendReportCache.class);
    private static final String KEY = "polyhouse:trend:view:latest";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMddHHmmssSSSSSS");
    private static final DefaultRedisScript<String> READ = script("redis/trend_read_view.lua", String.class);
    private static final DefaultRedisScript<Long> WRITE = script("redis/trend_write_view.lua", Long.class);
    private final StringRedisTemplate redis;
    private final TrendCacheProperties settings;
    private final Clock clock;
    private final AtomicReference<Instant> suppressedUntil = new AtomicReference<>(Instant.MIN);

    /** 只复用现有Redis连接及时钟，不改认证键、连接配置或全站依赖版本。 */
    public TrendReportCache(StringRedisTemplate redis, TrendCacheProperties settings, Clock clock) {
        this.redis = redis; this.settings = settings; this.clock = clock;
    }

    /** 关闭时完全保留旧数据库查询路径，不访问Redis。 */
    public boolean enabled() { return settings.enabled(); }

    /** 只读取与本次数据库已发布头一致的版本；TTL不因阅读续命。 */
    public String read(TrendReportRecord header) {
        if (!usable() || !validHeader(header)) return null;
        try { return redis.execute(READ, List.of(KEY), header.id(), STAMP.format(header.capturedAt())); }
        catch (RuntimeException failure) { suppress(); return null; }
    }

    /** 旧请求回源结束较晚也不能覆盖新缓存；只保存已发布JSON，限制字符及UTF8字节。 */
    public void write(TrendReportRecord record) {
        if (!usable() || !validHeader(record) || record.payload() == null || record.payload().isEmpty()
                || record.payload().length() > 256 * 1024 || record.payload().getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) return;
        try { redis.execute(WRITE, List.of(KEY), record.id(), STAMP.format(record.capturedAt()), record.payload()); }
        catch (RuntimeException failure) { suppress(); }
    }

    private boolean usable() { return settings.enabled() && !clock.instant().isBefore(suppressedUntil.get()); }
    private static boolean validHeader(TrendReportRecord record) {
        return record != null && record.id() != null && record.id().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                && record.capturedAt() != null && record.periodDate() != null;
    }
    private void suppress() {
        suppressedUntil.set(clock.instant().plusSeconds(30));
        LOGGER.warn("Trend content cache unavailable; database fallback active");
    }
    private static <T> DefaultRedisScript<T> script(String path, Class<T> type) {
        var script = new DefaultRedisScript<T>(); script.setLocation(new ClassPathResource(path)); script.setResultType(type); return script;
    }
}

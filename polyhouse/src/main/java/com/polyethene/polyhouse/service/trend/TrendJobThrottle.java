package com.polyethene.polyhouse.service.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;

/** 与普通业务异常分开，使管理入口始终返回 Retry-After。 */
public final class TrendJobThrottle extends BusinessException {
    private static final long serialVersionUID = 1L;
    private final long retryAfter;

    /** 只由服务端持久冷却时间计算，调用方不能指定等待值。 */
    public TrendJobThrottle(long seconds) {
        super(ErrorCode.TOO_MANY_REQUESTS);
        retryAfter = Math.max(1, Math.min(60, seconds));
    }

    /** HTTP 秒值有界，不暴露内部时钟或执行代数。 */
    public long retryAfter() { return retryAfter; }
}

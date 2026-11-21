package com.polyethene.polyhouse.service.trend;

/** 执行撤销必须穿透项目降级逻辑，不能作为单仓库资料缺失继续下一次付费。 */
public final class TrendExecutionLost extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 不携带原始数据库/会话异常，后台只持久记录固定原因。 */
    public TrendExecutionLost() { super("Trend execution authority lost"); }
}

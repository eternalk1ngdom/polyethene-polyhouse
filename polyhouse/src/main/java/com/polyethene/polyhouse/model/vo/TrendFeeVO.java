package com.polyethene.polyhouse.model.vo;

import java.util.List;

/**
 * 本地保守账本，不是DeepSeek账户余额或平台实际账单；只读不发起外部请求。
 * @param month 北京归属月
 * @param budgetMicros 当前配置的月上限，最高十元，历史月也按当前配置解释余量
 * @param accountedMicros 已计入预算，包含可信用量估算及人工保守关闭金额
 * @param heldMicros 未知/预留请求仍占用的预算
 * @param remainingMicros max(0,当前上限-已计入-持有)，不代表允许立即付费
 * @param conservativeMicros accounted中由人工保守关闭形成的部分
 * @param suspended 用量异常暂停状态，人工关闭不会解除
 * @param pending 仅本页待核对请求，可能包含尚未结束的正常请求
 * @param nextAfter 非空时有下一页；列表动态变化后应从第一页刷新
 */
public record TrendFeeVO(String month, long budgetMicros, long accountedMicros, long heldMicros, long remainingMicros,
                          long conservativeMicros, boolean suspended, List<TrendPendingChargeVO> pending, String nextAfter) {
    /** 固化返回页，避免调用方在响应序列化前修改查询结果。 */
    public TrendFeeVO { pending = List.copyOf(pending); }
}

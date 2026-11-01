package com.polyethene.polyhouse.model.vo;

import com.polyethene.polyhouse.common.constant.AccountManagementAction;
import com.polyethene.polyhouse.model.entity.ManagementOperation;

/**
 * 仅一次重置成功可交付新临时密码；其余响应只说明原操作成功事实，不代表目标现在仍相同。
 * @param requestId 原请求号
 * @param targetId 目标编号
 * @param username 处置时登录名
 * @param action 已提交动作
 * @param version 该动作提交后的管理版本
 * @param status 该动作提交后的状态
 * @param firstDelivery 是否首次交付重置密码
 * @param temporaryPassword 首次重置才有值，普通结果/确认固定 null
 */
public record AccountOperationVO(String requestId, String targetId, String username, AccountManagementAction action,
                                 String version, int status, boolean firstDelivery, String temporaryPassword) {
    /** 不读取历史密码；幂等重放和确认只能投影审计事实。 */
    public static AccountOperationVO confirmed(ManagementOperation operation) {
        return new AccountOperationVO(operation.requestId(), Long.toString(operation.targetId()), operation.username(),
                operation.action(), Long.toString(operation.afterVersion()), operation.afterStatus(), false, null);
    }

    /** 禁止调试字符串输出一次性明文，正常 JSON 仅由受限接口明确交付。 */
    @Override public String toString() { return "AccountOperationVO[credentials redacted]"; }
}

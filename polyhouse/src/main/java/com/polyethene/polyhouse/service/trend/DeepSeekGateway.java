package com.polyethene.polyhouse.service.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;

/** 付费网络边界独立于账本，模拟测试无需任何真实密钥。 */
public interface DeepSeekGateway {
    /** 调用前必须已持久预留费用；不传 Key 参数，避免进入任务或序列化内容。 */
    Reply complete(byte[] request);

    /**
     * 原始内容只作严格解析，不用于日志或公开错误。
     * @param status 上游 HTTP 状态
     * @param body 大小已限制的响应
     */
    record Reply(int status, byte[] body) {
        /** 防止诊断意外打印响应内容。 */
        @Override public String toString() { return "DeepSeekReply[status=" + status + "]"; }
    }

    /** 仅用于确定没有发送的本地拒绝；网络超时不能使用这一分类退回费用。 */
    final class NotSent extends BusinessException {
        private static final long serialVersionUID = 1L;
        /** 固定安全消息不拼接 Key 或请求对象。 */
        public NotSent() { super(ErrorCode.SERVICE_UNAVAILABLE, "AI 请求未发送"); }
    }
}

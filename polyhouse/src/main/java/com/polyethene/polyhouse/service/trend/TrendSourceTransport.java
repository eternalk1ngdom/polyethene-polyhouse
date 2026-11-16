package com.polyethene.polyhouse.service.trend;

import java.net.URI;

/** 外部 GET 边界便于离线验证采集口径，不允许由公网参数提供任意目标。 */
public interface TrendSourceTransport {
    /** 调用方负责解释状态；实现限制目标、时限、体积且不自动重试。 */
    Reply get(URI uri, String token);

    /**
     * 不保存请求令牌；正文只作解析，不能作为异常消息或公开响应。
     * @param status 上游 HTTP 状态
     * @param body 有限 UTF-8 正文
     * @param location 重定向目标，仍须通过相同白名单核验
     */
    record Reply(int status, String body, String location) {
        /** 避免日志意外记录未清洗的上游 HTML 或正文。 */
        @Override public String toString() { return "Reply[status=" + status + "]"; }
    }
}

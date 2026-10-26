package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 由服务端连接信息构造审计参数，不接受请求体、查询参数或转发头伪造登录地址。
 *
 * @param clientIp Servlet 提供的连接对端地址；未提供时允许不记录，不能信任客户端 X-Forwarded-For
 */
public record ClientRequestDTO(@Size(max = 64, message = "连接地址过长") String clientIp) {
}

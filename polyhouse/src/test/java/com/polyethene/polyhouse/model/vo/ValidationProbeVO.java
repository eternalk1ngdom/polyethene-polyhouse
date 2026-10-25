package com.polyethene.polyhouse.model.vo;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;

/**
 * 仅用于验证响应结构与角色编码序列化，不引入业务 Entity。
 *
 * @param role 只允许输出语义化编码的测试角色
 */
public record ValidationProbeVO(UserRoleEnum role) {
}

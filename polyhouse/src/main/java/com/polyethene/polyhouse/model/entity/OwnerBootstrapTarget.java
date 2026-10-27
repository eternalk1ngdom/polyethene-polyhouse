package com.polyethene.polyhouse.model.entity;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;

/**
 * 运维核对所需的最小用户投影，不读取密码、Token 或其他个人资料。
 * @param id 数据库唯一编号，不能凭用户名模糊选择
 * @param username 已规范化的实际账号，使用 Java 严格比较防止数据库大小写规则弱化核对
 * @param role 数据库当前角色，权限判断只使用枚举
 * @param status 正常状态才允许初始化
 * @param deleted 保留软删除标记，不能把注销账号当作不存在后重新授予
 */
public record OwnerBootstrapTarget(long id, String username, UserRoleEnum role, int status, int deleted) {
}

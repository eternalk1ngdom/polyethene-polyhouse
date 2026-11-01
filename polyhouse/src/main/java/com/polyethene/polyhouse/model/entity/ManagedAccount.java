package com.polyethene.polyhouse.model.entity;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import java.time.LocalDateTime;

/** 内部分页投影，不读取哈希/IP/扩展 JSON；必须再转 VO 才交付客户端。 */
public record ManagedAccount(long id, String username, String nickname, UserRoleEnum role,
                             int status, long managementVersion, boolean passwordChangeRequired,
                             LocalDateTime createdAt) { }

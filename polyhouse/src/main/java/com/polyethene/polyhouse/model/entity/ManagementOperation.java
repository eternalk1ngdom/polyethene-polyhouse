package com.polyethene.polyhouse.model.entity;

import com.polyethene.polyhouse.common.constant.AccountManagementAction;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;

/** 不可变成功事实兼作幂等审计；没有明文密码或 Token，原始快照用于拒绝改变重试意图。 */
public record ManagementOperation(long actorId, String requestId, long targetId, String username,
                                  AccountManagementAction action, UserRoleEnum actorRole,
                                  UserRoleEnum targetRole, int beforeStatus, int afterStatus,
                                  long beforeVersion, long afterVersion, String reason) { }

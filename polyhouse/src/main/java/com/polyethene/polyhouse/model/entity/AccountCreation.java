package com.polyethene.polyhouse.model.entity;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;

/**
 * 仅存成功开户事实，永远不能从此对象还原临时明文。
 * @param targetId 已创建账号编号
 * @param username 当时交付的登录账号，账号失效后仍用于核对
 * @param nickname 系统初始昵称，不作为当前昵称快照
 * @param role 当时授予的允许角色
 */
public record AccountCreation(long targetId, String username, String nickname, UserRoleEnum role) { }

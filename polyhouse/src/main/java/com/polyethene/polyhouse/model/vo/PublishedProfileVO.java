package com.polyethene.polyhouse.model.vo;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.model.entity.PublishedProfileField;
/** 历史清单与下架确认只由 OWNER 获取。
 * @param userId 精确账号编号
 * @param username 登录名
 * @param kind 目标字段
 * @param value 当前公开内容，可为默认头像 null
 * @param version 当前字段版本
 * @param verified 是否已由本审核流程确认
 * @param status 账号启停状态
 * @param role 当前账号身份
 */
public record PublishedProfileVO(String userId,String username,ProfileField kind,String value,String version,
        boolean verified,int status,UserRoleEnum role) {
    /** 不扩大为完整 User，也不输出凭据/扩展 JSON。 */
    public static PublishedProfileVO from(PublishedProfileField p) { return new PublishedProfileVO(Long.toString(p.userId()),
        p.username(),p.kind(),p.value(),Long.toString(p.version()),p.verified(),p.status(),p.role()); }
}

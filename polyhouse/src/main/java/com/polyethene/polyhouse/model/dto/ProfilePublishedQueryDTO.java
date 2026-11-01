package com.polyethene.polyhouse.model.dto;
import com.polyethene.polyhouse.common.constant.ProfileField;
import jakarta.validation.constraints.*;
/** 历史公开资料清点，不把未核对的旧内容自动标为批准。
 * @param page 页码
 * @param pageSize 最多 50 行
 * @param kind 一次查询一个明确资料字段
 * @param unverifiedOnly true 仅查看未核对内容
 */
public record ProfilePublishedQueryDTO(@Min(1) @Max(10000) Integer page, @Min(1) @Max(50) Integer pageSize,
        ProfileField kind, Boolean unverifiedOnly) {
    /** 默认先清点头像，提供显式开关查看已发布内容。 */
    public ProfilePublishedQueryDTO { page=page==null?1:page;pageSize=pageSize==null?20:pageSize;
        kind=kind==null?ProfileField.AVATAR:kind;unverifiedOnly=unverifiedOnly==null?true:unverifiedOnly; }
}

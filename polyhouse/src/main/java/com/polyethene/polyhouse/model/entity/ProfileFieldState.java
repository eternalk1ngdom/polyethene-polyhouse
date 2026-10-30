package com.polyethene.polyhouse.model.entity;
import com.polyethene.polyhouse.common.constant.ProfileField;
/** 每账号每字段一个版本；昵称与头像不共用写入版本，也不存图片字节。 */
public record ProfileFieldState(long userId,ProfileField kind,long version,Long currentId,boolean verified) { }

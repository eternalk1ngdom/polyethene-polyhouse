package com.polyethene.polyhouse.common.constant;

/** 资料字段白名单，不开放通用用户更新接口。 */
public enum ProfileField {
    /** 昵称内容与头像完全独立审批。 */
    NICKNAME,
    /** 只接收裁剪后小图，不接收 URL 或路径。 */
    AVATAR
}

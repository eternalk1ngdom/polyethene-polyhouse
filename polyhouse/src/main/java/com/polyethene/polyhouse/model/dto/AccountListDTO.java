package com.polyethene.polyhouse.model.dto;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * 有界分页与固定筛选字段，服务端还会按操作者限制可见角色。
 * @param page 页码，限制深分页造成无界数据库扫描
 * @param pageSize 单页最多五十条，避免一次返回全部账号
 * @param username 登录账号的字面前缀，不搜索密码或扩展资料
 * @param role 可选精确角色，不扩大管理员的 USER 范围
 * @param status 可选启停状态，仅 0/1
 */
public record AccountListDTO(@Min(1) @Max(10000) Integer page,
                             @Min(1) @Max(50) Integer pageSize,
                             @Pattern(regexp = "[A-Za-z0-9_]{0,32}") String username,
                             UserRoleEnum role, @Min(0) @Max(1) Integer status) {
    /** 空筛选使用小页默认值，非法的非空值仍交由 Validation 拒绝。 */
    public AccountListDTO {
        page = page == null ? 1 : page;
        pageSize = pageSize == null ? 20 : pageSize;
    }
}

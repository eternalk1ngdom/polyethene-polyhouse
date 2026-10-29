package com.polyethene.polyhouse.common.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * 定义数据库、鉴权逻辑与前端 JSON 共同使用的角色；管理职责与高开销资格分别授权。
 */
public enum UserRoleEnum {

    /** 普通注册用户，只能使用无服务端成本的基础能力。 */
    USER("USER", 10, "普通居民"),

    /** 协助管理普通用户，不因管理身份自动获得高开销功能资格。 */
    ADMIN("ADMIN", 30, "管理员"),

    /** 特邀熟人用户，可以访问受控的高开销能力。 */
    INSIDER("INSIDER", 50, "特邀熟人"),

    /** 站点所有者，拥有最高权限并向下兼容其他角色。 */
    OWNER("OWNER", 99, "聚妙屋屋主");

    @EnumValue
    private final String code;

    private final int level;

    private final String description;

    UserRoleEnum(String code, int level, String description) {
        this.code = code;
        this.level = level;
        this.description = description;
    }

    /**
     * 返回数据库存储值，同时作为 JSON 中稳定的角色标识。
     *
     * @return 角色编码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 返回角色权重，供统一权限判断使用，禁止业务层直接比较数字字面量。
     *
     * @return 角色权重
     */
    public int getLevel() {
        return level;
    }

    /**
     * 返回面向界面的中文角色说明。
     *
     * @return 角色说明
     */
    public String getDescription() {
        return description;
    }

    /**
     * 基础/高开销资格按既有等级判断，ADMIN 要求使用精确角色集合，防止 INSIDER 越权管理。
     *
     * @param required 目标角色
     * @return 满足目标角色的具体资格时返回 true；目标账号范围还须由业务策略检查
     */
    public boolean hasPermission(UserRoleEnum required) {
        Objects.requireNonNull(required, "required role must not be null");
        if (required == ADMIN) return this == ADMIN || this == OWNER;
        return level >= required.level;
    }
}

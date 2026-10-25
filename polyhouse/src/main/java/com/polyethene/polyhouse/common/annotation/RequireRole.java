package com.polyethene.polyhouse.common.annotation;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口或控制器所需的最低角色，供统一鉴权组件执行权重判断。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /**
     * 指定允许访问目标资源的最低角色，调用方必须显式声明以避免误开放高开销接口。
     *
     * @return 最低角色要求
     */
    UserRoleEnum value();
}

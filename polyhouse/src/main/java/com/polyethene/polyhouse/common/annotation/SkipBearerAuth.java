package com.polyethene.polyhouse.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式跳过统一 Bearer 入口，用于注册、登录及由服务层独立核验请求体 Token 的撤销接口。
 * 不跳过方法或类型上的 RequireRole，避免错误组合注解导致角色门槛被绕过。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipBearerAuth {
}

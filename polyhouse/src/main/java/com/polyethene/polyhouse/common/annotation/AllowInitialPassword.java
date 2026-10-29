package com.polyethene.polyhouse.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 仅给必要的本人资料/改密方法开放临时凭据；此标记不会跳过认证或角色检查。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowInitialPassword { }

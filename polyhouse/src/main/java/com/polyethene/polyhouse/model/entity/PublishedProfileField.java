package com.polyethene.polyhouse.model.entity;
import com.polyethene.polyhouse.common.constant.*;
/** 屋主专用最小清单投影，未核对并不等于历史内容曾经审批通过。 */
public record PublishedProfileField(long userId,String username,ProfileField kind,String value,long version,
        boolean verified,int status,UserRoleEnum role) { }

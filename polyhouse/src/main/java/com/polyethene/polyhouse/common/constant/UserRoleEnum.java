package com.polyethene.polyhouse.common.constant;

/**
 * Defines the stable role names used across authorization layers.
 */
public enum UserRoleEnum {
    /** Ordinary registered user. */
    USER,
    /** Trusted user allowed to access controlled resources. */
    INSIDER,
    /** Site owner with the highest authority. */
    OWNER
}

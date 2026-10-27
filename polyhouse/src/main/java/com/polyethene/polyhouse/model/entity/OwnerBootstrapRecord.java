package com.polyethene.polyhouse.model.entity;

/**
 * 单槽首次授权记录；不随用户物理删除自动清理，避免误删后由另一账号重新认领屋主。
 * @param slot 固定为 1，锁定这一行使多个命令串行执行
 * @param userId 已初始化的编号，空值表示从未完成初始化
 */
public record OwnerBootstrapRecord(int slot, Long userId) {
}

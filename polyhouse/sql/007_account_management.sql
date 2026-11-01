-- 在 001～006 后人工执行；保留现有账号、凭据和唯一屋主，先备份再迁移。
USE `polyhouse_db`;
SET @polyhouse_management_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
           AND TABLE_NAME='sys_user' AND COLUMN_NAME='management_version'),
    'SELECT 1',
    'ALTER TABLE `sys_user` ADD COLUMN `management_version` bigint NOT NULL DEFAULT 0 COMMENT ''账号管理并发版本，仅管理动作递增'' CHECK (`management_version` >= 0)'
);
PREPARE polyhouse_management_statement FROM @polyhouse_management_sql;
EXECUTE polyhouse_management_statement;
DEALLOCATE PREPARE polyhouse_management_statement;
SET @polyhouse_management_sql = NULL;

SET @polyhouse_management_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
           AND TABLE_NAME='sys_user' AND INDEX_NAME='idx_user_management_scope'),
    'SELECT 1',
    'CREATE INDEX `idx_user_management_scope` ON `sys_user` (`deleted`, `role`, `status`, `id`)'
);
PREPARE polyhouse_management_statement FROM @polyhouse_management_sql;
EXECUTE polyhouse_management_statement;
DEALLOCATE PREPARE polyhouse_management_statement;
SET @polyhouse_management_sql = NULL;

-- 成功事实同时承担幂等与最小管理审计，不保存任何明文密码或完整请求体，不级联删除。
CREATE TABLE IF NOT EXISTS `sys_account_management_operation` (
    `actor_id` bigint NOT NULL COMMENT '锁内核验的操作者',
    `request_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '同一次管理意图 UUID',
    `target_id` bigint NOT NULL COMMENT '精确目标编号',
    `username` varchar(32) NOT NULL COMMENT '操作时账号名',
    `action` varchar(32) NOT NULL COMMENT 'BAN/UNBAN/PASSWORD_RESET 白名单',
    `actor_role` varchar(16) NOT NULL COMMENT '成功时操作者角色',
    `target_role` varchar(16) NOT NULL COMMENT '锁内目标角色',
    `before_status` tinyint NOT NULL COMMENT '确认时状态',
    `after_status` tinyint NOT NULL COMMENT '成功后状态',
    `before_version` bigint NOT NULL COMMENT '管理前版本',
    `after_version` bigint NOT NULL COMMENT '成功后版本',
    `reason` varchar(200) NOT NULL COMMENT '最小必要原因，不填写凭据或私人信息',
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '数据库成功时间',
    PRIMARY KEY (`actor_id`, `request_id`),
    KEY `idx_management_target_time` (`target_id`, `create_time`),
    CONSTRAINT `chk_management_action` CHECK (`action` IN ('BAN','UNBAN','PASSWORD_RESET')),
    CONSTRAINT `chk_management_status` CHECK (`before_status` IN (0,1) AND `after_status` IN (0,1)),
    CONSTRAINT `chk_management_version` CHECK (`before_version` >= 0 AND `after_version` > `before_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号管理成功审计与幂等事实，无明文凭据';

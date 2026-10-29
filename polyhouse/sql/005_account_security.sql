-- 在 001～004 后人工执行；只增加安全状态，保留既有密码、角色、编号与会话。
-- 先迁移再启用新应用；新列默认值使历史账号仍可正常登录，不强制修改真实屋主密码。
USE `polyhouse_db`;

SET @polyhouse_security_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'credential_version'),
    'SELECT 1',
    'ALTER TABLE `sys_user` ADD COLUMN `credential_version` bigint NOT NULL DEFAULT 0 COMMENT ''凭据版本，改密与安全撤销后只增不减'' CHECK (`credential_version` >= 0)'
);
PREPARE polyhouse_security_statement FROM @polyhouse_security_sql;
EXECUTE polyhouse_security_statement;
DEALLOCATE PREPARE polyhouse_security_statement;

SET @polyhouse_security_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'password_change_required'),
    'SELECT 1',
    'ALTER TABLE `sys_user` ADD COLUMN `password_change_required` tinyint NOT NULL DEFAULT 0 COMMENT ''临时凭据必须先改密，历史账号默认不受限制'' CHECK (`password_change_required` IN (0, 1))'
);
PREPARE polyhouse_security_statement FROM @polyhouse_security_sql;
EXECUTE polyhouse_security_statement;
DEALLOCATE PREPARE polyhouse_security_statement;
SET @polyhouse_security_sql = NULL;

-- 不关联级联删除：软注销或后续移交不能抹去安全操作事实。此表不存明文凭据或 Token。
CREATE TABLE IF NOT EXISTS `sys_account_audit` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '审计流水编号',
    `actor_id` bigint NOT NULL COMMENT '服务端核验的操作者编号',
    `target_id` bigint NOT NULL COMMENT '操作目标编号',
    `action` varchar(32) NOT NULL COMMENT '受控的安全操作类型',
    `credential_version` bigint NOT NULL COMMENT '成功提交时的目标凭据版本',
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '数据库记录成功时间',
    PRIMARY KEY (`id`),
    KEY `idx_account_audit_target_time` (`target_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='最小账号安全审计，不保存凭据';

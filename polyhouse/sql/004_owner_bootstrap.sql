-- 在 001～003 后人工执行；不授予任何现有用户权限，不随应用启动改表。
-- OWNER（包括禁用/软删除记录）映射为固定槽位 1，其他角色为 NULL；唯一索引允许多个 NULL。
-- DDL 隐式提交：已有多个屋主时建索引失败，必须人工核对，不能自动选择或降权任何账号。
USE `polyhouse_db`;

SET @polyhouse_owner_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'owner_slot'),
    'SELECT 1',
    'ALTER TABLE `sys_user` ADD COLUMN `owner_slot` tinyint GENERATED ALWAYS AS (CASE WHEN `role` = ''OWNER'' THEN 1 ELSE NULL END) VIRTUAL COMMENT ''全库屋主唯一槽位，软删除不释放'''
);
PREPARE polyhouse_owner_statement FROM @polyhouse_owner_sql;
EXECUTE polyhouse_owner_statement;
DEALLOCATE PREPARE polyhouse_owner_statement;

SET @polyhouse_owner_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'uk_sys_user_owner_slot'),
    'SELECT 1',
    'ALTER TABLE `sys_user` ADD UNIQUE INDEX `uk_sys_user_owner_slot` (`owner_slot`)'
);
PREPARE polyhouse_owner_statement FROM @polyhouse_owner_sql;
EXECUTE polyhouse_owner_statement;
DEALLOCATE PREPARE polyhouse_owner_statement;
SET @polyhouse_owner_sql = NULL;

-- 单行既是初始化互斥锁，也是首次授权记录；无外键，误删用户后仍保留记录并阻止重新认领。
CREATE TABLE IF NOT EXISTS `sys_owner_bootstrap` (
    `slot` tinyint NOT NULL,
    `user_id` bigint DEFAULT NULL COMMENT '首次确认的屋主；不能因用户被删除而丢失记录',
    `username` varchar(32) DEFAULT NULL COMMENT '初始化时精确核对的规范账号',
    `previous_role` varchar(16) DEFAULT NULL COMMENT '首次执行前角色；已有同一屋主时记录 OWNER',
    `operator_name` varchar(128) DEFAULT NULL COMMENT '执行 Java 进程的操作系统账号名，并非网站身份',
    `initialized_at` datetime(6) DEFAULT NULL COMMENT '数据库记录首次完成时间',
    PRIMARY KEY (`slot`),
    CONSTRAINT `chk_owner_bootstrap_slot` CHECK (`slot` = 1),
    CONSTRAINT `chk_owner_bootstrap_record` CHECK (
        (`user_id` IS NULL AND `username` IS NULL AND `previous_role` IS NULL
            AND `operator_name` IS NULL AND `initialized_at` IS NULL)
        OR (`user_id` IS NOT NULL AND `user_id` > 0 AND `username` IS NOT NULL AND `previous_role` IS NOT NULL
            AND `operator_name` IS NOT NULL AND `initialized_at` IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='唯一屋主初始化锁与首次授权审计';

-- 重跑只确保空槽存在，绝不覆盖已记录的屋主或执行人。
INSERT INTO `sys_owner_bootstrap` (`slot`) VALUES (1) ON DUPLICATE KEY UPDATE `slot` = 1;

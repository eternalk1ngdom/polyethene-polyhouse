-- 在 001_init.sql 之后执行；既有本机开发库只执行本迁移，不重放建表基线。
-- 仅追加成长字段和非负约束，已有账号自动获得 0 经验、1 级、0 硬币。
-- 每列单独检查是否存在，允许中断后重跑；不会重置已存在的值。
-- MySQL DDL 会隐式提交，不能依赖事务回滚；应用升级前应先完成迁移并核对结构。
USE `polyhouse_db`;

SET @polyhouse_growth_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'experience'),
    'SELECT 1',
    'ALTER TABLE `sys_user` ADD COLUMN `experience` bigint NOT NULL DEFAULT 0 COMMENT ''累计经验，奖励规则待定'' AFTER `role`, ADD CONSTRAINT `chk_sys_user_experience` CHECK (`experience` >= 0)'
);
PREPARE polyhouse_growth_statement FROM @polyhouse_growth_sql;
EXECUTE polyhouse_growth_statement;
DEALLOCATE PREPARE polyhouse_growth_statement;

SET @polyhouse_growth_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'level'),
    'SELECT 1',
    'ALTER TABLE `sys_user` ADD COLUMN `level` int NOT NULL DEFAULT 1 COMMENT ''成长等级，与权限角色独立，升级规则待定'' AFTER `experience`, ADD CONSTRAINT `chk_sys_user_level` CHECK (`level` >= 1)'
);
PREPARE polyhouse_growth_statement FROM @polyhouse_growth_sql;
EXECUTE polyhouse_growth_statement;
DEALLOCATE PREPARE polyhouse_growth_statement;

SET @polyhouse_growth_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'coins'),
    'SELECT 1',
    'ALTER TABLE `sys_user` ADD COLUMN `coins` bigint NOT NULL DEFAULT 0 COMMENT ''整数硬币余额，发放与用途待定'' AFTER `level`, ADD CONSTRAINT `chk_sys_user_coins` CHECK (`coins` >= 0)'
);
PREPARE polyhouse_growth_statement FROM @polyhouse_growth_sql;
EXECUTE polyhouse_growth_statement;
DEALLOCATE PREPARE polyhouse_growth_statement;
SET @polyhouse_growth_sql = NULL;

-- 已使用旧版 001_schema.sql 建库的环境适用。执行前确认目标库和现有约束。
-- 先加新约束，再移除旧约束，避免两步之间失去数据库角色校验。
ALTER TABLE `sys_account_creation`
    ADD CONSTRAINT `chk_account_creation_role_v2` CHECK (`role` IN ('USER', 'ADMIN', 'INSIDER'));

ALTER TABLE `sys_account_creation` DROP CHECK `chk_account_creation_role`;

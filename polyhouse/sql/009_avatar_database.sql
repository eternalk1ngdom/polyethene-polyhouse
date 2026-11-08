-- 在 001～008 后停写并备份再执行。先连接目标库。
ALTER TABLE sys_user MODIFY COLUMN avatar MEDIUMTEXT COMMENT '当前生效的头像Base64';

SET @avatar_ddl = IF(
 EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='sys_user' AND COLUMN_NAME='pending_avatar'),
 'SELECT 1', 'ALTER TABLE sys_user ADD COLUMN pending_avatar MEDIUMTEXT COMMENT ''待审核的新头像Base64'''
);
PREPARE avatar_migration FROM @avatar_ddl;
EXECUTE avatar_migration;
DEALLOCATE PREPARE avatar_migration;

SET @avatar_ddl = IF(
 EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='sys_user' AND COLUMN_NAME='avatar_status'),
 'SELECT 1', 'ALTER TABLE sys_user ADD COLUMN avatar_status TINYINT NOT NULL DEFAULT 0 COMMENT ''0-无待审, 1-待审核, 2-已驳回'''
);
PREPARE avatar_migration FROM @avatar_ddl;
EXECUTE avatar_migration;
DEALLOCATE PREPARE avatar_migration;
SET @avatar_ddl = NULL;

-- 保留历史记录与文件键供离线核对；没有数据库图像的旧申请须重新提交。
UPDATE sys_profile_submission
SET status='INVALIDATED', reason='旧版待审头像请重新提交', reviewed_at=CURRENT_TIMESTAMP(6)
WHERE kind='AVATAR' AND status='PENDING' AND private_key IS NOT NULL;

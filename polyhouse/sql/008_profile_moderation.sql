-- 在 001～007 后先备份再人工执行。只增加独立资料状态，保留所有真实用户公开字段。
USE `polyhouse_db`;
CREATE TABLE IF NOT EXISTS `sys_profile_field` (
 `user_id` bigint NOT NULL, `kind` varchar(16) NOT NULL,
 `version` bigint NOT NULL DEFAULT 0 COMMENT '新申请/下架推进，字段独立',
 `current_id` bigint DEFAULT NULL COMMENT '当前公开申请，null 可能为历史内容/默认',
 `verified` tinyint NOT NULL DEFAULT 0 COMMENT '历史内容不自动算已审',
 PRIMARY KEY (`user_id`,`kind`),
 CONSTRAINT `chk_profile_field_kind` CHECK (`kind` IN ('NICKNAME','AVATAR')),
 CONSTRAINT `chk_profile_field_version` CHECK (`version`>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS `sys_profile_submission` (
 `id` bigint NOT NULL, `user_id` bigint NOT NULL, `kind` varchar(16) NOT NULL,
 `status` varchar(16) NOT NULL, `credential_version` bigint NOT NULL COMMENT '提交时安全版本，封禁后解封不能复活旧申请', `version` bigint NOT NULL,
 `request_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 `proposed_value` varchar(32) DEFAULT NULL, `content_hash` char(64) NOT NULL,
 `private_key` char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
 `reason` varchar(200) DEFAULT NULL,
 `created_at` datetime(6) NOT NULL, `expires_at` datetime(6) NOT NULL,
 `reviewed_at` datetime(6) DEFAULT NULL, `reviewer_id` bigint DEFAULT NULL,
 PRIMARY KEY (`id`), UNIQUE KEY `uk_profile_request` (`user_id`,`request_id`),
 UNIQUE KEY `uk_profile_generation` (`user_id`,`kind`,`version`),
 KEY `idx_profile_quota` (`user_id`,`kind`,`created_at`),
 KEY `idx_profile_queue` (`status`,`expires_at`,`id`),
 KEY `idx_profile_private` (`private_key`,`status`),
 CONSTRAINT `chk_profile_submission_kind` CHECK (`kind` IN ('NICKNAME','AVATAR')),
 CONSTRAINT `chk_profile_submission_status` CHECK (`status` IN ('PENDING','APPROVED','REJECTED','SUPERSEDED','EXPIRED','INVALIDATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='申请事实与最小内容，不存图片字节';
CREATE TABLE IF NOT EXISTS `sys_profile_audit` (
 `id` bigint NOT NULL, `actor_id` bigint DEFAULT NULL, `actor_role` varchar(16) DEFAULT NULL,
 `target_id` bigint NOT NULL, `kind` varchar(16) NOT NULL, `submission_id` bigint DEFAULT NULL,
 `action` varchar(24) NOT NULL, `version` bigint NOT NULL, `reason` varchar(200) NOT NULL,
 `created_at` datetime(6) NOT NULL,
 PRIMARY KEY (`id`),KEY `idx_profile_audit_target` (`target_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资料审核最小审计，无密码/Token/路径';
-- 不自动回填已审标记；不存在的字段行按未核对历史处理，首次操作才懒创建。

-- 清理每个候选文件都会核对引用；索引避免随历史申请增长反复全表扫描。
-- 兼容已在本机先执行过本阶段迁移的库，重跑只补缺少的索引，不改申请事实。
SET @polyhouse_profile_index = IF(
 EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='sys_profile_submission' AND INDEX_NAME='idx_profile_private'),
 'SELECT 1', 'CREATE INDEX `idx_profile_private` ON `sys_profile_submission` (`private_key`,`status`)'
);
PREPARE polyhouse_profile_statement FROM @polyhouse_profile_index;
EXECUTE polyhouse_profile_statement;
DEALLOCATE PREPARE polyhouse_profile_statement;
SET @polyhouse_profile_index = NULL;

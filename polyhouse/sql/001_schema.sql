-- MySQL 8.0 / TiDB Cloud 空库初始化。执行前选择明确的空数据库。
-- 不建库、不切库、不删表、不创建用户。表已存在时立即报错，请勿用强制继续模式。
-- 已有库只按文档选用文末的升级语句，不重跑整份文件。
-- 约束预检、屋主开户和旧库回退参见 docs/operations/database-bootstrap.md。

-- 用户名在软删除后仍被保留；同一时刻只允许一个 OWNER（包括禁用或软删除）。
CREATE TABLE `sys_user` (
    `id` bigint NOT NULL COMMENT '主键ID (雪花算法)',
    `username` varchar(32) NOT NULL COMMENT '登录账号 (唯一)',
    `password_hash` varchar(100) NOT NULL COMMENT 'BCrypt加盐慢哈希密文',
    `nickname` varchar(32) NOT NULL COMMENT '用户昵称 (右上角Popover展示)',
    `avatar` MEDIUMTEXT COMMENT '当前生效的头像Base64',
    `pending_avatar` MEDIUMTEXT COMMENT '待审核的新头像Base64',
    `avatar_status` tinyint NOT NULL DEFAULT 0 COMMENT '0-无待审, 1-待审核, 2-已驳回',
    `role` varchar(16) NOT NULL DEFAULT 'USER' COMMENT 'USER-普通用户, ADMIN-管理员, INSIDER-特邀熟人, OWNER-屋主',
    `experience` bigint NOT NULL DEFAULT 0 COMMENT '累计经验',
    `level` int NOT NULL DEFAULT 1 COMMENT '成长等级，与权限角色独立',
    `coins` bigint NOT NULL DEFAULT 0 COMMENT '整数硬币余额',
    `credential_version` bigint NOT NULL DEFAULT 0 COMMENT '凭据版本，安全撤销后递增',
    `password_change_required` tinyint NOT NULL DEFAULT 0 COMMENT '临时凭据必须先改密',
    `management_version` bigint NOT NULL DEFAULT 0 COMMENT '账号管理并发版本',
    `owner_slot` tinyint GENERATED ALWAYS AS (CASE WHEN `role` = 'OWNER' THEN 1 ELSE NULL END) VIRTUAL
        COMMENT '全库屋主唯一槽位，软删除不释放',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '账号状态: 1-正常, 0-封禁禁用',
    `last_login_ip` varchar(64) DEFAULT NULL COMMENT '最后登录IP',
    `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
    `ext_json` json DEFAULT NULL COMMENT '弹性扩展属性JSON (如主题偏好、个性化设置，免改表结构)',
    `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-未删, 1-已删',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_sys_user_owner_slot` (`owner_slot`),
    KEY `idx_user_management_scope` (`deleted`, `role`, `status`, `id`),
    CONSTRAINT `chk_sys_user_experience` CHECK (`experience` >= 0),
    CONSTRAINT `chk_sys_user_level` CHECK (`level` >= 1),
    CONSTRAINT `chk_sys_user_coins` CHECK (`coins` >= 0),
    CONSTRAINT `chk_sys_user_credential_version` CHECK (`credential_version` >= 0),
    CONSTRAINT `chk_sys_user_password_change_required` CHECK (`password_change_required` IN (0, 1)),
    CONSTRAINT `chk_sys_user_management_version` CHECK (`management_version` >= 0),
    CONSTRAINT `chk_sys_user_avatar_status` CHECK (`avatar_status` IN (0, 1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='聚妙屋系统用户表';

CREATE TABLE `tool_replace_template` (
    `id` bigint NOT NULL COMMENT '主键ID',
    `user_id` bigint NOT NULL COMMENT '所属用户ID (关联 sys_user.id)',
    `template_name` varchar(64) NOT NULL COMMENT '规则组名称 (如：代码重构词表 / 常用脱敏词表)',
    `rules_json` json NOT NULL COMMENT '替换规则数组 JSON: [{"search":"apple","replace":"banana"}]',
    `file_extensions` varchar(128) DEFAULT '.txt,.md,.json,.java,.ts,.vue,.html,.csv' COMMENT '限定处理的文件后缀',
    `ext_json` json DEFAULT NULL COMMENT '弹性扩展配置JSON',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='妙替-用户自定义批量替换模板表';

CREATE TABLE `tool_targetkb_preset` (
    `id` bigint NOT NULL COMMENT '主键ID',
    `preset_code` varchar(32) NOT NULL COMMENT '预设唯一编码 (GUOKAO, CET46, NTCE)',
    `title` varchar(64) NOT NULL COMMENT '预设名称',
    `target_width` int DEFAULT NULL COMMENT '目标宽度px',
    `target_height` int DEFAULT NULL COMMENT '目标高度px',
    `min_kb` int NOT NULL COMMENT '体积下限 (KB)',
    `max_kb` int NOT NULL COMMENT '体积上限 (KB)',
    `target_dpi` int NOT NULL DEFAULT '300' COMMENT '标准DPI值',
    `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序权重',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '1-启用, 0-下架',
    `ext_json` json DEFAULT NULL COMMENT '弹性扩展属性JSON (如底色建议、报考提示语)',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_preset_code` (`preset_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='妙压-官方报考预设表';

CREATE TABLE `tool_user_preset` (
    `id` bigint NOT NULL COMMENT '主键ID',
    `user_id` bigint NOT NULL COMMENT '所属用户ID',
    `preset_name` varchar(64) NOT NULL COMMENT '自定义规格名称',
    `target_width` int DEFAULT NULL,
    `target_height` int DEFAULT NULL,
    `min_kb` int NOT NULL,
    `max_kb` int NOT NULL,
    `ext_json` json DEFAULT NULL COMMENT '弹性扩展配置JSON',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='妙压-用户自定义卡位预设表';

CREATE TABLE `sys_user_growth_reward` (
    `user_id` bigint NOT NULL COMMENT '奖励归属，仅由已认证的服务端业务提供',
    `action_code` varchar(32) NOT NULL COMMENT '奖励行为，如 DAILY_LOGIN；多端共享次数',
    `reward_date` date NOT NULL COMMENT '服务端上海自然日，不接受客户端日期',
    `experience_delta` int NOT NULL COMMENT '本次实际增加的经验',
    `coins_delta` int NOT NULL COMMENT '本次实际增加的整数硬币',
    `rule_version` varchar(32) NOT NULL COMMENT '结算规则版本，仅作审计不参与防重',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '数据库记录入账时间',
    PRIMARY KEY (`user_id`, `action_code`, `reward_date`),
    CONSTRAINT `chk_growth_reward_experience` CHECK (`experience_delta` >= 0),
    CONSTRAINT `chk_growth_reward_coins` CHECK (`coins_delta` >= 0),
    -- 软注销保留用户行和奖励历史；物理清理专用测试用户时同时清理其精确归属流水。
    CONSTRAINT `fk_growth_reward_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户每日成长奖励流水与防重记录';

CREATE TABLE `sys_owner_bootstrap` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='唯一屋主初始化锁与首次授权审计';

CREATE TABLE `sys_account_audit` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '审计流水编号',
    `actor_id` bigint NOT NULL COMMENT '服务端核验的操作者编号',
    `target_id` bigint NOT NULL COMMENT '操作目标编号',
    `action` varchar(32) NOT NULL COMMENT '受控的安全操作类型',
    `credential_version` bigint NOT NULL COMMENT '成功提交时的目标凭据版本',
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '数据库记录成功时间',
    PRIMARY KEY (`id`),
    KEY `idx_account_audit_target_time` (`target_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='最小账号安全审计，不保存凭据';

CREATE TABLE `sys_account_sequence` (
    `slot` tinyint NOT NULL PRIMARY KEY COMMENT '固定账号发号槽',
    `next_value` bigint NOT NULL COMMENT '下次尝试的序号，成功发出后不回收',
    CONSTRAINT `chk_account_sequence_slot` CHECK (`slot` = 1),
    CONSTRAINT `chk_account_sequence_value` CHECK (`next_value` BETWEEN 1 AND 1000000000000000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='跨实例账号发号行锁';

CREATE TABLE `sys_account_creation` (
    `actor_id` bigint NOT NULL COMMENT '经锁内校验的屋主编号',
    `request_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建意图 UUID，不含凭据',
    `target_id` bigint NOT NULL COMMENT '成功创建的用户编号',
    `username` varchar(32) NOT NULL COMMENT '原始交付账号，注销后仍保留',
    `nickname` varchar(32) NOT NULL COMMENT '系统生成的初始昵称',
    `role` varchar(16) NOT NULL COMMENT '屋主发放的角色，限 USER、ADMIN、INSIDER',
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '成功提交时刻',
    PRIMARY KEY (`actor_id`, `request_id`),
    UNIQUE KEY `uk_account_creation_target` (`target_id`),
    CONSTRAINT `chk_account_creation_role_v2` CHECK (`role` IN ('USER', 'ADMIN', 'INSIDER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='开户成功事实；禁止保存初始明文密码';

CREATE TABLE `sys_account_management_operation` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='账号管理成功审计与幂等事实，无明文凭据';

CREATE TABLE `sys_profile_field` (
 `user_id` bigint NOT NULL, `kind` varchar(16) NOT NULL,
 `version` bigint NOT NULL DEFAULT 0 COMMENT '新申请/下架推进，字段独立',
 `current_id` bigint DEFAULT NULL COMMENT '当前公开申请，null 可能为历史内容/默认',
 `verified` tinyint NOT NULL DEFAULT 0 COMMENT '历史内容不自动算已审',
 PRIMARY KEY (`user_id`,`kind`),
 CONSTRAINT `chk_profile_field_kind` CHECK (`kind` IN ('NICKNAME','AVATAR')),
 CONSTRAINT `chk_profile_field_version` CHECK (`version`>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `sys_profile_submission` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='申请事实与最小内容，不存图片字节';

CREATE TABLE `sys_profile_audit` (
 `id` bigint NOT NULL, `actor_id` bigint DEFAULT NULL, `actor_role` varchar(16) DEFAULT NULL,
 `target_id` bigint NOT NULL, `kind` varchar(16) NOT NULL, `submission_id` bigint DEFAULT NULL,
 `action` varchar(24) NOT NULL, `version` bigint NOT NULL, `reason` varchar(200) NOT NULL,
 `created_at` datetime(6) NOT NULL,
 PRIMARY KEY (`id`),KEY `idx_profile_audit_target` (`target_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='资料审核最小审计，无密码/Token/路径';

-- 空槽用于串行化首次屋主初始化，发号从 1 开始。
INSERT INTO `sys_owner_bootstrap` (`slot`) VALUES (1);
INSERT INTO `sys_account_sequence` (`slot`, `next_value`) VALUES (1, 1);

-- 已有库升级：确认目标库和现有约束后，按文档复制所需语句单独执行。
-- 先添加新约束，再移除旧约束，避免中途失去角色校验。空库初始化会跳过此段。
/* EXISTING_DATABASE_INSIDER_MIGRATION
ALTER TABLE `sys_account_creation`
    ADD CONSTRAINT `chk_account_creation_role_v2` CHECK (`role` IN ('USER', 'ADMIN', 'INSIDER'));

ALTER TABLE `sys_account_creation` DROP CHECK `chk_account_creation_role`;
*/

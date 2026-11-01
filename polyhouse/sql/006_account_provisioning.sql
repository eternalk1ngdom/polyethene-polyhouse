-- 在 001～005 后人工执行；只追加发号和幂等记录，不改变已有账号或唯一屋主。
USE `polyhouse_db`;

CREATE TABLE IF NOT EXISTS `sys_account_sequence` (
    `slot` tinyint NOT NULL PRIMARY KEY COMMENT '固定账号发号槽',
    `next_value` bigint NOT NULL COMMENT '下次尝试的序号，成功发出后不回收',
    CONSTRAINT `chk_account_sequence_slot` CHECK (`slot` = 1),
    CONSTRAINT `chk_account_sequence_value` CHECK (`next_value` BETWEEN 1 AND 1000000000000000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨实例账号发号行锁';

-- 仅首次迁移从全部历史记录（含软删除）确定起点；运行时不使用 MAX+1。
-- 限制十八位数字，使 CAST、Java long 和序号上限均无溢出；重跑不重置既有发号进度。
INSERT IGNORE INTO `sys_account_sequence` (`slot`, `next_value`)
SELECT 1, COALESCE(MAX(CAST(SUBSTRING(`username`, 3) AS UNSIGNED)), 0) + 1
FROM `sys_user` WHERE LOWER(`username`) REGEXP '^ph[0-9]{6,18}$';

CREATE TABLE IF NOT EXISTS `sys_account_creation` (
    `actor_id` bigint NOT NULL COMMENT '经锁内校验的屋主编号',
    `request_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建意图 UUID，不含凭据',
    `target_id` bigint NOT NULL COMMENT '成功创建的用户编号',
    `username` varchar(32) NOT NULL COMMENT '原始交付账号，注销后仍保留',
    `nickname` varchar(32) NOT NULL COMMENT '系统生成的初始昵称',
    `role` varchar(16) NOT NULL COMMENT '创建时角色，限 USER 或 ADMIN',
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '成功提交时刻',
    PRIMARY KEY (`actor_id`, `request_id`),
    UNIQUE KEY `uk_account_creation_target` (`target_id`),
    CONSTRAINT `chk_account_creation_role` CHECK (`role` IN ('USER', 'ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开户成功事实；禁止保存初始明文密码';

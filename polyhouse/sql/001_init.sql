-- 从只读策划书归档的 MySQL 8.0 初始化基线，仅用于空数据库的人工初始化。
-- 不由 Spring Boot 自动执行；现有数据库不得直接重放，应先核对后编写独立迁移。
-- 不包含账号、密码、种子用户或删除语句；表已存在时让 CREATE TABLE 明确失败。
CREATE DATABASE IF NOT EXISTS `polyhouse_db`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `polyhouse_db`;

-- 用户账号唯一约束与逻辑删除并存：软删除后账号仍被保留，不能隐式复用身份。
CREATE TABLE `sys_user` (
    `id` bigint NOT NULL COMMENT '主键ID (雪花算法)',
    `username` varchar(32) NOT NULL COMMENT '登录账号 (唯一)',
    `password_hash` varchar(100) NOT NULL COMMENT 'BCrypt加盐慢哈希密文',
    `nickname` varchar(32) NOT NULL COMMENT '用户昵称 (右上角Popover展示)',
    `avatar` varchar(255) DEFAULT 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png' COMMENT '头像URL',
    `role` varchar(16) NOT NULL DEFAULT 'USER' COMMENT '三阶角色: USER-普通用户(10), INSIDER-特邀熟人(50), OWNER-屋主本人(99)',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '账号状态: 1-正常, 0-封禁禁用',
    `last_login_ip` varchar(64) DEFAULT NULL COMMENT '最后登录IP',
    `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
    `ext_json` json DEFAULT NULL COMMENT '弹性扩展属性JSON (如主题偏好、个性化设置，免改表结构)',
    `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-未删, 1-已删',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聚妙屋系统用户表';

-- 替换规则通过 user_id 建索引，后续查询仍必须按当前用户限定数据范围。
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='妙替-用户自定义批量替换模板表';

-- 官方预设编码唯一，为后续稳定引用与缓存键提供基础。
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='妙压-官方报考预设表';

-- 个人预设与官方预设分表，保留独立的用户归属与扩展配置。
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='妙压-用户自定义卡位预设表';

-- 在 001、002 之后执行；既有开发库只追加本迁移，重复执行不重置用户或流水。
-- 日奖励不使用 Redis 防重：流水和用户余额在同一 MySQL 事务中提交，避免缓存丢失后重复发奖。
USE `polyhouse_db`;

CREATE TABLE IF NOT EXISTS `sys_user_growth_reward` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户每日成长奖励流水与防重记录';

package com.polyethene.polyhouse.model.vo;

/**
 * 只暴露应用就绪事实，不泄露实例配置、依赖连接或任务信息。
 * @param status 固定READY，供脚本排除平台冷启动页面和普通HTTP 200页面
 * @param scope 固定APPLICATION，避免将应用启动误认为数据库、AI或任务验收
 */
public record TrendReadinessVO(String status, String scope) { }

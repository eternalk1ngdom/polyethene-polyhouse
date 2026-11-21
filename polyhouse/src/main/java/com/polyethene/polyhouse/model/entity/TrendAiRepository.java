package com.polyethene.polyhouse.model.entity;

/**
 * 每仓库一个付费执行槽及最近成功缓存，未知请求保留槽以阻止重复付费。
 * @param repositoryId GitHub 稳定编号
 * @param activeRequest 正在执行或结果待核对的请求 UUID
 * @param payload 最近成功解读的脱敏 JSON，失败不会清掉旧值
 */
public record TrendAiRepository(long repositoryId, String activeRequest, String payload) { }

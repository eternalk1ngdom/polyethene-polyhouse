package com.polyethene.polyhouse.model.vo;

/**
 * 活动任务会合并返回，调用方不能把已有任务误解为又排入了一批。
 * @param accepted 是否本次新受理
 * @param replayed 是否返回相同请求编号的已存事实
 * @param job 本次或当前唯一任务
 */
public record TrendJobSubmissionVO(boolean accepted, boolean replayed, TrendJobVO job) { }

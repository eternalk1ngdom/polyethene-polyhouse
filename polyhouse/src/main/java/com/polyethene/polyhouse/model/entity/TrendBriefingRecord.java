package com.polyethene.polyhouse.model.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 7 日风口简报有效稿与并发执行槽持久化投影；未产出有效稿前仅锚点日和活动槽位有值。
 * @param anchorDate 观察窗口锚点日期（北京时间）
 * @param windowStart 最近有效稿覆盖窗口起始日
 * @param windowEnd 最近有效稿覆盖窗口结束日
 * @param coveredDays 最近有效稿实际覆盖天数（3～7）
 * @param sampleStatus 最近有效稿样本档位（LIMITED 或 SUFFICIENT）
 * @param sourceFingerprint 最近有效稿对应的 64 位 SHA-256 快照指纹
 * @param model 最近有效稿使用的模型标识
 * @param ruleVersion 最近有效稿使用的规则版本
 * @param activeRequest 当前未结束付费请求的执行槽 UUID，空闲时为 null
 * @param payload 经严格校验的脱敏简报 JSON，不含原始 Prompt
 * @param generatedAt 最近有效稿生成时间（UTC）
 */
public record TrendBriefingRecord(LocalDate anchorDate, LocalDate windowStart, LocalDate windowEnd,
                                  Integer coveredDays, String sampleStatus, String sourceFingerprint,
                                  String model, String ruleVersion, String activeRequest,
                                  String payload, LocalDateTime generatedAt) { }

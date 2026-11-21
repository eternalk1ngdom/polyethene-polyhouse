package com.polyethene.polyhouse.model.entity;

import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import java.time.Instant;
import java.util.List;

/**
 * 采集与后续分析之间的有界内部材料，README 绝不直接进入公开接口。
 * @param capturedAt 开始采集的真实时间，不使用调用方指定的历史日期
 * @param projects 按来源顺序的最多二十五项
 */
public record TrendCollection(Instant capturedAt, List<Project> projects) {
    /** 将客户端结果冻结，后续发布不能修改来源顺序。 */
    public TrendCollection {
        projects = List.copyOf(projects);
    }

    /**
     * 仅在当前流水线使用的正文材料，不包含任何凭据。
     * @param snapshot 脱敏基础资料
     * @param readmeVersion GitHub 内容 SHA，未知为 null
     * @param readmeText 本次任务按用途、机制和边界选出的原文，总计最多五千字符，失败为空
     * @param sourceIssue 本项目首个采集问题，随内存材料传给解读服务；历史调用可为空
     */
    public record Project(TrendProjectVO snapshot, String readmeVersion, String readmeText, TrendSourceIssue sourceIssue) {
        /** 兼容只提供正文的既有调用，不为这些材料推断故障。 */
        public Project(TrendProjectVO snapshot, String readmeVersion, String readmeText) {
            this(snapshot, readmeVersion, readmeText, null);
        }
    }
}

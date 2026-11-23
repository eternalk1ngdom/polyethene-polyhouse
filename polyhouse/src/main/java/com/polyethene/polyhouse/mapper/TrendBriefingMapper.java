package com.polyethene.polyhouse.mapper;

import com.polyethene.polyhouse.model.entity.TrendBriefingRecord;
import com.polyethene.polyhouse.model.entity.TrendBriefingRequest;
import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 7 日风口简报槽位与计费事实映射；严格遵循 简报槽(anchor_date) -> 月预算(month_key) -> 请求(id) 锁序。
 */
@Mapper
public interface TrendBriefingMapper {
    /** 只读查询锚点日当前有效简报与活动槽位，不创建行也不加锁。 */
    @Select("""
        SELECT anchor_date,window_start,window_end,covered_days,sample_status,source_fingerprint,
        model,rule_version,active_request,payload,generated_at
        FROM tool_trend_briefing WHERE anchor_date=#{anchorDate}
        """)
    @Options(timeout = 3)
    TrendBriefingRecord briefing(@Param("anchorDate") LocalDate anchorDate);

    /** 幂等初始化锚点日槽位行，便于随后行锁串行并发触发。 */
    @Insert("INSERT INTO tool_trend_briefing(anchor_date) VALUES(#{anchorDate}) ON DUPLICATE KEY UPDATE anchor_date=anchor_date")
    int ensureBriefing(@Param("anchorDate") LocalDate anchorDate);

    /** 在判断并发执行、同指纹复用或写入有效稿前获取当前读行锁。 */
    @Select("""
        SELECT anchor_date,window_start,window_end,covered_days,sample_status,source_fingerprint,
        model,rule_version,active_request,payload,generated_at
        FROM tool_trend_briefing WHERE anchor_date=#{anchorDate} FOR UPDATE
        """)
    TrendBriefingRecord lockBriefing(@Param("anchorDate") LocalDate anchorDate);

    /** 仅在槽位空闲时占用，失败时整笔预留事务回滚。 */
    @Update("UPDATE tool_trend_briefing SET active_request=#{request} WHERE anchor_date=#{anchorDate} AND active_request IS NULL")
    int claim(@Param("anchorDate") LocalDate anchorDate, @Param("request") String request);

    /** 校验失败、发送前取消或人工保守关闭时释放执行槽，完整保留原有有效旧稿。 */
    @Update("UPDATE tool_trend_briefing SET active_request=NULL WHERE anchor_date=#{anchorDate} AND active_request=#{request}")
    int releaseWithoutPayload(@Param("anchorDate") LocalDate anchorDate, @Param("request") String request);

    /** 校验通过且结算同事务写入最新有效简报并释放执行槽。 */
    @Update("""
        UPDATE tool_trend_briefing
        SET active_request=NULL,window_start=#{windowStart},window_end=#{windowEnd},covered_days=#{coveredDays},
        sample_status=#{sampleStatus},source_fingerprint=#{fingerprint},model=#{model},rule_version=#{ruleVersion},
        payload=#{payload},generated_at=#{generatedAt}
        WHERE anchor_date=#{anchorDate} AND active_request=#{request}
        """)
    int releaseWithPayload(@Param("anchorDate") LocalDate anchorDate, @Param("request") String request,
                           @Param("windowStart") LocalDate windowStart, @Param("windowEnd") LocalDate windowEnd,
                           @Param("coveredDays") int coveredDays, @Param("sampleStatus") String sampleStatus,
                           @Param("fingerprint") String fingerprint, @Param("model") String model,
                           @Param("ruleVersion") String ruleVersion, @Param("payload") String payload,
                           @Param("generatedAt") LocalDateTime generatedAt);

    /** 按请求 UUID 查询简报计费事实，供重放识别与费用结算路由使用。 */
    @Select("""
        SELECT id,month_key,anchor_date,source_fingerprint,model,rule_version,state,
        reserved_micros,charged_micros,input_bound,output_bound,input_rate,output_rate
        FROM tool_trend_briefing_request WHERE id=#{id}
        """)
    @Options(timeout = 3)
    TrendBriefingRequest request(@Param("id") String id);

    /** 结算与状态变更前获取当前读行锁，防止同一请求重复扣减。 */
    @Select("""
        SELECT id,month_key,anchor_date,source_fingerprint,model,rule_version,state,
        reserved_micros,charged_micros,input_bound,output_bound,input_rate,output_rate
        FROM tool_trend_briefing_request WHERE id=#{id} FOR UPDATE
        """)
    TrendBriefingRequest lockRequest(@Param("id") String id);

    /** 读取不可变预留时间（UTC），用于人工保守关闭的 5 分钟安全等待窗口核验。 */
    @Select("SELECT created_at FROM tool_trend_briefing_request WHERE id=#{id}")
    @Options(timeout = 3)
    LocalDateTime createdAt(@Param("id") String id);

    /** 写入初始 RESERVED 预留记录，不保存 Prompt 或上游原始错误。 */
    @Insert("""
        INSERT INTO tool_trend_briefing_request(id,month_key,anchor_date,source_fingerprint,model,rule_version,state,
        reserved_micros,input_bound,output_bound,input_rate,output_rate,created_at)
        VALUES(#{r.id},#{r.monthKey},#{r.anchorDate},#{r.sourceFingerprint},#{r.model},#{r.ruleVersion},'RESERVED',
        #{r.reservedMicros},#{r.inputBound},#{r.outputBound},#{r.inputRate},#{r.outputRate},#{createdAt})
        """)
    int insert(@Param("r") TrendBriefingRequest request, @Param("createdAt") LocalDateTime createdAt);

    /** 已知用量或发送前取消时终结请求，费用变更已在同事务完成。 */
    @Update("""
        UPDATE tool_trend_briefing_request SET state=#{state},charged_micros=#{charged},prompt_tokens=#{input},
        completion_tokens=#{output},outcome=#{outcome} WHERE id=#{id} AND state IN ('RESERVED','UNKNOWN')
        """)
    int finish(@Param("id") String id, @Param("state") String state, @Param("charged") long charged,
               @Param("input") int input, @Param("output") int output, @Param("outcome") String outcome);

    /** 超时或用量违约时保留预留金额与执行槽，不自动退额或重发。 */
    @Update("UPDATE tool_trend_briefing_request SET state='UNKNOWN',outcome=#{reason} WHERE id=#{id} AND state='RESERVED'")
    int unknown(@Param("id") String id, @Param("reason") String reason);

    /** 屋主 5 分钟后保守关闭：全额按原预留计入预算，不伪造 token 用量。 */
    @Update("""
        UPDATE tool_trend_briefing_request SET state='SETTLED',charged_micros=reserved_micros,
        prompt_tokens=NULL,completion_tokens=NULL,outcome='MANUAL_RESERVED'
        WHERE id=#{id} AND state IN ('RESERVED','UNKNOWN') AND reserved_micros=#{expected}
        """)
    int closeReserved(@Param("id") String id, @Param("expected") long expected);
}

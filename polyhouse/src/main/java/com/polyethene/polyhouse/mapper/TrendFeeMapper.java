package com.polyethene.polyhouse.mapper;

import com.polyethene.polyhouse.model.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 费用只读投影与不可变审计（含单仓与 7 日风口简报），公网参数全部绑定，排序固定为UUID而非用户SQL。 */
@Mapper
public interface TrendFeeMapper {
    /** 同一语句读取共享月账本与两类保守结算金额，避免并发关闭后汇总金额比本次读到的已用还大。 */
    @Select("""
        SELECT m.month_key,m.used_micros,m.held_micros,m.suspended,
        ((SELECT COALESCE(SUM(r.accounted_micros),0) FROM tool_trend_ai_resolution r WHERE r.month_key=m.month_key)
         + (SELECT COALESCE(SUM(b.accounted_micros),0) FROM tool_trend_briefing_resolution b WHERE b.month_key=m.month_key)) AS conservative_micros
        FROM tool_trend_ai_month m WHERE m.month_key=#{month}
        """)
    @Options(timeout = 3)
    TrendFeeMonthRecord month(@Param("month") String month);

    /** 有界keyset页：合并单仓与简报待核对请求，不读取解读JSON、输入指纹或任何原始请求材料。 */
    @Select("""
        <script>
        SELECT p.id,p.repository_id,p.charge_kind,p.state,p.reserved_micros,p.created_at FROM (
            SELECT id,CAST(repository_id AS CHAR) AS repository_id,'REPO' AS charge_kind,state,reserved_micros,created_at
            FROM tool_trend_ai_request
            WHERE month_key=#{month} AND state IN ('RESERVED','UNKNOWN')
            <if test="after != null">AND id &gt; #{after}</if>
            UNION ALL
            SELECT id,CONCAT('briefing:',DATE_FORMAT(anchor_date,'%Y-%m-%d')) AS repository_id,'BRIEFING' AS charge_kind,state,reserved_micros,created_at
            FROM tool_trend_briefing_request
            WHERE month_key=#{month} AND state IN ('RESERVED','UNKNOWN')
            <if test="after != null">AND id &gt; #{after}</if>
        ) p
        ORDER BY p.id LIMIT #{limit}
        </script>
        """)
    @Options(timeout = 3)
    List<TrendPendingChargeRecord> pending(@Param("month") String month, @Param("after") String after, @Param("limit") int limit);

    /** 原单仓处理UUID重放或主动确认；不存在不意味着可以换号盲目重复。 */
    @Select("SELECT * FROM tool_trend_ai_resolution WHERE id=#{id}")
    @Options(timeout = 3)
    TrendAiResolutionRecord resolution(@Param("id") String id);

    /** 原简报处理UUID重放或主动确认。 */
    @Select("SELECT * FROM tool_trend_briefing_resolution WHERE id=#{id}")
    @Options(timeout = 3)
    TrendAiResolutionRecord briefingResolution(@Param("id") String id);

    /** 单槽锁之后当前读，避免并发重放看不到刚提交的单仓审计。 */
    @Select("SELECT * FROM tool_trend_ai_resolution WHERE id=#{id} FOR UPDATE")
    TrendAiResolutionRecord lockResolution(@Param("id") String id);

    /** 单槽锁之后当前读，避免并发重放看不到刚提交的简报审计。 */
    @Select("SELECT * FROM tool_trend_briefing_resolution WHERE id=#{id} FOR UPDATE")
    TrendAiResolutionRecord lockBriefingResolution(@Param("id") String id);

    /** 同一原单仓请求至多人工关闭一次；换处理UUID不能制造另一笔结算。 */
    @Select("SELECT * FROM tool_trend_ai_resolution WHERE request_id=#{id} FOR UPDATE")
    TrendAiResolutionRecord lockByRequest(@Param("id") String id);

    /** 同一原简报请求至多人工关闭一次；换处理UUID不能制造另一笔结算。 */
    @Select("SELECT * FROM tool_trend_briefing_resolution WHERE request_id=#{id} FOR UPDATE")
    TrendAiResolutionRecord lockBriefingByRequest(@Param("id") String id);

    /** 仅首次成功关闭单仓请求追加审计，和账本及仓库槽变更同事务；不提供更新/删除方法。 */
    @Insert("""
        INSERT INTO tool_trend_ai_resolution(id,request_id,actor_id,month_key,accounted_micros,reason,completed_at)
        VALUES(#{r.id},#{r.requestId},#{r.actorId},#{r.monthKey},#{r.accountedMicros},#{r.reason},#{r.completedAt})
        """)
    int insert(@Param("r") TrendAiResolutionRecord record);

    /** 仅首次成功关闭简报请求追加审计，和账本及简报槽变更同事务；不提供更新/删除方法。 */
    @Insert("""
        INSERT INTO tool_trend_briefing_resolution(id,request_id,actor_id,month_key,accounted_micros,reason,completed_at)
        VALUES(#{r.id},#{r.requestId},#{r.actorId},#{r.monthKey},#{r.accountedMicros},#{r.reason},#{r.completedAt})
        """)
    int insertBriefingResolution(@Param("r") TrendAiResolutionRecord record);
}

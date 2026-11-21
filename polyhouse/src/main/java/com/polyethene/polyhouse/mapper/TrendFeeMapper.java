package com.polyethene.polyhouse.mapper;

import com.polyethene.polyhouse.model.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 费用只读投影与不可变审计，公网参数全部绑定，排序固定为UUID而非用户SQL。 */
@Mapper
public interface TrendFeeMapper {
    /** 同一语句读取账本与保守部分，避免并发关闭后汇总金额比本次读到的已用还大。 */
    @Select("""
        SELECT m.month_key,m.used_micros,m.held_micros,m.suspended,
        (SELECT COALESCE(SUM(r.accounted_micros),0) FROM tool_trend_ai_resolution r WHERE r.month_key=m.month_key) AS conservative_micros
        FROM tool_trend_ai_month m WHERE m.month_key=#{month}
        """)
    @Options(timeout = 3)
    TrendFeeMonthRecord month(@Param("month") String month);

    /** 有界keyset页；不读取解读JSON、输入指纹或任何原始请求材料。 */
    @Select("""
        <script>
        SELECT id,repository_id,state,reserved_micros,created_at FROM tool_trend_ai_request
        WHERE month_key=#{month} AND state IN ('RESERVED','UNKNOWN')
        <if test="after != null">AND id &gt; #{after}</if>
        ORDER BY id LIMIT #{limit}
        </script>
        """)
    @Options(timeout = 3)
    List<TrendPendingChargeRecord> pending(@Param("month") String month, @Param("after") String after, @Param("limit") int limit);

    /** 原处理UUID重放或主动确认；不存在不意味着可以换号盲目重复。 */
    @Select("SELECT * FROM tool_trend_ai_resolution WHERE id=#{id}")
    @Options(timeout = 3)
    TrendAiResolutionRecord resolution(@Param("id") String id);

    /** 单槽锁之后当前读，避免并发重放看不到刚提交的审计。 */
    @Select("SELECT * FROM tool_trend_ai_resolution WHERE id=#{id} FOR UPDATE")
    TrendAiResolutionRecord lockResolution(@Param("id") String id);

    /** 同一原请求至多人工关闭一次；换处理UUID不能制造另一笔结算。 */
    @Select("SELECT * FROM tool_trend_ai_resolution WHERE request_id=#{id} FOR UPDATE")
    TrendAiResolutionRecord lockByRequest(@Param("id") String id);

    /** 仅首次成功关闭追加审计，和账本及仓库槽变更同事务；不提供更新/删除方法。 */
    @Insert("""
        INSERT INTO tool_trend_ai_resolution(id,request_id,actor_id,month_key,accounted_micros,reason,completed_at)
        VALUES(#{r.id},#{r.requestId},#{r.actorId},#{r.monthKey},#{r.accountedMicros},#{r.reason},#{r.completedAt})
        """)
    int insert(@Param("r") TrendAiResolutionRecord record);
}

package com.polyethene.polyhouse.mapper;

import com.polyethene.polyhouse.model.entity.*;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

/** 仓库槽、月预算和请求事实同库短事务；固定锁顺序为仓库、月份、请求。 */
@Mapper
public interface TrendAiMapper {
    /** 当前成功缓存是内部复用数据，不直接替换已经发布的日报。 */
    @Select("SELECT repository_id,active_request,payload FROM tool_trend_ai_repository WHERE repository_id=#{id}")
    @Options(timeout = 3)
    TrendAiRepository repository(@Param("id") long id);

    /** 新仓库行只由服务端稳定编号创建，重复执行仍取得同一行的锁。 */
    @Insert("INSERT INTO tool_trend_ai_repository(repository_id) VALUES(#{id}) ON DUPLICATE KEY UPDATE repository_id=repository_id")
    int ensureRepository(@Param("id") long id);

    /** 在判断并发执行和缓存变化前取得当前读。 */
    @Select("SELECT repository_id,active_request,payload FROM tool_trend_ai_repository WHERE repository_id=#{id} FOR UPDATE")
    TrendAiRepository lockRepository(@Param("id") long id);

    /** 月份由服务端时钟生成，不接受公网指定结算月份。 */
    @Insert("INSERT INTO tool_trend_ai_month(month_key) VALUES(#{month}) ON DUPLICATE KEY UPDATE month_key=month_key")
    int ensureMonth(@Param("month") String month);

    /** 同月所有预留串行，避免多个实例各自通过余额检查。 */
    @Select("SELECT month_key,used_micros,held_micros,suspended FROM tool_trend_ai_month WHERE month_key=#{month} FOR UPDATE")
    TrendAiMonth lockMonth(@Param("month") String month);

    /** 复核预算时把已用和持有同时计入，应用上限只能比数据库十元边界更小。 */
    @Update("UPDATE tool_trend_ai_month SET held_micros=held_micros+#{amount} WHERE month_key=#{month} AND suspended=FALSE AND used_micros+held_micros+#{amount} <= #{limit}")
    int hold(@Param("month") String month, @Param("amount") long amount, @Param("limit") long limit);

    /** 成功结算只释放本次持有额，不能挪用另一次预留或超过已有上界。 */
    @Update("UPDATE tool_trend_ai_month SET used_micros=used_micros+#{charged},held_micros=held_micros-#{held} WHERE month_key=#{month} AND held_micros >= #{held}")
    int settle(@Param("month") String month, @Param("held") long held, @Param("charged") long charged);

    /** 上游用量违约时不继续依赖错误估算发出新付费请求。 */
    @Update("UPDATE tool_trend_ai_month SET suspended=TRUE WHERE month_key=#{month}")
    int suspend(@Param("month") String month);

    /** UUID 重放读取持久事实，不把未完成请求当成可重发请求。 */
    @Select("SELECT * FROM tool_trend_ai_request WHERE id=#{id}")
    TrendAiRequest request(@Param("id") String id);

    /** 结算前使用当前读，防止重复调用把费用结算两次。 */
    @Select("SELECT * FROM tool_trend_ai_request WHERE id=#{id} FOR UPDATE")
    TrendAiRequest lockRequest(@Param("id") String id);

    /** 预留时间不可变，人工处理须等待安全窗口，不依据客户端时间。 */
    @Select("SELECT created_at FROM tool_trend_ai_request WHERE id=#{id}")
    @Options(timeout = 3)
    LocalDateTime createdAt(@Param("id") String id);

    /** 人工关闭只记保守金额，不能捏造token用量或退回已不确定的费用。 */
    @Update("""
        UPDATE tool_trend_ai_request SET state='SETTLED',charged_micros=reserved_micros,
        prompt_tokens=NULL,completion_tokens=NULL,outcome='MANUAL_RESERVED'
        WHERE id=#{id} AND state IN ('RESERVED','UNKNOWN') AND reserved_micros=#{expected}
        """)
    int closeReserved(@Param("id") String id, @Param("expected") long expected);

    /** 参数绑定资料指纹及价格，不保存 Prompt、正文或任何令牌。 */
    @Insert("""
        INSERT INTO tool_trend_ai_request(id,month_key,repository_id,readme_sha,model,rule_version,state,
        reserved_micros,input_bound,output_bound,input_rate,output_rate,created_at)
        VALUES(#{r.id},#{r.monthKey},#{r.repositoryId},#{r.readmeSha},#{r.model},#{r.ruleVersion},'RESERVED',
        #{r.reservedMicros},#{r.inputBound},#{r.outputBound},#{r.inputRate},#{r.outputRate},#{createdAt})
        """)
    int insert(@Param("r") TrendAiRequest request, @Param("createdAt") LocalDateTime createdAt);

    /** 槽只在空闲时分配，失败时整笔预留事务回滚。 */
    @Update("UPDATE tool_trend_ai_repository SET active_request=#{request} WHERE repository_id=#{repo} AND active_request IS NULL")
    int claim(@Param("repo") long repo, @Param("request") String request);

    /** 只有当前请求可释放仓库槽；无新解读时保留原成功缓存。 */
    @Update("UPDATE tool_trend_ai_repository SET active_request=NULL,payload=COALESCE(#{payload,jdbcType=VARCHAR},payload) WHERE repository_id=#{repo} AND active_request=#{request}")
    int release(@Param("repo") long repo, @Param("request") String request, @Param("payload") String payload);

    /** 只记录固定状态/原因及有限用量，费用已经在同事务结算。 */
    @Update("""
        UPDATE tool_trend_ai_request SET state=#{state},charged_micros=#{charged},prompt_tokens=#{input},
        completion_tokens=#{output},outcome=#{outcome} WHERE id=#{id} AND state IN ('RESERVED','UNKNOWN')
        """)
    int finish(@Param("id") String id, @Param("state") String state, @Param("charged") long charged,
               @Param("input") int input, @Param("output") int output, @Param("outcome") String outcome);

    /** 记录 017 增量字段（缓存命中拆分与参考平台实扣估算），仅用于屋主双口径对账。 */
    @Update("""
        UPDATE tool_trend_ai_request SET cache_hit_tokens=#{cacheHit},cache_miss_tokens=#{cacheMiss},
        estimated_actual_micros=#{estimatedActualMicros} WHERE id=#{id} AND state='SETTLED'
        """)
    int recordCacheUsage(@Param("id") String id, @Param("cacheHit") int cacheHit,
                         @Param("cacheMiss") int cacheMiss, @Param("estimatedActualMicros") long estimatedActualMicros);

    /** 未知结果保留费用和仓库槽；不因超时或重启自动退回预算。 */
    @Update("UPDATE tool_trend_ai_request SET state='UNKNOWN',outcome=#{reason} WHERE id=#{id} AND state='RESERVED'")
    int unknown(@Param("id") String id, @Param("reason") String reason);
}

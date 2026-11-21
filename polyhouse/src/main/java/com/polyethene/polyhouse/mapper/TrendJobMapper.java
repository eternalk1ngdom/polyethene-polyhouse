package com.polyethene.polyhouse.mapper;

import com.polyethene.polyhouse.model.entity.TrendJobRecord;
import com.polyethene.polyhouse.model.entity.TrendJobSlot;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

/** 固定单槽是所有实例的执行权来源；网络调用期间不持有数据库锁。 */
@Mapper
public interface TrendJobMapper {
    /** 使用数据库UTC时间，让多实例租约和冷却不依赖各自主机时钟。 */
    @Select("SELECT UTC_TIMESTAMP(6)")
    @Options(timeout = 3)
    LocalDateTime now();

    /** 不自动修补缺失单槽，迁移缺失时应拒绝任务而非各实例自行放行。 */
    @Select("SELECT active_id,generation,last_accepted_at FROM tool_trend_job_slot WHERE singleton_id=1 FOR UPDATE")
    TrendJobSlot lockSlot();

    /** 槽锁之后使用当前读，避免MySQL可重复读快照看不到并发完成的事实。 */
    @Select("SELECT * FROM tool_trend_job WHERE id=#{id} FOR UPDATE")
    TrendJobRecord lockJob(@Param("id") String id);

    /** 只清理终结且超过九十天的任务，不清除报告和费用事实。 */
    @Delete("""
        DELETE FROM tool_trend_job WHERE state IN ('SUCCEEDED','PARTIAL','FAILED','INTERRUPTED')
        AND completed_at < #{before} AND id NOT IN
        (SELECT active_id FROM tool_trend_job_slot WHERE active_id IS NOT NULL)
        """)
    int clean(@Param("before") LocalDateTime before);

    /** 数据库状态有固定容量，不能以反复换UUID制造无界队列和明细。 */
    @Select("SELECT COUNT(*) FROM tool_trend_job")
    long count();

    /** G2尚无任务外键，有任何待核对费用时保守保留所有任务上下文。 */
    @Select("SELECT EXISTS(SELECT 1 FROM tool_trend_ai_request WHERE state IN ('RESERVED','UNKNOWN') LIMIT 1)")
    boolean hasPendingFees();

    /** 在同一事务中保存事实并认领槽，失败整体回滚，不留下幽灵受理。 */
    @Insert("""
        INSERT INTO tool_trend_job(id,actor_id,credential_version,ai_requested,generation,state,phase,created_at,deadline)
        VALUES(#{r.id},#{r.actorId},#{r.credentialVersion},#{r.aiRequested},#{r.generation},'QUEUED','QUEUED',#{r.createdAt},#{r.deadline})
        """)
    int insert(@Param("r") TrendJobRecord job);

    /** 代数只递增，释放槽不重置，旧线程永远不能重新取得同一代执行权。 */
    @Update("UPDATE tool_trend_job_slot SET active_id=#{id},generation=#{generation},last_accepted_at=#{now} WHERE singleton_id=1 AND active_id IS NULL")
    int occupy(@Param("id") String id, @Param("generation") long generation, @Param("now") LocalDateTime now);

    /** 固定所有者比较，旧线程的异常清理不能释放新任务。 */
    @Update("UPDATE tool_trend_job_slot SET active_id=NULL WHERE singleton_id=1 AND active_id=#{id} AND generation=#{generation}")
    int release(@Param("id") String id, @Param("generation") long generation);

    /** 待执行事实可在重启后领取一次；运行中断不退回QUEUED。 */
    @Update("UPDATE tool_trend_job SET state='RUNNING',phase='COLLECTING',started_at=#{now},lease_until=#{lease} WHERE id=#{id} AND state='QUEUED'")
    int start(@Param("id") String id, @Param("now") LocalDateTime now, @Param("lease") LocalDateTime lease);

    /** 心跳只由已经核对有效租约的持有者调用，过期不会自行复活。 */
    @Update("UPDATE tool_trend_job SET lease_until=#{lease} WHERE id=#{id} AND state='RUNNING' AND generation=#{generation}")
    int heartbeat(@Param("id") String id, @Param("generation") long generation, @Param("lease") LocalDateTime lease);

    /** 固定阶段由服务端选择，不接受公网任意状态。 */
    @Update("UPDATE tool_trend_job SET phase=#{phase} WHERE id=#{id} AND state='RUNNING' AND generation=#{generation}")
    int phase(@Param("id") String id, @Param("generation") long generation, @Param("phase") String phase);

    /** 发布调用与终结使用同一短事务；失败时保留原阶段，便于判断是否触及外部AI。 */
    @Update("""
        UPDATE tool_trend_job SET state=#{state},phase=CASE WHEN #{report,jdbcType=VARCHAR} IS NOT NULL THEN 'DONE' ELSE phase END,
        completed_at=#{now},lease_until=NULL,report_id=#{report,jdbcType=VARCHAR},reason=#{reason,jdbcType=VARCHAR}
        WHERE id=#{id} AND generation=#{generation} AND state IN ('QUEUED','RUNNING')
        """)
    int finish(@Param("id") String id, @Param("generation") long generation, @Param("state") String state,
               @Param("report") String report, @Param("reason") String reason, @Param("now") LocalDateTime now);
}

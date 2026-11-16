package com.polyethene.polyhouse.mapper;

import com.polyethene.polyhouse.model.entity.TrendScheduleRecord;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;

/** 以日期/尝试唯一键和任务单槽共同防重，不依靠某个进程的定时器内存状态。 */
@Mapper
public interface TrendScheduleMapper {
    /** 任务槽之后当前读，多个实例或重启只能看到同一受理事实。 */
    @Select("SELECT * FROM tool_trend_schedule WHERE period_date=#{day} AND attempt=#{attempt} FOR UPDATE")
    TrendScheduleRecord lockAttempt(@Param("day") LocalDate day, @Param("attempt") int attempt);

    /** 系统身份还必须匹配已保存的定时受理事实，不能仅凭NULL用户放行。 */
    @Select("SELECT * FROM tool_trend_schedule WHERE job_id=#{id}")
    @Options(timeout = 3)
    TrendScheduleRecord byJob(@Param("id") String id);

    /** 已有当期公开内容时不自动创建另一版本，屋主明确手动更新仍可进行。 */
    @Select("SELECT EXISTS(SELECT 1 FROM tool_trend_daily WHERE period_date=#{day} AND report_id IS NOT NULL)")
    boolean published(@Param("day") LocalDate day);

    /** 与任务受理/占槽同事务，冲突时整笔回滚，不留下无映射的系统任务。 */
    @Insert("INSERT INTO tool_trend_schedule(period_date,attempt,job_id,created_at) VALUES(#{periodDate},#{attempt},#{jobId},#{createdAt})")
    int insert(TrendScheduleRecord record);
}

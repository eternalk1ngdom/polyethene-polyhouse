package com.polyethene.polyhouse.mapper;

import com.polyethene.polyhouse.model.entity.TrendReportRecord;
import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.util.List;

/** 日报头指针与不可变版本分开，版本写入和发布在同一短事务内完成。 */
@Mapper
public interface TrendMapper {
    /** 同一期号共用数据库行锁，重复发布与同日更新均串行，不用进程内锁代替持久一致性。 */
    @Insert("INSERT INTO tool_trend_daily(period_date) VALUES(#{day}) ON DUPLICATE KEY UPDATE period_date=period_date")
    int ensureDay(@Param("day") LocalDate day);

    /** 返回当前版本便于拒绝较早采集覆盖较新采集；FOR UPDATE 使用当前读。 */
    @Select("""
        SELECT v.id, v.period_date, v.captured_at, v.payload
        FROM tool_trend_daily d LEFT JOIN tool_trend_report_version v ON v.id=d.report_id
        WHERE d.period_date=#{day} FOR UPDATE
        """)
    TrendReportRecord lockDay(@Param("day") LocalDate day);

    /** 发布请求号是唯一主键，重放不生成第二份报告。 */
    @Select("SELECT id,period_date,captured_at,payload FROM tool_trend_report_version WHERE id=#{id}")
    @Options(timeout = 3)
    TrendReportRecord findVersion(@Param("id") String id);

    /** payload 使用参数绑定；新版本不会修改旧报告 JSON。 */
    @Insert("INSERT INTO tool_trend_report_version(id,period_date,captured_at,payload) VALUES(#{id},#{periodDate},#{capturedAt},#{payload})")
    int insertVersion(TrendReportRecord report);

    /** 只有候选版本全部保存后才切换当前公开指针，提交之前对其他连接不可见。 */
    @Update("UPDATE tool_trend_daily SET report_id=#{id} WHERE period_date=#{day}")
    int publish(@Param("day") LocalDate day, @Param("id") String id);

    /** 最新按采集期号判断，不因重试旧日报而把它变成最新一期。 */
    @Select("""
        SELECT v.id,v.period_date,v.captured_at,v.payload FROM tool_trend_daily d
        JOIN tool_trend_report_version v ON v.id=d.report_id ORDER BY d.period_date DESC LIMIT 1
        """)
    @Options(timeout = 3)
    TrendReportRecord latest();

    /** 公开查询只通过当前已发布指针读取，不提供候选或任意内部版本查询入口。 */
    @Select("""
        SELECT v.id,v.period_date,v.captured_at,v.payload FROM tool_trend_daily d
        JOIN tool_trend_report_version v ON v.id=d.report_id WHERE d.period_date=#{day}
        """)
    @Options(timeout = 3)
    TrendReportRecord published(@Param("day") LocalDate day);

    /** 缓存启用时仍先读权威头，只取小列，不把整份JSON传出数据库。 */
    @Select("""
        SELECT v.id,v.period_date,v.captured_at,NULL AS payload FROM tool_trend_daily d
        JOIN tool_trend_report_version v ON v.id=d.report_id ORDER BY d.period_date DESC LIMIT 1
        """)
    @Options(timeout = 3)
    TrendReportRecord latestHeader();

    /** 历史查询同样按当前发布指针核对版本，不能用Redis中的旧版本冒充当期当前内容。 */
    @Select("""
        SELECT v.id,v.period_date,v.captured_at,NULL AS payload FROM tool_trend_daily d
        JOIN tool_trend_report_version v ON v.id=d.report_id WHERE d.period_date=#{day}
        """)
    @Options(timeout = 3)
    TrendReportRecord publishedHeader(@Param("day") LocalDate day);

    /** 使用日期索引和排他游标，limit 来自已验证上限，不允许用户指定排序片段。 */
    @Select("""
        <script>SELECT period_date FROM tool_trend_daily WHERE report_id IS NOT NULL
        <if test="before != null">AND period_date &lt; #{before}</if>
        ORDER BY period_date DESC LIMIT #{limit}</script>
        """)
    @Options(timeout = 3)
    List<LocalDate> periods(@Param("before") LocalDate before, @Param("limit") int limit);

    /** 按当前公开指针读取指定闭区间内的已发布日报，同日重发只保留最新版本，用于近 7 日确定性统计。 */
    @Select("""
        SELECT v.id, v.period_date, v.captured_at, v.payload FROM tool_trend_daily d
        JOIN tool_trend_report_version v ON v.id=d.report_id
        WHERE d.period_date BETWEEN #{start} AND #{end}
        ORDER BY d.period_date DESC
        """)
    @Options(timeout = 3)
    List<TrendReportRecord> publishedRange(@Param("start") LocalDate start, @Param("end") LocalDate end);
}

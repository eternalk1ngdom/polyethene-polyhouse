package com.polyethene.polyhouse.mapper;

import com.polyethene.polyhouse.model.entity.DailyLoginReward;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/** 奖励流水仅由服务端写入；用户、行为、自然日的主键共同限制同类每日奖励次数。 */
@Mapper
public interface UserGrowthMapper {

    /**
     * 在服务已锁住用户行后读取当日领取状态；使用当前读，避免外层可重复读事务看不到刚提交的奖励。
     * 返回 null 表示没有记录，不能将未查询成功的存储故障当作未领取。
     */
    @Select("SELECT 1 FROM sys_user_growth_reward WHERE user_id = #{userId} "
            + "AND action_code = #{actionCode} AND reward_date = #{rewardDate} FOR UPDATE")
    Integer findRewardForUpdate(@Param("userId") long userId, @Param("actionCode") String actionCode,
                               @Param("rewardDate") LocalDate rewardDate);

    /** 读取已入账的真实数值；规则调整后仍展示当天流水原值，缺记录返回 null，故障不能冒充未领取。 */
    @Select("SELECT experience_delta AS experienceDelta, coins_delta AS coinsDelta "
            + "FROM sys_user_growth_reward WHERE user_id = #{userId} "
            + "AND action_code = #{actionCode} AND reward_date = #{rewardDate} FOR UPDATE")
    DailyLoginReward findRewardDetailsForUpdate(@Param("userId") long userId, @Param("actionCode") String actionCode,
                                               @Param("rewardDate") LocalDate rewardDate);

    /** 流水与用户余额在同一 MySQL 事务提交，规则版本只记录来源，不参与防重主键。 */
    @Insert("INSERT INTO sys_user_growth_reward "
            + "(user_id, action_code, reward_date, experience_delta, coins_delta, rule_version) "
            + "VALUES (#{userId}, #{actionCode}, #{rewardDate}, #{experienceDelta}, #{coinsDelta}, #{ruleVersion})")
    int insertReward(@Param("userId") long userId, @Param("actionCode") String actionCode,
                     @Param("rewardDate") LocalDate rewardDate, @Param("experienceDelta") int experienceDelta,
                     @Param("coinsDelta") int coinsDelta, @Param("ruleVersion") String ruleVersion);
}

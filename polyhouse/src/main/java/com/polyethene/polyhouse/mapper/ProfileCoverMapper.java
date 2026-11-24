package com.polyethene.polyhouse.mapper;

import org.apache.ibatis.annotations.*;

/** 所有读写在本人行锁之后执行，避免成长入账与消费交错。 */
@Mapper
public interface ProfileCoverMapper {
    /** 购买记录也是扣款凭证。 */
    @Select("SELECT COUNT(*) FROM sys_profile_cover_purchase WHERE user_id=#{id} AND cover_key='astrolabe'")
    int hasAstrolabe(long id);

    /** 没有选择记录的老账号自动使用默认款。 */
    @Select("SELECT cover_key FROM sys_profile_cover_preference WHERE user_id=#{id}")
    String selected(long id);

    /** 带余额下界的更新防止透支。 */
    @Update("UPDATE sys_user SET coins=coins-#{price} WHERE id=#{id} AND coins>=#{price}")
    int debit(@Param("id") long id, @Param("price") int price);

    /** 唯一键保证每个账号同款只产生一笔真实支出。 */
    @Insert("INSERT INTO sys_profile_cover_purchase(user_id,cover_key,spent) VALUES(#{id},'astrolabe',#{price})")
    void recordPurchase(@Param("id") long id, @Param("price") int price);

    /** 每人只有一条偏好，重复佩戴安全。 */
    @Insert("INSERT INTO sys_profile_cover_preference(user_id,cover_key) VALUES(#{id},#{key}) ON DUPLICATE KEY UPDATE cover_key=#{key}")
    void equip(@Param("id") long id, @Param("key") String key);
}

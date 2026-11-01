package com.polyethene.polyhouse.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 保存最小安全审计，不写入密码、Token、网络地址或完整请求体。 */
@Mapper
public interface AccountAuditMapper {
    /** 与凭据更新同一事务记录本人改密成功事实，失败时不能遗留“已成功”的记录。 */
    @Insert("INSERT INTO sys_account_audit(actor_id, target_id, action, credential_version) "
            + "VALUES(#{userId}, #{userId}, 'PASSWORD_CHANGE', #{version})")
    int recordPasswordChange(@Param("userId") long userId, @Param("version") long version);

    /** 与新账号/发号/幂等记录同事务；保留创建者和目标，不记录临时密码或屋主再验证凭据。 */
    @Insert("INSERT INTO sys_account_audit(actor_id, target_id, action, credential_version) "
            + "VALUES(#{actorId}, #{targetId}, 'ACCOUNT_CREATE', 0)")
    int recordCreation(@Param("actorId") long actorId, @Param("targetId") long targetId);
}

package com.polyethene.polyhouse.mapper;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.entity.AccountCreation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 发号和创建事实使用同一数据源事务；与登录/资料 Mapper 分开，避免通用更新绕过安全字段策略。 */
@Mapper
public interface AccountProvisioningMapper {
    /** 当前读避免等待屋主锁前形成的旧快照漏掉已提交的相同请求。 */
    @Select("SELECT target_id, username, nickname, role FROM sys_account_creation WHERE actor_id=#{actorId} AND request_id=#{requestId} FOR UPDATE")
    AccountCreation find(@Param("actorId") long actorId, @Param("requestId") String requestId);

    /** 全部应用实例竞争同一持久行锁，不依赖进程内计数或可能丢失的 Redis。 */
    @Select("SELECT next_value FROM sys_account_sequence WHERE slot=1 FOR UPDATE")
    Long lockSequence();

    /** 成功事务中推进；回滚不会留下用户或成功事实，允许重用从未交付的号码。 */
    @Update("UPDATE sys_account_sequence SET next_value=#{next} WHERE slot=1")
    int advance(@Param("next") long next);

    /** 显式包含软删除历史，防止逻辑删除过滤器让旧账号名重新被发出。 */
    @Select("SELECT COUNT(*) FROM sys_user WHERE username=#{username}")
    int occupied(@Param("username") String username);

    /** 新增账号始终受限首次改密，安全字段不通过允许通用实体回写来开放。 */
    @Insert("INSERT INTO sys_user(id, username, nickname, password_hash, role, status, deleted, credential_version, password_change_required) "
            + "VALUES(#{id}, #{username}, #{nickname}, #{hash}, #{role}, 1, 0, 0, 1)")
    int create(@Param("id") long id, @Param("username") String username, @Param("nickname") String nickname,
               @Param("hash") String hash, @Param("role") UserRoleEnum role);

    /** 操作者/请求号主键为数据库兜底；没有任何保存明文的列或参数。 */
    @Insert("INSERT INTO sys_account_creation(actor_id, request_id, target_id, username, nickname, role) "
            + "VALUES(#{actorId}, #{requestId}, #{id}, #{username}, #{nickname}, #{role})")
    int record(@Param("actorId") long actorId, @Param("requestId") String requestId, @Param("id") long id,
               @Param("username") String username, @Param("nickname") String nickname, @Param("role") UserRoleEnum role);
}

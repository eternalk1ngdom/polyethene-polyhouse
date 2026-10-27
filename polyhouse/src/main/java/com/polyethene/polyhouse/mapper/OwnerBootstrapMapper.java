package com.polyethene.polyhouse.mapper;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.entity.OwnerBootstrapRecord;
import com.polyethene.polyhouse.model.entity.OwnerBootstrapTarget;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 只为本机运维命令提供绑定参数的最小读写，不暴露 HTTP 入口。 */
@Mapper
public interface OwnerBootstrapMapper {
    /** 必须是预期的单列唯一索引；同名的普通/组合索引不能冒充唯一性保障。 */
    @Select("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
            + "AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'uk_sys_user_owner_slot' "
            + "AND NON_UNIQUE = 0 AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'owner_slot' "
            + "AND SUB_PART IS NULL AND IS_VISIBLE = 'YES' "
            + "AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
            + "AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'uk_sys_user_owner_slot') = 1")
    int uniqueGuardCount();

    /** 读取生成表达式以拒绝同名普通列或错误规则，不能只检查索引名称。 */
    @Select("SELECT GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
            + "AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'owner_slot' "
            + "AND DATA_TYPE = 'tinyint' AND EXTRA = 'VIRTUAL GENERATED'")
    String guardExpression();

    /** 只读预览不持有行锁，也不会写入初始化记录。 */
    @Select("SELECT slot, user_id FROM sys_owner_bootstrap WHERE slot = 1")
    OwnerBootstrapRecord record();

    /** 所有实际初始化先取得同一个锁，再锁用户；不能以 JVM 锁代替跨进程数据库锁。 */
    @Select("SELECT slot, user_id FROM sys_owner_bootstrap WHERE slot = 1 FOR UPDATE")
    OwnerBootstrapRecord lockRecord();

    /** 预览也按编号读取，不受用户名不区分大小写的数据库排序规则影响。 */
    @Select("SELECT id, username, role, status, deleted FROM sys_user WHERE id = #{id}")
    OwnerBootstrapTarget target(@Param("id") long id);

    /** 行锁与登录、注销及奖励共用，保证校验和角色更新之间账号状态不变化。 */
    @Select("SELECT id, username, role, status, deleted FROM sys_user WHERE id = #{id} FOR UPDATE")
    OwnerBootstrapTarget lockTarget(@Param("id") long id);

    /** 通过唯一槽位索引覆盖禁用/软删除屋主，不能把名额悄悄释放。 */
    @Select("SELECT id FROM sys_user WHERE owner_slot = 1")
    Long ownerId();

    /** 普通快照可能在等待初始化锁前形成；实际执行使用当前读核对最新屋主。 */
    @Select("SELECT id FROM sys_user WHERE owner_slot = 1 FOR UPDATE")
    Long lockOwnerId();

    /** 只修改角色，既有资料、密码及成长余额均不覆盖；唯一索引兜底其他写入路径。 */
    @Update("UPDATE sys_user SET role = #{ownerRole} WHERE id = #{id} AND role = #{previousRole} "
            + "AND status = 1 AND deleted = 0")
    int promote(@Param("id") long id, @Param("previousRole") UserRoleEnum previousRole,
                @Param("ownerRole") UserRoleEnum ownerRole);

    /** 授权记录与角色在同一事务提交，重复调用不能重写首次操作者及时间。 */
    @Update("UPDATE sys_owner_bootstrap SET user_id = #{id}, username = #{username}, "
            + "previous_role = #{previousRole}, operator_name = #{operator}, initialized_at = CURRENT_TIMESTAMP(6) "
            + "WHERE slot = 1 AND user_id IS NULL")
    int recordInitialization(@Param("id") long id, @Param("username") String username,
                             @Param("previousRole") UserRoleEnum previousRole, @Param("operator") String operator);
}

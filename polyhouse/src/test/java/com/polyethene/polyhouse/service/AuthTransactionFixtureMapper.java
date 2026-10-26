package com.polyethene.polyhouse.service;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 仅供真实事务测试观察数据库原始状态和清理已提交的随机测试记录，不进入生产构建。
 * 清理同时匹配测试分配的 ID 与随机账号，所有输入都绑定参数，禁止按前缀批量删除。
 */
@Mapper
public interface AuthTransactionFixtureMapper {

    /** 绕过实体的逻辑删除过滤，证明注销后记录仍存在且标记已经提交。 */
    @Select("SELECT deleted FROM sys_user WHERE id = #{id} AND username = #{username}")
    Integer deleted(@Param("id") long id, @Param("username") String username);

    /** 取得当前事务连接编号，后续只观察本测试线程是否实际进入数据库锁等待。 */
    @Select("SELECT CONNECTION_ID()")
    long connectionId();

    /** 使用实时锁关系匹配测试连接，避免缓存的引擎事务概要或单纯时间延迟掩盖实际竞争。 */
    @Select("SELECT COUNT(*) FROM performance_schema.data_lock_waits waits "
            + "JOIN performance_schema.threads threads ON threads.THREAD_ID = waits.REQUESTING_THREAD_ID "
            + "WHERE threads.PROCESSLIST_ID = #{connectionId}")
    int lockWaitCount(@Param("connectionId") long connectionId);

    /** 只物理清理本测试创建并登记的精确记录，防止提交事务测试留下软删除账号。 */
    @Delete("DELETE FROM sys_user WHERE id = #{id} AND username = #{username}")
    int removeOwnedFixture(@Param("id") long id, @Param("username") String username);
}

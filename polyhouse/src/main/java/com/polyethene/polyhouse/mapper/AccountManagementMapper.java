package com.polyethene.polyhouse.mapper;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.dto.AccountListDTO;
import com.polyethene.polyhouse.model.entity.ManagedAccount;
import com.polyethene.polyhouse.model.entity.ManagementOperation;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 有界最小投影和定向管理更新；用户锁在服务中按编号排序取得，SQL 不回写整行资料。 */
@Mapper
public interface AccountManagementMapper {
    /** 计数和列表共用权限/筛选条件，管理员既不能读隐藏行，也不能从总数推断隐藏账号。 */
    String SCOPE = "<where>deleted=0 <choose><when test='owner'> AND (role!='OWNER' OR id=#{actorId})</when>"
            + "<otherwise> AND role='USER'</otherwise></choose>"
            + "<if test='query.role != null'> AND role=#{query.role}</if>"
            + "<if test='query.status != null'> AND status=#{query.status}</if>"
            + "<if test='query.username != null and query.username != &quot;&quot;'>"
            + " AND username LIKE CONCAT(REPLACE(#{query.username}, '_', '!_'), '%') ESCAPE '!'</if></where>";

    /** 固定按编号倒序分页；转义下划线，用户名筛选不会把字面字符解释成通配符。 */
    @Select("<script>SELECT id, username, nickname, role, status, management_version, password_change_required, "
            + "create_time AS created_at FROM sys_user " + SCOPE + " ORDER BY id DESC LIMIT #{query.pageSize} OFFSET #{offset}</script>")
    List<ManagedAccount> list(@Param("query") AccountListDTO query, @Param("owner") boolean owner,
                              @Param("actorId") long actorId, @Param("offset") int offset);

    /** 与当前页同一事务读取，最多返回计数而非加载全量实体。 */
    @Select("<script>SELECT COUNT(*) FROM sys_user " + SCOPE + "</script>")
    long count(@Param("query") AccountListDTO query, @Param("owner") boolean owner, @Param("actorId") long actorId);

    /** 使用当前读，不能从事务旧快照取到管理版本；调用前已经持有相同用户行锁。 */
    @Select("SELECT management_version FROM sys_user WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Long version(@Param("id") long id);

    /** 所有动作推进凭据版本；解封也只恢复登录资格，永不恢复旧 Token。 */
    @Update("<script>UPDATE sys_user SET status=#{status}, management_version=#{nextVersion}, "
            + "credential_version=#{credentialVersion}"
            + "<if test='reset'>,password_hash=#{hash},password_change_required=1</if>"
            + " WHERE id=#{id} AND deleted=0 AND management_version=#{expectedVersion} AND role=#{role}</script>")
    int apply(@Param("id") long id, @Param("role") UserRoleEnum role, @Param("expectedVersion") long expectedVersion,
              @Param("nextVersion") long nextVersion, @Param("credentialVersion") long credentialVersion,
              @Param("status") int status, @Param("reset") boolean reset, @Param("hash") String hash);

    /** 同一操作者的请求记录在用户锁后读取，防止等待前建立的快照漏掉并发成功事实。 */
    @Select("SELECT actor_id,request_id,target_id,username,action,actor_role,target_role,before_status,after_status,"
            + "before_version,after_version,reason FROM sys_account_management_operation "
            + "WHERE actor_id=#{actorId} AND request_id=#{requestId} FOR UPDATE")
    ManagementOperation find(@Param("actorId") long actorId, @Param("requestId") String requestId);

    /** 更新、审计与幂等事实在同一事务，失败不得留下已成功的审计或改变后的密码。 */
    @Insert("INSERT INTO sys_account_management_operation(actor_id,request_id,target_id,username,action,actor_role,target_role,"
            + "before_status,after_status,before_version,after_version,reason) VALUES(#{o.actorId},#{o.requestId},#{o.targetId},"
            + "#{o.username},#{o.action},#{o.actorRole},#{o.targetRole},#{o.beforeStatus},#{o.afterStatus},#{o.beforeVersion},"
            + "#{o.afterVersion},#{o.reason})")
    int record(@Param("o") ManagementOperation operation);
}

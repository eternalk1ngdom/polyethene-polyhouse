package com.polyethene.polyhouse.mapper;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.entity.*;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

/** 审核状态、最小投影、定向用户字段更新；调用方必须先按编号锁用户，再锁申请。 */
@Mapper public interface ProfileModerationMapper {
    /** 内部固定投影不含密码/IP/扩展 JSON。 */
    String SELECT = "SELECT s.id,s.user_id,u.username,s.kind,s.status,s.version,s.request_id,s.proposed_value,"
        + "s.content_hash,s.private_key,s.reason,s.created_at,s.expires_at,s.reviewed_at,s.reviewer_id,s.credential_version "
        + "FROM sys_profile_submission s JOIN sys_user u ON u.id=s.user_id ";
    /** 队列与计数共用有效性条件，封禁再解封也无法恢复旧申请。 */
    String QUEUE = "<where> s.status='PENDING' AND s.expires_at &gt; #{now} AND u.deleted=0 AND u.status=1 "
        + "AND u.credential_version=s.credential_version <if test='q.kind != null'> AND s.kind=#{q.kind}</if></where>";
    /** 只懒创建目标字段状态，不在迁移中批量覆盖用户资料。 */
    @Insert("INSERT IGNORE INTO sys_profile_field(user_id,kind,version,current_id,verified) VALUES(#{uid},#{kind},0,null,#{verified})")
    int initialize(@Param("uid") long uid,@Param("kind") ProfileField kind,@Param("verified") boolean verified);
    /** 字段锁总在用户锁之后取得，避免与审批交叉形成反向锁。 */
    @Select("SELECT user_id,kind,version,current_id,verified FROM sys_profile_field WHERE user_id=#{uid} AND kind=#{kind} FOR UPDATE")
    ProfileFieldState state(@Param("uid") long uid,@Param("kind") ProfileField kind);
    /** 版本只随新申请/下架推进，当前发布引用独立保留。 */
    @Update("UPDATE sys_profile_field SET version=#{version} WHERE user_id=#{uid} AND kind=#{kind}")
    int advance(@Param("uid") long uid,@Param("kind") ProfileField kind,@Param("version") long version);
    /** 通过或下架只更新该字段的发布引用，历史申请事实不重写。 */
    @Update("UPDATE sys_profile_field SET current_id=#{id},verified=1 WHERE user_id=#{uid} AND kind=#{kind}")
    int publish(@Param("uid") long uid,@Param("kind") ProfileField kind,@Param("id") Long id);
    /** 非锁定定位只决定随后要锁的用户；关键内容要在用户锁之后再当前读。 */
    @Select(SELECT+"WHERE s.id=#{id}") ProfileSubmission find(@Param("id") long id);
    /** 当前读防止等待用户锁前的旧快照覆盖最新申请状态。 */
    @Select(SELECT+"WHERE s.id=#{id} FOR UPDATE") ProfileSubmission locked(@Param("id") long id);
    /** 同请求返回既有事实，重复响应不多计次、不重复写图片。 */
    @Select(SELECT+"WHERE s.user_id=#{uid} AND s.request_id=#{request} FOR UPDATE")
    ProfileSubmission byRequest(@Param("uid") long uid,@Param("request") String request);
    /** 每种字段仅一份有效待审，前置用户锁使替代与新增串行。 */
    @Select(SELECT+"WHERE s.user_id=#{uid} AND s.kind=#{kind} AND s.status='PENDING' FOR UPDATE")
    List<ProfileSubmission> pending(@Param("uid") long uid,@Param("kind") ProfileField kind);
    /** 成功入库即占额度，驳回/替代不退款，失败事务不留记录。 */
    @Select("SELECT COUNT(*) FROM sys_profile_submission WHERE user_id=#{uid} AND kind=#{kind} AND created_at >= #{start} AND created_at < #{end}")
    long used(@Param("uid") long uid,@Param("kind") ProfileField kind,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end);
    /** 申请与审计同事务，指纹只用于同意图校验，不可替代鉴权。 */
    @Insert("INSERT INTO sys_profile_submission(id,user_id,kind,status,version,request_id,proposed_value,content_hash,private_key,created_at,expires_at,credential_version) "
        + "VALUES(#{s.id},#{s.userId},#{s.kind},#{s.status},#{s.version},#{s.requestId},#{s.proposedValue},#{s.contentHash},#{s.privateKey},#{s.createdAt},#{s.expiresAt},#{s.credentialVersion})")
    int insert(@Param("s") ProfileSubmission submission);
    /** 终态不能二次发布；移除文件引用后才允许提交完成回调清理私有图。 */
    @Update("UPDATE sys_profile_submission SET status=#{status},private_key=null,reason=#{reason},reviewed_at=#{now},reviewer_id=#{actor} WHERE id=#{id} AND status='PENDING'")
    int terminal(@Param("id") long id,@Param("status") ProfileReviewStatus status,@Param("reason") String reason,
        @Param("now") LocalDateTime now,@Param("actor") Long actor);
    /** 本人只读取最多十条，接口不允许指定他人编号。 */
    @Select(SELECT+"WHERE s.user_id=#{uid} ORDER BY s.id DESC LIMIT 10") List<ProfileSubmission> recent(@Param("uid") long uid);
    /** 只加载当前页，不一口气加载全部图片。 */
    @Select("<script>"+SELECT+QUEUE+" ORDER BY s.id LIMIT #{q.pageSize} OFFSET #{offset}</script>")
    List<ProfileSubmission> queue(@Param("q") ProfileReviewQueryDTO q,@Param("now") LocalDateTime now,@Param("offset") int offset);
    /** 与页面完全相同筛选，不能误报已失效申请仍待审。 */
    @Select("<script>SELECT COUNT(*) FROM sys_profile_submission s JOIN sys_user u ON u.id=s.user_id "+QUEUE+"</script>")
    long queueCount(@Param("q") ProfileReviewQueryDTO q,@Param("now") LocalDateTime now);
    /** CASE 字段由枚举绑定决定，永不拼接客户端 SQL。 */
    @Update("<script>UPDATE sys_user SET <choose><when test='kind.name() == &quot;AVATAR&quot;'>avatar=#{value}</when><otherwise>nickname=#{value}</otherwise></choose> WHERE id=#{uid} AND deleted=0</script>")
    int updatePublic(@Param("uid") long uid,@Param("kind") ProfileField kind,@Param("value") String value);
    /** 不删除仍被其他用户引用的已公开文件，包括尚未清理的软删除账号。 */
    @Select("SELECT COUNT(*) FROM sys_user WHERE avatar=#{avatar}") long publicReferences(@Param("avatar") String avatar);
    /** 成功审计与实际动作提交/回滚一致；原因不包含全文请求或图片路径。 */
    @Insert("INSERT INTO sys_profile_audit(id,actor_id,actor_role,target_id,kind,submission_id,action,version,reason,created_at) "
        + "VALUES(#{id},#{actor},#{role},#{target},#{kind},#{submission},#{action},#{version},#{reason},#{now})")
    int audit(@Param("id") long id,@Param("actor") Long actor,@Param("role") UserRoleEnum role,@Param("target") long target,
        @Param("kind") ProfileField kind,@Param("submission") Long submission,@Param("action") String action,
        @Param("version") long version,@Param("reason") String reason,@Param("now") LocalDateTime now);
    /** 固定字段表达式用于清点，同名历史内容不会被自动算为已审。 */
    String PUBLIC = " FROM sys_user u LEFT JOIN sys_profile_field f ON f.user_id=u.id AND f.kind=#{q.kind} ";
    /** 默认头像无内容可审，其余历史字段必须显式保留或下架。 */
    String PUBLIC_WHERE = "<where> u.deleted=0 <if test='q.unverifiedOnly'> AND COALESCE(f.verified,CASE WHEN #{q.kind}='AVATAR' AND u.avatar IS NULL THEN 1 ELSE 0 END)=0</if></where>";
    /** 有界历史/已发布清单，不对原数据做批量迁移。 */
    @Select("<script>SELECT u.id AS user_id,u.username,#{q.kind} AS kind,CASE WHEN #{q.kind}='AVATAR' THEN u.avatar ELSE u.nickname END AS value,"
        + "COALESCE(f.version,0) AS version,COALESCE(f.verified,CASE WHEN #{q.kind}='AVATAR' AND u.avatar IS NULL THEN 1 ELSE 0 END) AS verified,u.status,u.role"
        + PUBLIC+PUBLIC_WHERE+" ORDER BY u.id DESC LIMIT #{q.pageSize} OFFSET #{offset}</script>")
    List<PublishedProfileField> published(@Param("q") ProfilePublishedQueryDTO q,@Param("offset") int offset);
    /** 清单计数复用相同条件。 */
    @Select("<script>SELECT COUNT(*)"+PUBLIC+PUBLIC_WHERE+"</script>") long publishedCount(@Param("q") ProfilePublishedQueryDTO q);
    /** 每轮最多清理 32 个失效待审，不以一次全库扫描拖慢普通请求。 */
    @Select("SELECT s.id FROM sys_profile_submission s JOIN sys_user u ON u.id=s.user_id WHERE s.status='PENDING' "
        + "AND (s.expires_at <= #{now} OR u.status!=1 OR u.deleted!=0 OR u.credential_version!=s.credential_version) ORDER BY s.id LIMIT 32")
    List<Long> obsolete(@Param("now") LocalDateTime now);
    /** 清理软删除目标也必须先锁物理用户行，不能因 TableLogic 过滤而失去串行纪律。 */
    @Select("SELECT id FROM sys_user WHERE id=#{uid} FOR UPDATE") Long lockPhysical(@Param("uid") long uid);
    /** 崩溃后无引用文件才可回收，绝不按文件名或年龄直接删除仍被待审引用的图。 */
    @Select("SELECT COUNT(*) FROM sys_profile_submission WHERE private_key=#{key} AND status='PENDING'")
    long privateReferences(@Param("key") String key);
}

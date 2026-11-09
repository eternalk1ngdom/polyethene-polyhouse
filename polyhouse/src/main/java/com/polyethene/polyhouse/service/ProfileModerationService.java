package com.polyethene.polyhouse.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.common.security.SessionSecurity;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.*;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.entity.*;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.utils.*;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Validator;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** 单独管理资料申请、精确版本审核/下架、配额与数据库持久化，不接管登录、成长或账号封禁。 */
@Service public class ProfileModerationService {
    private static final Logger LOG=LoggerFactory.getLogger(ProfileModerationService.class);
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final UserMapper users; private final ProfileModerationMapper records;
    private final AuthRedisStore sessions;private final JwtProperties jwt;private final Validator validator;
    private static final int AVATAR_LIMIT=5;
    private static final int AVATAR_NONE=0,AVATAR_PENDING=1,AVATAR_REJECTED=2;
    // 已提交事务的回调可能乱序，只推进次数。
    private static final DefaultRedisScript<Long> ADVANCE_QUOTA=new DefaultRedisScript<>("""
        local current = tonumber(redis.call('GET', KEYS[1])) or 0
        local nextCount = tonumber(ARGV[1])
        if nextCount > current then
            redis.call('SET', KEYS[1], ARGV[1], 'EX', 86400)
        end
        return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    private final Clock clock;private final TransactionTemplate transaction;
    /** 复用用户锁和会话；Redis 只记录已提交次数，数据库保障并发与回滚。 */
    public ProfileModerationService(UserMapper users,ProfileModerationMapper records,AuthRedisStore sessions,
        JwtProperties jwt,Validator validator,StringRedisTemplate redis,Clock clock,
        PlatformTransactionManager manager) {
        this.users=users;this.records=records;this.sessions=sessions;this.jwt=jwt;this.validator=validator;
        this.redis=redis;this.clock=clock;transaction=new TransactionTemplate(manager);
    }
    /** 昵称提交只写申请，不回写公开字段；相同请求不重复计次。 */
    public ProfileSubmissionVO submitNickname(NicknameSubmissionDTO dto,String token) {
        validate(dto);return submit(ProfileField.NICKNAME,dto.requestId(),dto.nickname(),null,token);
    }
    /* 通过会话检查后再验证和散列有界图像。 */
    ProfileSubmissionVO submitAvatar(AvatarSubmitDTO dto,String token) {
        validate(dto);return submit(ProfileField.AVATAR,dto.requestId(),null,dto.avatarBase64(),token);
    }
    private ProfileSubmissionVO submit(ProfileField kind,String requestId,String value,String avatar,String token) {
        var claims=claims(token);
        return tx(()->{
            User user=lockUser(claims.userId());requireActor(user,claims,false);
            String hash=digest(avatar==null?value.getBytes(StandardCharsets.UTF_8):AvatarImageUtils.decode(avatar));
            var previous=records.byRequest(user.getId(),requestId);
            if(previous!=null) {
                if(previous.kind()!=kind || !previous.contentHash().equals(hash))conflict();
                return view(previous,user,remaining(user,kind));
            }
            var now=now();
            var state=state(user,kind);int remaining=remaining(user,kind,now.toLocalDate());
            if(remaining<=0)throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,"今日该资料提交次数已用完");
            long version=Math.addExact(state.version(),1);
            // 申请、字节和审计同一事务，任何一步失败都保留原待审图。
            for(var old:records.pending(user.getId(),kind))terminal(old,ProfileReviewStatus.SUPERSEDED,"已被新申请替代",user);
            if(avatar!=null)changed(records.setPendingAvatar(user.getId(),avatar,AVATAR_PENDING));
            changed(records.advance(user.getId(),kind,version));
            var s=new ProfileSubmission(IdWorker.getId(),user.getId(),user.getUsername(),kind,
                ProfileReviewStatus.PENDING,version,requestId,value,hash,null,now,now.plusDays(7),null,null,user.getCredentialVersion());
            changed(records.insert(s));audit(user,user.getId(),kind,s.id(),"SUBMIT",version,"提交资料审核");
            if(avatar!=null)recordQuotaAfterCommit(user.getId(),now.toLocalDate(),AVATAR_LIMIT-remaining+1);
            return ProfileSubmissionVO.from(s,remaining-1);
        });
    }
    /** 本人近期记录和额度；失效申请即使后台尚未清理，也不能被界面误报为可审核。 */
    public ProfileStatusVO mine(String token) {
        var claims=claims(token);return tx(()->{
            User user=lockUser(claims.userId());requireActor(user,claims,false);
            int avatar=remaining(user,ProfileField.AVATAR),nickname=remaining(user,ProfileField.NICKNAME);
            var items=records.recent(user.getId()).stream().map(s->view(s,user,s.kind()==ProfileField.AVATAR?avatar:nickname)).toList();
            return new ProfileStatusVO(items,avatar,nickname);
        });
    }
    /** OWNER 队列按有界页读取，计数与行使用同一有效性规则。 */
    public ProfileReviewPageVO queue(ProfileReviewQueryDTO dto,String token) {
        validate(dto);var claims=claims(token);return tx(()->{
            requireActor(lockUser(claims.userId()),claims,true);var now=now();
            long count=records.queueCount(dto,now);var rows=records.queue(dto,now,(dto.page()-1)*dto.pageSize());
            return new ProfileReviewPageVO(rows.stream().map(s->ProfileSubmissionVO.from(s,0)).toList(),Long.toString(count),dto.page(),dto.pageSize());
        });
    }
    /** 私有图仅本人/OWNER 可读，账号/安全版本失效、终态和过期内容均不再提供预览。 */
    public byte[] image(ProfileResourceDTO id,String token) {
        validate(id);var claims=claims(token);var before=find(id.id());
        return tx(()->{
            var pair=lockPair(claims.userId(),before.userId());User actor=pair[0],target=pair[1];requireActor(actor,claims,false);
            if(actor.getId()!=before.userId() && actor.getRole()!=UserRoleEnum.OWNER)throw new BusinessException(ErrorCode.FORBIDDEN);
            var s=records.locked(id.id());
            if(s==null || s.kind()!=ProfileField.AVATAR || effective(s,target)!=ProfileReviewStatus.PENDING)
                throw new BusinessException(ErrorCode.NOT_FOUND);
            String avatar=records.pendingAvatar(target.getId());
            if(avatar==null)throw new BusinessException(ErrorCode.NOT_FOUND);
            return AvatarImageUtils.decode(avatar);
        });
    }
    /** 仅从有效申请发布其已提交内容；过期、重复与旧版本审核返回冲突，绝不重复发布。 */
    public ProfileSubmissionVO decide(ProfileResourceDTO id,ProfileDecisionDTO dto,String token) {
        validate(id);validate(dto);reason(dto.reason());var claims=claims(token);var before=find(id.id());
        return tx(()->{
            var pair=lockPair(claims.userId(),before.userId());User actor=pair[0],target=pair[1];requireActor(actor,claims,true);
            var s=records.locked(id.id());if(s==null || target==null || effective(s,target)!=ProfileReviewStatus.PENDING)conflict();
            var state=state(target,s.kind());
            if(s.version()!=dto.expectedVersion() || state.version()!=s.version())conflict();
            if(dto.approve()) {
                String value=s.proposedValue();
                if(s.kind()==ProfileField.AVATAR) {
                    value=records.pendingAvatar(target.getId());
                    if(value==null)conflict();
                    AvatarImageUtils.decode(value);
                }
                changed(records.updatePublic(target.getId(),s.kind(),value));
                changed(records.publish(target.getId(),s.kind(),s.id()));
            }
            var status=dto.approve()?ProfileReviewStatus.APPROVED:ProfileReviewStatus.REJECTED;
            terminal(s,status,dto.reason(),actor);
            audit(actor,target.getId(),s.kind(),s.id(),dto.approve()?"APPROVE":"REJECT",s.version(),dto.reason());
            return ProfileSubmissionVO.from(records.locked(s.id()),remaining(target,s.kind()));
        });
    }
    /** 已公开清单显示未核对标记，迁移不批量批准/撤下任何真实历史内容。 */
    public PublishedProfilePageVO published(ProfilePublishedQueryDTO dto,String token) {
        validate(dto);var claims=claims(token);return tx(()->{
            requireActor(lockUser(claims.userId()),claims,true);
            long count=records.publishedCount(dto);var rows=records.published(dto,(dto.page()-1)*dto.pageSize());
            return new PublishedProfilePageVO(rows.stream().map(PublishedProfileVO::from).toList(),Long.toString(count),dto.page(),dto.pageSize());
        });
    }
    /** 显式保留旧内容或按字段下架；核对版本和当前值，不能从旧列表覆盖新公开资料。 */
    public PublishedProfileVO changePublic(ProfileResourceDTO id,ProfilePublicChangeDTO dto,String token) {
        validate(id);validate(dto);reason(dto.reason());var claims=claims(token);
        return tx(()->{
            var pair=lockPair(claims.userId(),id.id());User actor=pair[0],target=pair[1];requireActor(actor,claims,true);
            if(target==null)throw new BusinessException(ErrorCode.NOT_FOUND);
            var state=state(target,dto.kind());String current=dto.kind()==ProfileField.AVATAR?target.getAvatar():target.getNickname();
            if(dto.kind()==ProfileField.AVATAR)current=AvatarImageUtils.displaySource(current);
            if(state.version()!=dto.expectedVersion() || !Objects.equals(current,dto.expectedValue()))conflict();
            long version=state.version();
            if(dto.action()==ProfilePublicAction.KEEP) { if(state.verified())conflict(); }
            else {
                version=Math.addExact(version,1);String value=dto.kind()==ProfileField.AVATAR?null:defaultNickname(target);
                for(var old:records.pending(target.getId(),dto.kind()))terminal(old,ProfileReviewStatus.INVALIDATED,"公开资料已下架，请重新提交",actor);
                changed(records.advance(target.getId(),dto.kind(),version));changed(records.updatePublic(target.getId(),dto.kind(),value));
                current=value;
            }
            changed(records.publish(target.getId(),dto.kind(),dto.action()==ProfilePublicAction.KEEP?state.currentId():null));
            audit(actor,target.getId(),dto.kind(),state.currentId(),dto.action().name(),version,dto.reason());
            return new PublishedProfileVO(Long.toString(target.getId()),target.getUsername(),dto.kind(),current,Long.toString(version),true,target.getStatus(),target.getRole());
        });
    }
    /** 每分钟有限失效清理；清除待审字节和状态时继续使用同一事务和锁顺序。 */
    @Scheduled(initialDelay=60000,fixedDelay=60000)
    public void maintain() {
        try {
            for(long id:records.obsolete(now())) {
                var before=records.find(id);if(before==null)continue;
                tx(()->{
                    records.lockPhysical(before.userId());User user=users.selectById(before.userId());var s=records.locked(id);
                    if(s!=null && s.status()==ProfileReviewStatus.PENDING && effective(s,user)!=ProfileReviewStatus.PENDING) {
                        var status=effective(s,user);String why=status==ProfileReviewStatus.EXPIRED?"待审超过七天，请重新提交":"账号安全状态已变化，请重新提交";
                        terminal(s,status,why,null);audit(null,s.userId(),s.kind(),s.id(),status.name(),s.version(),why);
                    }
                    return Boolean.TRUE;
                });
            }
        } catch(RuntimeException e) { LOG.warn("Profile cleanup deferred: type={}",e.getClass().getSimpleName()); }
    }
    private ProfileSubmissionVO view(ProfileSubmission s,User user,int remaining) {
        return new ProfileSubmissionVO(Long.toString(s.id()),Long.toString(s.userId()),s.username(),s.kind(),effective(s,user),Long.toString(s.version()),
            s.requestId(),s.proposedValue(),s.reason(),s.createdAt(),s.expiresAt(),remaining);
    }
    private ProfileReviewStatus effective(ProfileSubmission s,User user) {
        if(s.status()!=ProfileReviewStatus.PENDING)return s.status();
        if(user==null || !Integer.valueOf(1).equals(user.getStatus()) || !Integer.valueOf(0).equals(user.getDeleted())
            || !Objects.equals(user.getCredentialVersion(),s.credentialVersion()))return ProfileReviewStatus.INVALIDATED;
        return s.expiresAt().isAfter(now())?ProfileReviewStatus.PENDING:ProfileReviewStatus.EXPIRED;
    }
    private void terminal(ProfileSubmission s,ProfileReviewStatus status,String why,User actor) {
        changed(records.terminal(s.id(),status,why,now(),actor==null?null:actor.getId()));
        if(s.kind()==ProfileField.AVATAR) {
            changed(records.setPendingAvatar(s.userId(),null,status==ProfileReviewStatus.REJECTED?AVATAR_REJECTED:AVATAR_NONE));
        }
    }
    private ProfileFieldState state(User user,ProfileField kind) {
        records.initialize(user.getId(),kind,kind==ProfileField.AVATAR && user.getAvatar()==null);
        var state=records.state(user.getId(),kind);if(state==null)throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);return state;
    }
    private int remaining(User user,ProfileField kind) {
        return remaining(user,kind,now().toLocalDate());
    }
    private int remaining(User user,ProfileField kind,LocalDate today) {
        int limit=kind==ProfileField.AVATAR?AVATAR_LIMIT:2;
        // 缓存只用于已满额的快速拒绝。任何仍有余量的写入都由数据库计次约束。
        if(kind==ProfileField.AVATAR && quotaExhausted(user.getId(),today))return 0;
        long used=records.used(user.getId(),kind,today.atStartOfDay(),today.plusDays(1).atStartOfDay());
        // 升级当天沿用旧头像成功次数，不能借新表把当日额度清零；不改写旧 ext_json 设置。
        if(kind==ProfileField.AVATAR && user.getExtJson()!=null && user.getExtJson().get("avatarUpload") instanceof Map<?,?> old
            && today.toString().equals(old.get("date"))) {
            if(!(old.get("count") instanceof Number count) || count.longValue()<0)throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            used=Math.addExact(used,count.longValue());
        }
        return (int)Math.max(0,limit-Math.min(limit,used));
    }
    private User lockUser(long id) { return users.selectOne(new LambdaQueryWrapper<User>().eq(User::getId,id).last("FOR UPDATE")); }
    private User[] lockPair(long actor,long target) {
        User first=lockUser(Math.min(actor,target));User second=actor==target?first:lockUser(Math.max(actor,target));
        return actor<=target?new User[]{first,second}:new User[]{second,first};
    }
    private void requireActor(User actor,JwtUtils.TokenClaims claims,boolean owner) {
        SessionSecurity.requireCurrent(actor,claims.credentialVersion());SessionSecurity.requireFullAccess(actor);
        if(!sessions.isCurrentSession(claims))throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if(owner && actor.getRole()!=UserRoleEnum.OWNER)throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    private ProfileSubmission find(long id) {
        try { var s=records.find(id);if(s==null)throw new BusinessException(ErrorCode.NOT_FOUND);return s; }
        catch(DataAccessException e) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    }
    private JwtUtils.TokenClaims claims(String token) {
        try { return JwtUtils.parseAndValidate(token,jwt.signingKey(),clock.instant()); }
        catch(JwtException e) { throw new BusinessException(ErrorCode.UNAUTHORIZED); }
        catch(IllegalArgumentException e) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    }
    private <T>T tx(Supplier<T> work) {
        try { return transaction.execute(status->work.get()); }
        catch(DataAccessException|ArithmeticException e) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    }
    private void audit(User actor,long target,ProfileField kind,Long submission,String action,long version,String why) {
        changed(records.audit(IdWorker.getId(),actor==null?null:actor.getId(),actor==null?null:actor.getRole(),target,kind,submission,action,version,why,now()));
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZONE); }
    private void validate(Object dto) { if(dto==null || !validator.validate(dto).isEmpty())throw new BusinessException(ErrorCode.INVALID_PARAMETER); }
    private void changed(int rows) { if(rows!=1)throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    private void conflict() { throw new BusinessException(ErrorCode.CONFLICT); }
    private void reason(String reason) { if(reason.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT
        || Character.getType(c)==Character.LINE_SEPARATOR || Character.getType(c)==Character.PARAGRAPH_SEPARATOR))throw new BusinessException(ErrorCode.INVALID_PARAMETER); }
    private String defaultNickname(User user) {
        return "小屋住户"+(user.getUsername().matches("ph[0-9]{6,18}")?user.getUsername().substring(2):user.getId().toString());
    }
    private String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable",e); }
    }
    private String quotaKey(long userId,LocalDate date) { return "polyhouse:profile:avatar-quota:"+date+":"+userId; }
    private boolean quotaExhausted(long userId,LocalDate date) {
        try {
            return Integer.toString(AVATAR_LIMIT).equals(redis.opsForValue().get(quotaKey(userId,date)));
        } catch(DataAccessException e) {
            LOG.warn("Avatar quota cache unavailable: type={}",e.getClass().getSimpleName());
            return false;
        }
    }
    private void recordQuotaAfterCommit(long userId,LocalDate date,int used) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    redis.execute(ADVANCE_QUOTA,List.of(quotaKey(userId,date)),Integer.toString(used));
                } catch(DataAccessException e) {
                    LOG.warn("Avatar quota cache update deferred: type={}",e.getClass().getSimpleName());
                }
            }
        });
    }
}

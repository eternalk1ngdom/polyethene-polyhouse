package com.polyethene.polyhouse.user.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.AccountAuditMapper;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.vo.AccountOperationVO;
import com.polyethene.polyhouse.common.constant.AccountManagementAction;
import com.polyethene.polyhouse.mapper.AccountManagementMapper;
import com.polyethene.polyhouse.service.AccountManagementService;
import com.polyethene.polyhouse.service.AuthRedisStore;
import com.polyethene.polyhouse.service.AvatarService;
import com.polyethene.polyhouse.user.fixture.AvatarFixture;
import com.polyethene.polyhouse.utils.AvatarImageUtils;
import com.polyethene.polyhouse.service.ProfileModerationService;
import com.polyethene.polyhouse.mapper.ProfileModerationMapper;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.model.vo.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.*;
import com.polyethene.polyhouse.model.dto.LoginDTO;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.service.AccountProvisioningService;
import com.polyethene.polyhouse.service.AuthService;
import com.polyethene.polyhouse.utils.PasswordUtils;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 所有真实角色、图片和数据库故障仅在随机隔离库/本次 target 目录内验收。 */
@EnabledIfSystemProperty(named="polyhouse.profile.integration",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ProfileModerationIntegrationTest {
 private static final String PASSWORD="P4-isolated-fixture!";
 private static Sandbox sandbox;
 @Autowired private AuthService auth;
 @Autowired private ProfileModerationService profiles;
 @Autowired private AvatarService avatars;
 @Autowired private JdbcTemplate jdbc;
 @Autowired private StringRedisTemplate redis;
 @SpyBean private ProfileModerationMapper mapper;
 @SpyBean(name="userGrowthClock") private Clock clock;
 @LocalServerPort private int port;
 private final ObjectMapper json=new ObjectMapper();
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 private long owner,user,other,admin;private String ownerToken,userToken,otherToken,adminToken;
 @DynamicPropertySource static void properties(DynamicPropertyRegistry p)throws Exception{
  sandbox=new Sandbox();String key=Base64.getEncoder().encodeToString(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded());
  p.add("spring.datasource.url",()->sandbox.url);p.add("polyhouse.jwt.secret",()->key);
  p.add("spring.datasource.hikari.maximum-pool-size",()->4);p.add("spring.datasource.hikari.minimum-idle",()->1);
 }
 @BeforeEach void fixtures(){
  owner=create("p4_owner",UserRoleEnum.OWNER);user=create("p4_user",UserRoleEnum.USER);
  other=create("p4_other",UserRoleEnum.INSIDER);admin=create("p4_admin",UserRoleEnum.ADMIN);
  ownerToken=login("p4_owner");userToken=login("p4_user");otherToken=login("p4_other");adminToken=login("p4_admin");
 }
 @AfterEach void cleanup()throws Exception{
  reset(mapper,clock);
  for(long uid:jdbc.queryForList("SELECT id FROM sys_user",Long.class)) {
   redis.delete(List.of("PC","H5","PLUGIN").stream().map(c->RedisConstants.buildAuthTokenKey(uid,c)).toList());
   var day=LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai"));
   redis.delete(List.of("polyhouse:profile:avatar-quota:"+day+":"+uid,"polyhouse:profile:avatar-quota:"+day.plusDays(1)+":"+uid));
  }
  for(String table:List.of("sys_profile_audit","sys_profile_submission","sys_profile_field","sys_account_management_operation","sys_account_audit","sys_account_creation","sys_user_growth_reward","sys_user"))jdbc.execute("DELETE FROM "+table);
  if(Files.exists(sandbox.root))try(var files=Files.walk(sandbox.root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())if(!path.equals(sandbox.root))Files.deleteIfExists(path);}
 }
 @AfterAll static void drop()throws Exception{if(sandbox!=null)sandbox.close();}
 @Test void nicknameIsPrivateUntilApprovalAndFieldsDoNotOverwriteGrowth()throws Exception{
  String before=fingerprint(user);var s=nickname("新的小屋名字",userToken);
  assertThat(publicNickname(user)).isEqualTo("旧昵称");assertThat(profiles.mine(userToken).submissions()).hasSize(1);
  var row=profiles.queue(new ProfileReviewQueryDTO(null,null,null),ownerToken).items().getFirst();assertThat(row.proposedValue()).isEqualTo("新的小屋名字");
  assertCode(()->profiles.queue(new ProfileReviewQueryDTO(1,20,null),adminToken),ErrorCode.FORBIDDEN);
  assertCode(()->profiles.queue(new ProfileReviewQueryDTO(1,20,null),otherToken),ErrorCode.FORBIDDEN);
  profiles.decide(id(s),decision(s,true),ownerToken);assertThat(publicNickname(user)).isEqualTo("新的小屋名字");assertThat(fingerprint(user)).isEqualTo(before);
  assertCode(()->profiles.decide(id(s),decision(s,true),ownerToken),ErrorCode.CONFLICT);
  assertThat(call("GET","/api/manage/profile-reviews",userToken,null).statusCode()).isEqualTo(403);
  assertThat(call("GET","/api/manage/profile-reviews",null,null).statusCode()).isEqualTo(401);
 }
 @Test void privateAvatarRequiresOwnershipAndOnlyApprovalPublishesIt()throws Exception{
  var request=image(UUID.randomUUID().toString(),1);
  var result=call("POST","/api/auth/avatar/submissions",userToken,Map.of("requestId",request.requestId(),"avatarBase64",request.avatarBase64()));
  assertThat(result.statusCode()).isEqualTo(200);
  assertThat(result.body()).doesNotContain(request.avatarBase64());
  var s=profiles.mine(userToken).submissions().getFirst();
  assertThat(pendingAvatar(user)).isEqualTo(request.avatarBase64());
  assertThat(avatarState(user)).isEqualTo(1);
  assertThat(publicAvatar(user)).isNull();
  assertThat(call("GET","/api/profile-submissions/"+s.id()+"/image",null,null).statusCode()).isEqualTo(401);
  assertThat(call("GET","/api/profile-submissions/"+s.id()+"/image",otherToken,null).statusCode()).isEqualTo(403);
  var preview=call("GET","/api/profile-submissions/"+s.id()+"/image",userToken,null);
  assertThat(preview.statusCode()).isEqualTo(200);
  assertThat(preview.headers().firstValue("Cache-Control")).contains("no-store");
  assertThat(preview.headers().firstValue("Content-Type")).contains("image/webp");
  assertThat(profiles.image(id(s),ownerToken)).isEqualTo(AvatarImageUtils.decode(request.avatarBase64()));
  profiles.decide(id(s),decision(s,true),ownerToken);
  assertThat(publicAvatar(user)).isEqualTo(request.avatarBase64());
  assertThat(pendingAvatar(user)).isNull();
  assertThat(avatarState(user)).isZero();
  assertThat(auth.getCurrentUser(userToken).avatar()).isEqualTo(AvatarImageUtils.displaySource(request.avatarBase64()));
  assertCode(()->profiles.image(id(s),userToken),ErrorCode.NOT_FOUND);
 }
 @Test void ownerOwnApplicationRequiresExplicitApproval()throws Exception{
  var s=nickname("屋主也先审核",ownerToken);assertThat(publicNickname(owner)).isEqualTo("旧昵称");
  profiles.decide(id(s),decision(s,true),ownerToken);assertThat(publicNickname(owner)).isEqualTo("屋主也先审核");
 }
 @Test void replacementAndRejectionReleaseOnlyPendingBytes()throws Exception{
  var a=avatar(userToken);
  var request=image(UUID.randomUUID().toString(),12);
  var b=avatars.upload(request,userToken);
  assertCode(()->profiles.decide(id(a),decision(a,true),ownerToken),ErrorCode.CONFLICT);
  assertThat(status(a.id())).isEqualTo("SUPERSEDED");
  assertThat(pendingAvatar(user)).isEqualTo(request.avatarBase64());
  profiles.decide(id(b),decision(b,false),ownerToken);
  assertThat(publicAvatar(user)).isNull();
  assertThat(status(b.id())).isEqualTo("REJECTED");
  assertThat(pendingAvatar(user)).isNull();
  assertThat(avatarState(user)).isEqualTo(2);
 }
 @Test void quotasCountCommittedAttemptsAndIdempotencyDoesNotChargeAgain()throws Exception{
  var req=new NicknameSubmissionDTO(UUID.randomUUID().toString(),"第一个申请");var first=profiles.submitNickname(req,userToken);var replay=profiles.submitNickname(req,userToken);
  assertThat(replay.id()).isEqualTo(first.id());assertThat(replay.remainingToday()).isEqualTo(1);
  assertCode(()->profiles.submitNickname(new NicknameSubmissionDTO(req.requestId(),"另一个意图"),userToken),ErrorCode.CONFLICT);
  var second=nickname("第二个申请",userToken);profiles.decide(id(second),decision(second,false),ownerToken);
  assertCode(()->nickname("第三个申请",userToken),ErrorCode.TOO_MANY_REQUESTS);
  for(int i=0;i<5;i++)avatar(userToken);
  String key="polyhouse:profile:avatar-quota:"+LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai"))+":"+user;
  assertThat(redis.opsForValue().get(key)).isEqualTo("5");
  assertThat(redis.getExpire(key)).isBetween(1L,86400L);
  assertCode(()->avatarUnchecked(userToken),ErrorCode.TOO_MANY_REQUESTS);
  redis.delete(key);
  assertCode(()->avatarUnchecked(userToken),ErrorCode.TOO_MANY_REQUESTS);
  Instant tomorrow=clock.instant().plus(Duration.ofDays(1));doReturn(tomorrow).when(clock).instant();
  assertThat(nickname("新的一天",userToken).remainingToday()).isEqualTo(1);assertThat(avatar(userToken).remainingToday()).isEqualTo(4);
 }
 @Test void oldAvatarQuotaAndOtherSettingsRemainUntouched()throws Exception{
  String today=LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai")).toString();
  String value="{\"theme\":\"white\",\"avatarUpload\":{\"date\":\""+today+"\",\"count\":4}}";
  jdbc.update("UPDATE sys_user SET ext_json=CAST(? AS JSON) WHERE id=?",value,user);
  assertThat(avatar(userToken).remainingToday()).isZero();assertCode(()->avatarUnchecked(userToken),ErrorCode.TOO_MANY_REQUESTS);
  assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(ext_json,'$.theme')) FROM sys_user WHERE id=?",String.class,user)).isEqualTo("white");
 }
 @Test void auditFailureRollsBackApplicationQuotaAndPendingBytes()throws Exception{
  failAudit();assertCode(()->avatarUnchecked(userToken),ErrorCode.SERVICE_UNAVAILABLE);reset(mapper);
  assertThat(pendingAvatar(user)).isNull();assertThat(profiles.mine(userToken).avatarRemainingToday()).isEqualTo(5);assertThat(count("sys_profile_submission")).isZero();
  assertThat(avatar(userToken).remainingToday()).isEqualTo(4);
 }
 @Test void approvalAuditFailurePreservesPendingImage()throws Exception{
  var s=avatar(userToken);String pending=pendingAvatar(user);failAudit();
  assertCode(()->profiles.decide(id(s),decision(s,true),ownerToken),ErrorCode.SERVICE_UNAVAILABLE);reset(mapper);
  assertThat(publicAvatar(user)).isNull();assertThat(status(s.id())).isEqualTo("PENDING");assertThat(pendingAvatar(user)).isEqualTo(pending);
  profiles.decide(id(s),decision(s,true),ownerToken);assertThat(publicAvatar(user)).isNotNull();
 }
 @Test void failedReplacementPreservesOldPendingAndQuota()throws Exception{
  var old=avatar(userToken);String previous=pendingAvatar(user);
  failAudit();assertCode(()->avatarUnchecked(userToken),ErrorCode.SERVICE_UNAVAILABLE);reset(mapper);
  assertThat(status(old.id())).isEqualTo("PENDING");
  assertThat(pendingAvatar(user)).isEqualTo(previous);
  assertThat(profiles.mine(userToken).avatarRemainingToday()).isEqualTo(4);
 }
 @Test void banThenUnbanAndCredentialResetCannotRevivePendingApplication()throws Exception{
  var s=avatar(userToken);jdbc.update("UPDATE sys_user SET status=0,credential_version=credential_version+1 WHERE id=?",user);
  assertCode(()->profiles.decide(id(s),decision(s,true),ownerToken),ErrorCode.CONFLICT);
  jdbc.update("UPDATE sys_user SET status=1,credential_version=credential_version+1 WHERE id=?",user);
  assertCode(()->profiles.decide(id(s),decision(s,true),ownerToken),ErrorCode.CONFLICT);
  profiles.maintain();assertThat(status(s.id())).isEqualTo("INVALIDATED");assertThat(pendingAvatar(user)).isNull();
 }
 @Test void deregisterAndExpiryClearPendingBytesButNeverPublish()throws Exception{
  var expired=avatar(userToken);jdbc.update("UPDATE sys_profile_submission SET expires_at=? WHERE id=?",LocalDateTime.now().minusMinutes(1),expired.id());
  assertCode(()->profiles.decide(id(expired),decision(expired,true),ownerToken),ErrorCode.CONFLICT);profiles.maintain();assertThat(status(expired.id())).isEqualTo("EXPIRED");assertThat(pendingAvatar(user)).isNull();
  var deleted=avatar(userToken);auth.deregister(userToken);profiles.maintain();assertThat(status(deleted.id())).isEqualTo("INVALIDATED");assertThat(pendingAvatar(user)).isNull();
 }
 @Test void concurrentSubmitAndConflictingDecisionsHaveSingleWinners()throws Exception{
  var req=new NicknameSubmissionDTO(UUID.randomUUID().toString(),"同一并发申请");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var a=pool.submit(()->profiles.submitNickname(req,userToken));var b=pool.submit(()->profiles.submitNickname(req,userToken));
   var s=a.get(20,TimeUnit.SECONDS);assertThat(b.get(20,TimeUnit.SECONDS).id()).isEqualTo(s.id());
   var x=pool.submit(()->decisionOutcome(s,true));var y=pool.submit(()->decisionOutcome(s,false));
   assertThat(List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder("success","CONFLICT");
  }
  assertThat(count("sys_profile_submission")).isEqualTo(1);assertThat(count("sys_profile_audit")).isEqualTo(2);
 }
 @Test void legacyInventoryRequiresExplicitKeepAndTakedownInvalidatesOldPending()throws Exception{
  var q=new ProfilePublishedQueryDTO(1,20,ProfileField.NICKNAME,true);var legacy=profiles.published(q,ownerToken);assertThat(legacy.total()).isEqualTo("4");
  profiles.changePublic(new ProfileResourceDTO(user),new ProfilePublicChangeDTO(ProfileField.NICKNAME,0L,"旧昵称",ProfilePublicAction.KEEP,"核对历史昵称"),ownerToken);
  assertThat(publicNickname(user)).isEqualTo("旧昵称");assertThat(profiles.published(q,ownerToken).total()).isEqualTo("3");
  var s=nickname("待审被下架作废",userToken);
  profiles.changePublic(new ProfileResourceDTO(user),new ProfilePublicChangeDTO(ProfileField.NICKNAME,Long.parseLong(s.version()),"旧昵称",ProfilePublicAction.TAKEDOWN,"恢复默认昵称"),ownerToken);
  assertThat(publicNickname(user)).startsWith("小屋住户");assertThat(status(s.id())).isEqualTo("INVALIDATED");
  assertCode(()->profiles.decide(id(s),decision(s,true),ownerToken),ErrorCode.CONFLICT);
  assertCode(()->profiles.changePublic(new ProfileResourceDTO(user),new ProfilePublicChangeDTO(ProfileField.NICKNAME,0L,"旧昵称",ProfilePublicAction.TAKEDOWN,"过期目标"),ownerToken),ErrorCode.CONFLICT);
 }
 @Test void approvedHistoryRemainsApprovedAfterTakedownAndOtherFieldIsUnchanged()throws Exception{
  var s=avatar(userToken);profiles.decide(id(s),decision(s,true),ownerToken);String published=publicAvatar(user);
  profiles.changePublic(new ProfileResourceDTO(user),new ProfilePublicChangeDTO(ProfileField.AVATAR,Long.parseLong(s.version()),AvatarImageUtils.displaySource(published),ProfilePublicAction.TAKEDOWN,"下架测试图片"),ownerToken);
  assertThat(status(s.id())).isEqualTo("APPROVED");assertThat(publicAvatar(user)).isNull();assertThat(publicNickname(user)).isEqualTo("旧昵称");
 }
 @Test void staleInitialAndChangedActorRoleCannotApprove()throws Exception{
  var s=nickname("不能越权",userToken);jdbc.update("UPDATE sys_user SET role='INSIDER' WHERE id=?",owner);
  assertCode(()->profiles.decide(id(s),decision(s,true),ownerToken),ErrorCode.FORBIDDEN);
  jdbc.update("UPDATE sys_user SET role='OWNER',password_change_required=1 WHERE id=?",owner);
  assertCode(()->profiles.decide(id(s),decision(s,true),ownerToken),ErrorCode.PASSWORD_CHANGE_REQUIRED);
  assertCode(()->profiles.submitNickname(new NicknameSubmissionDTO(UUID.randomUUID().toString(),"非法\n昵称"),userToken),ErrorCode.INVALID_PARAMETER);
 }
 @Test void currentPendingBytesSurviveMaintenance()throws Exception{
  var live=avatar(userToken);String previous=pendingAvatar(user);
  profiles.maintain();
  assertThat(status(live.id())).isEqualTo("PENDING");
  assertThat(pendingAvatar(user)).isEqualTo(previous);
 }
 @Test void freshSchemaRejectsInvalidAvatarStatusAndPreservesPendingContent()throws Exception{
  String before=fingerprint(user);
  var submitted=avatar(userToken);
  String pending=pendingAvatar(user);
  assertThatThrownBy(() -> jdbc.update("UPDATE sys_user SET avatar_status=3 WHERE id=?",user))
    .isInstanceOf(org.springframework.dao.DataAccessException.class)
    .hasMessageContaining("chk_sys_user_avatar_status");
  assertThat(status(submitted.id())).isEqualTo("PENDING");
  assertThat(pendingAvatar(user)).isEqualTo(pending);
  assertThat(fingerprint(user)).isEqualTo(before);
 }
 @Test void existingPublishedBytesStayUntilApproval()throws Exception{
  String old=AvatarFixture.webp("green");
  jdbc.update("UPDATE sys_user SET avatar=? WHERE id=?",old,user);
  var s=avatar(userToken);
  assertThat(publicAvatar(user)).isEqualTo(old);
  profiles.decide(id(s),decision(s,true),ownerToken);
  assertThat(publicAvatar(user)).isEqualTo(AvatarFixture.webp("blue"));
 }
 @Test @EnabledIfEnvironmentVariable(named="POLYHOUSE_PROFILE_BROWSER",matches="true")
 void realBrowserUserAndOwnerReviewJourney()throws Exception{
  Path input=sandbox.root.resolve("browser-input.png");Files.write(input,pngFixture());
  ProcessBuilder builder=new ProcessBuilder("node.exe","scripts/verify-profile-moderation.cjs").directory(Path.of("../polyhouse-web").toFile()).redirectErrorStream(true);
  Map<String,String> inherited=new HashMap<>(builder.environment());builder.environment().clear();
  for(String key:List.of("PATH","SystemRoot","TEMP","TMP","USERPROFILE","LOCALAPPDATA","ProgramFiles","ProgramFiles(x86)","POLYHOUSE_PLAYWRIGHT_MODULE"))inherited.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(key)).findFirst().ifPresent(e->builder.environment().put(e.getKey(),e.getValue()));
  builder.environment().put("POLYHOUSE_BROWSER_API_BASE_URL","http://127.0.0.1:"+port);builder.environment().put("POLYHOUSE_BROWSER_FIXTURE_PASSWORD",PASSWORD);builder.environment().put("POLYHOUSE_BROWSER_INPUT",input.toString());
  Process child=builder.redirectOutput(Path.of("target/p4-browser-result.log").toFile()).start();
  if(!child.waitFor(150,TimeUnit.SECONDS)){child.destroyForcibly();throw new IllegalStateException("Browser verification timed out");}
  assertThat(child.exitValue()).as("See masked target/p4-browser-result.log").isZero();
 }
 /** U4 只在显式选中本方法和开关时保留隔离 HTTP 服务，供受控浏览器实际操作。 */
 @Test @EnabledIfSystemProperty(named="polyhouse.workspace.browser",matches="true")
 void interactiveWorkspaceRegression()throws Exception{
  long first=create("p4_first",UserRoleEnum.USER);
  jdbc.update("UPDATE sys_user SET password_change_required=1 WHERE id=?",first);
  Map<String,Object> sessions=new LinkedHashMap<>();
  for(var entry:Map.of("OWNER","p4_owner","ADMIN","p4_admin","USER","p4_user","INSIDER","p4_other","FIRST","p4_first").entrySet()){
   var session=auth.login(new LoginDTO(entry.getValue(),PASSWORD),null);
   // 测试文件使用 ISO 文本，不要求独立 ObjectMapper 注册生产时间模块或调整依赖。
   sessions.put(entry.getKey(),Map.of("accessToken",session.accessToken(),"user",session.user(),"tokenType",session.tokenType(),"expiresAt",session.expiresAt().toString()));
  }
  Path config=sandbox.root.resolve("workspace-browser.json"),complete=sandbox.root.resolve("workspace-complete.json");
  Files.write(sandbox.root.resolve("u4-avatar.png"),pngFixture());
  Files.writeString(config,json.writeValueAsString(Map.of("backend","http://127.0.0.1:"+port,"sessions",sessions)));
  ProcessBuilder builder=new ProcessBuilder("node.exe","scripts/workspace-regression-server.cjs")
   .directory(Path.of("../polyhouse-web").toFile()).redirectErrorStream(true);
  // 子进程只接收本次随机会话文件路径，不继承数据库密码或开发 JWT 密钥。
  Map<String,String> inherited=new HashMap<>(builder.environment());builder.environment().clear();
  for(String key:List.of("PATH","SystemRoot","TEMP","TMP","USERPROFILE","LOCALAPPDATA"))
   inherited.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(key)).findFirst().ifPresent(e->builder.environment().put(e.getKey(),e.getValue()));
  builder.environment().put("POLYHOUSE_WORKSPACE_CONFIG",config.toString());
  Process bridge=builder.redirectOutput(Path.of("target/u4-browser-bridge.log").toFile()).start();
  try{
   assertThat(bridge.waitFor(25,TimeUnit.MINUTES)).as("U4 browser verification must finish explicitly within its bounded session").isTrue();
   assertThat(bridge.exitValue()).as("See safe target/u4-browser-bridge.log").isZero();
   assertThat(json.readTree(Files.readString(complete)).path("passed").asBoolean()).isTrue();
   // 浏览器完成标记不能代替真实库断言：申请/开户/管理动作必须确实发生并持久化。
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_profile_submission WHERE user_id=? AND status='APPROVED'",Long.class,user)).isGreaterThanOrEqualTo(2);
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_profile_submission WHERE user_id=? AND status='REJECTED'",Long.class,user)).isGreaterThanOrEqualTo(1);
   assertThat(count("sys_account_creation")).isEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE id=?",Integer.class,user)).isEqualTo(1);
   assertThat(count("sys_account_management_operation")).isEqualTo(2);
   // 数据库防重之外还核对传输事实，防止模拟断线被浏览器透明重放而掩盖未知结果分支。
   var requests=json.readTree(Files.readString(sandbox.root.resolve("workspace-requests.json")));
   int bans=0,unbans=0,confirmations=0;
   for(var request:requests){
    String method=request.path("method").asText(),path=request.path("path").asText();
    if(method.equals("POST")&&path.equals("/api/manage/accounts/"+user+"/ban"))bans++;
    if(method.equals("POST")&&path.equals("/api/manage/accounts/"+user+"/unban"))unbans++;
    if(method.equals("GET")&&path.equals("/api/manage/accounts/operations/confirmation"))confirmations++;
   }
   assertThat(bans).isEqualTo(1);assertThat(unbans).isEqualTo(1);assertThat(confirmations).isGreaterThanOrEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE role=?",Long.class,UserRoleEnum.OWNER.getCode())).isEqualTo(1);
  }finally{
   if(bridge.isAlive())bridge.destroyForcibly();
   Files.deleteIfExists(config);
   Files.deleteIfExists(Path.of("../polyhouse-web/temp/u4-entry.ts"));
  }
 }
 private long create(String name,UserRoleEnum role){long id=Long.parseLong(auth.register(new RegisterDTO(name,PASSWORD,"旧昵称")).id());jdbc.update("UPDATE sys_user SET role=?,avatar=null WHERE id=?",role.getCode(),id);return id;}
 private String login(String name){return auth.login(new LoginDTO(name,PASSWORD),null).accessToken();}
 private ProfileSubmissionVO nickname(String text,String token){return profiles.submitNickname(new NicknameSubmissionDTO(UUID.randomUUID().toString(),text),token);}
 private ProfileSubmissionVO avatar(String token)throws Exception{return avatars.upload(image(UUID.randomUUID().toString(),1),token);}
 private void avatarUnchecked(String token){try{avatar(token);}catch(BusinessException e){throw e;}catch(Exception e){throw new IllegalStateException("fixture image failure",e);}}
 private AvatarSubmitDTO image(String request,int color)throws Exception{return new AvatarSubmitDTO(AvatarFixture.webp(color==1?"blue":"green"),request);}
 private byte[] pngFixture()throws Exception{
  var output=new ByteArrayOutputStream();
  ImageIO.write(new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB),"PNG",output);
  return output.toByteArray();
 }
 private ProfileResourceDTO id(ProfileSubmissionVO s){return new ProfileResourceDTO(Long.parseLong(s.id()));}
 private ProfileDecisionDTO decision(ProfileSubmissionVO s,boolean approve){return new ProfileDecisionDTO(Long.parseLong(s.version()),approve,approve?"审核通过":"审核驳回");}
 private String decisionOutcome(ProfileSubmissionVO s,boolean approve){try{profiles.decide(id(s),decision(s,approve),ownerToken);return "success";}catch(BusinessException e){return e.getErrorCode().name();}}
 private String pendingAvatar(long id){return jdbc.queryForObject("SELECT pending_avatar FROM sys_user WHERE id=?",String.class,id);}
 private int avatarState(long id){return jdbc.queryForObject("SELECT avatar_status FROM sys_user WHERE id=?",Integer.class,id);}
 private String status(String id){return jdbc.queryForObject("SELECT status FROM sys_profile_submission WHERE id=?",String.class,id);}
 private String publicNickname(long id){return jdbc.queryForObject("SELECT nickname FROM sys_user WHERE id=?",String.class,id);}
 private String publicAvatar(long id){return jdbc.queryForObject("SELECT avatar FROM sys_user WHERE id=?",String.class,id);}
 private String fingerprint(long id){return jdbc.queryForObject("SELECT CONCAT(role,':',experience,':',coins,':',level,':',status,':',credential_version) FROM sys_user WHERE id=?",String.class,id);}
 private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
 private void failAudit(){doThrow(new DataAccessResourceFailureException("isolated audit fault")).when(mapper).audit(anyLong(),any(),any(),anyLong(),any(),any(),anyString(),anyLong(),anyString(),any());}
 private HttpResponse<String> call(String method,String path,String token,Map<String,String> body)throws Exception{
  var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));if(token!=null)r.header("Authorization","Bearer "+token);
  if(method.equals("GET"))r.GET();else r.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
  return http.send(r.build(),HttpResponse.BodyHandlers.ofString());
 }
 private static void assertCode(Runnable action,ErrorCode code){assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));}
    /** 只允许本机 MySQL，库名由内部随机十六进制构成；不会重放开发库迁移；OWNER 夹具只位于本测试随机库。 */
    private static final class Sandbox implements AutoCloseable {
        private final Path root=Path.of("target").toAbsolutePath().resolve("p4-"+UUID.randomUUID().toString());
        private final String name = "polyhouse_profile_" + UUID.randomUUID().toString().replace("-", "");
        private final String user = System.getenv("DB_USERNAME");
        private final String password = System.getenv("DB_PASSWORD");
        private final String direct;
        private final String url;
        private Sandbox() throws Exception {
            Files.createDirectories(root);
            String configured = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://localhost:3306/polyhouse_db");
            URI parsed = URI.create(configured.substring(5));
            if (!List.of("localhost", "127.0.0.1").contains(parsed.getHost()) || !"/polyhouse_db".equals(parsed.getPath())) {
                throw new IllegalArgumentException("Only local development server is allowed");
            }
            String redisHost = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
            if (!List.of("localhost", "127.0.0.1", "::1").contains(redisHost)) throw new IllegalArgumentException("Only local Redis is allowed");
            direct = "jdbc:mysql://" + parsed.getHost() + ":" + (parsed.getPort() < 0 ? 3306 : parsed.getPort())
                    + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            url = direct.replace("/?", "/" + name + "?");
            try (Connection connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                // JDBC 不支持绑定数据库标识符；名字不是用户输入，且删除时再次验证固定格式。
                command.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
                connection.setCatalog(name);
                try {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource("sql/001_schema.sql"), StandardCharsets.UTF_8));
                } catch (Exception error) {
                    try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                    throw error;
                }
            }
        }
        /** 只删除本次生成且格式严格匹配的隔离库，不包含任何已有开发数据。 */
        @Override
        public void close() throws Exception {
            if (!name.matches("polyhouse_profile_[0-9a-f]{32}")) throw new IllegalStateException("Invalid sandbox name");
            try (Connection connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                command.execute("DROP DATABASE IF EXISTS `" + name + "`");
                Path boundary=Path.of("target").toAbsolutePath().normalize();
                if(!root.normalize().startsWith(boundary) || !root.getFileName().toString().startsWith("p4-"))throw new IllegalStateException("Unsafe fixture cleanup path");
                if(Files.exists(root))try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}

            }
        }
    }
}

package com.polyethene.polyhouse.acceptance;

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
import com.polyethene.polyhouse.service.AvatarStorage;
import com.polyethene.polyhouse.service.PrivateProfileImages;
import com.polyethene.polyhouse.service.ProfileModerationService;
import com.polyethene.polyhouse.mapper.ProfileModerationMapper;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.model.vo.*;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.nio.file.attribute.FileTime;
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
import org.springframework.core.io.ByteArrayResource;
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
 @SpyBean private PrivateProfileImages images;
 @SpyBean private AvatarStorage publicImages;
 @SpyBean(name="userGrowthClock") private Clock clock;
 @LocalServerPort private int port;
 private final ObjectMapper json=new ObjectMapper();
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 private long owner,user,other,admin;private String ownerToken,userToken,otherToken,adminToken;
 @DynamicPropertySource static void properties(DynamicPropertyRegistry p)throws Exception{
  sandbox=new Sandbox();String key=Base64.getEncoder().encodeToString(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded());
  p.add("spring.datasource.url",()->sandbox.url);p.add("polyhouse.jwt.secret",()->key);
  p.add("polyhouse.avatar.directory",()->sandbox.avatars.toString());p.add("spring.datasource.hikari.maximum-pool-size",()->4);p.add("spring.datasource.hikari.minimum-idle",()->1);
 }
 @BeforeEach void fixtures(){
  owner=create("p4_owner",UserRoleEnum.OWNER);user=create("p4_user",UserRoleEnum.USER);
  other=create("p4_other",UserRoleEnum.INSIDER);admin=create("p4_admin",UserRoleEnum.ADMIN);
  ownerToken=login("p4_owner");userToken=login("p4_user");otherToken=login("p4_other");adminToken=login("p4_admin");
 }
 @AfterEach void cleanup()throws Exception{
  reset(mapper,images,publicImages,clock);
  for(long uid:jdbc.queryForList("SELECT id FROM sys_user",Long.class))redis.delete(List.of("PC","H5","PLUGIN").stream().map(c->RedisConstants.buildAuthTokenKey(uid,c)).toList());
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
 @Test void privateAvatarRequiresOwnershipAndOnlyApprovalCreatesPublicFile()throws Exception{
  var s=avatar(userToken);String key=privateKey(s.id());assertThat(countFiles(sandbox.avatars)).isZero();
  assertThat(publicAvatar(user)).isNull();assertThat(call("GET","/api/profile-submissions/"+s.id()+"/image",null,null).statusCode()).isEqualTo(401);
  assertThat(call("GET","/api/profile-submissions/"+s.id()+"/image",otherToken,null).statusCode()).isEqualTo(403);
  var preview=call("GET","/api/profile-submissions/"+s.id()+"/image",userToken,null);assertThat(preview.statusCode()).isEqualTo(200);assertThat(preview.headers().firstValue("Cache-Control")).contains("no-store");
  assertThat(profiles.image(id(s),ownerToken)).isEqualTo(profiles.image(id(s),userToken));
  assertThat(call("GET","/api/media/avatars/"+key,null,null).statusCode()).isEqualTo(404);
  assertThat(call("GET","/api/media/avatars-pending/"+key,null,null).statusCode()).isEqualTo(404);
  profiles.decide(id(s),decision(s,true),ownerToken);String published=publicAvatar(user);
  assertThat(published).matches("/api/media/avatars/[a-f0-9]{32}\\.jpg");assertThat(Files.exists(sandbox.pending.resolve(key))).isFalse();
  var media=call("GET",published,null,null);assertThat(media.statusCode()).isEqualTo(200);assertThat(media.headers().firstValue("Cache-Control")).contains("max-age=86400, public");
  assertCode(()->profiles.image(id(s),userToken),ErrorCode.NOT_FOUND);
 }
 @Test void ownerOwnApplicationRequiresExplicitApproval()throws Exception{
  var s=nickname("屋主也先审核",ownerToken);assertThat(publicNickname(owner)).isEqualTo("旧昵称");
  profiles.decide(id(s),decision(s,true),ownerToken);assertThat(publicNickname(owner)).isEqualTo("屋主也先审核");
 }
 @Test void replacementAndRejectionKeepPublishedValueAndRemoveDiscardedPrivateFile()throws Exception{
  var a=avatar(userToken);String old=privateKey(a.id());var b=avatars.upload(image(UUID.randomUUID().toString(),12),userToken);
  assertThat(Files.exists(sandbox.pending.resolve(old))).isFalse();assertCode(()->profiles.decide(id(a),decision(a,true),ownerToken),ErrorCode.CONFLICT);
  assertThat(status(a.id())).isEqualTo("SUPERSEDED");String key=privateKey(b.id());profiles.decide(id(b),decision(b,false),ownerToken);
  assertThat(publicAvatar(user)).isNull();assertThat(status(b.id())).isEqualTo("REJECTED");assertThat(Files.exists(sandbox.pending.resolve(key))).isFalse();
 }
 @Test void quotasCountCommittedAttemptsAndIdempotencyDoesNotChargeAgain()throws Exception{
  var req=new NicknameSubmissionDTO(UUID.randomUUID().toString(),"第一个申请");var first=profiles.submitNickname(req,userToken);var replay=profiles.submitNickname(req,userToken);
  assertThat(replay.id()).isEqualTo(first.id());assertThat(replay.remainingToday()).isEqualTo(1);
  assertCode(()->profiles.submitNickname(new NicknameSubmissionDTO(req.requestId(),"另一个意图"),userToken),ErrorCode.CONFLICT);
  var second=nickname("第二个申请",userToken);profiles.decide(id(second),decision(second,false),ownerToken);
  assertCode(()->nickname("第三个申请",userToken),ErrorCode.TOO_MANY_REQUESTS);
  for(int i=0;i<5;i++)avatar(userToken);assertCode(()->avatarUnchecked(userToken),ErrorCode.TOO_MANY_REQUESTS);
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
 @Test void auditFailureRollsBackApplicationQuotaAndNewPrivateFile()throws Exception{
  failAudit();assertCode(()->avatarUnchecked(userToken),ErrorCode.SERVICE_UNAVAILABLE);reset(mapper);
  assertThat(countFiles(sandbox.pending)).isZero();assertThat(profiles.mine(userToken).avatarRemainingToday()).isEqualTo(5);assertThat(count("sys_profile_submission")).isZero();
  assertThat(avatar(userToken).remainingToday()).isEqualTo(4);
 }
 @Test void approvalAuditFailureRemovesNewPublicFileButPreservesPendingImage()throws Exception{
  var s=avatar(userToken);String key=privateKey(s.id());failAudit();
  assertCode(()->profiles.decide(id(s),decision(s,true),ownerToken),ErrorCode.SERVICE_UNAVAILABLE);reset(mapper);
  assertThat(publicAvatar(user)).isNull();assertThat(status(s.id())).isEqualTo("PENDING");assertThat(Files.exists(sandbox.pending.resolve(key))).isTrue();assertThat(countFiles(sandbox.avatars)).isZero();
  profiles.decide(id(s),decision(s,true),ownerToken);assertThat(publicAvatar(user)).isNotNull();
 }
 @Test void fileFailurePreservesOldPendingAndDoesNotUseQuota()throws Exception{
  var old=avatar(userToken);String key=privateKey(old.id());doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(images).save(any());
  assertCode(()->avatarUnchecked(userToken),ErrorCode.SERVICE_UNAVAILABLE);reset(images);
  assertThat(status(old.id())).isEqualTo("PENDING");assertThat(Files.exists(sandbox.pending.resolve(key))).isTrue();assertThat(profiles.mine(userToken).avatarRemainingToday()).isEqualTo(4);
  doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(publicImages).save(any());assertCode(()->profiles.decide(id(old),decision(old,true),ownerToken),ErrorCode.SERVICE_UNAVAILABLE);
  reset(publicImages);assertThat(status(old.id())).isEqualTo("PENDING");assertThat(publicAvatar(user)).isNull();
 }
 @Test void banThenUnbanAndCredentialResetCannotRevivePendingApplication()throws Exception{
  var s=avatar(userToken);String key=privateKey(s.id());jdbc.update("UPDATE sys_user SET status=0,credential_version=credential_version+1 WHERE id=?",user);
  assertCode(()->profiles.decide(id(s),decision(s,true),ownerToken),ErrorCode.CONFLICT);
  jdbc.update("UPDATE sys_user SET status=1,credential_version=credential_version+1 WHERE id=?",user);
  assertCode(()->profiles.decide(id(s),decision(s,true),ownerToken),ErrorCode.CONFLICT);
  profiles.maintain();assertThat(status(s.id())).isEqualTo("INVALIDATED");assertThat(Files.exists(sandbox.pending.resolve(key))).isFalse();
 }
 @Test void deregisterAndExpiryCleanFilesButNeverPublish()throws Exception{
  var expired=avatar(userToken);String key=privateKey(expired.id());jdbc.update("UPDATE sys_profile_submission SET expires_at=? WHERE id=?",LocalDateTime.now().minusMinutes(1),expired.id());
  assertCode(()->profiles.decide(id(expired),decision(expired,true),ownerToken),ErrorCode.CONFLICT);profiles.maintain();assertThat(status(expired.id())).isEqualTo("EXPIRED");assertThat(Files.exists(sandbox.pending.resolve(key))).isFalse();
  var deleted=avatar(userToken);String deletedKey=privateKey(deleted.id());auth.deregister(userToken);profiles.maintain();assertThat(status(deleted.id())).isEqualTo("INVALIDATED");assertThat(Files.exists(sandbox.pending.resolve(deletedKey))).isFalse();
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
  profiles.changePublic(new ProfileResourceDTO(user),new ProfilePublicChangeDTO(ProfileField.AVATAR,Long.parseLong(s.version()),published,ProfilePublicAction.TAKEDOWN,"下架测试图片"),ownerToken);
  assertThat(status(s.id())).isEqualTo("APPROVED");assertThat(publicAvatar(user)).isNull();assertThat(publicNickname(user)).isEqualTo("旧昵称");assertThat(countFiles(sandbox.avatars)).isZero();
 }
 @Test void staleInitialAndChangedActorRoleCannotApprove()throws Exception{
  var s=nickname("不能越权",userToken);jdbc.update("UPDATE sys_user SET role='INSIDER' WHERE id=?",owner);
  assertCode(()->profiles.decide(id(s),decision(s,true),ownerToken),ErrorCode.FORBIDDEN);
  jdbc.update("UPDATE sys_user SET role='OWNER',password_change_required=1 WHERE id=?",owner);
  assertCode(()->profiles.decide(id(s),decision(s,true),ownerToken),ErrorCode.PASSWORD_CHANGE_REQUIRED);
  assertCode(()->profiles.submitNickname(new NicknameSubmissionDTO(UUID.randomUUID().toString(),"非法\n昵称"),userToken),ErrorCode.INVALID_PARAMETER);
 }
 @Test void orphanSweepOnlyDeletesOldUnreferencedFiles()throws Exception{
  var live=avatar(userToken);String key=privateKey(live.id());String orphan="e".repeat(32)+".jpg";
  Files.write(sandbox.pending.resolve(orphan),new byte[]{1});FileTime old=FileTime.from(Instant.now().minus(Duration.ofHours(25)));
  Files.setLastModifiedTime(sandbox.pending.resolve(key),old);Files.setLastModifiedTime(sandbox.pending.resolve(orphan),old);
  profiles.maintain();assertThat(Files.exists(sandbox.pending.resolve(key))).isTrue();assertThat(Files.exists(sandbox.pending.resolve(orphan))).isFalse();
  assertThatThrownBy(()->images.read("../outside.jpg")).isInstanceOf(BusinessException.class);
 }
 @Test void migrationIsRepeatableAndDoesNotApproveLegacyContent()throws Exception{
  String before=fingerprint(user);try(Connection c=DriverManager.getConnection(sandbox.url,sandbox.user,sandbox.password)){sandbox.migrate(c,"008_profile_moderation.sql");}
  assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_profile_submission' AND INDEX_NAME='idx_profile_private'",Integer.class)).isEqualTo(1);
  assertThat(fingerprint(user)).isEqualTo(before);assertThat(profiles.published(new ProfilePublishedQueryDTO(1,20,ProfileField.NICKNAME,true),ownerToken).total()).isEqualTo("4");
 }
 @Test void existingPublishedFileStaysUntilApprovalAndThenIsCleaned()throws Exception{
  String old=publicImages.save(com.polyethene.polyhouse.utils.AvatarImageUtils.reencode(image(UUID.randomUUID().toString(),1).file().getBytes()));jdbc.update("UPDATE sys_user SET avatar=? WHERE id=?",old,user);
  var s=avatar(userToken);assertThat(publicAvatar(user)).isEqualTo(old);assertThat(countFiles(sandbox.avatars)).isEqualTo(1);
  profiles.decide(id(s),decision(s,true),ownerToken);assertThat(publicAvatar(user)).isNotEqualTo(old);assertThat(countFiles(sandbox.avatars)).isEqualTo(1);
 }
 @Test @EnabledIfEnvironmentVariable(named="POLYHOUSE_PROFILE_BROWSER",matches="true")
 void realBrowserUserAndOwnerReviewJourney()throws Exception{
  Path input=sandbox.root.resolve("browser-input.png");Files.write(input,image(UUID.randomUUID().toString(),1).file().getBytes());
  ProcessBuilder builder=new ProcessBuilder("node.exe","scripts/verify-profile-moderation.cjs").directory(Path.of("../polyhouse-web").toFile()).redirectErrorStream(true);
  Map<String,String> inherited=new HashMap<>(builder.environment());builder.environment().clear();
  for(String key:List.of("PATH","SystemRoot","TEMP","TMP","USERPROFILE","LOCALAPPDATA","ProgramFiles","ProgramFiles(x86)","POLYHOUSE_PLAYWRIGHT_MODULE"))inherited.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(key)).findFirst().ifPresent(e->builder.environment().put(e.getKey(),e.getValue()));
  builder.environment().put("POLYHOUSE_BROWSER_API_BASE_URL","http://127.0.0.1:"+port);builder.environment().put("POLYHOUSE_BROWSER_FIXTURE_PASSWORD",PASSWORD);builder.environment().put("POLYHOUSE_BROWSER_INPUT",input.toString());
  Process child=builder.redirectOutput(Path.of("target/p4-browser-result.log").toFile()).start();
  if(!child.waitFor(150,TimeUnit.SECONDS)){child.destroyForcibly();throw new IllegalStateException("Browser verification timed out");}
  assertThat(child.exitValue()).as("See masked target/p4-browser-result.log").isZero();
 }
 private long create(String name,UserRoleEnum role){long id=Long.parseLong(auth.register(new RegisterDTO(name,PASSWORD,"旧昵称")).id());jdbc.update("UPDATE sys_user SET role=?,avatar=null WHERE id=?",role.getCode(),id);return id;}
 private String login(String name){return auth.login(new LoginDTO(name,PASSWORD),null).accessToken();}
 private ProfileSubmissionVO nickname(String text,String token){return profiles.submitNickname(new NicknameSubmissionDTO(UUID.randomUUID().toString(),text),token);}
 private ProfileSubmissionVO avatar(String token)throws Exception{return avatars.upload(image(UUID.randomUUID().toString(),1),token);}
 private void avatarUnchecked(String token){try{avatar(token);}catch(BusinessException e){throw e;}catch(Exception e){throw new IllegalStateException("fixture image failure",e);}}
 private AvatarUploadDTO image(String request,int color)throws Exception{var b=new ByteArrayOutputStream();var img=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB);img.setRGB(0,0,color);ImageIO.write(img,"PNG",b);return new AvatarUploadDTO(new MockMultipartFile("file","fixture.png","image/png",b.toByteArray()),request);}
 private ProfileResourceDTO id(ProfileSubmissionVO s){return new ProfileResourceDTO(Long.parseLong(s.id()));}
 private ProfileDecisionDTO decision(ProfileSubmissionVO s,boolean approve){return new ProfileDecisionDTO(Long.parseLong(s.version()),approve,approve?"审核通过":"审核驳回");}
 private String decisionOutcome(ProfileSubmissionVO s,boolean approve){try{profiles.decide(id(s),decision(s,approve),ownerToken);return "success";}catch(BusinessException e){return e.getErrorCode().name();}}
 private String privateKey(String id){return jdbc.queryForObject("SELECT private_key FROM sys_profile_submission WHERE id=?",String.class,id);}
 private String status(String id){return jdbc.queryForObject("SELECT status FROM sys_profile_submission WHERE id=?",String.class,id);}
 private String publicNickname(long id){return jdbc.queryForObject("SELECT nickname FROM sys_user WHERE id=?",String.class,id);}
 private String publicAvatar(long id){return jdbc.queryForObject("SELECT avatar FROM sys_user WHERE id=?",String.class,id);}
 private String fingerprint(long id){return jdbc.queryForObject("SELECT CONCAT(role,':',experience,':',coins,':',level,':',status,':',credential_version) FROM sys_user WHERE id=?",String.class,id);}
 private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
 private long countFiles(Path root)throws Exception{if(!Files.isDirectory(root))return 0;try(var files=Files.list(root)){return files.filter(p->p.getFileName().toString().matches("[a-f0-9]{32}\\.jpg")).count();}}
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
        private final Path avatars=root.resolve("avatars");
        private final Path pending=root.resolve("avatars-pending");
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
                    for (String migration : List.of("001_init.sql", "002_user_growth.sql", "003_daily_growth_rewards.sql", "004_owner_bootstrap.sql", "005_account_security.sql", "006_account_provisioning.sql", "007_account_management.sql", "008_profile_moderation.sql")) migrate(connection, migration);
                } catch (Exception error) {
                    try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                    throw error;
                }
            }
        }
        private void migrate(Connection connection, String migration) throws Exception {
            String source = Files.readString(Path.of("sql", migration));
            if (migration.startsWith("001")) source = source.substring(source.indexOf("CREATE TABLE `"));
            source = source.replace("USE `polyhouse_db`;", "");
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(source.getBytes(StandardCharsets.UTF_8)));
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

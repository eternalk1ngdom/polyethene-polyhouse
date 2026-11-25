package com.polyethene.polyhouse.user.controller;

import com.polyethene.polyhouse.controller.ProfileModerationController;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.context.UserContext;
import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** HTTP 合同验证不能由前端隐藏按钮或单纯服务单测替代。 */
@WebMvcTest(ProfileModerationController.class)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import({WebMvcConfig.class,ProfileResourceConverter.class})
class ProfileModerationWebMvcTest {
 @Autowired private MockMvc mvc;
 @MockBean private AuthService auth;
 @MockBean private ProfileModerationService profiles;
 private static final String REQUEST="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
 @BeforeEach void defaults(){identity(UserRoleEnum.OWNER,false);when(profiles.queue(any(),any())).thenReturn(new ProfileReviewPageVO(List.of(),"0",1,20));when(profiles.mine(any())).thenReturn(new ProfileStatusVO(List.of(),5,2));}
 @AfterEach void contextCleared(){assertThat(UserContext.isAuthenticated()).isFalse();}
 @ParameterizedTest @EnumSource(UserRoleEnum.class)
 void allRolesCanApplyButOnlyOwnerCanReview(UserRoleEnum role)throws Exception{
  identity(role,false);
  mvc.perform(get("/api/auth/profile-submissions").header("Authorization","Bearer fixture")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  mvc.perform(get("/api/manage/profile-reviews").header("Authorization","Bearer fixture")).andExpect(status().is(role==UserRoleEnum.OWNER?200:403));
  mvc.perform(post("/api/manage/profile-reviews/42/decision").header("Authorization","Bearer fixture").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"approve\":true,\"reason\":\"审核测试\"}"))
   .andExpect(status().is(role==UserRoleEnum.OWNER?200:403));
 }
 @Test void pathTargetCannotBeOverwrittenAndBinaryIsPrivate()throws Exception{
  when(profiles.image(any(),any())).thenReturn(new byte[]{1,2});
  mvc.perform(get("/api/profile-submissions/42/image?id=99").header("Authorization","Bearer fixture"))
   .andExpect(status().isOk()).andExpect(content().contentType("image/webp")).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));
  verify(profiles).image(new ProfileResourceDTO(42L),"fixture");
 }
 @ParameterizedTest(name = "拒绝非法审核列表查询：{0}")
 @ValueSource(strings = {"page=0", "pageSize=51", "kind=PASSWORD"})
 void invalidReviewQueryNeverReachesService(String query)throws Exception{
  mvc.perform(get("/api/manage/profile-reviews?"+query).header("Authorization","Bearer fixture")).andExpect(status().isBadRequest());
  verifyNoInteractions(profiles);
 }
 @ParameterizedTest(name = "拒绝非法昵称提交请求体")
 @ValueSource(strings = {"{}", "{\"requestId\":\"bad\",\"nickname\":\"合法昵称\"}", "{\"requestId\":\""+REQUEST+"\",\"nickname\":\"含\\n换行\"}"})
 void invalidNicknameSubmissionNeverReachesService(String body)throws Exception{
  mvc.perform(post("/api/auth/nickname/submissions").header("Authorization","Bearer fixture").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
  verifyNoInteractions(profiles);
 }
 @Test void invalidPathAndInitialCredentialNeverReachService()throws Exception{
  mvc.perform(get("/api/profile-submissions/bad/image").header("Authorization","Bearer fixture")).andExpect(status().isBadRequest());
  identity(UserRoleEnum.OWNER,true);mvc.perform(get("/api/auth/profile-submissions").header("Authorization","Bearer fixture")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40301));
  verifyNoInteractions(profiles);
 }
 @ParameterizedTest(name = "匿名用户禁止访问私有资料接口：{0}")
 @ValueSource(strings = {"/api/auth/profile-submissions", "/api/profile-submissions/42/image", "/api/manage/profile-reviews", "/api/manage/profile-published"})
 void anonymousCannotReadPrivateStatusOrMedia(String path)throws Exception{
  mvc.perform(get(path)).andExpect(status().isUnauthorized());
  verifyNoInteractions(profiles);
 }
 private void identity(UserRoleEnum role,boolean initial){when(auth.getCurrentUser("fixture")).thenReturn(new UserProfileVO("1","fixture","测试",null,role,"0",1,"0",initial));}
}

package com.polyethene.polyhouse.user.controller;

import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.config.WebMvcConfig;
import com.polyethene.polyhouse.controller.AvatarController;
import com.polyethene.polyhouse.model.dto.AvatarSubmitDTO;
import com.polyethene.polyhouse.model.vo.ProfileSubmissionVO;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import com.polyethene.polyhouse.service.AuthService;
import com.polyethene.polyhouse.service.AvatarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证 JSON 绑定、长度边界和旧上传入口关闭，无需连接数据库。 */
@WebMvcTest(AvatarController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(WebMvcConfig.class)
class AvatarWebMvcTest {
    private static final String PATH = "/api/auth/avatar/submissions";
    private static final String ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    @Autowired private MockMvc mvc;
    @MockBean private AvatarService avatars;
    @MockBean private AuthService auth;

    @Test
    void acceptsJsonWithoutExposingPendingContent() throws Exception {
        authenticate();
        var dto = new AvatarSubmitDTO("UklGRg==", ID);
        var now = LocalDateTime.now();
        when(avatars.upload(dto, "fixture")).thenReturn(new ProfileSubmissionVO(
            "123","42","fixture",ProfileField.AVATAR,ProfileReviewStatus.PENDING,"1",ID,
            null,null,now,now.plusDays(7),4));
        mvc.perform(post(PATH).contentType("application/json").content(body(dto.avatarBase64()))
                .header("Authorization","Bearer fixture"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.avatarBase64").doesNotExist())
            .andExpect(jsonPath("$.data.remainingToday").value(4));
        verify(avatars).upload(dto,"fixture");
    }

    @Test
    void requiresSessionAndBoundsPayloadBeforeService() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(body("UklGRg==")))
            .andExpect(status().isUnauthorized());
        authenticate();
        mvc.perform(post(PATH).contentType("application/json").content(body("A".repeat(60_001)))
                .header("Authorization","Bearer fixture")).andExpect(status().isBadRequest());
        mvc.perform(multipart(PATH).header("Authorization","Bearer fixture"))
            .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(avatars);
    }

    @Test
    void legacyImmediateUploadAndFilesystemMediaAreClosed() throws Exception {
        authenticate();
        mvc.perform(post("/api/auth/avatar").header("Authorization","Bearer fixture"))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/media/avatars/"+"a".repeat(32)+".jpg")).andExpect(status().isNotFound());
        verifyNoInteractions(avatars);
    }

    private void authenticate() {
        when(auth.getCurrentUser("fixture")).thenReturn(
            new UserProfileVO("42","fixture","居民",null,UserRoleEnum.USER,"10",1,"1"));
    }

    private String body(String payload) {
        return """
            {"avatarBase64":"%s","requestId":"%s"}
            """.formatted(payload,ID);
    }
}

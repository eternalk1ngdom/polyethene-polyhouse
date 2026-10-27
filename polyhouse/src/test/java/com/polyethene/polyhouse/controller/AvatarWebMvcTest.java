package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.config.WebMvcConfig;
import com.polyethene.polyhouse.model.vo.AvatarVO;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import com.polyethene.polyhouse.service.AuthService;
import com.polyethene.polyhouse.service.AvatarService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 检查真实 DTO 绑定、普通用户认证和受限图片读取，不连接 MySQL 或 Redis。 */
@WebMvcTest(AvatarController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(WebMvcConfig.class)
class AvatarWebMvcTest {
    @TempDir static Path directory;
    @Autowired private MockMvc mvc;
    @MockBean private AvatarService avatars;
    @MockBean private AuthService auth;
    private static final String NAME = "a".repeat(32) + ".jpg";

    @DynamicPropertySource
    static void configureDirectory(DynamicPropertyRegistry registry) {
        registry.add("polyhouse.avatar.directory", () -> directory.toString());
    }

    @Test
    void ordinaryUserCanUploadOwnFileWithoutAcceptingUserId() throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(new UserProfileVO("42", "fixture", "居民", null, UserRoleEnum.USER, "10", 1, "1"));
        when(avatars.upload(any(), eq("fixture"))).thenReturn(new AvatarVO("/api/media/avatars/" + NAME, 4));
        mvc.perform(multipart("/api/auth/avatar").file(new MockMultipartFile("file", new byte[]{1}))
                        .param("userId", "99").header("Authorization", "Bearer fixture"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingToday").value(4));
        verify(avatars).upload(any(), eq("fixture"));
    }

    @Test
    void anonymousCannotUploadAndMissingFileIsBadRequest() throws Exception {
        mvc.perform(multipart("/api/auth/avatar").file(new MockMultipartFile("file", new byte[]{1})))
                .andExpect(status().isUnauthorized());
        when(auth.getCurrentUser("fixture")).thenReturn(new UserProfileVO("42", "fixture", "居民", null, UserRoleEnum.USER, "10", 1, "1"));
        mvc.perform(multipart("/api/auth/avatar").header("Authorization", "Bearer fixture"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(avatars);
    }

    @Test
    void avatarResourceIsPublicButPrivateMarkersAndOtherNamesAreNotServed() throws Exception {
        Files.write(directory.resolve(NAME), new byte[]{1, 2, 3});
        Files.writeString(directory.resolve("private.txt"), "fixture");
        mvc.perform(get("/api/media/avatars/" + NAME)).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Cache-Control", "max-age=86400, public"));
        mvc.perform(get("/api/media/avatars/private.txt")).andExpect(status().isNotFound());
        mvc.perform(get("/api/media/avatars/.cleanup/" + NAME)).andExpect(status().isNotFound());
        verifyNoInteractions(auth, avatars);
    }
}

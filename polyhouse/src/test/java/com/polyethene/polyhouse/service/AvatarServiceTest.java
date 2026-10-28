package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.AvatarUploadDTO;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 不连接开发数据库，验证图片边界和真实事务同步回调的提交/回滚补偿。 */
class AvatarServiceTest {
    private final UserMapper mapper = mock(UserMapper.class);
    private final AuthService auth = mock(AuthService.class);
    private final AvatarStorage storage = mock(AvatarStorage.class);
    private final AvatarService service = new AvatarService(mapper, auth, storage, new TestTransactions());
    private User user;
    private final String next = "/api/media/avatars/" + "a".repeat(32) + ".jpg";

    @BeforeEach
    void prepareIdentity() {
        user = new User();
        user.setId(42L);
        user.setStatus(1);
        user.setAvatar("previous-image");
        user.setExtJson(Map.of("theme", "green"));
        when(auth.getCurrentUser("fixture")).thenReturn(new UserProfileVO("42", "fixture", "居民", null,
                UserRoleEnum.USER, "10", 1, "1"));
    }

    @Test
    void reencodesAndOnlyCleansOldImageAfterCommit() throws Exception {
        when(mapper.selectOne(any())).thenReturn(user);
        when(storage.save(any())).thenReturn(next);
        when(mapper.updateById(any(User.class))).thenAnswer(call -> {
            verify(storage, never()).deleteEventually(any());
            User update = call.getArgument(0);
            assertThat(update.getRole()).isNull();
            assertThat(update.getExperience()).isNull();
            assertThat(update.getExtJson()).containsEntry("theme", "green");
            return 1;
        });
        assertThat(service.upload(image(256, 256), "fixture").remainingToday()).isEqualTo(4);
        var bytes = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(storage).save(bytes.capture());
        var decoded = ImageIO.read(new ByteArrayInputStream(bytes.getValue()));
        assertThat(decoded.getWidth()).isEqualTo(256);
        assertThat(bytes.getValue()[0] & 255).isEqualTo(255);
        verify(storage).deleteEventually("previous-image");
        verify(storage, never()).deleteEventually(next);
    }

    @Test
    void rollsBackNewFileWhenDatabaseWriteFails() throws Exception {
        when(mapper.selectOne(any())).thenReturn(user);
        when(storage.save(any())).thenReturn(next);
        when(mapper.updateById(any(User.class))).thenThrow(new IllegalStateException("fixture failure"));
        assertThatThrownBy(() -> service.upload(image(256, 256), "fixture")).isInstanceOf(IllegalStateException.class);
        verify(storage).deleteEventually(next);
        verify(storage, never()).deleteEventually("previous-image");
    }

    @Test
    void refusesSixthSuccessfulChangeBeforeSavingAnyFile() throws Exception {
        user.setExtJson(Map.of("avatarUpload", Map.of("date", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "count", 5)));
        when(mapper.selectOne(any())).thenReturn(user);
        assertThatThrownBy(() -> service.upload(image(256, 256), "fixture")).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(42900));
        verifyNoInteractions(storage);
    }

    @Test
    void doesNotConsumeQuotaOnFileFailureAndResetsYesterdayQuota() throws Exception {
        user.setExtJson(Map.of("theme", "green", "avatarUpload", Map.of("date", "2020-01-01", "count", 5)));
        when(mapper.selectOne(any())).thenReturn(user);
        when(storage.save(any())).thenThrow(new BusinessException(com.polyethene.polyhouse.common.constant.ErrorCode.SERVICE_UNAVAILABLE))
                .thenReturn(next);
        when(mapper.updateById(any(User.class))).thenReturn(1);
        assertThatThrownBy(() -> service.upload(image(256, 256), "fixture")).isInstanceOf(BusinessException.class);
        verify(mapper, never()).updateById(any(User.class));
        verify(storage, never()).deleteEventually(any());
        // 失败后在同一身份重试，只有成功这次计数；昨天的额度不能阻止今天保存。
        assertThat(service.upload(image(256, 256), "fixture").remainingToday()).isEqualTo(4);
        var update = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(mapper).updateById(update.capture());
        assertThat(update.getValue().getExtJson()).containsEntry("theme", "green")
                .containsEntry("avatarUpload", Map.of("date", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "count", 1));
        verify(storage).deleteEventually("previous-image");
    }

    @Test
    void rejectsDisguisedFilesWrongDimensionsAndOversizeBeforeStorage() throws Exception {
        for (AvatarUploadDTO input : new AvatarUploadDTO[]{image(512, 256),
                new AvatarUploadDTO(new MockMultipartFile("file", "fake.jpg", "image/jpeg", "<svg/>".getBytes())),
                new AvatarUploadDTO(new MockMultipartFile("file", new byte[256 * 1024 + 1]))}) {
            assertThatThrownBy(() -> service.upload(input, "fixture")).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(storage, mapper);
    }

    @Test
    void rejectsAccountDeletedWhileImageWasDecoded() throws Exception {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.upload(image(256, 256), "fixture")).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(40100));
        verifyNoInteractions(storage);
    }

    private static AvatarUploadDTO image(int width, int height) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "PNG", bytes);
        return new AvatarUploadDTO(new MockMultipartFile("file", "image.png", "image/png", bytes.toByteArray()));
    }

    /** 只模拟事务资源，事务同步回调仍由 Spring 实际管理，避免 mock 掉待验的补偿机制。 */
    private static class TestTransactions extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() { return new Object(); }
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override
        protected void doCommit(DefaultTransactionStatus status) { }
        @Override
        protected void doRollback(DefaultTransactionStatus status) { }
    }
}

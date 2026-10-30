package com.polyethene.polyhouse.service;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.dto.AvatarUploadDTO;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import com.polyethene.polyhouse.utils.AvatarImageUtils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** 图像边界和入口委托；原配额/事务补偿已改由真实 ProfileModerationIntegrationTest 覆盖。 */
class AvatarServiceTest {
    private final AuthService auth=mock(AuthService.class);
    private final ProfileModerationService moderation=mock(ProfileModerationService.class);
    private final AvatarService service=new AvatarService(auth,moderation);
    @Test void imageCodecReencodesPngIntoBoundedJpeg() throws Exception {
        var jpeg=AvatarImageUtils.reencode(png(256,256));
        assertThat(jpeg.length).isLessThan(256*1024);assertThat(jpeg[0]&255).isEqualTo(255);
        assertThat(ImageIO.read(new ByteArrayInputStream(jpeg)).getWidth()).isEqualTo(256);
    }
    @Test void rejectsSvgWrongSizeAndOversize() throws Exception {
        for(byte[] bytes:new byte[][]{png(512,256),"<svg/>".getBytes(),new byte[256*1024+1],new byte[0]})
            assertThatThrownBy(()->AvatarImageUtils.reencode(bytes)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(moderation);
    }
    @Test void imageWorkIsAfterAuthentication() throws Exception {
        when(auth.getCurrentUser("fixture")).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        assertThatThrownBy(()->service.upload(request(),"fixture")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(moderation);
    }
    @Test void initialCredentialCannotSubmit() throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(true));
        assertThatThrownBy(()->service.upload(request(),"fixture")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PASSWORD_CHANGE_REQUIRED));
        verifyNoInteractions(moderation);
    }
    @Test void validImageOnlyDelegatesAnApplicationNeverPublishes() throws Exception {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(false));var request=request();
        service.upload(request,"fixture");verify(moderation).submitAvatar(eq(request),any(byte[].class),eq("fixture"));
    }
    @Test void missingFileRefusedBeforeApplication() {
        when(auth.getCurrentUser("fixture")).thenReturn(profile(false));
        assertThatThrownBy(()->service.upload(null,"fixture")).isInstanceOf(BusinessException.class);verifyNoInteractions(moderation);
    }
    private AvatarUploadDTO request() throws Exception { return new AvatarUploadDTO(new MockMultipartFile("file",png(256,256)),UUID.randomUUID().toString()); }
    private UserProfileVO profile(boolean first){return new UserProfileVO("42","fixture","测试",null,UserRoleEnum.USER,"0",1,"0",first);}
    private byte[] png(int w,int h) throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB),"PNG",out);return out.toByteArray();}
}

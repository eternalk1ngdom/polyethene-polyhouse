package com.polyethene.polyhouse.user.service;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.user.fixture.AvatarFixture;
import com.polyethene.polyhouse.utils.AvatarImageUtils;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class AvatarImageUtilsTest {
    @Test
    void acceptsBrowserWebpAndRejectsNonCanonicalOrOversizedContent() throws Exception {
        String image=AvatarFixture.webp("blue");
        assertThat(AvatarImageUtils.decode(image)).isEqualTo(Base64.getDecoder().decode(image));
        for (String invalid : new String[]{"", "data:image/webp;base64,"+image, image+"\n",
                "A".repeat(60_001), Base64.getEncoder().encodeToString(new byte[20_481])}) {
            assertThatThrownBy(()->AvatarImageUtils.decode(invalid)).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void rejectsWrongCanvasSizeAnimationAndTruncatedPayload() throws Exception {
        byte[] image=Base64.getDecoder().decode(AvatarFixture.webp("blue"));
        for (int index : new int[]{4, 20, 24}) {
            byte[] bad=image.clone();
            bad[index]^=1;
            assertThatThrownBy(()->AvatarImageUtils.decode(Base64.getEncoder().encodeToString(bad)))
                .isInstanceOf(BusinessException.class);
        }
    }
}

package com.polyethene.polyhouse.user.service;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.user.fixture.AvatarFixture;
import com.polyethene.polyhouse.utils.AvatarImageUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 校验静态 WebP Base64 解码与文件头尺寸/动画/截断安全防线。 */
class AvatarImageUtilsTest {

    @Test
    void acceptsCanonicalBrowserWebp() throws Exception {
        String image = AvatarFixture.webp("blue");
        assertThat(AvatarImageUtils.decode(image)).isEqualTo(Base64.getDecoder().decode(image));
    }

    @ParameterizedTest(name = "拒绝非法或超限头像载荷：{0}")
    @MethodSource("invalidEncodedPayloads")
    void rejectsNonCanonicalOrOversizedContent(String label, String payload) {
        assertThatThrownBy(() -> AvatarImageUtils.decode(payload)).isInstanceOf(BusinessException.class);
    }

    private static Stream<Arguments> invalidEncodedPayloads() throws Exception {
        String image = AvatarFixture.webp("blue");
        return Stream.of(
                Arguments.of("空字符串", ""),
                Arguments.of("携带 data URI 前缀", "data:image/webp;base64," + image),
                Arguments.of("尾随换行符", image + "\n"),
                Arguments.of("超过 60000 Base64 字符上限", "A".repeat(60_001)),
                Arguments.of("解码后超过 20 KiB 字节上限", Base64.getEncoder().encodeToString(new byte[20_481]))
        );
    }

    @ParameterizedTest(name = "篡改 WebP 头字节偏移 {0} 时拒绝")
    @ValueSource(ints = {4, 20, 24})
    void rejectsTamperedHeaderSizeAnimationOrTruncatedPayload(int index) throws Exception {
        byte[] image = Base64.getDecoder().decode(AvatarFixture.webp("blue"));
        byte[] bad = image.clone();
        bad[index] ^= 1;
        assertThatThrownBy(() -> AvatarImageUtils.decode(Base64.getEncoder().encodeToString(bad)))
                .isInstanceOf(BusinessException.class);
    }
}

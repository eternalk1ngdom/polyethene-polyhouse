package com.polyethene.polyhouse.user.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Chromium Canvas 实际生成的静态 WebP，供 HTTP 和数据库隔离测试共享。 */
public final class AvatarFixture {
    private AvatarFixture() { }

    /** 返回测试图片原始 Base64，不引入图像编码器依赖。 */
    public static String webp(String name) throws IOException {
        try (var input = AvatarFixture.class.getResourceAsStream("/avatar/" + name + ".base64")) {
            if (input == null) throw new IOException("Missing WebP fixture");
            return new String(input.readAllBytes(), StandardCharsets.US_ASCII).strip();
        }
    }
}

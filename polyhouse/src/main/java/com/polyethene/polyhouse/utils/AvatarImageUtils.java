package com.polyethene.polyhouse.utils;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;

import java.util.Base64;

/** 检查 Canvas 输出的小型静态 WebP；服务端不加载全尺寸图像或重新编码。 */
public final class AvatarImageUtils {
    /** 限制传入文本，校验后另以解码字节数执行实际体积上限。 */
    public static final int MAX_BASE64_LENGTH = 60_000;
    /** 与浏览器导出预算一致。 */
    public static final int MAX_IMAGE_BYTES = 20 * 1024;
    /** 只允许服务端生成这个固定媒体前缀。 */
    public static final String DATA_URL_PREFIX = "data:image/webp;base64,";

    private AvatarImageUtils() { }

    /** 接受单个有损 VP8 帧，以及 Canvas 导出时可能附带的颜色配置。 */
    public static byte[] decode(String base64) {
        if (base64 == null || base64.isEmpty() || base64.length() > MAX_BASE64_LENGTH) throw invalid();
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (bytes.length < 32 || bytes.length > MAX_IMAGE_BYTES
            || !Base64.getEncoder().encodeToString(bytes).equals(base64)
            || !tag(bytes, 0, "RIFF") || le(bytes, 4, 4) != bytes.length - 8
            || !tag(bytes, 8, "WEBP")) throw invalid();
        int offset = 12;
        if (tag(bytes, offset, "VP8X")) {
            // 仅接受 Chromium 等编码器附带的 ICC 配置，拒绝动画、透明通道与其他元数据。
            if (bytes.length < 38 || le(bytes, 16, 4) != 10 || le(bytes, 20, 4) != 0x20
                || le(bytes, 24, 3) != 159 || le(bytes, 27, 3) != 159
                || !tag(bytes, 30, "ICCP")) throw invalid();
            int colorProfileSize = le(bytes, 34, 4);
            if (colorProfileSize < 1 || colorProfileSize > bytes.length - 38) throw invalid();
            offset = 38 + colorProfileSize + (colorProfileSize & 1);
        }
        if (bytes.length - offset < 19 || !tag(bytes, offset, "VP8 ")) throw invalid();
        int size = le(bytes, offset + 4, 4);
        int frame = le(bytes, offset + 8, 3);
        if (size < 11 || size > MAX_IMAGE_BYTES || offset + 8 + size + (size & 1) != bytes.length
            || ((size & 1) != 0 && bytes[bytes.length - 1] != 0)
            || (frame & 1) != 0 || ((frame >>> 1) & 7) > 3 || (frame & 16) == 0
            || (frame >>> 5) == 0 || (frame >>> 5) > size - 10
            || le(bytes, offset + 11, 3) != 0x2a019d
            || le(bytes, offset + 14, 2) != 160 || le(bytes, offset + 16, 2) != 160) throw invalid();
        return bytes;
    }

    /** 旧磁盘地址保留在库中供迁移核对，展示时回退为默认头像。 */
    public static String displaySource(String stored) {
        if (stored == null || !stored.startsWith("UklGR") || stored.length() > MAX_BASE64_LENGTH) return null;
        return DATA_URL_PREFIX + stored;
    }

    private static boolean tag(byte[] bytes, int offset, String tag) {
        for (int i = 0; i < tag.length(); i++) if (bytes[offset + i] != tag.charAt(i)) return false;
        return true;
    }

    // RIFF 与 VP8 头部使用小端序；每次读取只发生在最短长度校验之后。
    private static int le(byte[] bytes, int offset, int count) {
        int value = 0;
        for (int i = 0; i < count; i++) value |= (bytes[offset + i] & 255) << (i * 8);
        return value;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_PARAMETER, "头像必须是 160×160、20 KiB 以内的 WebP");
    }
}

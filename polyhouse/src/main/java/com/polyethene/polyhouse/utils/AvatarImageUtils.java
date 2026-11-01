package com.polyethene.polyhouse.utils;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
/** 对小头像字节做无状态纯转换，不读取磁盘、网络或 Spring Bean。 */
public final class AvatarImageUtils {
    private static final int SIZE=256;
    private AvatarImageUtils() { }
    /** 仅接收有界字节；先验头部和像素，再解码并清除元数据，防止伪装格式与超大图。 */
    public static byte[] reencode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw invalidImage();
        if (bytes.length > 256 * 1024) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
        try {
            try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalidImage();
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    String format = reader.getFormatName();
                    if ((!format.equalsIgnoreCase("JPEG") && !format.equalsIgnoreCase("PNG"))
                            || reader.getWidth(0) != SIZE || reader.getHeight(0) != SIZE) throw invalidImage();
                    BufferedImage image = reader.read(0);
                    BufferedImage clean = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
                    var graphics = clean.createGraphics();
                    try {
                        graphics.setColor(Color.WHITE);
                        graphics.fillRect(0, 0, SIZE, SIZE);
                        graphics.drawImage(image, 0, 0, null);
                    } finally {
                        graphics.dispose();
                    }
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    try (var encoded = new javax.imageio.stream.MemoryCacheImageOutputStream(output)) {
                        var writer = ImageIO.getImageWritersByFormatName("JPEG").next();
                        try {
                            writer.setOutput(encoded);
                            writer.write(clean);
                        } finally {
                            writer.dispose();
                        }
                    }
                    return output.toByteArray();
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalidImage();
        }
    }

    private static BusinessException invalidImage() {
        return new BusinessException(ErrorCode.INVALID_PARAMETER, "请上传裁剪后的 256×256 JPEG 或 PNG 图片");
    }
}

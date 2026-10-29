package com.polyethene.polyhouse.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.AvatarUploadDTO;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.model.vo.AvatarVO;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/** 统一处理头像校验、自己的资料更新、每日额度和事务后的文件补偿。 */
@Service
public class AvatarService {
    private static final int MAX_BYTES = 256 * 1024;
    private static final int SIZE = 256;
    private static final int DAILY_LIMIT = 5;
    private static final String QUOTA_KEY = "avatarUpload";
    private final UserMapper mapper;
    private final AuthService auth;
    private final AvatarStorage storage;
    private final TransactionTemplate transaction;

    /** 文件存储不参与 MySQL 事务，使用事务完成回调决定清理新图还是旧图。 */
    public AvatarService(UserMapper mapper, AuthService auth, AvatarStorage storage, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.auth = auth;
        this.storage = storage;
        transaction = new TransactionTemplate(manager);
    }

    /** 用户编号只能从有效会话取得；行锁内再次核对会话，使注销和并发替换串行完成。 */
    public AvatarVO upload(AvatarUploadDTO request, String token) {
        UserProfileVO profile = auth.getCurrentUser(token);
        if (profile.passwordChangeRequired()) throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
        byte[] jpeg = reencode(request == null ? null : request.file());
        return transaction.execute(status -> {
            User user = mapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, Long.parseLong(profile.id()))
                    .last("FOR UPDATE"));
            if (user == null || !Integer.valueOf(1).equals(user.getStatus())) throw new BusinessException(ErrorCode.UNAUTHORIZED);
            if (auth.getCurrentUser(token).passwordChangeRequired()) {
                throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
            }
            String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
            Map<String, Object> extra = user.getExtJson() == null ? new HashMap<>() : new HashMap<>(user.getExtJson());
            int count = usedToday(extra.get(QUOTA_KEY), today);
            if (count >= DAILY_LIMIT) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "今天已修改 5 次头像，请明天再试");
            String next = storage.save(jpeg);
            String previous = user.getAvatar();
            // 回滚包括提交失败；成功提交才删除旧图，任何数据库失败都保留原资料和原文件。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int completion) {
                    // UNKNOWN 可能已经提交，宁可暂留两张也不能误删数据库可能引用的新图。
                    if (completion == STATUS_COMMITTED) storage.deleteEventually(previous);
                    else if (completion == STATUS_ROLLED_BACK) storage.deleteEventually(next);
                }
            });
            extra.put(QUOTA_KEY, Map.of("date", today, "count", count + 1));
            User update = new User();
            update.setId(user.getId());
            update.setAvatar(next);
            update.setExtJson(extra);
            // 局部实体写入只更新头像和额度，不把角色、经验或硬币的旧快照写回。
            if (mapper.updateById(update) != 1) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            return new AvatarVO(next, DAILY_LIMIT - count - 1);
        });
    }

    private static int usedToday(Object value, String today) {
        if (!(value instanceof Map<?, ?> quota) || !today.equals(quota.get("date"))) return 0;
        if (!(quota.get("count") instanceof Number count) || count.intValue() < 0) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return count.intValue();
    }

    private static byte[] reencode(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.INVALID_PARAMETER, "请选择图片");
        if (file.getSize() > MAX_BYTES) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
        try {
            // 内存流避免 ImageIO 默认临时缓存占用系统盘；先读取图片头，确认尺寸再分配像素。
            byte[] bytes = file.getBytes();
            if (bytes.length > MAX_BYTES) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
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

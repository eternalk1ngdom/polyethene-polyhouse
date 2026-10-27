package com.polyethene.polyhouse.service.impl;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.AvatarWebConfig;
import com.polyethene.polyhouse.service.AvatarStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** 小头像保存到独立持久目录；不保留原图，不占用源码、Git 或 C 盘临时上传空间。 */
@Component
public class LocalAvatarStorage implements AvatarStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalAvatarStorage.class);
    private final Path root;

    /** 路径来自运行配置，空值仅使上传不可用，不回退到系统临时目录。 */
    public LocalAvatarStorage(@Value("${polyhouse.avatar.directory:}") String directory) {
        root = directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
    }

    /** CREATE_NEW 防止覆盖现有图片；失败时补偿清理可能写了一半的新文件。 */
    @Override
    public String save(byte[] jpeg) {
        if (root == null) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "头像存储尚未配置");
        String name = UUID.randomUUID().toString().replace("-", "") + ".jpg";
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw new IOException("unsafe directory");
            Files.write(root.resolve(name), jpeg, StandardOpenOption.CREATE_NEW);
            return AvatarWebConfig.URL_PREFIX + name;
        } catch (IOException exception) {
            deleteEventually(AvatarWebConfig.URL_PREFIX + name);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "头像暂时无法保存，请稍后重试");
        }
    }

    /** 外部旧默认头像不属于本机存储，绝不访问或删除外部地址。 */
    @Override
    public void deleteEventually(String avatar) {
        if (root == null || avatar == null || !avatar.startsWith(AvatarWebConfig.URL_PREFIX)) return;
        String name = avatar.substring(AvatarWebConfig.URL_PREFIX.length());
        if (!AvatarWebConfig.isFileName(name)) return;
        try {
            Files.deleteIfExists(root.resolve(name));
            Files.deleteIfExists(root.resolve(".cleanup").resolve(name));
        } catch (IOException exception) {
            // 删除失败记录在磁盘，进程重启后仍可重试；不输出用户地址和底层异常内容。
            try {
                Files.createDirectories(root.resolve(".cleanup"));
                Files.write(root.resolve(".cleanup").resolve(name), new byte[0]);
            } catch (IOException markerFailure) {
                LOGGER.warn("Avatar cleanup marker could not be saved: type={}", markerFailure.getClass().getSimpleName());
            }
            LOGGER.warn("Avatar cleanup deferred: type={}", exception.getClass().getSimpleName());
        }
    }

    /** 每分钟限量处理失败删除；标记不可公开访问，也不扫描或删除仍在使用的图片。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void retryCleanup() {
        if (root == null || !Files.isDirectory(root.resolve(".cleanup"), LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.list(root.resolve(".cleanup"))) {
            files.limit(32).map(path -> path.getFileName().toString()).filter(AvatarWebConfig::isFileName)
                    .forEach(name -> deleteEventually(AvatarWebConfig.URL_PREFIX + name));
        } catch (IOException exception) {
            LOGGER.warn("Avatar cleanup scan deferred: type={}", exception.getClass().getSimpleName());
        }
    }
}

package com.polyethene.polyhouse.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** 仅公开随机名称的小头像，不把整个上传目录或清理队列作为网站资源。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AvatarWebConfig implements WebMvcConfigurer {
    /** 同一前缀用于资料地址与静态资源注册，开发代理可直接复用现有 /api。 */
    public static final String URL_PREFIX = "/api/media/avatars/";
    private final String directory;

    /** 没有配置持久目录时不映射本机任意目录，账号其他功能仍可启动。 */
    public AvatarWebConfig(@Value("${polyhouse.avatar.directory:}") String directory) {
        this.directory = directory;
    }

    /** 随机版本地址允许缓存；新上传更换地址，所以不会一直显示浏览器缓存的旧图。 */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (directory.isBlank()) return;
        Path root = Path.of(directory).toAbsolutePath().normalize();
        String location = root.toUri().toString();
        registry.addResourceHandler(URL_PREFIX + "**")
                .addResourceLocations(location.endsWith("/") ? location : location + "/")
                .setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .resourceChain(false).addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 固定字符集和长度拒绝路径穿越、隐藏清理标记以及符号链接。
                        if (!isFileName(resourcePath) || Files.isSymbolicLink(root.resolve(resourcePath))) return null;
                        return super.getResource(resourcePath, location);
                    }
                });
    }

    /** 前后端使用同一格式，只有服务端 UUID 生成的 JPEG 可以公开访问和清理。 */
    public static boolean isFileName(String name) {
        return name != null && name.matches("[a-f0-9]{32}\\.jpg");
    }
}

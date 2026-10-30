package com.polyethene.polyhouse.service;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.AvatarWebConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Predicate;

/** 单机私有待审目录为公开头像目录的同级目录，没有静态资源映射，容量与清理独立。 */
@Component public class PrivateProfileImages {
    private static final Logger LOG=LoggerFactory.getLogger(PrivateProfileImages.class);
    private final Path root;
    /** 沿用已配置磁盘，不回退系统盘；私有目录绝不能位于公开目录内部。 */
    public PrivateProfileImages(@Value("${polyhouse.avatar.directory:}") String publicDirectory) {
        Path publicRoot=publicDirectory.isBlank()?null:Path.of(publicDirectory).toAbsolutePath().normalize();
        root=publicRoot==null || publicRoot.getFileName()==null?null:publicRoot.resolveSibling(publicRoot.getFileName()+"-pending");
    }
    /** 单 JVM 串行检查 512 张上限；单张最多 256KiB，无限重试不应无限占磁盘。 */
    public synchronized String save(byte[] bytes) {
        if(bytes==null || bytes.length==0 || bytes.length>256*1024) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        String name=UUID.randomUUID().toString().replace("-","")+".jpg";
        try {
            safeRoot(true);
            try(var files=Files.list(root)) { if(files.filter(p->AvatarWebConfig.isFileName(p.getFileName().toString())).limit(512).count()>=512)
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"待审存储已满，请先处理旧申请"); }
            Files.write(root.resolve(name),bytes,StandardOpenOption.CREATE_NEW);
            return name;
        } catch(IOException e) { deleteEventually(name);throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    }
    /** 私有资源只通过业务鉴权后调用；拒绝符号链接、目录和超过服务上限的文件。 */
    public byte[] read(String name) {
        try {
            safeRoot(false);if(!AvatarWebConfig.isFileName(name))throw new IOException("invalid key");
            Path file=root.resolve(name);
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>256*1024)throw new IOException("invalid file");
            // NOFOLLOW_LINKS 在打开时再拒绝叶节点链接，不只依赖检查时刻。
            try(var channel=Files.newByteChannel(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                var output=new java.io.ByteArrayOutputStream();var buffer=java.nio.ByteBuffer.allocate(8192);
                while(channel.read(buffer)>0) { buffer.flip();if(output.size()+buffer.remaining()>256*1024)throw new IOException("oversize");
                    output.write(buffer.array(),0,buffer.remaining());buffer.clear(); }
                if(output.size()==0)throw new IOException("empty image");
                return output.toByteArray();
            }
        } catch(IOException e) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"待审图片暂时无法读取"); }
    }
    /** 删除失败落磁盘标记，提交失败补偿与批准/驳回/替代后的清理共用安全路径。 */
    public void deleteEventually(String name) {
        if(root==null || !AvatarWebConfig.isFileName(name))return;
        try {
            safeRoot(false);Files.deleteIfExists(root.resolve(name));
            Path markers=root.resolve(".cleanup");if(Files.isSymbolicLink(markers))throw new IOException("unsafe marker directory");
            Files.deleteIfExists(markers.resolve(name));
        } catch(IOException e) {
            try { safeRoot(true);Path markers=root.resolve(".cleanup");if(Files.isSymbolicLink(markers))throw new IOException("unsafe marker directory");
                Files.createDirectories(markers);Files.write(markers.resolve(name),new byte[0],StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS); }
            catch(IOException failure) { LOG.warn("Private avatar cleanup marker deferred: type={}",failure.getClass().getSimpleName()); }
        }
    }
    /** 每轮限量重试与回收 24 小时以上的无引用孤儿，数据库不可用时不擅自删图。 */
    public void sweep(Predicate<String> isReferenced) {
        if(root==null || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))return;
        try {
            safeRoot(false);Path markers=root.resolve(".cleanup");
            if(Files.isDirectory(markers,LinkOption.NOFOLLOW_LINKS))try(var entries=Files.list(markers)) {
                entries.limit(32).map(p->p.getFileName().toString()).filter(AvatarWebConfig::isFileName)
                    .filter(name->!isReferenced.test(name)).forEach(this::deleteEventually);
            }
            try(var entries=Files.list(root)) {
                // 上限扫描与容量一致；只扫描服务自身生成的键，不访问任意外部路径。
                for(Path file:entries.limit(512).toList()) {
                    String name=file.getFileName().toString();
                    if(AvatarWebConfig.isFileName(name) && Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(Instant.now().minus(24,ChronoUnit.HOURS))
                        && !isReferenced.test(name))deleteEventually(name);
                }
            }
        } catch(IOException e) { LOG.warn("Private avatar cleanup scan deferred: type={}",e.getClass().getSimpleName()); }
    }
    private void safeRoot(boolean create) throws IOException {
        if(root==null)throw new IOException("directory unavailable");
        // 管理员配置中的祖先也不能是符号链接，避免私有目录被间接映射到公开路径。
        for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("unsafe ancestor");
        if(create)Files.createDirectories(root);
        if(Files.isSymbolicLink(root))throw new IOException("unsafe root");
    }
}

package com.polyethene.polyhouse.utils;

import cn.hutool.crypto.digest.BCrypt;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 封装本项目的 BCrypt 密码处理，不持有用户状态、不访问数据库或 Redis，也不记录凭据。
 * 哈希生成必须使用随机盐，因此本类只保证无业务状态，不保证相同输入产生相同哈希。
 */
public final class PasswordUtils {

    private static final int BCRYPT_COST = 10;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final int BCRYPT_HASH_LENGTH = 60;

    /**
     * 整串匹配版本、项目成本参数及 BCrypt 字符集；53 位分别为 22 位盐和 31 位摘要。
     * 仅接收当前 cost=10 的 2a/2b/2y 格式，避免损坏或外部传入的高成本哈希触发超量计算。
     * 将来修改成本参数时必须明确设计旧哈希兼容与迁移，不能直接放开任意成本。
     */
    private static final Pattern SUPPORTED_HASH = Pattern.compile(
            "\\A\\$2[aby]\\$" + BCRYPT_COST + "\\$[./A-Za-z0-9]{53}\\z");

    private PasswordUtils() {
    }

    /**
     * 为有效密码生成独立随机盐的 BCrypt 哈希，返回值可直接交给 Service 保存到 password_hash。
     * 不裁剪空格、不归一化 Unicode，不接收外部盐，防止改变密码或复用固定盐。
     *
     * @param rawPassword 原始密码，至少 8 个 UTF-16 字符单位且 UTF-8 编码不超过 72 字节
     * @return 包含算法版本、成本、盐和摘要的完整哈希；不包含明文密码
     * @throws IllegalArgumentException 输入为空、空白、过短、超长或包含不完整的 Unicode 字符时抛出
     */
    public static String hashPassword(String rawPassword) {
        if (!isValidPassword(rawPassword)) {
            // 异常消息固定，不将输入值附加到消息或异常链中。
            throw new IllegalArgumentException("password must be non-blank, at least 8 characters, "
                    + "and valid UTF-8 of at most 72 bytes");
        }
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(BCRYPT_COST));
    }

    /**
     * 用保存的完整哈希核验密码，复用其中的盐和成本，由 Hutool 完成哈希结果比较。
     * 输入无效、哈希缺失、格式不受支持或密码不匹配时统一返回 false，业务层负责模糊登录提示。
     *
     * @param rawPassword 用户原样输入的密码，不得先做 trim 或 Unicode 归一化
     * @param storedHash 数据库保存的完整哈希；目前只支持 cost=10 的 2a/2b/2y 格式
     * @return 输入与受支持的哈希匹配时返回 true，其余可预期无效情况返回 false
     */
    public static boolean matches(String rawPassword, String storedHash) {
        if (!isValidPassword(rawPassword) || storedHash == null
                || storedHash.length() != BCRYPT_HASH_LENGTH
                || !SUPPORTED_HASH.matcher(storedHash).matches()) {
            return false;
        }
        return BCrypt.checkpw(rawPassword, storedHash);
    }

    private static boolean isValidPassword(String rawPassword) {
        // UTF-8 字节数不少于 UTF-16 单位数，先限长可避免为超长输入分配编码缓冲区。
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH
                || rawPassword.length() > MAX_PASSWORD_BYTES || rawPassword.isBlank()) {
            return false;
        }
        // 拒绝孤立代理字符，避免默认编码替换成 '?' 后让不同输入退化为相同密码。
        return StandardCharsets.UTF_8.newEncoder().canEncode(rawPassword)
                && rawPassword.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }
}

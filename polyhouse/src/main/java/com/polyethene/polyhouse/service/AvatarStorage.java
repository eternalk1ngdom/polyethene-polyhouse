package com.polyethene.polyhouse.service;

/** 隔离文件保存与账号事务，未来 OSS 实现可复用相同的业务调用。 */
public interface AvatarStorage {
    /** 保存已安全重新编码的 JPEG，返回随机版本地址，不保存原始文件名。 */
    String save(byte[] jpeg);

    /** 只清理本存储管理的地址；失败保留重试记录，不推翻已提交的资料。 */
    void deleteEventually(String avatar);
}

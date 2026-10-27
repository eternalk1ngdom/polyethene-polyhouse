package com.polyethene.polyhouse.model.vo;

/** 仅交付头像变更，避免用上传期间的旧成长资料覆盖浏览器的新资料。 */
public record AvatarVO(
        /** 服务端生成的版本化图片地址，不含磁盘路径。 */
        String avatar,
        /** 上海自然日剩余成功修改次数，失败和取消不消耗额度。 */
        int remainingToday) {
}

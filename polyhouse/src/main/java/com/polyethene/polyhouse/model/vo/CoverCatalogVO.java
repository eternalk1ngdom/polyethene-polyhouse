package com.polyethene.polyhouse.model.vo;

import java.util.List;

/** @param userId 用于隔离客户端账号快照
 * @param coins 本次结算后的十进制余额
 * @param equipped 已复核资格的佩戴款
 * @param quoteVersion 用于确认购买的报价版本
 * @param covers 当前上架的款式及资格 */
public record CoverCatalogVO(String userId, String coins, String equipped, String quoteVersion, List<Item> covers) {
    /** @param key 客户端素材编号
     * @param reason 可用原因或尚缺的条件
     * @param price 购买所需硬币
     * @param missingExperience 解锁尚缺经验 */
    public record Item(String key, String reason, int price, long missingExperience) {
        /** 交易和目录共用同一个资格判断。 */
        public boolean available() {
            return !reason.equals("coins") && !reason.equals("levelRequired");
        }
    }
}

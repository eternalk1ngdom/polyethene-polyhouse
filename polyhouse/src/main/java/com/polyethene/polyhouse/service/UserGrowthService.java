package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.model.vo.UserGrowthVO;

/** 独立管理受控的账号成长结算，认证只在验证成功后调用，不接受浏览器自报奖励数值。 */
public interface UserGrowthService {

    /**
     * 为已核验身份结算当日登录奖励，并返回锁定读得到的最新内部用户资料。
     * 只允许可信业务调用；重复调用、多端并发只入账一次，普通资料仍须转换为 VO 后输出。
     */
    User rewardDailyLogin(long userId);

    /** Token 认证在同一用户锁内先核对凭据版本再结算，旧 Token 不能先触发奖励后才被拒绝。 */
    User rewardDailyLogin(long userId, long credentialVersion);

    /** 读取同一用户锁下的成长及当日流水快照；此方法自身不插入流水或发放奖励。 */
    UserGrowthVO getStatus(long userId);

    /** 在成长快照锁内核对 Token 版本和首次改密限制，避免并发改密后继续读受保护资料。 */
    UserGrowthVO getStatus(long userId, long credentialVersion);
}

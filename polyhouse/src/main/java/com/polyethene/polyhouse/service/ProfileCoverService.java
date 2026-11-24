package com.polyethene.polyhouse.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.common.security.SessionSecurity;
import com.polyethene.polyhouse.config.JwtProperties;
import com.polyethene.polyhouse.mapper.ProfileCoverMapper;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.CoverPurchaseDTO;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.model.vo.CoverCatalogVO;
import com.polyethene.polyhouse.model.vo.CoverCatalogVO.Item;
import com.polyethene.polyhouse.utils.JwtUtils;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;

/** 用户行串行化成长、购买与佩戴；临时角色资格不登记成永久购买。 */
@Service
@Transactional
public class ProfileCoverService {
    private static final int PRICE = 10;
    private static final String VERSION = "1";
    private final UserMapper users;
    private final ProfileCoverMapper covers;
    private final AuthRedisStore sessions;
    private final JwtProperties jwt;
    private final Clock clock;

    /** 把会话复核和经济操作放在同一个账号行锁内。 */
    public ProfileCoverService(UserMapper users, ProfileCoverMapper covers, AuthRedisStore sessions,
                               JwtProperties jwt, Clock clock) {
        this.users = users;
        this.covers = covers;
        this.sessions = sessions;
        this.jwt = jwt;
        this.clock = clock;
    }

    private User lock(String token) {
        JwtUtils.TokenClaims claims;
        try {
            claims = JwtUtils.parseAndValidate(token, jwt.signingKey(), clock.instant());
        } catch (JwtException failure) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        User user = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, claims.userId()).last("FOR UPDATE"));
        SessionSecurity.requireCurrent(user, claims.credentialVersion());
        SessionSecurity.requireFullAccess(user);
        if (!sessions.isCurrentSession(claims)) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return user;
    }

    /** 目录同时纠正失效偏好，避免身份恢复后自动换回旧款。 */
    public CoverCatalogVO catalog(String token) {
        return snapshot(lock(token));
    }

    private CoverCatalogVO snapshot(User user) {
        var items = options(user, covers.hasAstrolabe(user.getId()) > 0);
        String saved = covers.selected(user.getId());
        String equipped = items.stream().filter(Item::available).map(Item::key)
                .filter(key -> key.equals(saved)).findFirst().orElse("window");
        if (saved != null && !equipped.equals(saved)) covers.equip(user.getId(), equipped);
        return new CoverCatalogVO(user.getId().toString(), user.getCoins().toString(), equipped, VERSION, items);
    }

    static List<Item> options(User user, boolean purchased) {
        boolean privileged = user.getRole() == UserRoleEnum.INSIDER || user.getRole() == UserRoleEnum.OWNER;
        long missing = Math.max(0, UserGrowthRules.experienceForLevel(3) - user.getExperience());
        String brass = purchased ? "purchased" : privileged ? "role" : "coins";
        String koi = missing == 0 ? "level" : privileged ? "role" : "levelRequired";
        return List.of(new Item("window", "default", 0, 0), new Item("astrolabe", brass, PRICE, 0),
                new Item("koi", koi, 0, missing));
    }

    /** 重试已购或身份开放的星盘直接返回，不产生第二笔支出。 */
    public CoverCatalogVO purchase(String token, CoverPurchaseDTO request) {
        User user = lock(token);
        if (!"astrolabe".equals(request.coverKey())) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        var before = snapshot(user);
        if (item(before, "astrolabe").available()) return before;
        if (!VERSION.equals(request.quoteVersion())) throw new BusinessException(ErrorCode.COVER_QUOTE_CHANGED);
        if (covers.debit(user.getId(), PRICE) != 1) throw new BusinessException(ErrorCode.COVER_COINS_INSUFFICIENT);
        covers.recordPurchase(user.getId(), PRICE);
        user.setCoins(user.getCoins() - PRICE);
        return snapshot(user);
    }

    /** 所有客户端包括旧页面都必须重新通过当前的佩戴资格。 */
    public CoverCatalogVO equip(String token, String key) {
        User user = lock(token);
        var before = snapshot(user);
        if (!item(before, key).available()) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (!before.equipped().equals(key)) covers.equip(user.getId(), key);
        return snapshot(user);
    }

    private static Item item(CoverCatalogVO catalog, String key) {
        return catalog.covers().stream().filter(entry -> entry.key().equals(key)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PARAMETER));
    }
}

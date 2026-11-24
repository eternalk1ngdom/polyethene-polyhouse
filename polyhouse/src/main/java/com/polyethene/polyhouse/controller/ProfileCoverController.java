package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.vo.CoverCatalogVO;
import com.polyethene.polyhouse.service.ProfileCoverService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 身份来自统一 Bearer 参数解析器，私有响应沿用拦截器的禁止缓存策略。 */
@RestController
@RequestMapping("/api/profile/covers")
public class ProfileCoverController {
    private final ProfileCoverService service;

    /** 统一在事务服务内复核当前账号。 */
    public ProfileCoverController(ProfileCoverService service) { this.service = service; }

    /** 返回本人可选款和当前佩戴。 */
    @GetMapping
    public Result<CoverCatalogVO> catalog(@Valid SessionTokenDTO session) {
        return Result.success(service.catalog(session.token()));
    }

    /** 购买与应用拆开，成功后仍保留原佩戴。 */
    @PostMapping("/purchase")
    public Result<CoverCatalogVO> purchase(@Valid SessionTokenDTO session, @Valid @RequestBody CoverPurchaseDTO request) {
        return Result.success(service.purchase(session.token(), request));
    }

    /** 持久化已通过资格校验的选择。 */
    @PostMapping("/equip")
    public Result<CoverCatalogVO> equip(@Valid SessionTokenDTO session, @Valid @RequestBody CoverEquipDTO request) {
        return Result.success(service.equip(session.token(), request.coverKey()));
    }
}

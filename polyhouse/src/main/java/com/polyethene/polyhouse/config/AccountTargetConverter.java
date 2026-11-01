package com.polyethene.polyhouse.config;

import com.polyethene.polyhouse.model.dto.AccountTargetDTO;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** 仅转换管理路径 DTO；防止 ModelAttribute 从同名查询参数覆盖 URI 中的真实目标。 */
@Component
public class AccountTargetConverter implements Converter<String, AccountTargetDTO> {
    /** 非法/越界编号由框架统一返回 400；正数等业务校验仍由 DTO 和服务承担。 */
    @Override
    public AccountTargetDTO convert(String source) {
        return new AccountTargetDTO(Long.valueOf(source));
    }
}

package com.polyethene.polyhouse.config;
import com.polyethene.polyhouse.model.dto.ProfileResourceDTO;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
/** 把 URI 绑定为专用 DTO，避免 ModelAttribute 的查询参数覆盖。 */
@Component public class ProfileResourceConverter implements Converter<String, ProfileResourceDTO> {
    /** 非整数/越界由统一错误处理返回 400，正数校验在 DTO 与服务重复执行。 */
    @Override public ProfileResourceDTO convert(String source) { return new ProfileResourceDTO(Long.valueOf(source)); }
}

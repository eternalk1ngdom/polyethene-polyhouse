package com.polyethene.polyhouse.model.dto;
import com.polyethene.polyhouse.common.constant.*;
import jakarta.validation.constraints.*;
/** 有界队列，只向 OWNER 提供必要待审资料。
 * @param page 页码
 * @param pageSize 最多 50 项
 * @param kind 可选字段白名单
 */
public record ProfileReviewQueryDTO(@Min(1) @Max(10000) Integer page, @Min(1) @Max(50) Integer pageSize,
        ProfileField kind) {
    /** 默认小页，非空非法值仍由 Validation 拒绝。 */
    public ProfileReviewQueryDTO { page=page==null?1:page;pageSize=pageSize==null?20:pageSize; }
}

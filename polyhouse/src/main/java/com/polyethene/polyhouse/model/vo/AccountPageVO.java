package com.polyethene.polyhouse.model.vo;

import java.util.List;

/**
 * 分页总数来自与列表相同的权限条件，避免通过总数泄露隐藏账号。
 * @param items 当前页的必要资料
 * @param total 权限范围内总数，字符串避免整数精度损失
 * @param page 已校验页码
 * @param pageSize 已校验每页数量
 */
public record AccountPageVO(List<ManagedAccountVO> items, String total, int page, int pageSize) { }

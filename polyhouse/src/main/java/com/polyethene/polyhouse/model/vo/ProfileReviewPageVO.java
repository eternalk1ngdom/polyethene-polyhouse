package com.polyethene.polyhouse.model.vo;
import java.util.List;
/** 审核队列有界分页。
 * @param items 可审核的申请
 * @param total 同一筛选条件下的总数
 * @param page 已校验页码
 * @param pageSize 已校验页大小
 */
public record ProfileReviewPageVO(List<ProfileSubmissionVO> items,String total,int page,int pageSize) { }

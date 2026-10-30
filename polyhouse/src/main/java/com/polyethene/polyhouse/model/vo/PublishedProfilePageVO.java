package com.polyethene.polyhouse.model.vo;
import java.util.List;
/** 分页清点，禁止一次加载所有用户与全部图片。
 * @param items 当前页
 * @param total 同筛选条件总数
 * @param page 页码
 * @param pageSize 页大小
 */
public record PublishedProfilePageVO(List<PublishedProfileVO> items,String total,int page,int pageSize) { }

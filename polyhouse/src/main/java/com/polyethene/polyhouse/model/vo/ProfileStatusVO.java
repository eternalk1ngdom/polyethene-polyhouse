package com.polyethene.polyhouse.model.vo;
import java.util.List;
/** 本人最多十条近期记录与两种额度，公开展示仍由 /me 提供。
 * @param submissions 最近申请，不包含他人内容
 * @param avatarRemainingToday 当日头像剩余提交数
 * @param nicknameRemainingToday 当日昵称剩余提交数
 */
public record ProfileStatusVO(List<ProfileSubmissionVO> submissions,int avatarRemainingToday,int nicknameRemainingToday) { }

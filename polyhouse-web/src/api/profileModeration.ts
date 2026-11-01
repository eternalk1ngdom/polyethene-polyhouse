import { ApiError, apiRequest, apiImage, isRecord } from './index'
/** 两种资料的版本互不影响。 */
export type ProfileField = 'AVATAR' | 'NICKNAME'
/** 终态不能再次审核；历史已批准不代表仍公开。 */
export type ReviewStatus =
  'PENDING' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED' | 'EXPIRED' | 'INVALIDATED'
/** 私有路径/内容指纹不进入前端模型。 */
export interface ProfileSubmission {
  /** 精确申请编号。 */ id: string
  /** 所属用户编号。 */ userId: string
  /** 核对目标账号。 */ username: string
  /** 独立资料字段。 */ kind: ProfileField
  /** 当前申请状态。 */ status: ReviewStatus
  /** 审核必须匹配的版本。 */ version: string
  /** 提交防重编号。 */ requestId: string
  /** 昵称纯文本，头像为 null。 */ proposedValue: string | null
  /** 必要处理说明。 */ reason: string | null
  /** 上海提交时间。 */ createdAt: string
  /** 七天有效期。 */ expiresAt: string
  /** 本人提交响应的当日额度。 */ remainingToday: number
}
/** 本人只读历史和额度。 */
export interface ProfileStatus {
  /** 最近十条。 */ submissions: ProfileSubmission[]
  /** 头像剩余。 */ avatarRemainingToday: number
  /** 昵称剩余。 */ nicknameRemainingToday: number
}
/** 分页共用固定壳。 */
export interface ReviewPage<T> {
  /** 当前页。 */ items: T[]
  /** 总数保留精度。 */ total: string
  /** 页码。 */ page: number
  /** 页大小。 */ pageSize: number
}
/** 已发布字段快照仅屋主可读。 */
export interface PublishedProfile {
  /** 用户编号。 */ userId: string
  /** 账号。 */ username: string
  /** 字段。 */ kind: ProfileField
  /** 当前值。 */ value: string | null
  /** 并发版本。 */ version: string
  /** 是否经审核。 */ verified: boolean
  /** 启停状态。 */ status: 0 | 1
  /** 身份。 */ role: 'USER' | 'ADMIN' | 'INSIDER' | 'OWNER'
}
const positive = (v: unknown): v is string => typeof v === 'string' && /^[1-9]\d*$/.test(v)
const integer = (v: unknown): v is string => typeof v === 'string' && /^(0|[1-9]\d*)$/.test(v)
const field = (v: unknown): v is ProfileField => v === 'AVATAR' || v === 'NICKNAME'
const states = ['PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED', 'EXPIRED', 'INVALIDATED']
/** 不接收任意字段更新，只投影已验证契约。 */
export function decodeSubmission(v: unknown): ProfileSubmission {
  if (
    !isRecord(v) ||
    !positive(v.id) ||
    !positive(v.userId) ||
    typeof v.username !== 'string' ||
    !field(v.kind) ||
    !states.includes(String(v.status)) ||
    !positive(v.version) ||
    typeof v.requestId !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v.requestId) ||
    !(typeof v.proposedValue === 'string' || v.proposedValue === null) ||
    (v.kind === 'AVATAR' && v.proposedValue !== null) ||
    !(v.reason === null || typeof v.reason === 'string') ||
    typeof v.createdAt !== 'string' ||
    typeof v.expiresAt !== 'string' ||
    !Number.isInteger(v.remainingToday) ||
    Number(v.remainingToday) < 0 ||
    Number(v.remainingToday) > 5
  )
    throw new ApiError('申请状态不完整，请刷新核对', 502)
  return {
    id: v.id,
    userId: v.userId,
    username: v.username,
    kind: v.kind,
    status: v.status as ReviewStatus,
    version: v.version,
    requestId: v.requestId,
    proposedValue: v.proposedValue,
    reason: v.reason,
    createdAt: v.createdAt,
    expiresAt: v.expiresAt,
    remainingToday: Number(v.remainingToday),
  }
}
function decodePage<T>(v: unknown, decode: (row: unknown) => T): ReviewPage<T> {
  if (
    !isRecord(v) ||
    !Array.isArray(v.items) ||
    v.items.length > 50 ||
    !integer(v.total) ||
    !Number.isInteger(v.page) ||
    Number(v.page) < 1 ||
    !Number.isInteger(v.pageSize) ||
    Number(v.pageSize) < 1 ||
    Number(v.pageSize) > 50
  )
    throw new ApiError('审核列表不完整', 502)
  return {
    items: v.items.map(decode),
    total: v.total,
    page: Number(v.page),
    pageSize: Number(v.pageSize),
  }
}
/** 本人记录不允许附带他人编号。 */
export function getProfileStatus(token: string): Promise<ProfileStatus> {
  return apiRequest(
    { method: 'GET', url: '/auth/profile-submissions' },
    (v) => {
      if (
        !isRecord(v) ||
        !Array.isArray(v.submissions) ||
        v.submissions.length > 10 ||
        !Number.isInteger(v.avatarRemainingToday) ||
        Number(v.avatarRemainingToday) < 0 ||
        Number(v.avatarRemainingToday) > 5 ||
        !Number.isInteger(v.nicknameRemainingToday) ||
        Number(v.nicknameRemainingToday) < 0 ||
        Number(v.nicknameRemainingToday) > 2
      )
        throw new ApiError('资料申请状态不完整', 502)
      return {
        submissions: v.submissions.map(decodeSubmission),
        avatarRemainingToday: Number(v.avatarRemainingToday),
        nicknameRemainingToday: Number(v.nicknameRemainingToday),
      }
    },
    token,
  )
}
/** 昵称先审后发，同意图由服务端防重。 */
export function submitNickname(
  requestId: string,
  nickname: string,
  token: string,
): Promise<ProfileSubmission> {
  return apiRequest(
    { method: 'POST', url: '/auth/nickname/submissions', data: { requestId, nickname } },
    decodeSubmission,
    token,
  )
}
/** 屋主待审分页。 */
export function getReviews(
  page: number,
  kind: ProfileField | '',
  token: string,
): Promise<ReviewPage<ProfileSubmission>> {
  return apiRequest(
    {
      method: 'GET',
      url: '/manage/profile-reviews',
      params: { page, pageSize: 20, kind: kind || undefined },
    },
    (v) => decodePage(v, decodeSubmission),
    token,
  )
}
/** 只在显式确认后发一次决定，不自动重试。 */
export function decideReview(
  s: ProfileSubmission,
  approve: boolean,
  reason: string,
  token: string,
): Promise<ProfileSubmission> {
  return apiRequest(
    {
      method: 'POST',
      url: `/manage/profile-reviews/${s.id}/decision`,
      data: { expectedVersion: s.version, approve, reason },
    },
    decodeSubmission,
    token,
  )
}
/** Blob 由组件创建临时 URL，用毕必须撤销。 */
export function privateImage(id: string, token: string): Promise<Blob> {
  return apiImage(`/profile-submissions/${id}/image`, token)
}
function decodePublished(v: unknown): PublishedProfile {
  if (
    !isRecord(v) ||
    !positive(v.userId) ||
    typeof v.username !== 'string' ||
    !field(v.kind) ||
    !(typeof v.value === 'string' || v.value === null) ||
    !integer(v.version) ||
    typeof v.verified !== 'boolean' ||
    (v.status !== 0 && v.status !== 1) ||
    !['USER', 'ADMIN', 'INSIDER', 'OWNER'].includes(String(v.role))
  )
    throw new ApiError('公开资料清单不完整', 502)
  return {
    userId: v.userId,
    username: v.username,
    kind: v.kind,
    value: v.value,
    version: v.version,
    verified: v.verified,
    status: v.status as 0 | 1,
    role: v.role as PublishedProfile['role'],
  }
}
/** 默认先清点旧内容，也可查看已批准资料进行下架。 */
export function getPublished(
  page: number,
  kind: ProfileField,
  unverifiedOnly: boolean,
  token: string,
): Promise<ReviewPage<PublishedProfile>> {
  return apiRequest(
    {
      method: 'GET',
      url: '/manage/profile-published',
      params: { page, pageSize: 20, kind, unverifiedOnly },
    },
    (v) => decodePage(v, decodePublished),
    token,
  )
}
/** 目标版本与当前值一并确认，返回服务端最终投影。 */
export function changePublished(
  s: PublishedProfile,
  action: 'KEEP' | 'TAKEDOWN',
  reason: string,
  token: string,
): Promise<PublishedProfile> {
  return apiRequest(
    {
      method: 'POST',
      url: `/manage/accounts/${s.userId}/profile/takedown`,
      data: { kind: s.kind, expectedVersion: s.version, expectedValue: s.value, action, reason },
    },
    decodePublished,
    token,
  )
}

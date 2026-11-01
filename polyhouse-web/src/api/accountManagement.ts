import { ApiError, apiRequest, isRecord } from './index'
import type { UserProfile } from './auth'

/** 管理动作只来自固定按钮，不开放任意更新字段。 */
export type ManagementAction = 'BAN' | 'UNBAN' | 'PASSWORD_RESET'

/** 最小账号列表，不接收哈希、IP、扩展资料或凭据版本。 */
export interface ManagedAccount {
  /** 用户编号保留整数精度。 */
  id: string
  /** 系统或历史登录名，不可编辑。 */
  username: string
  /** 展示昵称，只用纯文本呈现。 */
  nickname: string
  /** 最新角色，确认时再次携带并由服务端核对。 */
  role: UserProfile['role']
  /** 0 停用、1 启用。 */
  status: 0 | 1
  /** 仅管理动作递增的并发版本。 */
  version: string
  /** 是否仍需首次改密。 */
  passwordChangeRequired: boolean
  /** 屋主自身只读，服务端仍独立拒绝修改。 */
  readOnly: boolean
  /** 数据库创建时间，不用于并发比较。 */
  createdAt: string
}

/** 分页/筛选的固定白名单，不接受客户端授权范围或排序 SQL。 */
export interface AccountQuery {
  /** 有界页码。 */
  page: number
  /** 单页不超过五十条。 */
  pageSize: number
  /** 登录名的字面前缀。 */
  username?: string
  /** 可选目标角色。 */
  role?: UserProfile['role']
  /** 可选启停状态。 */
  status?: 0 | 1
}

/** 列表与总数使用相同的服务端可见范围。 */
export interface AccountPage {
  /** 本页必要资料。 */
  items: ManagedAccount[]
  /** 总数以字符串交付，不强转超大整数。 */
  total: string
  /** 服务端确认的页码。 */
  page: number
  /** 服务端确认的单页数量。 */
  pageSize: number
}

/** 一次固定的管理意图；请求不接受修改角色、余额或当前密码哈希。 */
export interface ManagementRequest {
  /** 同一次操作使用同一 UUID。 */
  requestId: string
  /** 用户确认时的管理版本。 */
  expectedVersion: string
  /** 用户确认时的角色。 */
  expectedRole: UserProfile['role']
  /** 用户确认时的状态。 */
  expectedStatus: 0 | 1
  /** 最小必要原因，不填密码或私人信息。 */
  reason: string
  /** 仅重置及封禁管理员时再次验证屋主。 */
  ownerPassword?: string
}

/** 已提交操作的历史事实；查询不返回原密码，也不保证目标现在仍维持该状态。 */
export interface ManagementResult {
  /** 用来核对响应是否属于当前请求。 */
  requestId: string
  /** 被操作的精确编号。 */
  targetId: string
  /** 操作时登录名。 */
  username: string
  /** 已成功的动作。 */
  action: ManagementAction
  /** 成功后的管理版本。 */
  version: string
  /** 成功后的状态。 */
  status: 0 | 1
  /** 仅首次重置成功交付明文。 */
  firstDelivery: boolean
  /** 只保存在当前组件内存。 */
  temporaryPassword: string | null
}

const roles = ['USER', 'ADMIN', 'INSIDER', 'OWNER']
const actions = ['BAN', 'UNBAN', 'PASSWORD_RESET']
const integer = (value: unknown): value is string =>
  typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value)
const positiveId = (value: unknown): value is string => integer(value) && value !== '0'

/** 校验契约并丢弃额外字段，客户端只保留展示所需投影；服务端仍负责禁止泄露。 */
export function decodeAccountPage(value: unknown): AccountPage {
  if (
    !isRecord(value) ||
    !Array.isArray(value.items) ||
    value.items.length > 50 ||
    !integer(value.total) ||
    !Number.isInteger(value.page) ||
    Number(value.page) < 1 ||
    Number(value.page) > 10000 ||
    !Number.isInteger(value.pageSize) ||
    Number(value.pageSize) < 1 ||
    Number(value.pageSize) > 50
  )
    throw new ApiError('账号列表不完整，请重新加载', 502)
  const items = value.items.map((row: unknown): ManagedAccount => {
    if (
      !isRecord(row) ||
      !positiveId(row.id) ||
      typeof row.username !== 'string' ||
      typeof row.nickname !== 'string' ||
      !roles.includes(String(row.role)) ||
      (row.status !== 0 && row.status !== 1) ||
      !integer(row.version) ||
      typeof row.passwordChangeRequired !== 'boolean' ||
      typeof row.readOnly !== 'boolean' ||
      row.readOnly !== (row.role === 'OWNER') ||
      typeof row.createdAt !== 'string'
    )
      throw new ApiError('账号条目不完整，请重新加载', 502)
    return {
      id: row.id,
      username: row.username,
      nickname: row.nickname,
      role: row.role as ManagedAccount['role'],
      status: row.status,
      version: row.version,
      passwordChangeRequired: row.passwordChangeRequired,
      readOnly: row.readOnly,
      createdAt: row.createdAt,
    }
  })
  return { items, total: value.total, page: Number(value.page), pageSize: Number(value.pageSize) }
}

/** 首次重置与密码有无必须一致；未知响应按结果未确认处理。 */
export function decodeManagementResult(value: unknown): ManagementResult {
  if (
    !isRecord(value) ||
    typeof value.requestId !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value.requestId) ||
    !positiveId(value.targetId) ||
    typeof value.username !== 'string' ||
    !actions.includes(String(value.action)) ||
    !integer(value.version) ||
    (value.status !== 0 && value.status !== 1) ||
    typeof value.firstDelivery !== 'boolean' ||
    (value.firstDelivery
      ? value.action !== 'PASSWORD_RESET' ||
        typeof value.temporaryPassword !== 'string' ||
        !/^[A-Za-z2-9]{20}$/.test(value.temporaryPassword)
      : value.temporaryPassword !== null)
  )
    throw new ApiError('操作结果不完整，请核对原请求', 502)
  return {
    requestId: value.requestId,
    targetId: value.targetId,
    username: value.username,
    action: value.action as ManagementAction,
    version: value.version,
    status: value.status,
    firstDelivery: value.firstDelivery,
    temporaryPassword: value.temporaryPassword as string | null,
  }
}

/** 分页读取的权限范围始终由服务端决定。 */
export function listManagedAccounts(query: AccountQuery, token: string): Promise<AccountPage> {
  return apiRequest(
    { method: 'GET', url: '/manage/accounts', params: query },
    decodeAccountPage,
    token,
  )
}

/** 写操作不自动重发，URL 后缀只从固定动作映射产生。 */
export function manageAccount(
  id: string,
  action: ManagementAction,
  request: ManagementRequest,
  token: string,
): Promise<ManagementResult> {
  const suffix = { BAN: 'ban', UNBAN: 'unban', PASSWORD_RESET: 'password/reset' }[action]
  return apiRequest(
    { method: 'POST', url: `/manage/accounts/${id}/${suffix}`, data: request },
    decodeManagementResult,
    token,
  )
}

/** 提交未知时只核对本人的请求，不重新生成临时密码。 */
export function confirmManagementOperation(
  requestId: string,
  token: string,
): Promise<ManagementResult> {
  return apiRequest(
    { method: 'GET', url: '/manage/accounts/operations/confirmation', params: { requestId } },
    decodeManagementResult,
    token,
  )
}

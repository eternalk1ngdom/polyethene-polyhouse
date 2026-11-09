import { ApiError, apiRequest, isRecord } from './index'

/** 开户选项和响应使用同一白名单，屋主账号通过独立的初始化流程管理。 */
export const provisionedRoleLabels = {
  USER: '普通用户',
  ADMIN: '管理员',
  INSIDER: '内部用户',
} as const

export type ProvisionedRole = keyof typeof provisionedRoleLabels

/** 服务端响应和本地保存的开户意图均须符合当前可发放角色。 */
export function isProvisionedRole(value: unknown): value is ProvisionedRole {
  return typeof value === 'string' && Object.hasOwn(provisionedRoleLabels, value)
}

/** 创建意图可用于重试，密码不进入任何持久缓存。 */
export interface AccountCreateRequest {
  /** 同一次发放保持 UUID 不变。 */
  requestId: string
  /** 只能发放普通用户、管理员或内部用户。 */
  role: ProvisionedRole
  /** 仅管理员开户重新验证屋主当前密码。 */
  ownerPassword?: string
}

/** 首次响应包含临时凭据；确认/重放只有最小创建事实。 */
export interface AccountCreation {
  /** 创建的雪花编号必须保持字符串。 */
  id: string
  /** 系统分配的登录名。 */
  username: string
  /** 系统初始昵称，并非后续资料快照。 */
  nickname: string
  /** 创建时角色。 */
  role: ProvisionedRole
  /** 只有首次提交成功才能交付明文。 */
  firstDelivery: boolean
  /** 不得放入用户资料、全局缓存或日志。 */
  initialPassword: string | null
}

/** 严格验证凭据有无与首次标记一致，异常响应按结果未知处理，不能重新开一个账号。 */
export function decodeAccountCreation(value: unknown): AccountCreation {
  if (
    !isRecord(value) ||
    typeof value.id !== 'string' ||
    !/^[1-9]\d*$/.test(value.id) ||
    typeof value.username !== 'string' ||
    !/^ph\d{6,18}$/.test(value.username) ||
    typeof value.nickname !== 'string' ||
    !isProvisionedRole(value.role) ||
    typeof value.firstDelivery !== 'boolean' ||
    (value.firstDelivery
      ? typeof value.initialPassword !== 'string' ||
        !/^[A-Za-z2-9]{20}$/.test(value.initialPassword)
      : value.initialPassword !== null)
  )
    throw new ApiError('开户结果不完整，请核对本次创建结果', 502)
  return {
    id: value.id,
    username: value.username,
    nickname: value.nickname,
    role: value.role,
    firstDelivery: value.firstDelivery,
    initialPassword: value.initialPassword as string | null,
  }
}

/** 写请求绝不自动重发，调用者必须保留本次意图再显式重试。 */
export function createAccount(
  request: AccountCreateRequest,
  token: string,
): Promise<AccountCreation> {
  return apiRequest(
    { method: 'POST', url: '/manage/accounts', data: request },
    decodeAccountCreation,
    token,
  )
}

/** 只核对是否提交，不向服务端索取或重新生成密码。 */
export function confirmAccountCreation(requestId: string, token: string): Promise<AccountCreation> {
  return apiRequest(
    { method: 'GET', url: '/manage/accounts/creation', params: { requestId } },
    decodeAccountCreation,
    token,
  )
}

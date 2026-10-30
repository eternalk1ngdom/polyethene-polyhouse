import { ApiError, apiRequest, isRecord } from './index'

/** 服务端脱敏资料；ID、经验、硬币按字符串传递，避免 JavaScript 整数精度丢失。 */
export interface UserProfile {
  /** 后端雪花 ID，不转换成 number。 */
  id: string
  /** 登录账号仅用于展示，不作为授权参数。 */
  username: string
  /** 兼容旧资料的空昵称。 */
  nickname: string | null
  /** 服务端管理的头像路径；展示组件仅加载本项目受控图片。 */
  avatar: string | null
  /** 展示角色，实际权限始终由后端决定。 */
  role: 'USER' | 'ADMIN' | 'INSIDER' | 'OWNER'
  /** 临时凭据的限制由服务端给出，前端据此引导改密，不能自行解除。 */
  passwordChangeRequired: boolean
  /** 后端结算的累计经验，不由浏览器自行增加。 */
  experience: string
  /** 等级独立于角色权限。 */
  level: number
  /** 硬币余额保持十进制字符串。 */
  coins: string
}

/** 登录只接收账号与原始密码，不做裁剪或归一化。 */
export interface LoginRequest {
  /** 账号规则与后端 DTO 一致。 */
  username: string
  /** 密码仅随当前请求发送，不写入缓存。 */
  password: string
}

/** 只修改当前会话本人密码，不接受账号编号、角色或凭据版本。 */
export interface PasswordChangeRequest {
  /** 重新确认当前凭据，原样发送，不保存。 */
  currentPassword: string
  /** 新密码仅存在于当前表单与请求内存。 */
  newPassword: string
}

/** 登录契约只向会话管理交付经过校验的 Token 与安全用户资料。 */
export interface LoginResponse {
  /** 当前浏览器终端的令牌，不输出到日志。 */
  accessToken: string
  /** 本项目使用 Bearer 认证。 */
  tokenType: 'Bearer'
  /** 服务端过期时刻；客户端时钟不用于最终验签。 */
  expiresAt: string
  /** 登录时返回的最新资料。 */
  user: UserProfile
}

/** 对齐后端 Result，命令响应 data 为 null，不把 HTTP 200 直接等同于业务成功。 */
export interface AuthCommandResult {
  /** 与后端 Result 共享成功码 0，用于区分 HTTP 成功与业务成功。 */
  code: number
  /** 后端统一处理后的安全提示，不包含存储异常或凭据。 */
  message: string
  /** 撤销命令不交付用户实体或令牌，固定为空。 */
  data: null
}

/** 会话命令只提交当前 Token，用户身份由后端验签决定。 */
interface SessionTokenRequest {
  /** 仅在请求体中传递，不写入 URL 或错误文本。 */
  token: string
}

/** 区分令牌失效与网络/服务故障，客户端只能对确认失效的会话执行注销后的清理。 */
export class AuthCommandError extends ApiError {
  /** 保存安全提示和状态，不把包含 Token 的 Axios 请求对象传播给界面日志。 */
  constructor(message: string, sessionExpired = false) {
    super(message, sessionExpired ? 401 : 0)
    this.name = 'AuthCommandError'
  }
}

/** 校验服务端资料而非信任 localStorage；仅投影允许展示的字段。 */
export function decodeUserProfile(value: unknown): UserProfile {
  // 非负整数只能用规范十进制字符串，不能把丢失精度的 number 再转回字符串。
  const decimal = (entry: unknown): entry is string =>
    typeof entry === 'string' && /^(0|[1-9]\d*)$/.test(entry)
  if (
    !isRecord(value) ||
    !decimal(value.id) ||
    value.id === '0' ||
    typeof value.username !== 'string' ||
    !(typeof value.nickname === 'string' || value.nickname === null) ||
    !(typeof value.avatar === 'string' || value.avatar === null) ||
    typeof value.role !== 'string' ||
    !['USER', 'ADMIN', 'INSIDER', 'OWNER'].includes(value.role) ||
    typeof value.passwordChangeRequired !== 'boolean' ||
    !decimal(value.experience) ||
    !decimal(value.coins) ||
    typeof value.level !== 'number' ||
    !Number.isSafeInteger(value.level) ||
    value.level < 1
  ) {
    throw new ApiError('用户资料格式不完整，请稍后重试', 502)
  }
  return {
    id: value.id,
    username: value.username,
    nickname: value.nickname,
    avatar: value.avatar,
    role: value.role as UserProfile['role'],
    passwordChangeRequired: value.passwordChangeRequired,
    experience: value.experience,
    level: value.level,
    coins: value.coins,
  }
}

function decodeLogin(value: unknown): LoginResponse {
  if (
    !isRecord(value) ||
    typeof value.accessToken !== 'string' ||
    !value.accessToken ||
    value.accessToken.length > 8192 ||
    /\s/.test(value.accessToken) ||
    value.tokenType !== 'Bearer' ||
    typeof value.expiresAt !== 'string' ||
    !Number.isFinite(Date.parse(value.expiresAt))
  ) {
    throw new ApiError('登录响应不完整，请稍后重试', 502)
  }
  return {
    accessToken: value.accessToken,
    tokenType: 'Bearer',
    expiresAt: value.expiresAt,
    user: decodeUserProfile(value.user),
  }
}

/** 浏览器统一使用 PC 会话，屏幕尺寸变化不切换服务端终端编号。 */
export async function login(request: LoginRequest): Promise<LoginResponse> {
  try {
    return await apiRequest(
      { method: 'POST', url: '/auth/login', data: { ...request, clientType: 'PC' } },
      decodeLogin,
    )
  } catch (error: unknown) {
    if (error instanceof ApiError && error.sessionExpired)
      throw new ApiError('账号或密码错误，请重新输入', 401, error.code)
    throw error
  }
}

/** 恢复会话必须请求后端，缓存中的用户资料和角色不能替代认证。 */
export function getCurrentUser(token: string): Promise<UserProfile> {
  return apiRequest({ method: 'GET', url: '/auth/me' }, decodeUserProfile, token)
}

/** 改密不是可自动重试的查询；成功返回空结果，调用方需清理本次会话并重新登录。 */
export function changePassword(request: PasswordChangeRequest, token: string): Promise<void> {
  return apiRequest(
    { method: 'POST', url: '/auth/password/change', data: request },
    (value) => {
      if (value !== null) throw new ApiError('改密结果不完整，请重新登录核对', 502)
    },
    token,
  )
}

/** 上传只产生审核申请，不能用响应把待审头像写入公共资料。 */
export interface AvatarChange {
  /** 服务端申请编号。 */
  submissionId: string
  /** 核对提交结果的防重编号。 */
  requestId: string
  /** 重试可返回被替代/已决定的历史申请。 */
  status: string
  /** 今日剩余提交额度。 */
  remainingToday: number
}
/** 公开头像地址仅允许本项目版本化资源。 */
export function isAvatarPath(value: unknown): value is string {
  return typeof value === 'string' && /^\/api\/media\/avatars\/[a-f0-9]{32}\.jpg$/.test(value)
}
/** UUID 由浏览器安全随机源产生，支持普通 IP 的开发上下文。 */
export function newProfileRequestId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  // 固定 UUID v4 的版本与变体位，其余位保留安全随机值。
  bytes[6] = (bytes[6]! & 15) | 64
  bytes[8] = (bytes[8]! & 63) | 128
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
/** 同一图像意图沿用请求号；旧响应没有申请字段时拒绝，不能误报已发布。 */
export function uploadAvatar(
  file: Blob,
  token: string,
  requestId = newProfileRequestId(),
): Promise<AvatarChange> {
  const data = new FormData()
  data.append('file', file, 'avatar.jpg')
  data.append('requestId', requestId)
  return apiRequest(
    { method: 'POST', url: '/auth/avatar/submissions', data },
    (value) => {
      if (
        !isRecord(value) ||
        typeof value.id !== 'string' ||
        !/^[1-9]\d*$/.test(value.id) ||
        value.requestId !== requestId ||
        value.kind !== 'AVATAR' ||
        !['PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED', 'EXPIRED', 'INVALIDATED'].includes(
          String(value.status),
        ) ||
        !Number.isInteger(value.remainingToday) ||
        Number(value.remainingToday) < 0 ||
        Number(value.remainingToday) > 5
      )
        throw new ApiError('头像申请响应不完整，请刷新申请状态核对', 502)
      return {
        submissionId: value.id,
        requestId,
        status: String(value.status),
        remainingToday: Number(value.remainingToday),
      }
    },
    token,
  )
}

async function endSession(path: 'logout' | 'deregister', token: string): Promise<void> {
  const request: SessionTokenRequest = { token }
  try {
    await apiRequest({ method: 'POST', url: `/auth/${path}`, data: request }, (value) => {
      if (value !== null) throw new ApiError('服务返回的数据不完整，请稍后重试', 502)
    })
  } catch (error: unknown) {
    if (error instanceof ApiError) throw new AuthCommandError(error.message, error.sessionExpired)
    throw new AuthCommandError('认证服务暂时不可用，请稍后重试')
  }
}

/** 退出当前终端，不撤销同账号的其他终端。 */
export function logout(token: string): Promise<void> {
  return endSession('logout', token)
}

/** 软注销当前账号，后端成功后客户端才能将此操作展示为注销成功。 */
export function deregister(token: string): Promise<void> {
  return endSession('deregister', token)
}

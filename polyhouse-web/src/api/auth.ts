import axios from 'axios'

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
export class AuthCommandError extends Error {
  /** 只有确认 401 时才能按失效会话清理，网络故障不代表账号已注销。 */
  readonly sessionExpired: boolean

  /** 保存安全提示和状态，不把包含 Token 的 Axios 请求对象传播给界面日志。 */
  constructor(message: string, sessionExpired = false) {
    super(message)
    this.name = 'AuthCommandError'
    this.sessionExpired = sessionExpired
  }
}

async function endSession(path: 'logout' | 'deregister', token: string): Promise<void> {
  const request: SessionTokenRequest = { token }
  try {
    const response = await axios.post<AuthCommandResult>(`/api/auth/${path}`, request)
    if (response.data.code !== 0) {
      throw new AuthCommandError(response.data.message, response.data.code === 40100)
    }
  } catch (error: unknown) {
    if (error instanceof AuthCommandError) throw error
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      throw new AuthCommandError('登录状态已失效，请重新登录', true)
    }
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

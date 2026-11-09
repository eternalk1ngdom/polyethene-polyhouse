import { ApiError, apiClient, isRecord } from './index'

// Render 的典型唤醒约一分钟，给平台波动留余量，每次只保留一个短请求。
const WAKE_BUDGET_MS = 90_000
const PROBE_TIMEOUT_MS = 6_000
const PROBE_INTERVAL_MS = 3_000

/** 外部 API 可能休眠，本地相对路径仍直接登录。 */
export function usesExternalLoginApi(): boolean {
  return /^https?:\/\//i.test(apiClient.defaults.baseURL ?? '')
}

/** 等待后端返回本人接口的匿名业务响应，期间不发送登录表单或会话令牌。 */
export async function waitForLoginService(signal: AbortSignal): Promise<void> {
  const deadline = performance.now() + WAKE_BUDGET_MS
  while (performance.now() < deadline) {
    signal.throwIfAborted()
    const response = await apiClient
      .get<unknown>('/auth/me', {
        signal,
        timeout: Math.max(1, Math.min(PROBE_TIMEOUT_MS, deadline - performance.now())),
        validateStatus: () => true,
      })
      // 平台启动页可能缺少 CORS 头，浏览器会把它和暂时的网络故障一样报告。
      .catch(() => undefined)
    signal.throwIfAborted()
    if (performance.now() >= deadline) break
    if (response?.status === 401 && isRecord(response.data) && response.data.code === 40100) {
      return
    }
    if (response?.status === 429) {
      throw new ApiError('请求过于频繁，请稍后重试', 429)
    }
    if (response && response.status >= 400 && response.status < 500 && response.status !== 408) {
      throw new ApiError('暂时无法连接登录服务，请稍后重试', response.status)
    }

    const remaining = deadline - performance.now()
    if (remaining > 0) await pause(Math.min(PROBE_INTERVAL_MS, remaining), signal)
  }
  signal.throwIfAborted()
  throw new ApiError('等待服务启动超时，请稍后重试')
}

function pause(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', done)
      resolve()
    }
    const timer = setTimeout(done, milliseconds)
    signal.addEventListener('abort', done, { once: true })
  })
}

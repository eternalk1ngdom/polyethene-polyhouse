import axios, { type AxiosRequestConfig } from 'axios'

/** 所有 API 共用响应外壳，业务数据仍必须经过运行时校验。 */
interface ApiResult {
  /** HTTP 成功不代表业务成功，服务端成功码为 0。 */
  code: number
  /** 原始数据保持 unknown，禁止把未经校验的响应当成用户资料。 */
  data: unknown
}

/** 认证失败回调只携带发出请求时的 Token，用于防止旧请求清除新会话。 */
interface UnauthorizedHandler {
  (submittedToken: string): void
}

/** 复用连接配置和有限等待时间；实例独立于页面，不从用户缓存推断身份。 */
export const apiClient = axios.create({ baseURL: '/api', timeout: 12000 })
let unauthorizedHandler: UnauthorizedHandler | undefined
let passwordChangeRequiredHandler: UnauthorizedHandler | undefined

/** 只传播安全状态和固定提示，不附带可能含密码或 Token 的 Axios 请求、响应或 cause。 */
export class ApiError extends Error {
  /** HTTP 状态帮助界面区分未登录、无权限和服务故障。 */
  readonly status: number
  /** 业务码保留给可信调用方判断，不包含原始响应数据。 */
  readonly code: number | null
  /** 网络异常与 403 都不能作为清理会话的依据。 */
  readonly sessionExpired: boolean

  /** 丢弃底层异常对象，只保存经过筛选的状态与提示。 */
  constructor(message: string, status = 0, code: number | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.sessionExpired = status === 401 || code === 40100
  }
}

/** 启动时连接状态管理，不在请求模块导入 Pinia，避免循环依赖。 */
export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  unauthorizedHandler = handler
}

/** 临时凭据被业务拒绝时仍保留改密所需会话；回调只能影响发出请求时的同一身份。 */
export function setPasswordChangeRequiredHandler(handler: UnauthorizedHandler): void {
  passwordChangeRequiredHandler = handler
}

/** JSON 对象的最小类型收窄，数组和 null 都不是合法响应外壳。 */
export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function safeError(status: number, code: number | null = null): ApiError {
  if (code === 40301) return new ApiError('请先修改初始密码', 403, code)
  if (status === 401 || code === 40100) return new ApiError('登录状态已失效，请重新登录', 401, code)
  if (status === 403 || code === 40300)
    return new ApiError('当前账号没有执行此操作的权限', 403, code)
  if (status === 409 || code === 40900)
    return new ApiError('当前数据状态已变化，请刷新后重试', 409, code)
  if (status === 400 || code === 40000)
    return new ApiError('填写的信息不符合要求，请检查后重试', 400, code)
  if (status === 429) return new ApiError('请求过于频繁，请稍后重试', 429, code)
  if (status === 413) return new ApiError('图片体积超过限制，请重新选择图片', 413, code)
  if (status === 0) return new ApiError('暂时无法连接服务，请检查网络后重试')
  return new ApiError('服务暂时不可用，请稍后重试', status, code)
}

/** 发出请求、检查业务码并解析数据；只有显式提供 Token 的请求才发送 Bearer 和触发失效回调。 */
export async function apiRequest<T>(
  config: AxiosRequestConfig,
  decode: (value: unknown) => T,
  submittedToken?: string,
): Promise<T> {
  try {
    const response = await apiClient.request<unknown>({
      ...config,
      headers: {
        ...config.headers,
        ...(submittedToken ? { Authorization: `Bearer ${submittedToken}` } : {}),
      },
    })
    const body = response.data
    if (!isRecord(body) || typeof body.code !== 'number' || !('data' in body)) {
      throw new ApiError('服务返回的数据不完整，请稍后重试', 502)
    }
    const result: ApiResult = { code: body.code, data: body.data }
    if (result.code !== 0) throw safeError(response.status, result.code)
    return decode(result.data)
  } catch (error: unknown) {
    const safe =
      error instanceof ApiError
        ? error
        : axios.isAxiosError<unknown>(error)
          ? safeError(
              error.response?.status ?? 0,
              isRecord(error.response?.data) && typeof error.response.data.code === 'number'
                ? error.response.data.code
                : null,
            )
          : new ApiError('服务返回的数据不完整，请稍后重试', 502)
    if (safe.sessionExpired && submittedToken) unauthorizedHandler?.(submittedToken)
    if (safe.code === 40301 && submittedToken) passwordChangeRequiredHandler?.(submittedToken)
    throw safe
  }
}

/** 私有审核图沿用统一会话失效处理；媒体字节不强行套 JSON Result 外壳。 */
export async function apiImage(path: string, submittedToken: string): Promise<Blob> {
  try {
    const response = await apiClient.request<Blob>({
      method: 'GET',
      url: path,
      responseType: 'blob',
      headers: { Authorization: `Bearer ${submittedToken}`, 'Cache-Control': 'no-store' },
    })
    if (
      !(response.data instanceof Blob) ||
      response.data.type !== 'image/jpeg' ||
      response.data.size === 0 ||
      response.data.size > 256 * 1024
    )
      throw new ApiError('待审图片响应不完整，请重试', 502)
    return response.data
  } catch (failure: unknown) {
    let safe: ApiError
    if (failure instanceof ApiError) safe = failure
    else if (axios.isAxiosError(failure)) {
      let code: number | null = null
      const raw: unknown = failure.response?.data
      // Blob 错误仍只读有界 Result 的业务码，不传播原始响应或 Axios 请求对象。
      if (raw instanceof Blob && raw.size < 8192) {
        try {
          const body: unknown = JSON.parse(await raw.text())
          if (isRecord(body) && typeof body.code === 'number') code = body.code
        } catch {
          code = null
        }
      }
      safe = safeError(failure.response?.status ?? 0, code)
    } else safe = new ApiError('待审图片暂时无法读取', 502)
    if (safe.sessionExpired) unauthorizedHandler?.(submittedToken)
    if (safe.code === 40301) passwordChangeRequiredHandler?.(submittedToken)
    throw safe
  }
}

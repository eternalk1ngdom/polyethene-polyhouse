import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError } from '../api/index'
import {
  AuthCommandError,
  deregister as deregisterAccount,
  logout as logoutSession,
  getCurrentUser,
  login as loginAccount,
  uploadAvatar as uploadAvatarImage,
  changePassword as changePasswordRequest,
  type AvatarChange,
  type LoginRequest,
  type PasswordChangeRequest,
  type UserProfile,
} from '../api/auth'

// 保留原有类型导入位置，资料契约集中在 API 层，避免 API 反向依赖状态管理。
export type { UserProfile } from '../api/auth'

/** 缓存存在只进入 checking，身份确认失败时单独显示 unavailable，不能伪装成已登录。 */
export type SessionStatus = 'anonymous' | 'checking' | 'authenticated' | 'unavailable'

const TOKEN_KEY = 'polyhouse_token'
const USER_KEY = 'polyhouse_user'

/** 管理当前页面的会话，退出或注销后同步清除内存状态和既有本地缓存键。 */
export const useUserStore = defineStore('user', () => {
  const storageAvailable = ref(true)
  const token = ref(readStoredToken())
  const user = ref<UserProfile | null>(null)
  const status = ref<SessionStatus>(token.value ? 'checking' : 'anonymous')
  const sessionMessage = ref('')
  const isLoggedIn = computed(
    () => status.value === 'authenticated' && !!token.value && user.value !== null,
  )
  const storageMessage = computed(() =>
    storageAvailable.value ? '' : '浏览器暂不允许保存登录状态，刷新后可能需要重新登录',
  )
  const requiresPasswordChange = computed(
    () => isLoggedIn.value && user.value?.passwordChangeRequired === true,
  )
  // 每次建立/清理会话都推进版本，连同 Token 比较一起拦住旧请求和同 Token 的 ABA 变化。
  let revision = 0
  // 资料变更单独计数，不使同一会话正在进行的退出命令失效。
  let profileRevision = 0
  let restoreSequence = 0
  let pendingRestore: { revision: number; requestId: number; promise: Promise<void> } | undefined

  function readStoredToken(): string {
    try {
      return localStorage.getItem(TOKEN_KEY) ?? ''
    } catch {
      storageAvailable.value = false
      return ''
    }
  }

  function removeCache(expectedToken?: string): void {
    try {
      // 另一标签页可能已写入新会话；旧撤销响应不能删除它尚未同步到内存的凭据。
      if (expectedToken !== undefined && localStorage.getItem(TOKEN_KEY) !== expectedToken) return
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    } catch {
      storageAvailable.value = false
    }
  }

  function cacheProfile(): void {
    try {
      localStorage.setItem(TOKEN_KEY, token.value)
      localStorage.setItem(USER_KEY, JSON.stringify(user.value))
    } catch {
      storageAvailable.value = false
      // 缓存只写了一半时清掉属于当前会话的片段，页面仍可使用已验证的内存身份。
      removeCache(token.value)
    }
  }

  /** 只由真实登录结果或受信任业务交付身份，页面渲染不读取缓存资料。 */
  function setSession(accessToken: string, profile: UserProfile): void {
    revision += 1
    token.value = accessToken
    user.value = profile
    status.value = 'authenticated'
    sessionMessage.value = ''
    cacheProfile()
  }

  /** 内存与 localStorage 一并失效，防止刷新页面又恢复已退出的 Token。 */
  function clearSession(): void {
    const previousToken = token.value
    revision += 1
    token.value = ''
    user.value = null
    status.value = 'anonymous'
    sessionMessage.value = ''
    removeCache(previousToken || undefined)
  }

  /** 请求只允许使发出时的身份失效；公开登录的 401 不触发此回调。 */
  function expireSession(submittedToken: string): void {
    if (token.value !== submittedToken) return
    clearSession()
    sessionMessage.value = '登录状态已失效，请重新登录'
  }

  /** 受保护请求收到明确的 40301 后进入改密流程，旧请求不能标记后来登录的身份。 */
  function requirePasswordChange(submittedToken: string): void {
    if (!isLoggedIn.value || token.value !== submittedToken || !user.value) return
    profileRevision += 1
    pendingRestore = undefined
    user.value = { ...user.value, passwordChangeRequired: true }
    cacheProfile()
  }

  /** 改密成功只清理发起请求的会话；换号、跨标签换号及旧失败均不能污染新身份。 */
  async function changePassword(request: PasswordChangeRequest): Promise<boolean> {
    if (!isLoggedIn.value) throw new ApiError('请先登录再修改密码', 401)
    const submittedToken = token.value
    const submittedRevision = revision
    try {
      await changePasswordRequest(request, submittedToken)
    } catch (error: unknown) {
      if (revision !== submittedRevision || token.value !== submittedToken) return false
      if (storageAvailable.value && readStoredToken() !== submittedToken) {
        await syncStoredSession()
        return false
      }
      throw error
    }
    if (revision !== submittedRevision || token.value !== submittedToken) return false
    if (storageAvailable.value && readStoredToken() !== submittedToken) {
      await syncStoredSession()
      return false
    }
    clearSession()
    sessionMessage.value = '密码已修改，请使用新密码重新登录'
    return true
  }

  /** 主动登录会替换本地待确认身份；旧恢复或旧登录结果不能覆盖后续操作。 */
  async function login(request: LoginRequest): Promise<boolean> {
    clearSession()
    const submittedRevision = revision
    const result = await loginAccount(request)
    if (revision !== submittedRevision) return false
    setSession(result.accessToken, result.user)
    return true
  }

  /** 多标签页只同步 Token 并重新向服务端确认，绝不信任另一页写入的角色资料。 */
  async function syncStoredSession(): Promise<void> {
    const stored = readStoredToken()
    if (!storageAvailable.value || stored === token.value) return
    revision += 1
    token.value = stored
    user.value = null
    status.value = stored ? 'checking' : 'anonymous'
    sessionMessage.value = ''
    if (stored) await restoreSession()
  }

  /** 启动、回到页面或手动重试时读取 /me；并发触发复用同一次请求。 */
  function restoreSession(): Promise<void> {
    if (!token.value) {
      if (status.value !== 'anonymous') clearSession()
      return Promise.resolve()
    }
    if (pendingRestore?.revision === revision) return pendingRestore.promise
    const submittedToken = token.value
    const submittedRevision = revision
    const wasAuthenticated = isLoggedIn.value
    const submittedProfileRevision = profileRevision
    const requestId = ++restoreSequence
    if (!wasAuthenticated) status.value = 'checking'
    const promise = (async () => {
      try {
        const profile = await getCurrentUser(submittedToken)
        if (
          revision !== submittedRevision ||
          token.value !== submittedToken ||
          profileRevision !== submittedProfileRevision
        )
          return
        // 存储事件与网络响应的到达顺序不固定，提交资料前再次核对共享缓存。
        const stored = readStoredToken()
        if (storageAvailable.value && stored !== submittedToken) {
          await syncStoredSession()
          return
        }
        user.value = profile
        status.value = 'authenticated'
        sessionMessage.value = ''
        cacheProfile()
      } catch (error: unknown) {
        if (
          revision !== submittedRevision ||
          token.value !== submittedToken ||
          profileRevision !== submittedProfileRevision
        )
          return
        if (error instanceof ApiError && error.sessionExpired) {
          expireSession(submittedToken)
        } else {
          // 网络错误、403 或格式错误都不等于令牌失效；隐藏旧资料但保留凭据以供重试。
          user.value = null
          status.value = 'unavailable'
          sessionMessage.value =
            error instanceof ApiError ? error.message : '暂时无法确认登录状态，请稍后重试'
        }
      } finally {
        if (pendingRestore?.requestId === requestId) pendingRestore = undefined
      }
    })()
    pendingRestore = { revision: submittedRevision, requestId, promise }
    return promise
  }

  /** 旧上传不能污染后来登录的账号；成功时推进版本，使在途旧 /me 不能覆盖新头像。 */
  async function uploadAvatar(file: Blob): Promise<AvatarChange | null> {
    if (!isLoggedIn.value || !user.value) throw new ApiError('请先登录再修改头像', 401)
    if (requiresPasswordChange.value) throw new ApiError('请先修改初始密码', 403, 40301)
    const submittedToken = token.value
    const submittedRevision = revision
    const submittedId = user.value.id
    const change = await uploadAvatarImage(file, submittedToken)
    if (
      revision !== submittedRevision ||
      token.value !== submittedToken ||
      user.value?.id !== submittedId
    )
      return null
    // 共享缓存可能先于 storage 事件变化，不能用旧上传响应把另一页的新会话写回去。
    const stored = readStoredToken()
    if (storageAvailable.value && stored !== submittedToken) {
      await syncStoredSession()
      return null
    }
    profileRevision += 1
    pendingRestore = undefined
    user.value = { ...user.value, avatar: change.avatar }
    cacheProfile()
    return change
  }

  async function endCurrentSession(cancelAccount: boolean): Promise<void> {
    const submittedToken = token.value
    const submittedRevision = revision
    if (!submittedToken) {
      clearSession()
      if (cancelAccount) throw new AuthCommandError('请先登录再注销账号', true)
      return
    }
    try {
      await (cancelAccount ? deregisterAccount(submittedToken) : logoutSession(submittedToken))
    } catch (error: unknown) {
      if (
        error instanceof ApiError &&
        error.sessionExpired &&
        token.value === submittedToken &&
        revision === submittedRevision
      ) {
        expireSession(submittedToken)
      }
      throw error
    }
    // 请求等待期间若已登录另一账号或取得新 Token，旧响应不能清掉新会话。
    if (token.value === submittedToken && revision === submittedRevision) clearSession()
  }

  /** 等待后端撤销成功后退出；网络故障会保留本地状态并提示重试。 */
  function logout(): Promise<void> {
    return endCurrentSession(false)
  }

  /** 必须由界面的明确确认操作调用，失败不能冒充账号已注销。 */
  function deregister(): Promise<void> {
    return endCurrentSession(true)
  }

  return {
    token,
    user,
    status,
    isLoggedIn,
    sessionMessage,
    storageMessage,
    requiresPasswordChange,
    requirePasswordChange,
    changePassword,
    setSession,
    clearSession,
    expireSession,
    login,
    restoreSession,
    syncStoredSession,
    uploadAvatar,
    logout,
    deregister,
  }
})

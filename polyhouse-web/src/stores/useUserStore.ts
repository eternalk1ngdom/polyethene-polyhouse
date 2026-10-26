import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { AuthCommandError, deregister as deregisterAccount, logout as logoutSession } from '../api/auth'

/** 只保存后端已脱敏的资料；ID 使用字符串保留雪花主键精度。 */
export interface UserProfile {
  /** 后端雪花 ID 保留为字符串，避免 number 精度损失。 */
  id: string
  /** 只展示自己的账号，不用于授权或指定注销对象。 */
  username: string
  /** 允许兼容已有资料中的空昵称。 */
  nickname: string | null
  /** 未配置头像时由未来界面使用默认展示。 */
  avatar: string | null
  /** 仅供界面展示，服务端仍使用数据库最新角色做权限判断。 */
  role: 'USER' | 'INSIDER' | 'OWNER'
  /** 累计经验使用十进制字符串保留 bigint 精度，由后端结算每日奖励。 */
  experience: string
  /** 成长等级独立于角色权限，从 1 级开始，达到后端累计门槛后升级。 */
  level: number
  /** 整数硬币余额使用十进制字符串传递，后端已支持每日发放，消费用途待定。 */
  coins: string
}

const TOKEN_KEY = 'polyhouse_token'
const USER_KEY = 'polyhouse_user'

/** 管理当前页面的会话，退出或注销后同步清除内存状态和既有本地缓存键。 */
export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) ?? '')
  const user = ref<UserProfile | null>(null)
  const isLoggedIn = computed(() => token.value.length > 0)

  /** 由未来登录入口交付已验证的 Token 与安全资料，本地缓存不作为后端授权依据。 */
  function setSession(accessToken: string, profile: UserProfile): void {
    token.value = accessToken
    user.value = profile
    localStorage.setItem(TOKEN_KEY, accessToken)
    localStorage.setItem(USER_KEY, JSON.stringify(profile))
  }

  /** 内存与 localStorage 一并失效，防止刷新页面又恢复已退出的 Token。 */
  function clearSession(): void {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  async function endCurrentSession(cancelAccount: boolean): Promise<void> {
    const submittedToken = token.value
    if (!submittedToken) {
      clearSession()
      if (cancelAccount) throw new AuthCommandError('请先登录再注销账号', true)
      return
    }
    try {
      await (cancelAccount ? deregisterAccount(submittedToken) : logoutSession(submittedToken))
    } catch (error: unknown) {
      if (error instanceof AuthCommandError && error.sessionExpired && token.value === submittedToken) {
        clearSession()
      }
      throw error
    }
    // 请求等待期间若已登录另一账号或取得新 Token，旧响应不能清掉新会话。
    if (token.value === submittedToken) clearSession()
  }

  /** 等待后端撤销成功后退出；网络故障会保留本地状态并提示重试。 */
  function logout(): Promise<void> {
    return endCurrentSession(false)
  }

  /** 必须由界面的明确确认操作调用，失败不能冒充账号已注销。 */
  function deregister(): Promise<void> {
    return endCurrentSession(true)
  }

  return { token, user, isLoggedIn, setSession, clearSession, logout, deregister }
})

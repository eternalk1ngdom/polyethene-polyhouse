import { ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { ApiError } from '../api'
import { useUserStore } from './useUserStore'

/** 在导航和个人中心之间共享账号操作，防止两个入口同时提交撤销命令。 */
export const useAccountUiStore = defineStore('account-ui', () => {
  const userStore = useUserStore()
  const authVisible = ref(false)
  const cancellationVisible = ref(false)
  const cancellationUsername = ref('')
  const pending = ref<'logout' | 'deregister' | null>(null)
  const actionError = ref('')
  const notice = ref('')
  let confirmationToken = ''

  /** 取消操作立即销毁确认对象；重新打开必须针对当前已验证身份重新确认。 */
  function closeCancellation(): void {
    if (pending.value === 'deregister') return
    cancellationVisible.value = false
    cancellationUsername.value = ''
    confirmationToken = ''
    actionError.value = ''
  }

  /** 所有未登录入口共用一个弹窗，保持当前浏览页面。 */
  function openLogin(): void {
    if (pending.value) return
    notice.value = ''
    actionError.value = ''
    authVisible.value = true
  }

  /** 只允许当前已核实身份进入注销确认，不能从缓存或路由参数指定账号。 */
  function openCancellation(): void {
    if (pending.value) return
    if (!userStore.isLoggedIn || !userStore.user) {
      openLogin()
      return
    }
    confirmationToken = userStore.token
    cancellationUsername.value = userStore.user.username
    actionError.value = ''
    notice.value = ''
    cancellationVisible.value = true
  }

  /** 确认会话撤销成功后显示反馈；旧响应不替新登录显示“已退出”。 */
  async function logout(): Promise<boolean> {
    if (pending.value || !userStore.isLoggedIn) return false
    pending.value = 'logout'
    actionError.value = ''
    notice.value = ''
    const submittedToken = userStore.token
    try {
      await userStore.logout()
      if (userStore.token) return false
      notice.value = '已退出登录，其他终端不受影响。'
      return true
    } catch (error: unknown) {
      if (userStore.token && userStore.token !== submittedToken) return false
      actionError.value =
        error instanceof ApiError ? error.message : '暂时无法确认退出结果，请稍后核实'
      return false
    } finally {
      pending.value = null
    }
  }

  /** 明确同意影响并输入固定确认词后才发送；确认期间换号则必须重新开始。 */
  async function deregister(confirmation: string, acknowledged: boolean): Promise<boolean> {
    if (pending.value || !cancellationVisible.value || !acknowledged || confirmation !== '注销账号')
      return false
    if (
      !userStore.isLoggedIn ||
      !confirmationToken ||
      confirmationToken !== userStore.token ||
      cancellationUsername.value !== userStore.user?.username
    ) {
      closeCancellation()
      return false
    }
    pending.value = 'deregister'
    actionError.value = ''
    const submittedToken = confirmationToken
    try {
      await userStore.deregister()
      cancellationVisible.value = false
      confirmationToken = ''
      cancellationUsername.value = ''
      // 服务端可能已完成旧账号命令，但当前若已建立新会话，不能把成功反馈套给新账号。
      if (userStore.token) return false
      notice.value = '账号已注销，所有终端的登录状态已撤销。'
      return true
    } catch (error: unknown) {
      if (userStore.token && userStore.token !== submittedToken) return false
      actionError.value =
        error instanceof ApiError ? error.message : '暂时无法确认注销结果，请稍后核实'
      return false
    } finally {
      pending.value = null
      if (cancellationVisible.value && confirmationToken !== userStore.token) closeCancellation()
    }
  }

  /** 成功反馈可手动收起，不写入持久缓存。 */
  function dismissNotice(): void {
    notice.value = ''
  }

  watch(
    () => userStore.token,
    (token) => {
      if (
        cancellationVisible.value &&
        token !== confirmationToken &&
        pending.value !== 'deregister'
      )
        closeCancellation()
    },
    { flush: 'sync' },
  )
  watch(
    () => userStore.isLoggedIn,
    (loggedIn) => {
      if (loggedIn) {
        notice.value = ''
        actionError.value = ''
      }
    },
    { flush: 'sync' },
  )

  return {
    authVisible,
    cancellationVisible,
    cancellationUsername,
    pending,
    actionError,
    notice,
    openLogin,
    openCancellation,
    closeCancellation,
    logout,
    deregister,
    dismissNotice,
  }
})

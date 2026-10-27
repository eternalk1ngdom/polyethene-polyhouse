import { computed, onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { ApiError } from '../api'
import { getGrowthSummary, type UserGrowthSummary } from '../api/growth'
import { useUserStore } from './useUserStore'

/** 为个人卡片和成长面板共享服务端快照；不缓存领取状态，也不使用浏览器日期发奖。 */
export const useUserGrowthStore = defineStore('user-growth', () => {
  const userStore = useUserStore()
  const data = ref<UserGrowthSummary | null>(null)
  const loadedToken = ref('')
  const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const errorMessage = ref('')
  const summary = computed(() =>
    userStore.isLoggedIn &&
    loadedToken.value === userStore.token &&
    data.value?.userId === userStore.user?.id
      ? data.value
      : null,
  )
  let generation = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let pending:
    { token: string; userId: string; generation: number; promise: Promise<void> } | undefined

  function clearTimer(): void {
    if (timer !== undefined) clearTimeout(timer)
    timer = undefined
  }

  function reset(): void {
    generation += 1
    clearTimer()
    data.value = null
    loadedToken.value = ''
    status.value = 'idle'
    errorMessage.value = ''
    pending = undefined
  }

  /** 同一身份的并发刷新去重；退出、换号和旧失败响应不能污染新账号。 */
  function refresh(): Promise<void> {
    if (!userStore.isLoggedIn || !userStore.user) {
      reset()
      return Promise.resolve()
    }
    const token = userStore.token
    const userId = userStore.user.id
    if (pending?.token === token && pending.userId === userId) return pending.promise
    const submittedGeneration = ++generation
    clearTimer()
    data.value = null
    loadedToken.value = ''
    status.value = 'loading'
    errorMessage.value = ''
    const current = (): boolean =>
      generation === submittedGeneration &&
      userStore.isLoggedIn &&
      userStore.token === token &&
      userStore.user?.id === userId
    const promise = (async () => {
      try {
        const result = await getGrowthSummary(token)
        if (!current()) return
        if (result.userId !== userId)
          throw new ApiError('成长资料与当前账号不一致，请稍后重试', 502)
        data.value = result
        loadedToken.value = token
        status.value = 'ready'
        // 用两个服务端时刻的差值安排跨日刷新，不依赖本地时间或固定的“24 小时”领取周期。
        const delay = Math.max(
          1000,
          Date.parse(result.nextRewardAt) - Date.parse(result.observedAt),
        )
        timer = setTimeout(() => {
          if (!current()) return
          data.value = null
          status.value = 'idle'
          // 隐藏页面不主动制造活跃访问；重新可见时由主入口核验身份并刷新。
          if (typeof document === 'undefined' || document.visibilityState === 'visible')
            void refresh()
        }, delay)
      } catch (error: unknown) {
        if (!current()) return
        status.value = 'error'
        errorMessage.value =
          error instanceof ApiError ? error.message : '暂时无法读取成长数据，请稍后重试'
      } finally {
        if (pending?.generation === submittedGeneration) pending = undefined
      }
    })()
    pending = { token, userId, generation: submittedGeneration, promise }
    return promise
  }

  watch(
    [() => userStore.token, () => userStore.user?.id, () => userStore.isLoggedIn],
    () => {
      reset()
      if (userStore.isLoggedIn) void refresh()
    },
    { immediate: true },
  )
  onScopeDispose(() => {
    generation += 1
    clearTimer()
  })

  return { summary, status, errorMessage, refresh }
})

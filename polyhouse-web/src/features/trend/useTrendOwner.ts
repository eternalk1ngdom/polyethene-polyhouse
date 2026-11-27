import { computed, onScopeDispose, ref, watch } from 'vue'
import { ApiError } from '../../api/index'
import {
  generateTrendBriefing,
  getTrendBriefing,
  getTrendJob,
  getTrendScheduleStatus,
  isTrendId,
  setTrendSchedulePaused,
  submitTrendJob,
  type TrendBriefing,
  type TrendBriefingGenerateOutcome,
  type TrendJob,
  type TrendScheduleStatus,
} from '../../api/trend'
import { useUserStore } from '../../stores/useUserStore'

interface OwnerSession {
  token: string
  storageKey: string
  requests: AbortController
  inFlightManaged: number
  managedQueue: Array<() => void>
}

/** 与后端 /api/manage/trend/* 并发预算（并发=2）对齐，杜绝发布台多卡并发撞穿限流。 */
const MAX_MANAGED_CONCURRENCY = 2

/** 丢失提交响应时保留原编号，只有屋主再次点击才发送同一请求。 */
export function useTrendOwner() {
  const user = useUserStore()
  const allowed = computed(
    () => user.isLoggedIn && user.user?.role === 'OWNER' && !user.requiresPasswordChange,
  )
  const job = ref<TrendJob | null>(null)
  const briefing = ref<TrendBriefing | null>(null)
  const scheduleStatus = ref<TrendScheduleStatus | null>(null)
  const intent = ref<string | null>(null)
  const knownJobId = ref<string | null>(null)
  const jobBusy = ref(false)
  const briefingBusy = ref(false)
  const scheduleBusy = ref(false)
  const scheduleToggling = ref(false)
  const briefingGenerating = ref(false)
  const jobError = ref('')
  const briefingError = ref('')
  const scheduleError = ref('')
  const submissionNote = ref('')
  const briefingNote = ref('')
  const jobWaitUntil = ref(0)
  const briefingWaitUntil = ref(0)
  const scheduleWaitUntil = ref(0)
  const waiting = ref(false)
  const briefingWaiting = ref(false)
  const active = computed(() => job.value?.state === 'QUEUED' || job.value?.state === 'RUNNING')
  let session: OwnerSession | undefined
  let poll: ReturnType<typeof setTimeout> | undefined
  let unblock: ReturnType<typeof setTimeout> | undefined
  let briefingUnblock: ReturnType<typeof setTimeout> | undefined

  function reset(): void {
    const previous = session
    session = undefined
    previous?.requests.abort()
    if (previous) {
      // 立刻唤醒排队中的管理调用，使其感知会话已失效并安静退出，避免挂起微任务。
      const waiters = previous.managedQueue.splice(0)
      for (const wake of waiters) wake()
    }
    clearTimeout(poll)
    clearTimeout(unblock)
    clearTimeout(briefingUnblock)
    job.value = null
    briefing.value = null
    scheduleStatus.value = null
    intent.value = null
    knownJobId.value = null
    jobBusy.value = false
    briefingBusy.value = false
    scheduleBusy.value = false
    scheduleToggling.value = false
    briefingGenerating.value = false
    jobError.value = ''
    briefingError.value = ''
    scheduleError.value = ''
    submissionNote.value = ''
    briefingNote.value = ''
    jobWaitUntil.value = 0
    briefingWaitUntil.value = 0
    scheduleWaitUntil.value = 0
    waiting.value = false
    briefingWaiting.value = false
  }

  /**
   * 统一排程所有走 /api/manage/trend/* 的请求，保证同一会话内同时在途严格 ≤ 2，
   * 不放宽后端安全限流预算而从源头根治并发 429。
   */
  async function runManaged<T>(current: OwnerSession, task: () => Promise<T>): Promise<T | undefined> {
    if (session !== current) return undefined
    if (current.inFlightManaged >= MAX_MANAGED_CONCURRENCY) {
      await new Promise<void>((resolve) => {
        current.managedQueue.push(resolve)
      })
    }
    if (session !== current) return undefined
    current.inFlightManaged += 1
    try {
      return await task()
    } finally {
      current.inFlightManaged -= 1
      const next = current.managedQueue.shift()
      next?.()
    }
  }

  function remember(current: OwnerSession): void {
    // 页面关闭后无需保留这些编号。浏览器禁用存储时仍可在本页内继续核对。
    try {
      const key = current.storageKey
      if (knownJobId.value) sessionStorage.setItem(`${key}:job`, knownJobId.value)
      else sessionStorage.removeItem(`${key}:job`)
      if (intent.value) sessionStorage.setItem(`${key}:intent`, intent.value)
      else sessionStorage.removeItem(`${key}:intent`)
    } catch {
      return
    }
  }

  function restore(current: OwnerSession): void {
    try {
      const storedJob = sessionStorage.getItem(`${current.storageKey}:job`)
      const storedIntent = sessionStorage.getItem(`${current.storageKey}:intent`)
      knownJobId.value = isTrendId(storedJob) ? storedJob : null
      intent.value = isTrendId(storedIntent) ? storedIntent : null
    } catch {
      return
    }
  }

  function schedulePoll(delay = 5000): void {
    clearTimeout(poll)
    poll = setTimeout(() => {
      void refreshJob()
    }, delay)
  }

  function onJobRateLimit(failure: ApiError): void {
    const delay = (failure.retryAfterSeconds ?? 5) * 1000
    jobWaitUntil.value = Date.now() + delay
    waiting.value = true
    clearTimeout(unblock)
    unblock = setTimeout(() => {
      waiting.value = false
    }, delay)
    jobError.value = '请求较频繁或更新正在冷却，请稍后重试。'
  }

  /** 任务结束后联动刷新自动采集状态与公开 7 日简报，确保各卡片即时一致。 */
  async function refreshAfterJobFinished(current: OwnerSession): Promise<void> {
    void refreshBriefing()
    if (session !== current) return
    await refreshScheduleStatus()
  }

  async function refreshJob(options?: { cascade?: boolean }): Promise<void> {
    const cascade = options?.cascade ?? true
    const current = session
    const target = intent.value ?? knownJobId.value
    if (!current || !target || jobBusy.value || Date.now() < jobWaitUntil.value) return
    clearTimeout(poll)
    jobBusy.value = true
    jobError.value = ''
    try {
      const result = await runManaged(current, () =>
        getTrendJob(current.token, target, current.requests.signal),
      )
      if (!result || session !== current) return
      const wasActive = active.value
      job.value = result
      knownJobId.value = result.jobId
      if (intent.value === target) intent.value = null
      remember(current)
      if (active.value) schedulePoll()
      else if (cascade && (wasActive || !scheduleStatus.value)) void refreshAfterJobFinished(current)
    } catch (failure: unknown) {
      if (session !== current) return
      if (failure instanceof ApiError && failure.status === 429) {
        onJobRateLimit(failure)
        if (active.value) schedulePoll(Math.max(5000, jobWaitUntil.value - Date.now() + 100))
      } else if (failure instanceof ApiError && failure.status === 404) {
        job.value = null
        knownJobId.value = null
        remember(current)
        jobError.value = intent.value
          ? '暂未查到这笔更新，可以用原编号继续。'
          : '该任务记录已不可用。'
      } else jobError.value = '暂时无法读取任务状态，可以手动重查。'
    } finally {
      if (session === current) jobBusy.value = false
    }
  }

  async function submit(): Promise<void> {
    const current = session
    if (!current || jobBusy.value || active.value || Date.now() < jobWaitUntil.value) return
    if (!intent.value && !globalThis.crypto?.randomUUID) {
      jobError.value = '请在安全连接下提交更新。'
      return
    }
    const hadPriorIntent = intent.value !== null
    const requestId = intent.value ?? globalThis.crypto.randomUUID()
    intent.value = requestId
    remember(current)
    jobBusy.value = true
    jobError.value = ''
    submissionNote.value = ''
    try {
      const result = await runManaged(current, () =>
        submitTrendJob(current.token, requestId, current.requests.signal),
      )
      if (!result || session !== current) return
      job.value = result.job
      knownJobId.value = result.job.jobId
      intent.value = null
      remember(current)
      submissionNote.value = result.accepted
        ? '已受理本次更新。'
        : result.replayed
          ? '已查到同一次更新。'
          : '已有任务在执行，正在查看该任务。'
      if (active.value) schedulePoll()
      else void refreshAfterJobFinished(current)
    } catch (failure: unknown) {
      if (session !== current) return
      // 明确被限流/参数/权限拒绝的新请求必然未被服务端受理，须立即清理 intent 避免残留「本次更新待确认」。
      const definiteRejection =
        failure instanceof ApiError &&
        (failure.status === 429 ||
          failure.status === 400 ||
          failure.status === 401 ||
          failure.status === 403)
      if (!hadPriorIntent && definiteRejection) {
        intent.value = null
        remember(current)
      }
      if (failure instanceof ApiError && failure.status === 429) {
        onJobRateLimit(failure)
      } else if (definiteRejection && failure instanceof ApiError) {
        jobError.value = failure.message || '当前无法发起本次更新，请刷新后重试。'
      } else {
        jobError.value = '未收到更新确认。可以先重查任务，也可以用原编号继续。'
      }
    } finally {
      if (session === current) jobBusy.value = false
    }
  }

  function onBriefingRateLimit(failure: ApiError): void {
    const delay = (failure.retryAfterSeconds ?? 15) * 1000
    briefingWaitUntil.value = Date.now() + delay
    briefingWaiting.value = true
    clearTimeout(briefingUnblock)
    briefingUnblock = setTimeout(() => {
      briefingWaiting.value = false
    }, delay)
    briefingError.value = '风口简报触发正在冷却，请稍后重试。'
  }

  function briefingOutcomeNote(outcome: TrendBriefingGenerateOutcome): string {
    if (outcome === 'GENERATED') return '已生成并发布本期 7 日风口简报。'
    if (outcome === 'REUSED') return '当期快照未变化，已零费用复用现有简报。'
    if (outcome === 'PENDING') return '当期已有在途或待核对的简报请求，请稍后刷新状态核对。'
    if (outcome === 'BUDGET_LIMIT') return '本月 AI 预算已达上限或已暂停，未发起新生成。'
    if (outcome === 'INVALID_OUTPUT')
      return '模型输出未通过实名引用或格式校验，已按实际用量结算并保留旧稿。'
    return '当前无法连接模型或请求未发出，未产生费用。'
  }

  async function refreshBriefing(): Promise<void> {
    const current = session
    if (!current || briefingBusy.value || briefingGenerating.value) return
    briefingBusy.value = true
    briefingError.value = ''
    try {
      const result = await getTrendBriefing(undefined, current.requests.signal)
      if (session !== current) return
      briefing.value = result
    } catch (failure: unknown) {
      if (session !== current) return
      if (failure instanceof ApiError && failure.status === 404) {
        briefing.value = null
      } else {
        briefingError.value = '暂时无法读取风口简报状态，请稍后重试。'
      }
    } finally {
      if (session === current) briefingBusy.value = false
    }
  }

  async function generateBriefing(): Promise<void> {
    const current = session
    if (!current || briefingGenerating.value || Date.now() < briefingWaitUntil.value) return
    briefingGenerating.value = true
    briefingError.value = ''
    briefingNote.value = ''
    try {
      const result = await runManaged(current, () =>
        generateTrendBriefing(
          current.token,
          briefing.value?.anchorDate,
          current.requests.signal,
        ),
      )
      if (!result || session !== current) return
      briefing.value = result.briefing
      briefingNote.value = briefingOutcomeNote(result.outcome)
    } catch (failure: unknown) {
      if (session !== current) return
      if (failure instanceof ApiError && failure.status === 429) {
        onBriefingRateLimit(failure)
      } else if (failure instanceof ApiError && failure.message) {
        briefingError.value = failure.message
      } else {
        briefingError.value = '风口简报生成未收到完整确认，请先刷新状态确认结果。'
      }
    } finally {
      if (session === current) briefingGenerating.value = false
    }
  }

  async function refreshScheduleStatus(): Promise<void> {
    const current = session
    if (!current || scheduleBusy.value || Date.now() < scheduleWaitUntil.value) return
    scheduleBusy.value = true
    scheduleError.value = ''
    try {
      const result = await runManaged(current, () =>
        getTrendScheduleStatus(current.token, current.requests.signal),
      )
      if (!result || session !== current) return
      scheduleStatus.value = result
    } catch (failure: unknown) {
      if (session !== current) return
      if (failure instanceof ApiError && failure.status === 429) {
        scheduleWaitUntil.value = Date.now() + (failure.retryAfterSeconds ?? 5) * 1000
      }
      scheduleError.value = '暂时无法读取自动采集状态，请稍后重试。'
    } finally {
      if (session === current) scheduleBusy.value = false
    }
  }

  async function toggleSchedulePaused(paused: boolean): Promise<boolean> {
    const current = session
    if (!current || scheduleToggling.value || Date.now() < scheduleWaitUntil.value) return false
    scheduleToggling.value = true
    scheduleError.value = ''
    try {
      const result = await runManaged(current, () =>
        setTrendSchedulePaused(current.token, paused, current.requests.signal),
      )
      if (!result || session !== current) return false
      scheduleStatus.value = result
      return true
    } catch (failure: unknown) {
      if (session !== current) return false
      if (failure instanceof ApiError && failure.status === 429) {
        scheduleWaitUntil.value = Date.now() + (failure.retryAfterSeconds ?? 5) * 1000
      }
      scheduleError.value =
        (failure instanceof ApiError && failure.message) ||
        (paused ? '暂时无法暂停自动采集，请稍后重试。' : '暂时无法恢复自动采集，请稍后重试。')
      return false
    } finally {
      if (session === current) scheduleToggling.value = false
    }
  }

  /**
   * 登录初始化编排：公开只读简报可并行加载，管理读接口严格串行（并发=1），
   * 为屋主在进页瞬间点击「更新本期日报」或「生成简报」预留 1 个实时并发槽位。
   */
  async function hydrate(current: OwnerSession): Promise<void> {
    void refreshBriefing()
    if (intent.value || knownJobId.value) {
      await refreshJob({ cascade: false })
    }
    if (session !== current) return
    await refreshScheduleStatus()
  }

  watch(
    [allowed, () => user.token, () => user.user?.id],
    ([canRead, token, ownerId]) => {
      reset()
      if (!canRead || !token || !ownerId) return
      session = {
        token,
        storageKey: `polyhouse:star-tide:${ownerId}`,
        requests: new AbortController(),
        inFlightManaged: 0,
        managedQueue: [],
      }
      restore(session)
      void hydrate(session)
    },
    { immediate: true },
  )
  onScopeDispose(reset)

  return {
    allowed,
    job,
    briefing,
    scheduleStatus,
    intent,
    knownJobId,
    jobBusy,
    briefingBusy,
    scheduleBusy,
    scheduleToggling,
    briefingGenerating,
    jobError,
    briefingError,
    scheduleError,
    submissionNote,
    briefingNote,
    active,
    waiting,
    briefingWaiting,
    refreshJob,
    submit,
    refreshBriefing,
    refreshScheduleStatus,
    toggleSchedulePaused,
    generateBriefing,
  }
}

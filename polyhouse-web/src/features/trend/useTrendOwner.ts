import { computed, onScopeDispose, ref, watch } from 'vue'
import { ApiError } from '../../api/index'
import {
  generateTrendBriefing,
  getTrendBriefing,
  getTrendFees,
  getTrendJob,
  getTrendScheduleStatus,
  isTrendId,
  submitTrendJob,
  type TrendBriefing,
  type TrendBriefingGenerateOutcome,
  type TrendFees,
  type TrendJob,
  type TrendScheduleStatus,
} from '../../api/trend'
import { useUserStore } from '../../stores/useUserStore'

interface OwnerSession {
  token: string
  storageKey: string
  requests: AbortController
}

/** 丢失提交响应时保留原编号，只有屋主再次点击才发送同一请求。 */
export function useTrendOwner() {
  const user = useUserStore()
  const allowed = computed(
    () => user.isLoggedIn && user.user?.role === 'OWNER' && !user.requiresPasswordChange,
  )
  const job = ref<TrendJob | null>(null)
  const fees = ref<TrendFees | null>(null)
  const briefing = ref<TrendBriefing | null>(null)
  const scheduleStatus = ref<TrendScheduleStatus | null>(null)
  const intent = ref<string | null>(null)
  const knownJobId = ref<string | null>(null)
  const jobBusy = ref(false)
  const feesBusy = ref(false)
  const briefingBusy = ref(false)
  const scheduleBusy = ref(false)
  const briefingGenerating = ref(false)
  const jobError = ref('')
  const feeError = ref('')
  const briefingError = ref('')
  const scheduleError = ref('')
  const submissionNote = ref('')
  const briefingNote = ref('')
  const jobWaitUntil = ref(0)
  const feesWaitUntil = ref(0)
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
    session?.requests.abort()
    session = undefined
    clearTimeout(poll)
    clearTimeout(unblock)
    clearTimeout(briefingUnblock)
    job.value = null
    fees.value = null
    briefing.value = null
    scheduleStatus.value = null
    intent.value = null
    knownJobId.value = null
    jobBusy.value = false
    feesBusy.value = false
    briefingBusy.value = false
    scheduleBusy.value = false
    briefingGenerating.value = false
    jobError.value = ''
    feeError.value = ''
    briefingError.value = ''
    scheduleError.value = ''
    submissionNote.value = ''
    briefingNote.value = ''
    jobWaitUntil.value = 0
    feesWaitUntil.value = 0
    briefingWaitUntil.value = 0
    scheduleWaitUntil.value = 0
    waiting.value = false
    briefingWaiting.value = false
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

  async function refreshJob(): Promise<void> {
    const current = session
    const target = intent.value ?? knownJobId.value
    if (!current || !target || jobBusy.value || Date.now() < jobWaitUntil.value) return
    clearTimeout(poll)
    jobBusy.value = true
    jobError.value = ''
    try {
      const result = await getTrendJob(current.token, target, current.requests.signal)
      if (session !== current) return
      const wasActive = active.value
      job.value = result
      knownJobId.value = result.jobId
      if (intent.value === target) intent.value = null
      remember(current)
      if (active.value) schedulePoll()
      else if (wasActive || !fees.value) void refreshFees()
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
    const requestId = intent.value ?? globalThis.crypto.randomUUID()
    intent.value = requestId
    remember(current)
    jobBusy.value = true
    jobError.value = ''
    submissionNote.value = ''
    try {
      const result = await submitTrendJob(current.token, requestId, current.requests.signal)
      if (session !== current) return
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
      else void refreshFees()
    } catch (failure: unknown) {
      if (session !== current) return
      if (failure instanceof ApiError && failure.status === 429) onJobRateLimit(failure)
      else jobError.value = '未收到更新确认。可以先重查任务，也可以用原编号继续。'
    } finally {
      if (session === current) jobBusy.value = false
    }
  }

  async function refreshFees(append = false): Promise<void> {
    const current = session
    const previous = fees.value
    if (!current || feesBusy.value || Date.now() < feesWaitUntil.value) return
    if (append && !previous?.nextAfter) return
    feesBusy.value = true
    feeError.value = ''
    try {
      const result = await getTrendFees(
        current.token,
        append ? (previous?.nextAfter ?? undefined) : undefined,
        current.requests.signal,
      )
      if (session !== current) return
      if (append && previous && previous.month === result.month) {
        result.pending = [...previous.pending, ...result.pending]
      } else if (append) {
        feeError.value = '账本已经跨月，请刷新本月费用。'
        return
      }
      fees.value = result
    } catch (failure: unknown) {
      if (session !== current) return
      if (failure instanceof ApiError && failure.status === 429)
        feesWaitUntil.value = Date.now() + (failure.retryAfterSeconds ?? 5) * 1000
      feeError.value = '暂时无法读取费用，请稍后重试。'
    } finally {
      if (session === current) feesBusy.value = false
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
    if (outcome === 'PENDING') return '当期已有在途或待核对的简报请求，请在下方费用面板核对。'
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
      const result = await generateTrendBriefing(
        current.token,
        briefing.value?.anchorDate,
        current.requests.signal,
      )
      if (session !== current) return
      briefing.value = result.briefing
      briefingNote.value = briefingOutcomeNote(result.outcome)
      if (result.charged) void refreshFees()
    } catch (failure: unknown) {
      if (session !== current) return
      if (failure instanceof ApiError && failure.status === 429) {
        onBriefingRateLimit(failure)
      } else if (failure instanceof ApiError && failure.message) {
        briefingError.value = failure.message
      } else {
        briefingError.value = '风口简报生成未收到完整确认，请先刷新状态与费用账本。'
      }
      void refreshFees()
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
      const result = await getTrendScheduleStatus(current.token, current.requests.signal)
      if (session !== current) return
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

  watch(
    [allowed, () => user.token, () => user.user?.id],
    ([canRead, token, ownerId]) => {
      reset()
      if (!canRead || !token || !ownerId) return
      session = {
        token,
        storageKey: `polyhouse:star-tide:${ownerId}`,
        requests: new AbortController(),
      }
      restore(session)
      void refreshFees()
      void refreshBriefing()
      void refreshScheduleStatus()
      if (intent.value || knownJobId.value) void refreshJob()
    },
    { immediate: true },
  )
  onScopeDispose(reset)

  return {
    allowed,
    job,
    fees,
    briefing,
    scheduleStatus,
    intent,
    knownJobId,
    jobBusy,
    feesBusy,
    briefingBusy,
    scheduleBusy,
    briefingGenerating,
    jobError,
    feeError,
    briefingError,
    scheduleError,
    submissionNote,
    briefingNote,
    active,
    waiting,
    briefingWaiting,
    refreshJob,
    submit,
    refreshFees,
    refreshBriefing,
    refreshScheduleStatus,
    generateBriefing,
  }
}

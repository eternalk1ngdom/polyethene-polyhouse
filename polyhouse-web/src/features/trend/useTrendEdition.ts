import { onScopeDispose, ref, watch, type ComputedRef } from 'vue'
import { ApiError } from '../../api/index'
import {
  getTrendBriefing,
  getTrendPeriods,
  getTrendReport,
  getTrendWindow,
  isTrendDate,
  type TrendBriefing,
  type TrendReport,
  type TrendWindowStats,
} from '../../api/trend'

/** 目录和详情共用当前期的快照。切期取消旧请求，7 日统计按视图切换懒加载，离开星潮即释放。 */
export function useTrendEdition(
  edition: ComputedRef<unknown>,
  inDirectory: ComputedRef<boolean>,
  windowVisible: ComputedRef<boolean>,
) {
  const report = ref<TrendReport | null>(null)
  const reportError = ref('')
  const loadingReport = ref(false)
  const noReport = ref(false)
  const dates = ref<string[]>([])
  const cursor = ref<string | null>(null)
  const loadingDates = ref(false)
  const dateError = ref(false)
  const dateBlockedUntil = ref(0)
  const windowStats = ref<TrendWindowStats | null>(null)
  const windowBriefing = ref<TrendBriefing | null>(null)
  const windowError = ref('')
  const loadingWindow = ref(false)
  let reportBlockedUntil = 0
  let windowBlockedUntil = 0
  let reportRequest: AbortController | undefined
  let datesRequest: AbortController | undefined
  let windowRequest: AbortController | undefined
  let briefingRequest: AbortController | undefined

  async function loadBriefing(anchorKey: string) {
    briefingRequest?.abort()
    const request = new AbortController()
    briefingRequest = request
    try {
      const result = await getTrendBriefing(anchorKey, request.signal)
      if (!request.signal.aborted) {
        windowBriefing.value = result.status === 'READY' ? result : null
      }
    } catch {
      // 简报读取失败静默降级，绝不阻断或干扰 2A 确定性统计展示。
      if (!request.signal.aborted) windowBriefing.value = null
    }
  }

  async function loadWindow(anchorKey?: string) {
    const key = anchorKey ?? report.value?.periodKey
    windowRequest?.abort()
    briefingRequest?.abort()
    windowError.value = ''
    loadingWindow.value = false
    if (!key || !isTrendDate(key)) {
      windowStats.value = null
      windowBriefing.value = null
      return
    }
    if (Date.now() < windowBlockedUntil) {
      windowError.value = '近 7 日统计请求过于频繁，请稍后重试。'
      return
    }
    const request = new AbortController()
    windowRequest = request
    loadingWindow.value = true
    void loadBriefing(key)
    try {
      const result = await getTrendWindow(key, request.signal)
      if (!request.signal.aborted) windowStats.value = result
    } catch (failure: unknown) {
      if (request.signal.aborted) return
      windowStats.value = null
      if (failure instanceof ApiError && failure.status === 429) {
        windowBlockedUntil = Date.now() + (failure.retryAfterSeconds ?? 2) * 1000
        windowError.value = '近 7 日统计请求过于频繁，请稍后重试。'
      } else {
        windowError.value = '近 7 日榜内统计暂时无法读取，不影响当期日报阅读。'
      }
    } finally {
      if (!request.signal.aborted) loadingWindow.value = false
    }
  }

  async function loadReport() {
    reportRequest?.abort()
    windowRequest?.abort()
    briefingRequest?.abort()
    report.value = null
    windowStats.value = null
    windowBriefing.value = null
    windowError.value = ''
    loadingWindow.value = false
    noReport.value = false
    reportError.value = ''
    loadingReport.value = false
    const day = edition.value
    if (day !== undefined && !isTrendDate(day)) {
      reportError.value = '日期格式无效，请重新选择日报期次。'
      return
    }
    if (Date.now() < reportBlockedUntil) {
      reportError.value = '请求过于频繁，请稍后重试。'
      return
    }
    const request = new AbortController()
    reportRequest = request
    loadingReport.value = true
    try {
      const result = await getTrendReport(day, request.signal)
      if (!request.signal.aborted) report.value = result
    } catch (failure: unknown) {
      if (request.signal.aborted) return
      if (failure instanceof ApiError && failure.status === 404) noReport.value = true
      else if (failure instanceof ApiError && failure.status === 429) {
        reportBlockedUntil = Date.now() + (failure.retryAfterSeconds ?? 2) * 1000
        reportError.value = '请求过于频繁，请稍后重试。'
      } else reportError.value = '暂时无法读取日报，请稍后重试。'
    } finally {
      if (!request.signal.aborted) loadingReport.value = false
    }
  }

  async function loadDates(append = false): Promise<void> {
    if (loadingDates.value || Date.now() < dateBlockedUntil.value) return
    const before = append ? cursor.value : null
    if (append && !before) return
    const request = new AbortController()
    datesRequest = request
    loadingDates.value = true
    dateError.value = false
    try {
      const result = await getTrendPeriods(before ?? undefined, request.signal)
      if (request.signal.aborted) return
      dates.value = append ? [...new Set([...dates.value, ...result.items])] : result.items
      cursor.value = result.nextBefore
    } catch (failure: unknown) {
      if (request.signal.aborted) return
      dateError.value = true
      if (failure instanceof ApiError && failure.status === 429)
        dateBlockedUntil.value = Date.now() + (failure.retryAfterSeconds ?? 2) * 1000
    } finally {
      if (!request.signal.aborted) loadingDates.value = false
    }
  }

  watch(
    edition,
    (day) => {
      if (day === undefined || day !== report.value?.periodKey) void loadReport()
    },
    { immediate: true },
  )
  watch(
    inDirectory,
    (visible) => {
      if (visible && !dates.value.length) void loadDates()
    },
    { immediate: true },
  )
  // 仅当用户切换到「近 7 日风向」视图且当期锚点就绪时才按需加载，不增加普通日报的请求与限流压力。
  watch(
    [windowVisible, () => report.value?.periodKey],
    ([visible, periodKey]) => {
      if (!visible || !periodKey) return
      if (windowStats.value?.anchorDate === periodKey) return
      void loadWindow(periodKey)
    },
    { immediate: true },
  )
  onScopeDispose(() => {
    reportRequest?.abort()
    datesRequest?.abort()
    windowRequest?.abort()
    briefingRequest?.abort()
  })
  return {
    report,
    reportError,
    loadingReport,
    noReport,
    loadReport,
    dates,
    cursor,
    dateError,
    loadingDates,
    loadDates,
    windowStats,
    windowBriefing,
    windowError,
    loadingWindow,
    loadWindow,
  }
}

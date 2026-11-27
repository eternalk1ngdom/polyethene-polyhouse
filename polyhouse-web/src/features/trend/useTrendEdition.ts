import { onScopeDispose, ref, watch, type ComputedRef } from 'vue'
import { ApiError } from '../../api/index'
import {
  getTrendBriefing,
  getTrendPeriods,
  getTrendPeriodsByMonth,
  getTrendReport,
  getTrendWindow,
  isTrendDate,
  type TrendBriefing,
  type TrendReport,
  type TrendWindowStats,
} from '../../api/trend'
import {
  extractYearMonth,
  findAdjacentAvailableDate,
  isMonthOutOfBounds,
  parseYearMonth,
  shiftMonth,
} from '../../core/calendar'

/** 单次跨月邻期探测最多翻阅的空月数量，防止极端稀疏归档连续触发限流。 */
const MAX_STEP_MONTH_PROBES = 12

/** 目录和详情共用当前期的快照。切期取消旧请求，历史期号按月缓存，7 日统计按视图切换懒加载，离开星潮即释放。 */
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
  const earliestDate = ref<string | null>(null)
  const latestDate = ref<string | null>(null)
  const cursor = ref<string | null>(null)
  const loadingDates = ref(false)
  const dateError = ref(false)
  const loadingMonth = ref(false)
  const monthError = ref('')
  const dateBlockedUntil = ref(0)
  const windowStats = ref<TrendWindowStats | null>(null)
  const windowBriefing = ref<TrendBriefing | null>(null)
  const windowError = ref('')
  const loadingWindow = ref(false)
  const loadedMonths = new Set<string>()
  let reportBlockedUntil = 0
  let windowBlockedUntil = 0
  let lastFailedDateAppend = false
  let reportRequest: AbortController | undefined
  let datesRequest: AbortController | undefined
  let monthRequest: AbortController | undefined
  let windowRequest: AbortController | undefined
  let briefingRequest: AbortController | undefined

  function mergeDates(incoming: readonly string[]) {
    if (!incoming.length) return
    dates.value = [...new Set([...dates.value, ...incoming])].sort().reverse()
  }

  function applyBounds(earliest?: string | null, latest?: string | null) {
    if (earliest) earliestDate.value = earliest
    if (latest) latestDate.value = latest
  }

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
      if (!request.signal.aborted) {
        report.value = result
        mergeDates([result.periodKey])
        if (!day) latestDate.value = result.periodKey
      }
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
      applyBounds(result.earliestDate, result.latestDate)
      lastFailedDateAppend = false
    } catch (failure: unknown) {
      if (request.signal.aborted) return
      dateError.value = true
      // 记住失败时的分页方向，重试历史时才能接回中断的游标而不是覆盖回首页。
      lastFailedDateAppend = append
      if (failure instanceof ApiError && failure.status === 429)
        dateBlockedUntil.value = Date.now() + (failure.retryAfterSeconds ?? 2) * 1000
    } finally {
      if (!request.signal.aborted) loadingDates.value = false
    }
  }

  /** 按自然月（YYYY-MM）拉取并缓存该月全部已发布日期；已缓存月份默认直接命中内存，零重复请求。 */
  async function loadMonth(yearMonth: string, force = false): Promise<string[]> {
    if (!parseYearMonth(yearMonth)) return []
    if (!force && loadedMonths.has(yearMonth)) {
      monthError.value = ''
      return dates.value.filter((item) => item.startsWith(`${yearMonth}-`))
    }
    if (Date.now() < dateBlockedUntil.value) {
      monthError.value = '日期查询过于频繁，请稍后重试。'
      return []
    }
    monthRequest?.abort()
    const request = new AbortController()
    monthRequest = request
    loadingMonth.value = true
    monthError.value = ''
    try {
      const result = await getTrendPeriodsByMonth(yearMonth, request.signal)
      if (request.signal.aborted) return []
      loadedMonths.add(yearMonth)
      mergeDates(result.items)
      applyBounds(result.earliestDate, result.latestDate)
      return result.items
    } catch (failure: unknown) {
      if (request.signal.aborted) return []
      if (failure instanceof ApiError && failure.status === 429) {
        dateBlockedUntil.value = Date.now() + (failure.retryAfterSeconds ?? 2) * 1000
        monthError.value = '日期查询过于频繁，请稍后重试。'
      } else {
        monthError.value = '本月归档暂时无法读取，请稍后重试。'
      }
      return []
    } finally {
      if (!request.signal.aborted) loadingMonth.value = false
    }
  }

  /**
   * 跨月解析当前日期的上一期或下一期：优先命中内存缓存，未命中时沿边界按月向前/向后探测。
   */
  async function resolveAdjacentDate(
    currentDate: string,
    direction: 'prev' | 'next',
  ): Promise<string | null> {
    const cached = findAdjacentAvailableDate(currentDate, dates.value, direction)
    if (cached) return cached
    let probeYm = extractYearMonth(currentDate)
    if (!probeYm) return null
    const step = direction === 'prev' ? -1 : 1
    for (let i = 0; i < MAX_STEP_MONTH_PROBES; i += 1) {
      probeYm = shiftMonth(probeYm, step)
      if (!probeYm) return null
      if (isMonthOutOfBounds(probeYm, earliestDate.value, latestDate.value)) return null
      const monthItems = await loadMonth(probeYm)
      if (monthError.value) return null
      if (monthItems.length) {
        return findAdjacentAvailableDate(currentDate, dates.value, direction)
      }
    }
    return null
  }

  /** 按上一次失败的分页方向重试历史日期，保留已加载页和游标位置。 */
  function retryDates(): Promise<void> {
    return loadDates(lastFailedDateAppend)
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
  // 当目录页当前期号就绪时，自动预热当月日历数据，打开日历面板即可零等待展示完整月视图。
  watch(
    [inDirectory, () => report.value?.periodKey],
    ([visible, periodKey]) => {
      if (!visible || !periodKey) return
      const ym = extractYearMonth(periodKey)
      if (ym && !loadedMonths.has(ym)) void loadMonth(ym)
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
    monthRequest?.abort()
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
    earliestDate,
    latestDate,
    cursor,
    dateError,
    loadingDates,
    loadingMonth,
    monthError,
    loadDates,
    loadMonth,
    resolveAdjacentDate,
    retryDates,
    windowStats,
    windowBriefing,
    windowError,
    loadingWindow,
    loadWindow,
  }
}

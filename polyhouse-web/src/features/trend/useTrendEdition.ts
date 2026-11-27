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
  findNearestAvailableDate,
  isMonthOutOfBounds,
  parseIsoDate,
  parseYearMonth,
  shiftMonth,
} from '../../core/calendar'

/** 单次跨月邻期探测最多翻阅的空月数量，防止极端稀疏归档连续触发限流。 */
const MAX_STEP_MONTH_PROBES = 12

/** 手动输入日期异步核验结果（与 ArchiveDatePicker 解耦，供离散归档按月核验） */
export type TrendDateResolveResult =
  | { status: 'available'; date: string }
  | { status: 'missing'; date: string; nearestDate: string | null }
  | { status: 'error'; message: string }

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
  const loadedMonths = ref<ReadonlySet<string>>(new Set())
  const inFlightMonths = new Map<string, { controller: AbortController; promise: Promise<string[]> }>()
  let initialDatesPromise: Promise<void> | null = null
  let reportBlockedUntil = 0
  let windowBlockedUntil = 0
  let lastFailedDateAppend = false
  let reportRequest: AbortController | undefined
  let datesRequest: AbortController | undefined
  let windowRequest: AbortController | undefined
  let briefingRequest: AbortController | undefined

  function markMonthsLoaded(yearMonths: readonly string[]) {
    let changed = false
    const next = new Set(loadedMonths.value)
    for (const ym of yearMonths) {
      if (parseYearMonth(ym) && !next.has(ym)) {
        next.add(ym)
        changed = true
      }
    }
    if (changed) loadedMonths.value = next
  }

  /**
   * 依据首屏无游标分页结果推导已被完整覆盖的自然月，直接标记入 loadedMonths 以消除首屏二次请求。
   * - nextBefore === null：全站最新至已返回最旧期已无更早数据，当月必然完整覆盖；若最旧项已触达 earliestDate，
   *   则整个 [earliestYm..topYm] 均已完整覆盖；
   * - nextBefore !== null：从最新月往回，凡当月 1 日不早于最旧返回项的整月均已被本页完整覆盖。
   */
  function markCoveredMonthsFromInitialPage(
    items: readonly string[],
    nextBefore: string | null,
    earliest?: string | null,
    latest?: string | null,
  ) {
    const topDate = latest ?? items[0] ?? null
    const topYm = extractYearMonth(topDate)
    if (!topYm) return

    const sortedItems = [...items].filter((d) => Boolean(parseIsoDate(d))).sort()
    const oldestItem = sortedItems[0] ?? null
    const covered: string[] = []

    if (nextBefore === null) {
      covered.push(topYm)
      const earliestYm = extractYearMonth(earliest)
      if (earliestYm && oldestItem && earliest && oldestItem <= earliest) {
        let cursorYm: string | null = topYm
        let guard = 0
        while (cursorYm && cursorYm >= earliestYm && guard < 120) {
          covered.push(cursorYm)
          cursorYm = shiftMonth(cursorYm, -1)
          guard += 1
        }
      }
    } else if (oldestItem) {
      let cursorYm: string | null = topYm
      let guard = 0
      while (cursorYm && `${cursorYm}-01` >= oldestItem && guard < 24) {
        covered.push(cursorYm)
        cursorYm = shiftMonth(cursorYm, -1)
        guard += 1
      }
    }

    if (covered.length) markMonthsLoaded(covered)
  }

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
    const task: Promise<void> = (async () => {
      try {
        const result = await getTrendPeriods(before ?? undefined, request.signal)
        if (request.signal.aborted) return
        dates.value = append ? [...new Set([...dates.value, ...result.items])] : result.items
        cursor.value = result.nextBefore
        applyBounds(result.earliestDate, result.latestDate)
        if (!append) {
          markCoveredMonthsFromInitialPage(
            result.items,
            result.nextBefore,
            result.earliestDate,
            result.latestDate,
          )
        }
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
        if (!append && datesRequest === request) initialDatesPromise = null
      }
    })()
    if (!append) initialDatesPromise = task
    return task
  }

  /** 按自然月（YYYY-MM）拉取并缓存该月全部已发布日期；已缓存或同月在途请求直接复用，零重复请求。 */
  async function loadMonth(yearMonth: string, force = false): Promise<string[]> {
    if (!parseYearMonth(yearMonth)) return []
    if (!force && loadedMonths.value.has(yearMonth)) {
      monthError.value = ''
      return dates.value.filter((item) => item.startsWith(`${yearMonth}-`))
    }
    const existing = inFlightMonths.get(yearMonth)
    if (!force && existing) {
      return existing.promise
    }
    if (force && existing) {
      existing.controller.abort()
      inFlightMonths.delete(yearMonth)
    }
    if (Date.now() < dateBlockedUntil.value) {
      monthError.value = '日期查询过于频繁，请稍后重试。'
      return []
    }
    const controller = new AbortController()
    monthError.value = ''
    const promise = (async (): Promise<string[]> => {
      try {
        const result = await getTrendPeriodsByMonth(yearMonth, controller.signal)
        if (controller.signal.aborted) return []
        markMonthsLoaded([yearMonth])
        mergeDates(result.items)
        applyBounds(result.earliestDate, result.latestDate)
        return result.items
      } catch (failure: unknown) {
        if (controller.signal.aborted) return []
        if (failure instanceof ApiError && failure.status === 429) {
          dateBlockedUntil.value = Date.now() + (failure.retryAfterSeconds ?? 2) * 1000
          monthError.value = '日期查询过于频繁，请稍后重试。'
        } else {
          monthError.value = '本月归档暂时无法读取，请稍后重试。'
        }
        return []
      } finally {
        if (inFlightMonths.get(yearMonth)?.controller === controller) {
          inFlightMonths.delete(yearMonth)
        }
        loadingMonth.value = inFlightMonths.size > 0
      }
    })()
    inFlightMonths.set(yearMonth, { controller, promise })
    loadingMonth.value = inFlightMonths.size > 0
    return promise
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

  /**
   * 核验指定自然日是否存在已发布日报：先命中内存，未加载月则按月拉取；
   * 若该日缺采，顺手定位最近一期有内容日期，避免用户盲跳 404。
   */
  async function ensureDateAvailable(isoDate: string): Promise<TrendDateResolveResult> {
    if (!parseIsoDate(isoDate)) {
      return { status: 'missing', date: isoDate, nearestDate: null }
    }
    if (dates.value.includes(isoDate)) {
      return { status: 'available', date: isoDate }
    }
    const ym = extractYearMonth(isoDate)
    if (!ym) {
      return { status: 'missing', date: isoDate, nearestDate: null }
    }
    if (initialDatesPromise && !loadedMonths.value.has(ym)) {
      await initialDatesPromise
      if (dates.value.includes(isoDate)) {
        return { status: 'available', date: isoDate }
      }
    }
    if (!loadedMonths.value.has(ym)) {
      await loadMonth(ym)
      if (!loadedMonths.value.has(ym)) {
        return {
          status: 'error',
          message: monthError.value || '该月归档暂时无法读取，请稍后重试。',
        }
      }
    }
    if (dates.value.includes(isoDate)) {
      return { status: 'available', date: isoDate }
    }
    let nearestDate = findNearestAvailableDate(isoDate, dates.value)
    if (!nearestDate) {
      nearestDate =
        (await resolveAdjacentDate(isoDate, 'prev')) ??
        (await resolveAdjacentDate(isoDate, 'next'))
    }
    return { status: 'missing', date: isoDate, nearestDate }
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
  // 当目录页当前期号就绪时，先等待首屏分页收敛再按需预热当月日历，避免与 loadDates 并发发重复请求。
  watch(
    [inDirectory, () => report.value?.periodKey],
    async ([visible, periodKey]) => {
      if (!visible || !periodKey) return
      const ym = extractYearMonth(periodKey)
      if (!ym) return
      if (initialDatesPromise) await initialDatesPromise
      if (!inDirectory.value || report.value?.periodKey !== periodKey) return
      if (!loadedMonths.value.has(ym)) void loadMonth(ym)
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
    for (const entry of inFlightMonths.values()) entry.controller.abort()
    inFlightMonths.clear()
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
    loadedMonths,
    cursor,
    dateError,
    loadingDates,
    loadingMonth,
    monthError,
    loadDates,
    loadMonth,
    resolveAdjacentDate,
    ensureDateAvailable,
    retryDates,
    windowStats,
    windowBriefing,
    windowError,
    loadingWindow,
    loadWindow,
  }
}

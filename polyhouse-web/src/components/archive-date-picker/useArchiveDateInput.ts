import { onScopeDispose, ref, watch } from 'vue'
import {
  extractYearMonth,
  findNearestAvailableDate,
  parseFlexibleDateInput,
  parseIsoDate,
} from '../../core/calendar'

/** 手动输入日期异步按月核验的统一返回契约 */
export type ArchiveDateResolveResult =
  | { status: 'available'; date: string }
  | { status: 'missing'; date: string; nearestDate: string | null }
  | { status: 'error'; message: string }

/** 手动输入日期校验后的内联反馈状态 */
export interface ArchiveDateInputFeedback {
  /** 错误/拦截分类 */
  kind: 'invalid' | 'out-of-bounds' | 'missing' | 'error'
  /** 面向用户的中文原因提示 */
  message: string
  /** 可选一键修复或跳转按钮文案 */
  actionLabel?: string
  /** 点击修复按钮后要直接切换到的合法归档日期 */
  actionDate?: string
  /** 网络/限流失败后供原位重试的规范日期 */
  retryDate?: string
}

/** useArchiveDateInput 组合式函数入参契约 */
export interface UseArchiveDateInputOptions {
  /** 读取当前激活锚点日期（YYYY-MM-DD 或空串） */
  getActiveDate: () => string
  /** 读取归档最早一期日期 */
  getEarliestDate: () => string | null | undefined
  /** 读取归档最新一期日期 */
  getLatestDate: () => string | null | undefined
  /** 读取已知有归档内容的日期集合或列表 */
  getAvailableDates: () => ReadonlySet<string> | readonly string[]
  /** 读取已完整加载到本地缓存的自然月集合（YYYY-MM） */
  getLoadedMonths: () => ReadonlySet<string> | null | undefined
  /** 可选异步按月核验回调（仅在目标月尚未加载时触发） */
  getResolveDate: () => ((isoDate: string) => Promise<ArchiveDateResolveResult>) | null | undefined
  /** 内容名词（用于缺采提示“YYYY-MM-DD 当日暂无XX”，如“日报”/“内容”） */
  getItemNoun: () => string
  /** 核验通过并确认切换到目标日期时调用 */
  onCommit: (isoDate: string) => void
  /** 缺采拦截时可选将日历面板定位到目标月份 */
  onLocateMonth?: (yearMonth: string) => void
}

/**
 * 归档日期手动输入状态机：管理输入草稿、4 类分级校验、最近期引导与异步核验取消隔离。
 * 遵循“保留原文便于修改、非必要零网络请求、绝不盲跳 404”的离散归档原则。
 */
export function useArchiveDateInput(options: UseArchiveDateInputOptions) {
  const draftText = ref(options.getActiveDate())
  const feedback = ref<ArchiveDateInputFeedback | null>(null)
  const resolving = ref(false)
  // 递增请求令牌：新提交、重新编辑、重置或卸载都会使旧异步回包失效，杜绝乱序竞态。
  let requestSeq = 0

  watch(
    () => options.getActiveDate(),
    (nextDate) => {
      requestSeq += 1
      resolving.value = false
      draftText.value = nextDate
      feedback.value = null
    },
  )

  function hasKnownDate(iso: string): boolean {
    const pool = options.getAvailableDates()
    if (pool instanceof Set) return pool.has(iso)
    for (const item of pool) {
      if (item === iso) return true
    }
    return false
  }

  function validBound(raw: string | null | undefined): string | null {
    return raw && parseIsoDate(raw) ? raw : null
  }

  /** 用户编辑草稿时即时清理旧错误提示并作废在途核验。 */
  function updateDraft(nextRaw: string) {
    draftText.value = nextRaw
    if (feedback.value || resolving.value) {
      requestSeq += 1
      resolving.value = false
      feedback.value = null
    }
  }

  /** 还原草稿为当前激活日期并清空全部反馈与在途请求。 */
  function resetDraft() {
    requestSeq += 1
    resolving.value = false
    draftText.value = options.getActiveDate()
    feedback.value = null
  }

  /** 按“空值 → 格式 → 边界 → 内存直出 → 缺采/异步按月核验”顺序执行提交。 */
  async function submitInput(overrideRaw?: string): Promise<boolean> {
    const raw = (overrideRaw ?? draftText.value).trim()
    if (!raw) {
      resetDraft()
      return false
    }

    const earliest = validBound(options.getEarliestDate())
    const latest = validBound(options.getLatestDate())
    const sampleDate = latest ?? options.getActiveDate() ?? '2026-11-27'
    const parsed = parseFlexibleDateInput(raw)
    if (!parsed) {
      requestSeq += 1
      resolving.value = false
      feedback.value = {
        kind: 'invalid',
        message: `请输入有效日期，如 ${sampleDate}`,
      }
      return false
    }

    if (earliest && parsed < earliest) {
      requestSeq += 1
      resolving.value = false
      feedback.value = {
        kind: 'out-of-bounds',
        message: latest
          ? `超出归档范围（${earliest} 至 ${latest}）`
          : `早于最早归档日期（${earliest}）`,
        actionLabel: `跳到最早一期 (${earliest})`,
        actionDate: earliest,
      }
      return false
    }

    if (latest && parsed > latest) {
      requestSeq += 1
      resolving.value = false
      feedback.value = {
        kind: 'out-of-bounds',
        message: earliest
          ? `超出归档范围（${earliest} 至 ${latest}）`
          : `晚于最新归档日期（${latest}）`,
        actionLabel: `跳到最新一期 (${latest})`,
        actionDate: latest,
      }
      return false
    }

    if (hasKnownDate(parsed)) {
      requestSeq += 1
      resolving.value = false
      draftText.value = parsed
      feedback.value = null
      options.onCommit(parsed)
      return true
    }

    const ym = extractYearMonth(parsed)
    const loadedMonths = options.getLoadedMonths()
    const resolver = options.getResolveDate()
    const monthAlreadyLoaded = Boolean(loadedMonths && ym && loadedMonths.has(ym))

    if (monthAlreadyLoaded || !resolver) {
      requestSeq += 1
      resolving.value = false
      if (ym) options.onLocateMonth?.(ym)
      const nearest = findNearestAvailableDate(parsed, options.getAvailableDates())
      feedback.value = {
        kind: 'missing',
        message: `${parsed} 当日暂无${options.getItemNoun()}`,
        actionLabel: nearest ? `转到最近一期 (${nearest})` : undefined,
        actionDate: nearest ?? undefined,
      }
      return false
    }

    const seq = ++requestSeq
    resolving.value = true
    feedback.value = null
    try {
      const result = await resolver(parsed)
      if (seq !== requestSeq) return false
      if (result.status === 'available') {
        draftText.value = result.date
        feedback.value = null
        options.onCommit(result.date)
        return true
      }
      if (result.status === 'missing') {
        if (ym) options.onLocateMonth?.(ym)
        const nearest =
          result.nearestDate ?? findNearestAvailableDate(parsed, options.getAvailableDates())
        feedback.value = {
          kind: 'missing',
          message: `${parsed} 当日暂无${options.getItemNoun()}`,
          actionLabel: nearest ? `转到最近一期 (${nearest})` : undefined,
          actionDate: nearest ?? undefined,
        }
        return false
      }
      feedback.value = {
        kind: 'error',
        message: result.message || '日期核验暂时失败，请稍后重试。',
        actionLabel: '重试',
        retryDate: parsed,
      }
      return false
    } catch {
      if (seq !== requestSeq) return false
      feedback.value = {
        kind: 'error',
        message: '日期核验暂时失败，请稍后重试。',
        actionLabel: '重试',
        retryDate: parsed,
      }
      return false
    } finally {
      if (seq === requestSeq) resolving.value = false
    }
  }

  /**
   * 无害失焦策略：
   * - 正在核验或已有错误提示时保留现状，便于用户查看与点击修复按钮；
   * - 空串或与当前日期一致则静默对齐；
   * - 已敲出合法且已知可用日期则平滑提交；
   * - 半成品/非法输入不盲跳也不留孤悬浮层，静默还原为当前选中日期。
   */
  function handleBlur() {
    if (resolving.value || feedback.value) return
    const active = options.getActiveDate()
    const trimmed = draftText.value.trim()
    if (!trimmed || trimmed === active) {
      draftText.value = active
      return
    }
    const parsed = parseFlexibleDateInput(trimmed)
    const earliest = validBound(options.getEarliestDate())
    const latest = validBound(options.getLatestDate())
    if (
      parsed &&
      (!earliest || parsed >= earliest) &&
      (!latest || parsed <= latest) &&
      hasKnownDate(parsed)
    ) {
      draftText.value = parsed
      options.onCommit(parsed)
      return
    }
    draftText.value = active
  }

  /** 执行反馈条内的一键修复（跳到最早/最新/最近一期，或原位重试）。 */
  function applyFeedbackAction() {
    const current = feedback.value
    if (!current) return
    if (current.actionDate) {
      requestSeq += 1
      resolving.value = false
      draftText.value = current.actionDate
      feedback.value = null
      options.onCommit(current.actionDate)
      return
    }
    if (current.retryDate) {
      void submitInput(current.retryDate)
    }
  }

  onScopeDispose(() => {
    requestSeq += 1
  })

  return {
    draftText,
    feedback,
    resolving,
    updateDraft,
    resetDraft,
    submitInput,
    handleBlur,
    applyFeedbackAction,
  }
}

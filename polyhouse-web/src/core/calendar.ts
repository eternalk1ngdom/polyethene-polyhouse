/**
 * 归档日历纯算法内核（零 DOM / Vue / 时区依赖）。
 * 所有日期计算均基于公历纯整数年月日完成，避免 Date 本地时区解析导致的跨日偏移，
 * 供公共组件 ArchiveDatePicker 与各类日更/归档工具复用。
 */

/** 解析后的 ISO 日期整数拆解 */
export interface IsoDateParts {
  /** 公历年份（>= 1000） */
  year: number
  /** 月份（1～12） */
  month: number
  /** 日期（1～31） */
  day: number
}

/** 解析后的年月整数拆解 */
export interface YearMonthParts {
  /** 公历年份（>= 1000） */
  year: number
  /** 月份（1～12） */
  month: number
}

/** 闭区间日期范围（YYYY-MM-DD） */
export interface DateRange {
  /** 区间起点（含） */
  start: string
  /** 区间终点（含） */
  end: string
}

/** 42 格标准月历中的单个日期格模型 */
export interface CalendarDayCell {
  /** ISO 日期字符串（YYYY-MM-DD） */
  date: string
  /** 日号（1～31） */
  day: number
  /** 所属年月（YYYY-MM） */
  yearMonth: string
  /** 是否属于当前展示月份 */
  inCurrentMonth: boolean
  /** 该日是否存在可访问的归档内容 */
  available: boolean
  /** 是否为当前选中日期 */
  selected: boolean
  /** 是否为最新一期 */
  isLatest: boolean
  /** 是否位于高亮区间内 */
  inRange: boolean
  /** 是否为高亮区间起点 */
  isRangeStart: boolean
  /** 是否为高亮区间终点 */
  isRangeEnd: boolean
}

/** 生成单月 42 格日历的可选上下文参数 */
export interface MonthGridOptions {
  /** 当前选中的 ISO 日期 */
  selectedDate?: string | null
  /** 最早一期的 ISO 日期（用于排除建档之前自然日的缺采统计） */
  earliestDate?: string | null
  /** 最新一期的 ISO 日期（用于标识最新期及排除未到来日期的缺采统计） */
  latestDate?: string | null
  /** 有归档内容的日期集合或数组 */
  availableDates?: ReadonlySet<string> | readonly string[]
  /** 可选闭区间高亮带（如近 7 日观察窗口） */
  highlightRange?: DateRange | null
}

/** 单月日历网格及当月覆盖统计 */
export interface MonthGridResult {
  /** 规范化的年月（YYYY-MM） */
  yearMonth: string
  /** 年份 */
  year: number
  /** 月份（1～12） */
  month: number
  /** 固定 42 格（6 行 × 7 列，周一为第一列） */
  cells: CalendarDayCell[]
  /** 当月总天数（28～31） */
  daysInMonth: number
  /** 当月有归档内容的天数 */
  availableCount: number
  /** 当月缺采天数（若存在 latestDate 且晚于该日期的自然日不计入缺采） */
  missingCount: number
}

/** 周一至周日中文表头常量 */
export const WEEKDAY_LABELS_MONDAY_FIRST = ['一', '二', '三', '四', '五', '六', '日'] as const

const ISO_DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/
const YEAR_MONTH_RE = /^(\d{4})-(\d{2})$/
const MONTH_DAYS = [0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31] as const
// Sakamoto 算法月份偏移表，用于纯整数 O(1) 计算星期几，避免依赖运行环境时区。
const SAKAMOTO_OFFSETS = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4] as const

function pad2(value: number): string {
  return value < 10 ? `0${value}` : String(value)
}

function pad4(value: number): string {
  return String(value).padStart(4, '0')
}

/** 判断公历年份是否为闰年。 */
export function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0
}

/** 返回指定公历年月的自然天数（28～31），非法月份返回 0。 */
export function daysInMonth(year: number, month: number): number {
  if (!Number.isInteger(year) || !Number.isInteger(month) || month < 1 || month > 12) return 0
  if (month === 2) return isLeapYear(year) ? 29 : 28
  return MONTH_DAYS[month] ?? 0
}

/** 严格解析 ISO 日期（YYYY-MM-DD），拒绝 2 月 30 日、年份 < 1000 等非法值。 */
export function parseIsoDate(value: string | null | undefined): IsoDateParts | null {
  if (typeof value !== 'string') return null
  const match = ISO_DATE_RE.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  if (year < 1000 || month < 1 || month > 12) return null
  const maxDay = daysInMonth(year, month)
  if (day < 1 || day > maxDay) return null
  return { year, month, day }
}

/** 将合法整数年月日格式化为 YYYY-MM-DD。 */
export function formatIsoDate(year: number, month: number, day: number): string {
  return `${pad4(year)}-${pad2(month)}-${pad2(day)}`
}

/** 严格解析年月字符串（YYYY-MM），拒绝月份越界或年份 < 1000。 */
export function parseYearMonth(value: string | null | undefined): YearMonthParts | null {
  if (typeof value !== 'string') return null
  const match = YEAR_MONTH_RE.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  if (year < 1000 || month < 1 || month > 12) return null
  return { year, month }
}

/** 将整数年月格式化为 YYYY-MM。 */
export function formatYearMonth(year: number, month: number): string {
  return `${pad4(year)}-${pad2(month)}`
}

/** 从合法 ISO 日期中提取所属年月（YYYY-MM），非法日期返回 null。 */
export function extractYearMonth(isoDate: string | null | undefined): string | null {
  const parts = parseIsoDate(isoDate)
  return parts ? formatYearMonth(parts.year, parts.month) : null
}

/**
 * 纯整数计算某公历日期是星期几（周一=0，周二=1，…，周日=6）。
 * 使用 Sakamoto 算法：先算周日=0..周六=6，再平移为周一首列索引。
 */
export function dayOfWeekMondayFirst(year: number, month: number, day: number): number {
  const adjustedYear = month < 3 ? year - 1 : year
  const sundayFirst =
    (adjustedYear +
      Math.floor(adjustedYear / 4) -
      Math.floor(adjustedYear / 100) +
      Math.floor(adjustedYear / 400) +
      (SAKAMOTO_OFFSETS[month - 1] ?? 0) +
      day) %
    7
  return (sundayFirst + 6) % 7
}

/** 将公历年月日转换为连续儒略日序数（Howard Hinnant 算法），用于精确跨月/跨年日期加减。 */
function toEpochDay(year: number, month: number, day: number): number {
  const y = month <= 2 ? year - 1 : year
  const era = Math.floor((y >= 0 ? y : y - 399) / 400)
  const yoe = y - era * 400
  const m = month + (month > 2 ? -3 : 9)
  const doy = Math.floor((153 * m + 2) / 5) + day - 1
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy
  return era * 146097 + doe - 719468
}

/** 将连续儒略日序数还原为公历年月日。 */
function fromEpochDay(epochDay: number): IsoDateParts {
  const z = epochDay + 719468
  const era = Math.floor((z >= 0 ? z : z - 146096) / 146097)
  const doe = z - era * 146097
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365)
  const y = yoe + era * 400
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100))
  const mp = Math.floor((5 * doy + 2) / 153)
  const day = doy - Math.floor((153 * mp + 2) / 5) + 1
  const month = mp + (mp < 10 ? 3 : -9)
  const year = y + (month <= 2 ? 1 : 0)
  return { year, month, day }
}

/** 按指定自然天数平移 ISO 日期（支持负数与跨年/闰年），结果年份 < 1000 或入参非法返回 null。 */
export function shiftIsoDate(isoDate: string, deltaDays: number): string | null {
  if (!Number.isInteger(deltaDays)) return null
  const parts = parseIsoDate(isoDate)
  if (!parts) return null
  const shifted = fromEpochDay(toEpochDay(parts.year, parts.month, parts.day) + deltaDays)
  if (shifted.year < 1000 || shifted.year > 9999) return null
  return formatIsoDate(shifted.year, shifted.month, shifted.day)
}

/** 按指定月数平移年月（支持负数与跨年），结果年份越界或入参非法返回 null。 */
export function shiftMonth(yearMonth: string, deltaMonths: number): string | null {
  if (!Number.isInteger(deltaMonths)) return null
  const parts = parseYearMonth(yearMonth)
  if (!parts) return null
  const totalMonths = parts.year * 12 + (parts.month - 1) + deltaMonths
  const targetYear = Math.floor(totalMonths / 12)
  const targetMonth = (totalMonths % 12 + 12) % 12 + 1
  if (targetYear < 1000 || targetYear > 9999) return null
  return formatYearMonth(targetYear, targetMonth)
}

/** 构造以 anchorDate 为终点、向前覆盖 spanDays 天的闭区间（如 7 日窗口为 [anchor-6, anchor]）。 */
export function buildTrailingDateRange(anchorDate: string | null | undefined, spanDays: number): DateRange | null {
  if (!anchorDate || !Number.isInteger(spanDays) || spanDays < 1) return null
  const anchor = parseIsoDate(anchorDate)
  if (!anchor) return null
  const normalizedAnchor = formatIsoDate(anchor.year, anchor.month, anchor.day)
  const start = shiftIsoDate(normalizedAnchor, -(spanDays - 1))
  if (!start) return null
  return { start, end: normalizedAnchor }
}

/** 判断合法 ISO 日期是否落在闭区间内（自动容忍非法范围返回 false）。 */
export function isDateInRange(isoDate: string, range: DateRange | null | undefined): boolean {
  if (!range) return false
  if (!parseIsoDate(isoDate) || !parseIsoDate(range.start) || !parseIsoDate(range.end)) return false
  if (range.start > range.end) return false
  return isoDate >= range.start && isoDate <= range.end
}

/** 将数组或集合统一为 Set，便于 O(1) 判断日期是否有内容。 */
function toDateSet(dates?: ReadonlySet<string> | readonly string[]): ReadonlySet<string> {
  if (!dates) return new Set<string>()
  return dates instanceof Set ? dates : new Set(dates)
}

/**
 * 生成指定月份的固定 42 格日历（6 行 × 7 列，周一首列）及当月覆盖统计。
 * 固定 42 格可消除翻月时面板高度抖动（CLS = 0）。
 */
export function buildMonthGrid(yearMonth: string, options: MonthGridOptions = {}): MonthGridResult {
  const parsed = parseYearMonth(yearMonth)
  if (!parsed) {
    throw new RangeError(`Invalid yearMonth: ${yearMonth}`)
  }
  const { year, month } = parsed
  const normalizedYm = formatYearMonth(year, month)
  const totalDays = daysInMonth(year, month)
  const firstDayOffset = dayOfWeekMondayFirst(year, month, 1)
  const startEpoch = toEpochDay(year, month, 1) - firstDayOffset

  const availableSet = toDateSet(options.availableDates)
  const selected = parseIsoDate(options.selectedDate) ? options.selectedDate! : null
  const earliest = parseIsoDate(options.earliestDate) ? options.earliestDate! : null
  const latest = parseIsoDate(options.latestDate) ? options.latestDate! : null
  const validRange =
    options.highlightRange &&
    parseIsoDate(options.highlightRange.start) &&
    parseIsoDate(options.highlightRange.end) &&
    options.highlightRange.start <= options.highlightRange.end
      ? options.highlightRange
      : null

  const cells: CalendarDayCell[] = []
  let availableCount = 0
  let missingCount = 0

  for (let index = 0; index < 42; index += 1) {
    const current = fromEpochDay(startEpoch + index)
    const iso = formatIsoDate(current.year, current.month, current.day)
    const cellYm = formatYearMonth(current.year, current.month)
    const inCurrentMonth = cellYm === normalizedYm
    const available = availableSet.has(iso)
    const inRange = validRange ? iso >= validRange.start && iso <= validRange.end : false

    if (inCurrentMonth) {
      if (available) {
        availableCount += 1
      } else if ((!earliest || iso >= earliest) && (!latest || iso <= latest)) {
        // 建档前（早于 earliestDate）与未来（晚于 latestDate）的自然日均不算缺采，避免首月/当月误报大量缺采。
        missingCount += 1
      }
    }

    cells.push({
      date: iso,
      day: current.day,
      yearMonth: cellYm,
      inCurrentMonth,
      available,
      selected: selected === iso,
      isLatest: latest === iso,
      inRange,
      isRangeStart: validRange ? iso === validRange.start : false,
      isRangeEnd: validRange ? iso === validRange.end : false,
    })
  }

  return {
    yearMonth: normalizedYm,
    year,
    month,
    cells,
    daysInMonth: totalDays,
    availableCount,
    missingCount,
  }
}

/**
 * 在已知有内容日期集合中查找当前日期的上一期（prev）或下一期（next）。
 * 自动跳过中间缺采日；越过已知最早/最晚边界或入参非法时返回 null。
 */
export function findAdjacentAvailableDate(
  currentDate: string,
  availableDates: ReadonlySet<string> | readonly string[],
  direction: 'prev' | 'next',
): string | null {
  if (!parseIsoDate(currentDate)) return null
  let best: string | null = null
  for (const candidate of availableDates) {
    if (!parseIsoDate(candidate)) continue
    if (direction === 'prev') {
      if (candidate < currentDate && (best === null || candidate > best)) {
        best = candidate
      }
    } else if (candidate > currentDate && (best === null || candidate < best)) {
      best = candidate
    }
  }
  return best
}

/**
 * 判断某个年月是否完全超出归档的最早/最晚日期边界。
 * 用于禁用无意义的跨界翻月或年份/月份速选格。
 */
export function isMonthOutOfBounds(
  yearMonth: string,
  earliestDate?: string | null,
  latestDate?: string | null,
): boolean {
  const ym = parseYearMonth(yearMonth)
  if (!ym) return true
  const normalized = formatYearMonth(ym.year, ym.month)
  const earliestYm = extractYearMonth(earliestDate)
  const latestYm = extractYearMonth(latestDate)
  if (earliestYm && normalized < earliestYm) return true
  if (latestYm && normalized > latestYm) return true
  return false
}

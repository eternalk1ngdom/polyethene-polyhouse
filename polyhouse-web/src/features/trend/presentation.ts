const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  month: 'long',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})
const numberFormatter = new Intl.NumberFormat('zh-CN')

/** 发布、采集和解读统一按北京时区显示，避免跨时区读者误解期号。 */
export function trendTime(instant: string): string {
  return dateFormatter.format(new Date(instant))
}

/** 缺失和实际为零的来源数据采用不同文案。 */
export function trendNumber(value: number | null): string {
  return value === null ? '未知' : numberFormatter.format(value)
}

const insightLabels: Record<string, string> = {
  ANALYZED: '本期解读',
  REUSED: '复用已有解读',
  STALE: '沿用旧解读',
  NOT_REQUIRED: '该项目无需解读',
  NOT_ANALYZED: '本期暂无解读',
  BUDGET_LIMIT: '本期解读受预算限制',
  PENDING: '解读结果待核对',
  INVALID_OUTPUT: '本次生成的解读未通过校验',
  BUSY: '本次生成通道正忙',
  MATERIAL_UNAVAILABLE: '本次缺少可用于解读的仓库资料',
  RECHECK: '本次分析时仓库记录已有更新',
  REPLAY: '本次请求已处理',
}

/** 只有任务明确记录未启用生成时，才把未分析解释为基础采集。 */
export function insightReasonLabel(reason: string): string {
  return reason === 'NOT_ANALYZED' ? '本次仅采集资料' : insightLabel(reason)
}

/** 新增后台状态时仍给读者有意义的提示，不直接展示内部状态码。 */
export function insightLabel(state: string): string {
  return Object.hasOwn(insightLabels, state) ? insightLabels[state]! : '本期解读暂不可用'
}

/** 将解读正文按换行拆分为纯文本段落，兼顾旧单段稿与新多段稿的阅读层次。 */
export function trendParagraphs(value: string): string[] {
  const parts = value
    .split(/\r?\n+/)
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
  return parts.length ? parts : [value]
}

const categoryLabels: Record<string, string> = {
  AI_AGENT: 'AI 智能体',
  AI_INFRA: 'AI 基础设施',
  FRONTEND: '前端开发',
  BACKEND: '后端服务',
  DEVTOOLS: '开发工具',
  DESKTOP: '桌面应用',
  GRAPHICS: '图形与视觉',
  OTHER: '其他方向',
}

/** 分类固定语义配色：贯穿 7 日堆叠总览条、分类卡、多日连榜、日榜与项目详情。 */
export interface TrendCategoryTone {
  /** 占比进度条与图例圆点主色。 */
  accent: string
  /** 分类胶囊柔和浅底色。 */
  bg: string
  /** 分类胶囊深色文字（与浅底满足 WCAG AA 对比度）。 */
  ink: string
}

const categoryTones: Record<string, TrendCategoryTone> = {
  DEVTOOLS: { accent: '#1e5575', bg: '#e8f1f6', ink: '#16435e' },
  AI_AGENT: { accent: '#257e8c', bg: '#e5f3f5', ink: '#175762' },
  AI_INFRA: { accent: '#c98a2b', bg: '#fbf2de', ink: '#7d5312' },
  FRONTEND: { accent: '#74589c', bg: '#f1ebf8', ink: '#513874' },
  BACKEND: { accent: '#3d5a99', bg: '#ebf0fa', ink: '#2b4273' },
  DESKTOP: { accent: '#c26551', bg: '#faedea', ink: '#843d2e' },
  GRAPHICS: { accent: '#2e7d62', bg: '#e7f4ef', ink: '#1f5c47' },
  OTHER: { accent: '#738896', bg: '#eef2f5', ink: '#495d6b' },
}

/** 返回固定八大类对应的柔和语义色；未知或空分类安全回退到海雾灰。 */
export function categoryTone(category: string | null | undefined): TrendCategoryTone {
  if (!category) return categoryTones.OTHER!
  return Object.hasOwn(categoryTones, category) ? categoryTones[category]! : categoryTones.OTHER!
}

/** 将后端固定八大类枚举转为通俗中文标签，未知代号安全回退而不暴露内部字段。 */
export function categoryLabel(category: string | null): string {
  if (!category) return '未分类'
  return Object.hasOwn(categoryLabels, category) ? categoryLabels[category]! : '其他方向'
}

/** 后端固定八大类的展示顺序；日榜筛选按此顺序列出，避免选项随每期数据跳动。 */
export const trendCategoryKeys = [
  'AI_AGENT',
  'AI_INFRA',
  'FRONTEND',
  'BACKEND',
  'DEVTOOLS',
  'DESKTOP',
  'GRAPHICS',
  'OTHER',
] as const
/** 无解读的项目没有分类，用独立筛选值表示，避免与“其他方向”混在一起。 */
export const UNCATEGORIZED = 'NONE'
/** 网址 `cat` 参数允许的全部取值。 */
export type TrendCategoryFilter = (typeof trendCategoryKeys)[number] | typeof UNCATEGORIZED

/** 网址参数只接受白名单值，手改或过期链接按“全部分类”处理。 */
export function isTrendCategoryFilter(value: unknown): value is TrendCategoryFilter {
  return (
    value === UNCATEGORIZED ||
    (typeof value === 'string' && (trendCategoryKeys as readonly string[]).includes(value))
  )
}

/** 把条目的解读分类归入筛选值：缺失为未分类，后端新增的未知代号与标签一致归入其他方向。 */
export function projectCategory(category: string | null | undefined): TrendCategoryFilter {
  if (!category) return UNCATEGORIZED
  return isTrendCategoryFilter(category) ? category : 'OTHER'
}

/** 统计一期内各分类条数；所有分类都有键，便于把 0 条的选项保留并禁用。 */
export function categoryCounts(
  categories: readonly (string | null | undefined)[],
): Map<TrendCategoryFilter, number> {
  const counts = new Map<TrendCategoryFilter, number>()
  for (const key of trendCategoryKeys) counts.set(key, 0)
  counts.set(UNCATEGORIZED, 0)
  for (const category of categories) {
    const key = projectCategory(category)
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  return counts
}

/** 连续阅读时当前篇的前后位置；`filtered` 表示是否沿用了列表筛选顺序。 */
export interface TrendNeighbors<T> {
  /** 排在当前篇之前的一篇，首篇为 null。 */
  previous: T | null
  /** 排在当前篇之后的一篇，末篇为 null。 */
  next: T | null
  /** 当前篇在序列中的序号，从 1 开始。 */
  position: number
  /** 序列总篇数。 */
  total: number
  /** 当前篇不在筛选结果中时退回整期顺序，此时为 false。 */
  filtered: boolean
}

/**
 * 按读者看到的列表顺序找前后篇。仓库名按不区分大小写比较，与详情页查找规则一致；
 * 手改网址导致当前篇不在筛选结果中时，退回整期顺序，仍能继续阅读。
 */
export function siblingProjects<T extends { fullName: string }>(
  filteredItems: readonly T[],
  allItems: readonly T[],
  fullName: string,
): TrendNeighbors<T> | null {
  const target = fullName.toLowerCase()
  const find = (list: readonly T[]) =>
    list.findIndex((item) => item.fullName.toLowerCase() === target)
  let list = filteredItems
  let index = find(list)
  // 筛选后条数与整期相同等同于未筛选，说明文字不必提示筛选条件。
  const filtered = index >= 0 && filteredItems.length < allItems.length
  if (index < 0) {
    list = allItems
    index = find(list)
  }
  if (index < 0) return null
  return {
    previous: list[index - 1] ?? null,
    next: list[index + 1] ?? null,
    position: index + 1,
    total: list.length,
    filtered,
  }
}

/** 将 0～1 比例格式化为整数百分比；分母为空时显式返回横线而非 0%。 */
export function trendPercent(value: number | null): string {
  if (value === null) return '—'
  return `${Math.round(value * 100)}%`
}

/** 将占比变化格式化为带符号百分点；仅在存在有效对比基准时使用。 */
export function trendDeltaPercent(value: number | null): string {
  if (value === null) return '—'
  const points = Math.round(value * 100)
  if (points === 0) return '持平'
  return points > 0 ? `+${points} 个百分点` : `${points} 个百分点`
}

/** 中位数可能因偶数样本出现 .5，整数保留千分位，小数保留一位；缺失不显示为 0。 */
export function trendMedianNumber(value: number | null): string {
  if (value === null) return '未知'
  if (Number.isInteger(value)) return numberFormatter.format(value)
  return numberFormatter.format(Math.floor(value)) + '.5'
}

/** 将 YYYY-MM-DD 北京期号简写为 MM-DD，直接截取避免跨时区偏移。 */
export function shortTrendDate(isoDate: string): string {
  return /^\d{4}-\d{2}-\d{2}$/.test(isoDate) ? isoDate.slice(5) : isoDate
}

/** 榜上异动身份徽标展示模型。 */
export interface TrendPresenceBadge {
  label: string
  tone: 'new-top' | 'new' | 'returning' | 'streak'
  rankRise: string | null
}

/** 判断项目是否属于当期突发新面孔（含普通新上榜与空降前 5）。 */
export function isNewFacePresence(
  presence: { status: string } | null | undefined,
): boolean {
  return presence?.status === 'NEW' || presence?.status === 'NEW_TOP'
}

/**
 * 将后端榜上异动信号映射为通俗中文徽标；无基线或连榜不足 2 天时返回 null，避免视觉噪音。
 */
export function presenceBadge(
  presence: { status: string; streakDays: number; rankDelta: number | null } | null | undefined,
): TrendPresenceBadge | null {
  if (!presence) return null
  if (presence.status === 'NEW_TOP') {
    return { label: '空降前 5', tone: 'new-top', rankRise: null }
  }
  if (presence.status === 'NEW') {
    return { label: '新上榜', tone: 'new', rankRise: null }
  }
  if (presence.status === 'RETURNING') {
    return { label: '重回榜单', tone: 'returning', rankRise: null }
  }
  if (presence.status === 'STREAK' && presence.streakDays >= 2) {
    const rankRise =
      presence.rankDelta !== null && presence.rankDelta >= 3 ? `↑${presence.rankDelta}` : null
    // 前 7 日全勤加上今日算出 8 天时统一写「连榜 7+ 天」，避免与近 7 日观察窗口认知冲突。
    const label = presence.streakDays > 7 ? '连榜 7+ 天' : `连榜 ${presence.streakDays} 天`
    return { label, tone: 'streak', rankRise }
  }
  return null
}

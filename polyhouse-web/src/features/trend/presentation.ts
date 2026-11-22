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

/** 将后端固定八大类枚举转为通俗中文标签，未知代号安全回退而不暴露内部字段。 */
export function categoryLabel(category: string | null): string {
  if (!category) return '未分类'
  return Object.hasOwn(categoryLabels, category) ? categoryLabels[category]! : '其他方向'
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

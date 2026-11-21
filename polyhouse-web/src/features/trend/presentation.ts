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

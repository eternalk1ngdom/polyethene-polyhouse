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
}

/** 新增后台状态时仍给读者有意义的提示，不直接展示内部状态码。 */
export function insightLabel(state: string): string {
  return Object.hasOwn(insightLabels, state) ? insightLabels[state]! : '本期解读暂不可用'
}

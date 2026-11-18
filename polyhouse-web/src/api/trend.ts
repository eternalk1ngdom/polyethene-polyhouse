import { ApiError, apiRequest, isRecord } from './index'

/** 解读与 GitHub 原始资料分别展示，未知字段保留缺失状态。 */
export interface TrendInsight {
  category: string
  definition: string
  painPoint: string
  comparison: string | null
  highlights: string[]
  documentedLimitations: string[]
  inferredRisks: string[]
  analyzedAt: string
  readmeVersion: string
  model: string
  ruleVersion: string
}

export interface TrendProject {
  rankNum: number
  repositoryId: string | null
  fullName: string
  repoUrl: string
  description: string | null
  primaryLanguage: string | null
  totalStars: number | null
  starsAdded: number | null
  forksCount: number | null
  license: string | null
  archived: boolean | null
  repoType: string
  classificationReason: string
  metadataStatus: string
  readmeStatus: string
  insightStatus: string
  insight: TrendInsight | null
}

export interface TrendReport {
  reportId: string
  periodType: 'DAILY'
  periodKey: string
  capturedAt: string
  publishedAt: string
  sourceUrl: string
  insightComplete: boolean
  items: TrendProject[]
}

export interface TrendPeriods {
  periodType: 'DAILY'
  items: string[]
  nextBefore: string | null
}

const jobStates = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'INTERRUPTED'] as const
const jobPhases = ['QUEUED', 'COLLECTING', 'ANALYZING', 'PUBLISHING', 'DONE'] as const

export interface TrendJob {
  jobId: string
  state: (typeof jobStates)[number]
  phase: (typeof jobPhases)[number]
  aiRequested: boolean
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  deadline: string
  reportId: string | null
  reason: string | null
  trigger: 'MANUAL' | 'SCHEDULED'
}

export interface TrendJobSubmission {
  accepted: boolean
  replayed: boolean
  job: TrendJob
}

export interface TrendPendingCharge {
  requestId: string
  repositoryId: string
  state: 'RESERVED' | 'UNKNOWN'
  reservedMicros: number
  createdAt: string
}

export interface TrendFees {
  month: string
  budgetMicros: number
  accountedMicros: number
  heldMicros: number
  remainingMicros: number
  conservativeMicros: number
  suspended: boolean
  pending: TrendPendingCharge[]
  nextAfter: string | null
}

/** 日期参数是北京期号。UTC 只用于验证日历日期，避免本机时区改变判断。 */
export function isTrendDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const day = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(day.getTime()) && day.toISOString().slice(0, 10) === value
}

/** 重试与会话恢复只接受服务端约定的小写 UUID。 */
export function isTrendId(value: unknown): value is string {
  return typeof value === 'string' && /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(value)
}

function invalid(): never {
  throw new ApiError('星潮返回的数据不完整，请稍后重试', 502)
}

function object(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : invalid()
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : invalid()
}

function optional<T>(value: unknown, decode: (input: unknown) => T): T | null {
  return value === null || value === undefined ? null : decode(value)
}

function whole(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : invalid()
}

function truth(value: unknown): boolean {
  return typeof value === 'boolean' ? value : invalid()
}

function date(value: unknown): string {
  return isTrendDate(value) ? value : invalid()
}

function instant(value: unknown): string {
  return typeof value === 'string' && value.endsWith('Z') && Number.isFinite(Date.parse(value))
    ? value
    : invalid()
}

function id(value: unknown): string {
  return isTrendId(value) ? value : invalid()
}

function choice<T extends string>(value: unknown, values: readonly T[]): T {
  return values.find((item) => item === value) ?? invalid()
}

function list<T>(value: unknown, max: number, decode: (input: unknown) => T): T[] {
  return Array.isArray(value) && value.length <= max ? value.map(decode) : invalid()
}

function insight(value: unknown): TrendInsight {
  const row = object(value)
  return {
    category: text(row.category),
    definition: text(row.definition),
    painPoint: text(row.painPoint),
    comparison: optional(row.comparison, text),
    highlights: list(row.highlights, 3, text),
    documentedLimitations: list(row.documentedLimitations, 3, text),
    inferredRisks: list(row.inferredRisks, 3, text),
    analyzedAt: instant(row.analyzedAt),
    readmeVersion: text(row.readmeVersion),
    model: text(row.model),
    ruleVersion: text(row.ruleVersion),
  }
}

function project(value: unknown): TrendProject {
  const row = object(value)
  const fullName = text(row.fullName)
  // 原始描述和解读只渲染为文字，仓库链接也须与允许的 GitHub 路径一致。
  if (
    !/^[\w.-]+\/[\w.-]+$/.test(fullName) ||
    fullName.split('/').some((part) => part === '.' || part === '..')
  )
    invalid()
  const repoUrl = `https://github.com/${fullName}`
  if (row.repoUrl !== repoUrl) invalid()
  return {
    rankNum: whole(row.rankNum),
    repositoryId: optional(row.repositoryId, text),
    fullName,
    repoUrl,
    description: optional(row.description, text),
    primaryLanguage: optional(row.primaryLanguage, text),
    totalStars: optional(row.totalStars, whole),
    starsAdded: optional(row.starsAdded, whole),
    forksCount: optional(row.forksCount, whole),
    license: optional(row.license, text),
    archived: optional(row.archived, truth),
    repoType: text(row.repoType),
    classificationReason: text(row.classificationReason),
    metadataStatus: text(row.metadataStatus),
    readmeStatus: text(row.readmeStatus),
    insightStatus: text(row.insightStatus),
    insight: optional(row.insight, insight),
  }
}

function report(value: unknown): TrendReport {
  const row = object(value)
  return {
    reportId: id(row.reportId),
    periodType: choice(row.periodType, ['DAILY']),
    periodKey: date(row.periodKey),
    capturedAt: instant(row.capturedAt),
    publishedAt: instant(row.publishedAt),
    sourceUrl: choice(row.sourceUrl, ['https://github.com/trending?since=daily']),
    insightComplete: truth(row.insightComplete),
    items: list(row.items, 25, project),
  }
}

function periods(value: unknown): TrendPeriods {
  const row = object(value)
  return {
    periodType: choice(row.periodType, ['DAILY']),
    items: list(row.items, 30, date),
    nextBefore: optional(row.nextBefore, date),
  }
}

function job(value: unknown): TrendJob {
  const row = object(value)
  return {
    jobId: id(row.jobId),
    state: choice(row.state, jobStates),
    phase: choice(row.phase, jobPhases),
    aiRequested: truth(row.aiRequested),
    createdAt: instant(row.createdAt),
    startedAt: optional(row.startedAt, instant),
    completedAt: optional(row.completedAt, instant),
    deadline: instant(row.deadline),
    reportId: optional(row.reportId, id),
    reason: optional(row.reason, text),
    trigger: choice(row.trigger, ['MANUAL', 'SCHEDULED']),
  }
}

function submission(value: unknown): TrendJobSubmission {
  const row = object(value)
  return { accepted: truth(row.accepted), replayed: truth(row.replayed), job: job(row.job) }
}

function pending(value: unknown): TrendPendingCharge {
  const row = object(value)
  return {
    requestId: id(row.requestId),
    repositoryId: text(row.repositoryId),
    state: choice(row.state, ['RESERVED', 'UNKNOWN']),
    reservedMicros: whole(row.reservedMicros),
    createdAt: instant(row.createdAt),
  }
}

function fees(value: unknown): TrendFees {
  const row = object(value)
  return {
    month: text(row.month),
    budgetMicros: whole(row.budgetMicros),
    accountedMicros: whole(row.accountedMicros),
    heldMicros: whole(row.heldMicros),
    remainingMicros: whole(row.remainingMicros),
    conservativeMicros: whole(row.conservativeMicros),
    suspended: truth(row.suspended),
    pending: list(row.pending, 50, pending),
    nextAfter: optional(row.nextAfter, id),
  }
}

/** 公共日报不携带会话，取消旧期请求不会触发账号清理。 */
export function getTrendReport(periodKey?: string, signal?: AbortSignal): Promise<TrendReport> {
  return apiRequest({ url: '/trend/report', params: { periodKey }, signal }, report)
}

/** 传入后端提供的排他游标分页，空列表与无服务分别处理。 */
export function getTrendPeriods(before?: string, signal?: AbortSignal): Promise<TrendPeriods> {
  return apiRequest({ url: '/trend/periods', params: { before }, signal }, periods)
}

/** 同一操作使用原编号重试，后台决定是否受理或回送已有任务。 */
export function submitTrendJob(
  token: string,
  requestId: string,
  signal?: AbortSignal,
): Promise<TrendJobSubmission> {
  return apiRequest(
    { url: '/manage/trend/jobs', method: 'POST', data: { requestId }, signal },
    submission,
    token,
  )
}

/** 按已知任务编号读取实际阶段。 */
export function getTrendJob(token: string, jobId: string, signal?: AbortSignal): Promise<TrendJob> {
  return apiRequest({ url: '/manage/trend/jobs', params: { jobId }, signal }, job, token)
}

/** 费用面板使用本地账本，读取本身不会支付费用。 */
export function getTrendFees(
  token: string,
  after?: string,
  signal?: AbortSignal,
): Promise<TrendFees> {
  return apiRequest({ url: '/manage/trend/fees', params: { after }, signal }, fees, token)
}

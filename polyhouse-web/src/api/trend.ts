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
  /** 单独标注的 AI 技术风向观察；旧版本归档或材料不足时为 null。 */
  trendSignal: string | null
  /** GitHub 原始简介的简明中文译文；原简介为空、本身为中文或旧版本归档时为 null。 */
  descriptionZh: string | null
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
  insightReason: string | null
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

const windowSampleStatuses = ['INSUFFICIENT', 'LIMITED', 'SUFFICIENT'] as const

export type TrendWindowSampleStatus = (typeof windowSampleStatuses)[number]

/** 锚点当日的分母与分类覆盖摘要。 */
export interface TrendWindowAnchorSummary {
  totalItems: number
  classifiedItems: number
  unclassifiedItems: number
  docListItems: number
  missingInsightItems: number
}

/** 7 日去重仓库池的分母与资料质量摘要。 */
export interface TrendWindowSummary {
  uniqueRepoCount: number
  totalAppearances: number
  classifiedRepoCount: number
  unclassifiedRepoCount: number
  docListRepoCount: number
  missingInsightRepoCount: number
  starsKnownRepoCount: number
  staleClassifiedRepoCount: number
  oldestStaleAnalyzedAt: string | null
  unverifiedIdentityCount: number
  radar3ReadyCount: number
}

/** 分类下可供核对的代表仓库摘要。 */
export interface TrendWindowSampleRepo {
  repositoryId: string | null
  identityVerified: boolean
  fullName: string
  repoUrl: string
  inAnchorDay: boolean
  appearanceDays: number
  latestRank: number
  latestStarsAdded: number | null
}

/** 单个技术分类在 7 日窗口与锚点当日的确定性聚合结果。 */
export interface TrendWindowCategoryStat {
  category: string
  anchorDayCount: number
  anchorDayShare: number | null
  windowUniqueCount: number
  windowAppearances: number
  windowShare: number | null
  shareDelta: number | null
  starsSampleCount: number
  medianStarsAdded: number | null
  maxRepoStarsAdded: number | null
  staleClassifiedCount: number
  oldestStaleAnalyzedAt: string | null
  sampleRepos: TrendWindowSampleRepo[]
}

/** 窗口内多日重复上榜仓库的观察条目。 */
export interface TrendWindowRecurringRepo {
  repositoryId: string | null
  identityVerified: boolean
  fullName: string
  repoUrl: string
  inAnchorDay: boolean
  category: string | null
  definition: string | null
  appearanceDays: number
  consecutiveDays: number
  anchorStreakDays: number
  latestRank: number
  bestRank: number
  latestStarsAdded: number | null
  medianStarsAdded: number | null
  appearedDates: string[]
  insightStatus: string
  insightAnalyzedAt: string | null
}

/** 7 日榜内风向确定性统计视图，全部指标来自已发布日报快照。 */
export interface TrendWindowStats {
  periodType: 'DAILY'
  anchorDate: string
  windowStart: string
  windowEnd: string
  windowDays: number
  coveredDays: number
  coveredDates: string[]
  missingDates: string[]
  sampleStatus: TrendWindowSampleStatus
  anchorSummary: TrendWindowAnchorSummary
  windowSummary: TrendWindowSummary
  categories: TrendWindowCategoryStat[]
  recurringRepos: TrendWindowRecurringRepo[]
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
  summary: TrendPublicationSummary | null
}

export interface TrendPublicationSummary {
  total: number
  generated: number
  reused: number
  stale: number
  skipped: number
  missing: number
  rejected: number
  pending: number
  readmeAvailable: number
  eligible: number
  currentEligible: number
  missingDescription: number
}

export interface TrendJobSubmission {
  accepted: boolean
  replayed: boolean
  job: TrendJob
}

export interface TrendPendingCharge {
  requestId: string
  repositoryId: string
  chargeKind: 'REPO' | 'BRIEFING'
  state: 'RESERVED' | 'UNKNOWN'
  reservedMicros: number
  createdAt: string
}

export type TrendBriefingSignalType = 'CONCENTRATION' | 'RECURRING' | 'EMERGING'

export interface TrendBriefingEvidenceRepo {
  fullName: string
  repositoryId: string | null
  repoUrl: string
  inAnchorDay: boolean
}

export interface TrendBriefingTheme {
  title: string
  signalType: TrendBriefingSignalType
  summary: string
  evidenceRepos: TrendBriefingEvidenceRepo[]
}

export interface TrendBriefing {
  status: 'READY' | 'ABSENT'
  anchorDate: string
  windowStart: string
  windowEnd: string
  coveredDays: number
  sampleStatus: 'INSUFFICIENT' | 'LIMITED' | 'SUFFICIENT'
  currentCoveredDays: number
  radar3ReadyCount: number
  eligible: boolean
  ineligibleReason: string | null
  stale: boolean
  generatedAt: string | null
  model: string | null
  ruleVersion: string | null
  headline: string | null
  overview: string | null
  themes: TrendBriefingTheme[]
  watchouts: string[]
}

export type TrendBriefingGenerateOutcome =
  'GENERATED' | 'REUSED' | 'PENDING' | 'BUDGET_LIMIT' | 'INVALID_OUTPUT' | 'UNAVAILABLE'

export interface TrendBriefingGenerateResult {
  outcome: TrendBriefingGenerateOutcome
  charged: boolean
  briefing: TrendBriefing
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

function ratio(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1
    ? value
    : invalid()
}

function signedRatio(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= -1 && value <= 1
    ? value
    : invalid()
}

function nonNegativeNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : invalid()
}

function repoIdentity(row: Record<string, unknown>): { fullName: string; repoUrl: string } {
  const fullName = text(row.fullName)
  // 原始描述和解读只渲染为文字，仓库链接也须与允许的 GitHub 路径一致。
  if (
    !/^[\w.-]+\/[\w.-]+$/.test(fullName) ||
    fullName.split('/').some((part) => part === '.' || part === '..')
  )
    invalid()
  const repoUrl = `https://github.com/${fullName}`
  if (row.repoUrl !== repoUrl) invalid()
  return { fullName, repoUrl }
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
    trendSignal: optional(row.trendSignal, text),
    descriptionZh: optional(row.descriptionZh, text),
    analyzedAt: instant(row.analyzedAt),
    readmeVersion: text(row.readmeVersion),
    model: text(row.model),
    ruleVersion: text(row.ruleVersion),
  }
}

function project(value: unknown): TrendProject {
  const row = object(value)
  const { fullName, repoUrl } = repoIdentity(row)
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
    insightReason: optional(row.insightReason, text),
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

function windowAnchorSummary(value: unknown): TrendWindowAnchorSummary {
  const row = object(value)
  return {
    totalItems: whole(row.totalItems),
    classifiedItems: whole(row.classifiedItems),
    unclassifiedItems: whole(row.unclassifiedItems),
    docListItems: whole(row.docListItems),
    missingInsightItems: whole(row.missingInsightItems),
  }
}

function windowSummary(value: unknown): TrendWindowSummary {
  const row = object(value)
  return {
    uniqueRepoCount: whole(row.uniqueRepoCount),
    totalAppearances: whole(row.totalAppearances),
    classifiedRepoCount: whole(row.classifiedRepoCount),
    unclassifiedRepoCount: whole(row.unclassifiedRepoCount),
    docListRepoCount: whole(row.docListRepoCount),
    missingInsightRepoCount: whole(row.missingInsightRepoCount),
    starsKnownRepoCount: whole(row.starsKnownRepoCount),
    staleClassifiedRepoCount: whole(row.staleClassifiedRepoCount),
    oldestStaleAnalyzedAt: optional(row.oldestStaleAnalyzedAt, instant),
    unverifiedIdentityCount: whole(row.unverifiedIdentityCount),
    radar3ReadyCount: whole(row.radar3ReadyCount),
  }
}

function windowSampleRepo(value: unknown): TrendWindowSampleRepo {
  const row = object(value)
  const { fullName, repoUrl } = repoIdentity(row)
  return {
    repositoryId: optional(row.repositoryId, text),
    identityVerified: truth(row.identityVerified),
    fullName,
    repoUrl,
    inAnchorDay: truth(row.inAnchorDay),
    appearanceDays: whole(row.appearanceDays),
    latestRank: whole(row.latestRank),
    latestStarsAdded: optional(row.latestStarsAdded, whole),
  }
}

function windowCategoryStat(value: unknown): TrendWindowCategoryStat {
  const row = object(value)
  return {
    category: text(row.category),
    anchorDayCount: whole(row.anchorDayCount),
    anchorDayShare: optional(row.anchorDayShare, ratio),
    windowUniqueCount: whole(row.windowUniqueCount),
    windowAppearances: whole(row.windowAppearances),
    windowShare: optional(row.windowShare, ratio),
    shareDelta: optional(row.shareDelta, signedRatio),
    starsSampleCount: whole(row.starsSampleCount),
    medianStarsAdded: optional(row.medianStarsAdded, nonNegativeNumber),
    maxRepoStarsAdded: optional(row.maxRepoStarsAdded, whole),
    staleClassifiedCount: whole(row.staleClassifiedCount),
    oldestStaleAnalyzedAt: optional(row.oldestStaleAnalyzedAt, instant),
    sampleRepos: list(row.sampleRepos, 50, windowSampleRepo),
  }
}

function windowRecurringRepo(value: unknown): TrendWindowRecurringRepo {
  const row = object(value)
  const { fullName, repoUrl } = repoIdentity(row)
  return {
    repositoryId: optional(row.repositoryId, text),
    identityVerified: truth(row.identityVerified),
    fullName,
    repoUrl,
    inAnchorDay: truth(row.inAnchorDay),
    category: optional(row.category, text),
    definition: optional(row.definition, text),
    appearanceDays: whole(row.appearanceDays),
    consecutiveDays: whole(row.consecutiveDays),
    anchorStreakDays: whole(row.anchorStreakDays),
    latestRank: whole(row.latestRank),
    bestRank: whole(row.bestRank),
    latestStarsAdded: optional(row.latestStarsAdded, whole),
    medianStarsAdded: optional(row.medianStarsAdded, nonNegativeNumber),
    appearedDates: list(row.appearedDates, 7, date),
    insightStatus: text(row.insightStatus),
    insightAnalyzedAt: optional(row.insightAnalyzedAt, instant),
  }
}

/** 7 日窗口分类统计列表上限：留出温和扩展余量，避免后端新增分类时前端硬编码 8 触发 502。 */
const MAX_WINDOW_CATEGORIES = 32

function windowStats(value: unknown): TrendWindowStats {
  const row = object(value)
  return {
    periodType: choice(row.periodType, ['DAILY']),
    anchorDate: date(row.anchorDate),
    windowStart: date(row.windowStart),
    windowEnd: date(row.windowEnd),
    windowDays: whole(row.windowDays),
    coveredDays: whole(row.coveredDays),
    coveredDates: list(row.coveredDates, 7, date),
    missingDates: list(row.missingDates, 7, date),
    sampleStatus: choice(row.sampleStatus, windowSampleStatuses),
    anchorSummary: windowAnchorSummary(row.anchorSummary),
    windowSummary: windowSummary(row.windowSummary),
    categories: list(row.categories, MAX_WINDOW_CATEGORIES, windowCategoryStat),
    recurringRepos: list(row.recurringRepos, 15, windowRecurringRepo),
  }
}

function publicationSummary(value: unknown): TrendPublicationSummary {
  const row = object(value)
  return {
    total: whole(row.total),
    generated: whole(row.generated),
    reused: whole(row.reused),
    stale: whole(row.stale),
    skipped: whole(row.skipped),
    missing: whole(row.missing),
    rejected: whole(row.rejected),
    pending: whole(row.pending),
    readmeAvailable: whole(row.readmeAvailable),
    eligible: whole(row.eligible),
    currentEligible: whole(row.currentEligible),
    missingDescription: whole(row.missingDescription),
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
    summary: optional(row.summary, publicationSummary),
  }
}

function submission(value: unknown): TrendJobSubmission {
  const row = object(value)
  return { accepted: truth(row.accepted), replayed: truth(row.replayed), job: job(row.job) }
}

function pending(value: unknown): TrendPendingCharge {
  const row = object(value)
  const kind =
    row.chargeKind === undefined || row.chargeKind === null
      ? 'REPO'
      : choice(row.chargeKind, ['REPO', 'BRIEFING'])
  return {
    requestId: id(row.requestId),
    repositoryId: text(row.repositoryId),
    chargeKind: kind,
    state: choice(row.state, ['RESERVED', 'UNKNOWN']),
    reservedMicros: whole(row.reservedMicros),
    createdAt: instant(row.createdAt),
  }
}

function briefingEvidenceRepo(value: unknown): TrendBriefingEvidenceRepo {
  const row = object(value)
  const { fullName, repoUrl } = repoIdentity(row)
  return {
    fullName,
    repositoryId: optional(row.repositoryId, text),
    repoUrl,
    inAnchorDay: truth(row.inAnchorDay),
  }
}

function briefingTheme(value: unknown): TrendBriefingTheme {
  const row = object(value)
  const title = text(row.title)
  const summary = text(row.summary)
  const evidenceRepos = list(row.evidenceRepos, 5, briefingEvidenceRepo)
  if (!title.trim() || !summary.trim() || evidenceRepos.length < 2) invalid()
  return {
    title,
    signalType: choice(row.signalType, ['CONCENTRATION', 'RECURRING', 'EMERGING']),
    summary,
    evidenceRepos,
  }
}

function briefing(value: unknown): TrendBriefing {
  const row = object(value)
  const status = choice(row.status, ['READY', 'ABSENT'])
  const themes = list(row.themes, 3, briefingTheme)
  const watchouts = list(row.watchouts, 2, text)
  const headline = optional(row.headline, text)
  const overview = optional(row.overview, text)
  if (status === 'READY' && (!headline?.trim() || !overview?.trim() || themes.length < 2)) invalid()
  return {
    status,
    anchorDate: date(row.anchorDate),
    windowStart: date(row.windowStart),
    windowEnd: date(row.windowEnd),
    coveredDays: whole(row.coveredDays),
    sampleStatus: choice(row.sampleStatus, ['INSUFFICIENT', 'LIMITED', 'SUFFICIENT']),
    currentCoveredDays: whole(row.currentCoveredDays),
    radar3ReadyCount: whole(row.radar3ReadyCount),
    eligible: truth(row.eligible),
    ineligibleReason: optional(row.ineligibleReason, text),
    stale: truth(row.stale),
    generatedAt: optional(row.generatedAt, instant),
    model: optional(row.model, text),
    ruleVersion: optional(row.ruleVersion, text),
    headline,
    overview,
    themes,
    watchouts,
  }
}

function briefingGenerate(value: unknown): TrendBriefingGenerateResult {
  const row = object(value)
  return {
    outcome: choice(row.outcome, [
      'GENERATED',
      'REUSED',
      'PENDING',
      'BUDGET_LIMIT',
      'INVALID_OUTPUT',
      'UNAVAILABLE',
    ]),
    charged: truth(row.charged),
    briefing: briefing(row.briefing),
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

/** 公共 7 日榜内风向统计不携带会话，零模型调用，只基于已入库日报做确定性聚合。 */
export function getTrendWindow(
  periodKey?: string,
  signal?: AbortSignal,
): Promise<TrendWindowStats> {
  return apiRequest({ url: '/trend/window', params: { periodKey }, signal }, windowStats)
}

/** 公共 7 日风口简报只读接口，不携带会话；无简报时返回 status=ABSENT，绝不触发生成。 */
export function getTrendBriefing(periodKey?: string, signal?: AbortSignal): Promise<TrendBriefing> {
  return apiRequest({ url: '/trend/briefing', params: { periodKey }, signal }, briefing)
}

/** 屋主按需同步触发 7 日风口简报生成；同指纹输入零费用复用，放宽单次等待超时至 65 秒以覆盖上游响应。 */
export function generateTrendBriefing(
  token: string,
  periodKey?: string,
  signal?: AbortSignal,
): Promise<TrendBriefingGenerateResult> {
  return apiRequest(
    {
      url: '/manage/trend/briefings',
      method: 'POST',
      data: periodKey ? { periodKey } : {},
      timeout: 65000,
      signal,
    },
    briefingGenerate,
    token,
  )
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

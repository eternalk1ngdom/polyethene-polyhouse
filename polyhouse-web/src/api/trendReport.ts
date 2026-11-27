import { apiRequest } from './index'
import {
  choice,
  date,
  id,
  instant,
  list,
  nonNegativeNumber,
  object,
  optional,
  ratio,
  repoIdentity,
  signedInt,
  signedRatio,
  text,
  truth,
  whole,
} from './trendCodec'

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

const presenceStatuses = ['NEW', 'NEW_TOP', 'RETURNING', 'STREAK'] as const

export type TrendPresenceStatus = (typeof presenceStatuses)[number]

/** 单个项目相对前 7 日已发布快照实时计算的榜上异动信号。 */
export interface TrendPresence {
  status: TrendPresenceStatus
  streakDays: number
  previousRank: number | null
  rankDelta: number | null
}

/** 上一已发布日在榜、当期退榜的项目速览。 */
export interface TrendDroppedProject {
  previousRank: number
  repositoryId: string | null
  fullName: string
  repoUrl: string
  primaryLanguage: string | null
  category: string | null
  previousStarsAdded: number | null
}

/** 当期日榜相对前 7 日已发布快照的异动汇总；无前序历史时为 null。 */
export interface TrendPresenceSummary {
  previousDate: string
  lookbackDays: number
  newCount: number
  newTopCount: number
  returningCount: number
  streakCount: number
  droppedItems: TrendDroppedProject[]
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
  presence: TrendPresence | null
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
  presenceSummary: TrendPresenceSummary | null
}

export interface TrendPeriods {
  periodType: 'DAILY'
  items: string[]
  nextBefore: string | null
  earliestDate: string | null
  latestDate: string | null
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

function presence(value: unknown): TrendPresence {
  const row = object(value)
  return {
    status: choice(row.status, presenceStatuses),
    streakDays: whole(row.streakDays),
    previousRank: optional(row.previousRank, whole),
    rankDelta: optional(row.rankDelta, signedInt),
  }
}

function droppedProject(value: unknown): TrendDroppedProject {
  const row = object(value)
  const { fullName, repoUrl } = repoIdentity(row)
  return {
    previousRank: whole(row.previousRank),
    repositoryId: optional(row.repositoryId, text),
    fullName,
    repoUrl,
    primaryLanguage: optional(row.primaryLanguage, text),
    category: optional(row.category, text),
    previousStarsAdded: optional(row.previousStarsAdded, whole),
  }
}

function presenceSummary(value: unknown): TrendPresenceSummary {
  const row = object(value)
  return {
    previousDate: date(row.previousDate),
    lookbackDays: whole(row.lookbackDays),
    newCount: whole(row.newCount),
    newTopCount: whole(row.newTopCount),
    returningCount: whole(row.returningCount),
    streakCount: whole(row.streakCount),
    droppedItems: list(row.droppedItems, 25, droppedProject),
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
    presence: optional(row.presence, presence),
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
    presenceSummary: optional(row.presenceSummary, presenceSummary),
  }
}

function periods(value: unknown): TrendPeriods {
  const row = object(value)
  return {
    periodType: choice(row.periodType, ['DAILY']),
    // 按月查询最多返回 31 天，必须放宽单次解码上限至 31，避免满勤大月被误判为 502。
    items: list(row.items, 31, date),
    nextBefore: optional(row.nextBefore, date),
    earliestDate: optional(row.earliestDate, date),
    latestDate: optional(row.latestDate, date),
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

/** 公共日报不携带会话，取消旧期请求不会触发账号清理。 */
export function getTrendReport(periodKey?: string, signal?: AbortSignal): Promise<TrendReport> {
  return apiRequest({ url: '/trend/report', params: { periodKey }, signal }, report)
}

/** 传入后端提供的排他游标分页，空列表与无服务分别处理。 */
export function getTrendPeriods(before?: string, signal?: AbortSignal): Promise<TrendPeriods> {
  return apiRequest({ url: '/trend/periods', params: { before }, signal }, periods)
}

/** 按指定自然月（YYYY-MM）查询该月全部已发布期号，并附带全局最早/最晚日期边界。 */
export function getTrendPeriodsByMonth(
  month: string,
  signal?: AbortSignal,
): Promise<TrendPeriods> {
  return apiRequest({ url: '/trend/periods', params: { month }, signal }, periods)
}

/** 公共 7 日榜内风向统计不携带会话，零模型调用，只基于已入库日报做确定性聚合。 */
export function getTrendWindow(
  periodKey?: string,
  signal?: AbortSignal,
): Promise<TrendWindowStats> {
  return apiRequest({ url: '/trend/window', params: { periodKey }, signal }, windowStats)
}

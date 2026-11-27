import { apiRequest } from './index'
import {
  choice,
  date,
  id,
  instant,
  list,
  object,
  optional,
  text,
  truth,
  whole,
} from './trendCodec'

const jobStates = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'INTERRUPTED'] as const
const jobPhases = ['QUEUED', 'COLLECTING', 'ANALYZING', 'PUBLISHING', 'DONE'] as const
const scheduleTodayStatuses = [
  'DISABLED',
  'PAUSED',
  'WAITING_WINDOW',
  'RUNNING',
  'PUBLISHED_AUTO',
  'PUBLISHED_MANUAL',
  'RETRY_PENDING',
  'FAILED',
  'MISSED_WINDOW',
] as const

export type TrendScheduleTodayStatus = (typeof scheduleTodayStatuses)[number]

/** 屋主发布台「每日自动采集状态」只读视图。 */
export interface TrendScheduleStatus {
  moduleEnabled: boolean
  jobsEnabled: boolean
  scheduleEnabled: boolean
  schedulePaused: boolean
  briefingAutoEnabled: boolean
  aiReady: boolean
  dailyTime: string
  retryDelayMinutes: number
  todayDate: string
  todayStatus: TrendScheduleTodayStatus
  todayPublished: boolean
  recentJobs: TrendJob[]
}

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

/** 本月 AI Token 级汇总与参考平台实扣估算，便于对照 DeepSeek 控制台核账。 */
export interface TrendFeeUsage {
  settledRequests: number
  promptTokens: number
  cacheHitTokens: number
  cacheMissTokens: number
  completionTokens: number
  estimatedActualMicros: number
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
  usage: TrendFeeUsage | null
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

function feeUsage(value: unknown): TrendFeeUsage {
  const row = object(value)
  return {
    settledRequests: whole(row.settledRequests),
    promptTokens: whole(row.promptTokens),
    cacheHitTokens: whole(row.cacheHitTokens),
    cacheMissTokens: whole(row.cacheMissTokens),
    completionTokens: whole(row.completionTokens),
    estimatedActualMicros: whole(row.estimatedActualMicros),
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
    usage: optional(row.usage, feeUsage),
  }
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

function scheduleStatus(value: unknown): TrendScheduleStatus {
  const row = object(value)
  return {
    moduleEnabled: truth(row.moduleEnabled),
    jobsEnabled: truth(row.jobsEnabled),
    scheduleEnabled: truth(row.scheduleEnabled),
    schedulePaused:
      row.schedulePaused === undefined || row.schedulePaused === null
        ? false
        : truth(row.schedulePaused),
    briefingAutoEnabled: truth(row.briefingAutoEnabled),
    aiReady: truth(row.aiReady),
    dailyTime: text(row.dailyTime),
    retryDelayMinutes: whole(row.retryDelayMinutes),
    todayDate: date(row.todayDate),
    todayStatus: choice(row.todayStatus, scheduleTodayStatuses),
    todayPublished: truth(row.todayPublished),
    recentJobs: list(row.recentJobs, 5, job),
  }
}

/** 费用面板使用本地账本，读取本身不会支付费用。 */
export function getTrendFees(
  token: string,
  after?: string,
  signal?: AbortSignal,
): Promise<TrendFees> {
  return apiRequest({ url: '/manage/trend/fees', params: { after }, signal }, fees, token)
}

/** 屋主只读查询每日自动采集开关、今日受理状态与最近 5 条任务记录。 */
export function getTrendScheduleStatus(
  token: string,
  signal?: AbortSignal,
): Promise<TrendScheduleStatus> {
  return apiRequest({ url: '/manage/trend/jobs/schedule', signal }, scheduleStatus, token)
}

/** 屋主人工急停或恢复每日自动采集；仅在服务端总闸开启时生效。 */
export function setTrendSchedulePaused(
  token: string,
  paused: boolean,
  signal?: AbortSignal,
): Promise<TrendScheduleStatus> {
  return apiRequest(
    { url: '/manage/trend/jobs/schedule/pause', method: 'POST', data: { paused }, signal },
    scheduleStatus,
    token,
  )
}

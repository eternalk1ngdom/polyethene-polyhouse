import { apiRequest } from './index'
import { choice, id, instant, list, object, optional, text, truth, whole } from './trendCodec'

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

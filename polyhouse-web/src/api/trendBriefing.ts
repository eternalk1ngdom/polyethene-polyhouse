import { apiRequest } from './index'
import {
  choice,
  date,
  instant,
  invalid,
  list,
  object,
  optional,
  repoIdentity,
  text,
  truth,
  whole,
} from './trendCodec'

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
  | 'GENERATED'
  | 'REUSED'
  | 'PENDING'
  | 'BUDGET_LIMIT'
  | 'INVALID_OUTPUT'
  | 'UNAVAILABLE'

export interface TrendBriefingGenerateResult {
  outcome: TrendBriefingGenerateOutcome
  charged: boolean
  briefing: TrendBriefing
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

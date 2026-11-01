import {
  MAX_ATTEMPTS,
  MAX_QUALITY,
  MAX_SCALE_STEPS,
  MAX_TARGET_BYTES,
  MIN_LONG_EDGE,
  MIN_QUALITY,
  SCALE_FACTOR,
} from './targetKbTypes'
import type {
  TargetKbProbe,
  TargetKbResult,
  TargetKbSearchState,
  TargetKbStep,
  TargetKbTarget,
  TargetKbVerdict,
} from './targetKbTypes'

/** 体积单位换算表；不带单位时按字节理解，避免把 "200" 误当 200KB。 */
const SIZE_UNITS: Record<string, number> = { B: 1, KB: 1024, MB: 1024 * 1024 }

/** 收敛阈值：质量差小于千分之一再细分已没有实际意义。 */
const QUALITY_EPSILON = 0.001

/** 搜索输入：目标区间与是否允许缩小尺寸。 */
export interface TargetKbSearchInput {
  /** 目标体积区间。 */
  target: TargetKbTarget
  /** 原图最长边；传 null 表示不缩小尺寸，只能靠质量调整。 */
  longEdge: number | null
}

/**
 * 解析用户输入的目标体积，接受 `200KB`、`1.5MB`、`307200` 三种写法。
 * 只解析上限，下限由调用方通过 createTargetRange 组合。
 */
export function parseTargetSize(input: string): TargetKbTarget {
  const text = input.trim().toUpperCase()
  const matched = /^(\d+(?:\.\d+)?)\s*(B|KB|MB)?$/.exec(text)
  if (!matched) throw new Error('请填写类似 200KB 或 1.5MB 的目标体积')
  const amount = Number(matched[1])
  const unit = matched[2] ?? 'B'
  const bytes = Math.round(amount * (SIZE_UNITS[unit] ?? 1))
  if (!Number.isFinite(bytes) || bytes < 1) throw new Error('目标体积必须大于 0')
  if (bytes > MAX_TARGET_BYTES) throw new Error('目标体积不能超过 20MB')
  return { minBytes: 0, maxBytes: bytes }
}

/**
 * 组合上下限并校验区间。
 * 下限存在时用于"不得小于 N 字节"的场合，由 COM 段补齐而不是提高质量，避免无谓地变大变糊。
 */
export function createTargetRange(minBytes: number, maxBytes: number): TargetKbTarget {
  if (!Number.isInteger(minBytes) || minBytes < 0) throw new Error('体积下限必须是非负整数')
  if (!Number.isInteger(maxBytes) || maxBytes < 1) throw new Error('体积上限必须是正整数')
  if (maxBytes > MAX_TARGET_BYTES) throw new Error('目标体积不能超过 20MB')
  if (minBytes > maxBytes) throw new Error('体积下限不能大于上限')
  return { minBytes, maxBytes }
}

/** 判定一次编码结果落在区间的哪一侧；调用方据此决定是否继续搜索。 */
export function judgeSize(bytes: number, target: TargetKbTarget): TargetKbVerdict {
  if (bytes > target.maxBytes) return 'too-large'
  if (bytes < target.minBytes) return 'too-small'
  return 'within'
}

/**
 * 开始搜索：先以最高质量试探一次。
 * 大多数图片本来就不超限，这样一次编码即可结束，不必牺牲画质。
 */
export function startSearch(input: TargetKbSearchInput): TargetKbStep {
  const { target, longEdge } = input
  if (!Number.isInteger(target.maxBytes) || target.maxBytes < 1)
    throw new Error('体积上限必须是正整数')
  if (target.maxBytes > MAX_TARGET_BYTES) throw new Error('目标体积不能超过 20MB')
  if (!Number.isInteger(target.minBytes) || target.minBytes < 0)
    throw new Error('体积下限必须是非负整数')
  if (target.minBytes > target.maxBytes) throw new Error('体积下限不能大于上限')
  if (longEdge !== null && (!Number.isInteger(longEdge) || longEdge < 1))
    throw new Error('原图最长边必须是正整数')

  const probe: TargetKbProbe = { quality: MAX_QUALITY, maxEdge: longEdge }
  const state: TargetKbSearchState = {
    target: { ...target },
    originalLongEdge: longEdge,
    maxEdge: longEdge,
    scaleStep: 0,
    low: MIN_QUALITY,
    high: MAX_QUALITY,
    attempts: 0,
    best: null,
    probe,
  }
  return { kind: 'probe', probe, state }
}

/**
 * 用实测字节数推进一步：返回下一次试探、成功结果或失败原因。
 * 只做纯计算，编码由调用方完成，因此可以在 Node 中用假编码器验证整个搜索过程。
 */
export function advanceSearch(state: TargetKbSearchState, bytes: number): TargetKbStep {
  if (!Number.isFinite(bytes) || bytes < 1) throw new Error('编码结果字节数无效')

  const attempts = state.attempts + 1
  let best = state.best
  let low = state.low
  let high = state.high

  if (bytes <= state.target.maxBytes) {
    // 记录"满足上限"的最大质量，最终结果取它而不是最后一次试探，避免退化到更糊的画质。
    if (best === null || state.probe.quality > best.quality)
      best = { quality: state.probe.quality, maxEdge: state.probe.maxEdge, bytes }
    low = state.probe.quality
  } else {
    high = state.probe.quality
  }

  if (attempts >= MAX_ATTEMPTS || high - low <= QUALITY_EPSILON) {
    if (best) return { kind: 'done', result: toResult(state, best) }
    return descend(state, bytes)
  }

  const probe: TargetKbProbe = {
    quality: (low + high) / 2,
    maxEdge: state.maxEdge,
  }
  const next: TargetKbSearchState = {
    ...state,
    low,
    high,
    attempts,
    best,
    probe,
  }
  return { kind: 'probe', probe, state: next }
}

/** 组装成功结果；低于下限时交给调用方补 COM 段，而不是继续压低质量。 */
function toResult(
  state: TargetKbSearchState,
  best: NonNullable<TargetKbSearchState['best']>,
): TargetKbResult {
  const needsPadding = state.target.minBytes > 0 && best.bytes < state.target.minBytes
  return {
    quality: best.quality,
    maxEdge: best.maxEdge,
    bytes: best.bytes,
    needsPadding,
    padToBytes: needsPadding ? state.target.minBytes : 0,
    scaleStep: state.scaleStep,
  }
}

/**
 * 当前档位在最低质量下仍然超限时，缩小一档尺寸重新搜索。
 * 尺寸无法再缩（未知原尺寸、已到最小边长或档位用尽）就如实失败，不返回超限文件。
 */
function descend(state: TargetKbSearchState, bytes: number): TargetKbStep {
  const nextStep = state.scaleStep + 1
  const scaled =
    state.originalLongEdge === null
      ? null
      : Math.round(state.originalLongEdge * SCALE_FACTOR ** nextStep)
  const canShrink =
    scaled !== null &&
    nextStep <= MAX_SCALE_STEPS &&
    scaled >= MIN_LONG_EDGE &&
    (state.maxEdge === null || scaled < state.maxEdge)
  if (!canShrink) return { kind: 'failed', reason: 'exceeds-target', bytes }

  const probe: TargetKbProbe = { quality: MAX_QUALITY, maxEdge: scaled }
  const next: TargetKbSearchState = {
    target: state.target,
    originalLongEdge: state.originalLongEdge,
    maxEdge: scaled,
    scaleStep: nextStep,
    low: MIN_QUALITY,
    high: MAX_QUALITY,
    attempts: 0,
    best: null,
    probe,
  }
  return { kind: 'probe', probe, state: next }
}

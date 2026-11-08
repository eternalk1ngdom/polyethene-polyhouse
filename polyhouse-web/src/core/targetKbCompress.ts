import {
  FLOOR_PROBE_RATIO,
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

/** 保留历史 KB/MB 的二进制换算；不带单位时按字节理解。 */
const SIZE_UNITS: Record<string, number> = {
  B: 1,
  KB: 1024,
  KIB: 1024,
  MB: 1024 ** 2,
  MIB: 1024 ** 2,
}
const TARGET_LIMIT_MESSAGE = `目标体积不能超过 ${MAX_TARGET_BYTES / 1024 / 1024} MiB`

/**
 * 收敛阈值：质量差小于千分之五再细分已没有实际意义。
 * 同一张照片质量差 0.005 通常不到 1% 体积，肉眼也看不出来；
 * 而从 0.001 放宽到 0.005 能少编码两次，换算成 25MP 图约省半秒。
 */
const QUALITY_EPSILON = 0.005

/** 搜索输入：目标区间与是否允许缩小尺寸。 */
export interface TargetKbSearchInput {
  /** 目标体积区间。 */
  target: TargetKbTarget
  /** 原图最长边；传 null 表示不缩小尺寸，只能靠质量调整。 */
  longEdge: number | null
}

/**
 * 解析用户输入的目标体积，接受 `200 KiB`、`1.5 MiB`、旧单位与裸字节数。
 * 只解析上限，下限由调用方通过 createTargetRange 组合。
 */
export function parseTargetSize(input: string): TargetKbTarget {
  const text = input.trim().toUpperCase()
  const matched = /^(\d+(?:\.\d+)?)\s*(B|KI?B|MI?B)?$/.exec(text)
  if (!matched) throw new Error('请填写类似 200 KiB 或 1.5 MiB 的目标体积')
  const amount = Number(matched[1])
  const unit = matched[2] ?? 'B'
  const bytes = Math.round(amount * (SIZE_UNITS[unit] ?? 1))
  if (!Number.isFinite(bytes) || bytes < 1) throw new Error('目标体积必须大于 0')
  if (bytes > MAX_TARGET_BYTES) throw new Error(TARGET_LIMIT_MESSAGE)
  return { minBytes: 0, maxBytes: bytes }
}

/**
 * 组合上下限并校验区间。
 * 下限存在时用于"不得小于 N 字节"的场合，由 COM 段补齐而不是提高质量，避免无谓地变大变糊。
 */
export function createTargetRange(minBytes: number, maxBytes: number): TargetKbTarget {
  if (!Number.isInteger(minBytes) || minBytes < 0) throw new Error('体积下限必须是非负整数')
  if (!Number.isInteger(maxBytes) || maxBytes < 1) throw new Error('体积上限必须是正整数')
  if (maxBytes > MAX_TARGET_BYTES) throw new Error(TARGET_LIMIT_MESSAGE)
  if (minBytes > maxBytes) throw new Error('体积下限不能大于上限')
  return { minBytes, maxBytes }
}

/** 判定一次编码结果落在区间的哪一侧；调用方据此决定是否继续搜索。 */
export function judgeSize(bytes: number, target: TargetKbTarget): TargetKbVerdict {
  if (bytes > target.maxBytes) return 'too-large'
  if (bytes < target.minBytes) return 'too-small'
  return 'within'
}

/** 交付计划：原样返回源文件，还是进入编码搜索。 */
export type TargetKbPlan = 'passthrough' | 'encode'

/** 判定原样交付所需的输入。 */
export interface TargetKbPlanInput {
  /** 源文件字节数。 */
  sourceBytes: number
  /** 源文件是否为 JPEG；只有 JPEG 能只改 JFIF APP0 写 DPI 而不动像素。 */
  isJpeg: boolean
  /** 目标体积区间。 */
  target: TargetKbTarget
  /** 目标 DPI；null 表示不改。 */
  dpi: number | null
}

/**
 * 判断能不能把源文件原样交给用户。
 *
 * 上限只是"允许的最大值"，不是"要填满的配额"。搜索的第一发是 q=1.0，
 * 而 q=1.0 重编码出的 JPEG 恒大于已经压过的源文件（二次有损编码、WebP 本身更省），
 * 于是 70 KB 的图会被"压"成 148 KB。所以源文件本身已落在区间内时，一个字节都不该动。
 */
export function planDelivery(input: TargetKbPlanInput): TargetKbPlan {
  const { sourceBytes, isJpeg, target, dpi } = input
  if (!Number.isFinite(sourceBytes) || sourceBytes < 1) throw new Error('源文件字节数无效')
  // 低于下限：只能编码后补 COM 段凑体积。
  if (sourceBytes < target.minBytes) return 'encode'
  // 已超上限：必须压。
  if (sourceBytes > target.maxBytes) return 'encode'
  // 要求写 DPI 但源不是 JPEG：只有转成 JPEG 才带得上 JFIF APP0。
  if (dpi !== null && !isJpeg) return 'encode'
  return 'passthrough'
}

/**
 * 开始搜索：先以最高质量试探一次。
 * 大多数图片本来就不超限，这样一次编码即可结束，不必牺牲画质。
 */
export function startSearch(input: TargetKbSearchInput): TargetKbStep {
  const { target, longEdge } = input
  if (!Number.isInteger(target.maxBytes) || target.maxBytes < 1)
    throw new Error('体积上限必须是正整数')
  if (target.maxBytes > MAX_TARGET_BYTES) throw new Error(TARGET_LIMIT_MESSAGE)
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
    floorProbed: false,
    best: null,
    probe,
  }
  return { kind: 'probe', probe, state }
}

/**
 * 用实测字节数推进一步：返回下一次试探、成功结果或失败原因。
 * 只做纯计算，编码由调用方完成，因此可以在 Node 中用假编码器验证整个搜索过程。
 *
 * 策略是"先探顶、必要时探底、再二分"：
 * 1. 先按最高质量编码，本来就不超限的图片一次就结束，不牺牲画质；
 * 2. 顶格就远超上限（达到 FLOOR_PROBE_RATIO 倍）时，先探一次最低质量：
 *    最低质量仍超限说明这一档靠质量压不下来，立刻降档——比逐格二分到 0.1
 *    少编码约十次；能压下则两端都有实测值，后续二分反而更稳；
 * 3. 其余情况在 [low, high] 上二分，记录"满足上限的最大质量"作为结果。
 */
export function advanceSearch(state: TargetKbSearchState, bytes: number): TargetKbStep {
  if (!Number.isFinite(bytes) || bytes < 1) throw new Error('编码结果字节数无效')

  const attempts = state.attempts + 1
  let best = state.best
  let low = state.low
  let high = state.high
  let failed = false

  if (bytes <= state.target.maxBytes) {
    // 记录"满足上限"的最大质量，最终结果取它而不是最后一次试探，避免退化到更糊的画质。
    if (best === null || state.probe.quality > best.quality)
      best = { quality: state.probe.quality, maxEdge: state.probe.maxEdge, bytes }
    low = state.probe.quality
  } else {
    high = state.probe.quality
    failed = true
  }

  if (attempts >= MAX_ATTEMPTS || high - low <= QUALITY_EPSILON) {
    if (best) return { kind: 'done', result: toResult(state, best) }
    return descend(state, bytes)
  }

  // 顶格编码远超上限：与其一格一格往 0.1 逼近，不如直接问"最低质量能不能装下"。
  // 探底失败时 high 会落到 0.1、与 low 重合，下一次推进自然转入降档。
  const shouldProbeFloor =
    failed &&
    !state.floorProbed &&
    state.probe.quality > MIN_QUALITY &&
    bytes >= state.target.maxBytes * FLOOR_PROBE_RATIO
  if (shouldProbeFloor) {
    const probe: TargetKbProbe = { quality: MIN_QUALITY, maxEdge: state.maxEdge }
    const next: TargetKbSearchState = {
      ...state,
      low,
      high,
      attempts,
      best,
      floorProbed: true,
      probe,
    }
    return { kind: 'probe', probe, state: next }
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
    floorProbed: state.floorProbed,
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
    floorProbed: false,
    best: null,
    probe,
  }
  return { kind: 'probe', probe, state: next }
}

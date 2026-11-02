/**
 * 妙压（TargetKB）共享类型与上限常量。
 * 这里只放纯数据定义，不引用 DOM、Vue 或浏览器 API，便于在 Node 中直接测试。
 */

/** 目标体积上限的最大值：20 MiB，超过这个量级应先评估浏览器内存与耗时。 */
export const MAX_TARGET_BYTES = 20 * 1024 * 1024

/** 二分搜索的质量下界；再低会明显糊化，此时应改为缩小尺寸。 */
export const MIN_QUALITY = 0.1

/** 质量上界固定为 1，先尝试"不损失质量"是否已满足目标。 */
export const MAX_QUALITY = 1

/** 单个档位内最多编码次数，控制最坏情况下的等待时间。 */
export const MAX_ATTEMPTS = 12

/**
 * 探底阈值：最高质量的字节数达到上限这么多倍时，先探一次最低质量。
 *
 * 同一张照片 q=1.0 与 q=0.1 的字节数通常相差 8~12 倍，所以这个倍数正好是
 * "最低质量也可能压不下来"的界线：低于它，质量区间里一定有解，逐格二分最快；
 * 高于它，很可能整档无解，先探一次底就能立刻决定是否降档。
 */
export const FLOOR_PROBE_RATIO = 8

/** 降档时最长边的缩放比例，每次缩小约 15%。 */
export const SCALE_FACTOR = 0.85

/** 最多降档次数，避免无限缩小到无意义的尺寸。 */
export const MAX_SCALE_STEPS = 6

/** 允许的最短边像素；低于此值即使满足体积也失去使用价值。 */
export const MIN_LONG_EDGE = 64

/** DPI 允许范围，覆盖屏幕预览到印刷常见值。 */
export const MIN_DPI = 1

/** 与 MIN_DPI 配套的上限，拒绝明显错误的输入。 */
export const MAX_DPI = 1200

/** 目标体积区间；下限为 0 表示只限制上限。 */
export interface TargetKbTarget {
  /** 允许的最小字节数，0 表示没有下限。 */
  minBytes: number
  /** 允许的最大字节数，必须为正且不超过 MAX_TARGET_BYTES。 */
  maxBytes: number
}

/** 一次体积判定的三态结果。 */
export type TargetKbVerdict = 'within' | 'too-large' | 'too-small'

/** 一次试探使用的编码参数。 */
export interface TargetKbProbe {
  /** JPEG 质量，取值在 MIN_QUALITY 与 MAX_QUALITY 之间。 */
  quality: number
  /** 当前档位允许的最长边；null 表示不缩放尺寸。 */
  maxEdge: number | null
}

/** 搜索过程中已确认"不超过上限"的最好结果。 */
export interface TargetKbCandidate {
  /** 该结果使用的质量。 */
  quality: number
  /** 该结果使用的最长边。 */
  maxEdge: number | null
  /** 调用方实测的字节数。 */
  bytes: number
}

/** 搜索状态；每次推进返回新对象，不原地修改，便于在测试中比较前后状态。 */
export interface TargetKbSearchState {
  /** 目标体积区间。 */
  target: TargetKbTarget
  /** 原图最长边，用于逐档缩小；null 表示不允许靠缩尺寸降体积。 */
  originalLongEdge: number | null
  /** 当前档位的最长边。 */
  maxEdge: number | null
  /** 已降档次数，0 表示保持原尺寸。 */
  scaleStep: number
  /** 当前档位的质量下界。 */
  low: number
  /** 当前档位的质量上界。 */
  high: number
  /** 当前档位已评估次数，用于限制总编码次数。 */
  attempts: number
  /** 当前档位是否已探过最低质量；避免同一档位重复探底。 */
  floorProbed: boolean
  /** 满足上限的最大质量结果。 */
  best: TargetKbCandidate | null
  /** 本次交给调用方编码的参数。 */
  probe: TargetKbProbe
}

/** 成功结果；低于下限时由调用方补 COM 段，而不是继续压质量。 */
export interface TargetKbResult {
  /** 最终采用的质量。 */
  quality: number
  /** 最终采用的最长边。 */
  maxEdge: number | null
  /** 该参数下的实测字节数。 */
  bytes: number
  /** 是否低于下限、需要用 COM 段补齐。 */
  needsPadding: boolean
  /** 补齐目标字节数；不需要补齐时为 0。 */
  padToBytes: number
  /** 已降档次数，用于向用户说明"已缩小过尺寸"。 */
  scaleStep: number
}

/** 失败原因；目前只有"压到极限仍超上限"这一种，其余错误直接抛出。 */
export type TargetKbFailure = 'exceeds-target'

/** 搜索的一步：继续试探、成功或失败。 */
export type TargetKbStep =
  | { readonly kind: 'probe'; readonly probe: TargetKbProbe; readonly state: TargetKbSearchState }
  | { readonly kind: 'done'; readonly result: TargetKbResult }
  | { readonly kind: 'failed'; readonly reason: TargetKbFailure; readonly bytes: number | null }

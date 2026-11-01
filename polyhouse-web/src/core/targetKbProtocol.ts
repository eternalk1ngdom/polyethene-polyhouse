/**
 * 妙压的 Worker 消息协议。
 * 与妙替的差别：妙压一次任务要往返多次编码，所以每条消息都带递增 id，
 * 由页面与 Worker 共同维护"当前有效 id"，用它丢弃已作废任务的迟到回调。
 */
import { createTargetRange } from './targetKbCompress'
import { MAX_DPI, MIN_DPI } from './targetKbTypes'
import type { TargetKbTarget } from './targetKbTypes'

/** 失败原因；与页面提示文案一一对应，不把内部异常直接抛给用户。 */
export type TargetKbFailureReason =
  | 'file-too-large'
  | 'pixels-too-large'
  | 'exceeds-target'
  | 'decode-failed'
  | 'encode-failed'
  | 'invalid-request'
  | 'cancelled'
  | 'internal'

/** 单次试探的实测记录；用于验收报告与后续进度展示。 */
export interface TargetKbProbeRecord {
  /** 本次试探使用的 JPEG 质量。 */
  quality: number
  /** 本次试探使用的最长边；null 表示未缩放。 */
  maxEdge: number | null
  /** 编码后的字节数。 */
  bytes: number
  /** 本次编码耗时（毫秒）。 */
  elapsedMs: number
}

/** 提交一次压缩任务。 */
export interface TargetKbEncodeRequest {
  /** 递增任务编号，由页面生成；Worker 用它判断回调是否已过期。 */
  id: number
  /** 请求类型。 */
  kind: 'encode'
  /** 原图字节；通过 transfer 转移，不在两端各留一份。 */
  bytes: ArrayBuffer
  /** 目标体积区间。 */
  target: TargetKbTarget
  /** 是否允许缩小尺寸降档；false 时只能靠质量逼近，避免用户不想改分辨率却被缩图。 */
  allowShrink: boolean
  /** 目标 DPI；null 表示保持原文件的 JFIF 密度不动。 */
  dpi: number | null
}

/** 取消任务；只作废回调，不强行终止 Worker，避免每次取消都重建线程。 */
export interface TargetKbCancelRequest {
  /** 要取消的任务编号。 */
  id: number
  /** 请求类型。 */
  kind: 'cancel'
}

/** Worker 接受的请求。 */
export type TargetKbRequest = TargetKbEncodeRequest | TargetKbCancelRequest

/** 补齐结果；低于下限但差额不足 5 字节时无法精确补齐，需要如实告知。 */
export interface TargetKbPadding {
  /** 目标字节数；未要求补齐时为 0。 */
  targetBytes: number
  /** 实际新增字节数。 */
  added: number
  /** 是否达到目标字节数。 */
  satisfied: boolean
}

/** 成功结果。 */
export interface TargetKbEncodeResult {
  /** 输出 JPEG 字节；transfer 后 Worker 侧失效。 */
  bytes: ArrayBuffer
  /** 输出像素宽。 */
  width: number
  /** 输出像素高。 */
  height: number
  /** 最终采用的质量；原样交付（未编码）时为 null。 */
  quality: number | null
  /** 最终采用的最长边；null 表示未缩放。 */
  maxEdge: number | null
  /** 已降档次数。 */
  scaleStep: number
  /** 是否一个字节都没改、直接返回了源文件。 */
  passthrough: boolean
  /** 实际输出类型；原样交付时就是源文件的类型。 */
  mime: string
  /** 每次试探的实测记录，按发生顺序；原样交付时为空。 */
  probes: TargetKbProbeRecord[]
  /** COM 段补齐情况。 */
  padding: TargetKbPadding
  /** 实际写入的 DPI；null 表示未改动。 */
  dpi: number | null
  /** 整个任务耗时（毫秒）。 */
  elapsedMs: number
}

/** 进行中的试探进度；让页面在大图上能看到"还在动"，而不是只转圈。 */
export interface TargetKbProgress {
  /** 已完成试探次数。 */
  attempts: number
  /** 最近一次试探的质量。 */
  quality: number
  /** 最近一次试探的字节数。 */
  bytes: number
}

/** Worker 回复；成功带结果，失败只带原因与提示，不返回半成品。 */
export interface TargetKbReply {
  /** 对应请求的 id。 */
  id: number
  /** 是否成功。 */
  ok: boolean
  /** 成功时的输出。 */
  result?: TargetKbEncodeResult
  /** 进行中的进度；与 result 互斥，收到它不代表任务结束。 */
  progress?: TargetKbProgress
  /** 失败原因。 */
  reason?: TargetKbFailureReason
  /** 可直接展示的中文提示。 */
  message?: string
}

/**
 * 判断一条回复是否属于当前任务。
 * 0 表示"当前没有有效任务"（例如已取消），此时任何回复都应丢弃。
 */
export function isCurrentReply(replyId: number, activeId: number): boolean {
  return Number.isInteger(replyId) && Number.isInteger(activeId) && activeId > 0 && replyId === activeId
}

/** 校验目标区间；直接复用内核规则，避免协议层另立一套上下限判断。 */
function parseTarget(value: unknown): TargetKbTarget {
  if (typeof value !== 'object' || value === null) throw new Error('缺少目标体积')
  const record = value as Record<string, unknown>
  const { minBytes, maxBytes } = record
  if (typeof minBytes !== 'number' || typeof maxBytes !== 'number')
    throw new Error('目标体积必须是数字')
  return createTargetRange(minBytes, maxBytes)
}

/**
 * 校验 Worker 收到的消息。
 * postMessage 的数据在类型层面是不可信的（结构化克隆不带类型），
 * 所以进 Worker 前必须逐字段确认，避免坏参数把搜索推进到非法状态。
 */
export function parseRequest(data: unknown): TargetKbRequest {
  if (typeof data !== 'object' || data === null) throw new Error('请求格式无效')
  const record = data as Record<string, unknown>

  const { id, kind } = record
  if (typeof id !== 'number' || !Number.isInteger(id) || id < 1) throw new Error('请求编号无效')
  if (kind === 'cancel') return { id, kind: 'cancel' }
  if (kind !== 'encode') throw new Error('未知的请求类型')

  const bytes = record.bytes
  if (!(bytes instanceof ArrayBuffer) || bytes.byteLength === 0) throw new Error('缺少图片数据')

  const allowShrink = record.allowShrink
  if (typeof allowShrink !== 'boolean') throw new Error('缺少是否允许缩小尺寸')

  const rawDpi = record.dpi
  let dpi: number | null = null
  if (rawDpi !== null && rawDpi !== undefined) {
    if (typeof rawDpi !== 'number' || !Number.isInteger(rawDpi) || rawDpi < MIN_DPI || rawDpi > MAX_DPI)
      throw new Error(`DPI 必须是 ${MIN_DPI}～${MAX_DPI} 的整数`)
    dpi = rawDpi
  }

  return { id, kind: 'encode', bytes, target: parseTarget(record.target), allowShrink, dpi }
}

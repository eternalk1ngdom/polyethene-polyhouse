/**
 * 妙压批量任务的边界与命名规则。
 * 只放纯函数，便于在 Node 中验证上限、汇总与 ZIP 内命名，不依赖 DOM 或 fflate。
 */

/** 一批最多处理多少张；再多串行等待过长，用户也难以逐张核对。 */
export const MAX_BATCH_FILES = 20

/** 一批输入总字节上限；单张 20 MiB 由 Worker 再挡一次，这里管的是整批。 */
export const MAX_BATCH_BYTES = 60 * 1024 * 1024

/** 单张图的处理状态。 */
export type TargetKbBatchStatus = 'pending' | 'running' | 'done' | 'failed'

/** 列表中一行的展示数据；输出字节单独存放，避免把大数组放进响应式对象。 */
export interface TargetKbBatchRow {
  /** 原始文件名。 */
  name: string
  /** 原始字节数。 */
  size: number
  /** 当前状态。 */
  status: TargetKbBatchStatus
  /** 成功后的输出字节数。 */
  outputBytes?: number
  /** 失败原因，直接展示给用户。 */
  message?: string
}

/** 批量汇总；用于"成功几张、失败几张、总体积变化"的一行说明。 */
export interface TargetKbBatchSummary {
  /** 总张数。 */
  total: number
  /** 成功张数。 */
  done: number
  /** 失败张数。 */
  failed: number
  /** 尚未处理（含正在处理）的张数。 */
  pending: number
  /** 所选文件的输入总字节。 */
  inputBytes: number
  /** 成功项的输入总字节；体积对比只用它，避免把失败文件的体积算进压缩前。 */
  doneInputBytes: number
  /** 成功项的输出总字节。 */
  outputBytes: number
}

/** 批量校验只关心名称与体积，不读文件内容。 */
export interface TargetKbBatchEntry {
  /** 文件名，用于提示。 */
  name: string
  /** 字节数。 */
  size: number
}

/** 把字节数换成一位小数的 MB，供提示文案使用。 */
function toMegabytes(bytes: number): string {
  return (bytes / (1024 * 1024)).toFixed(1)
}

/**
 * 校验一批输入。
 * 在选图阶段就拦住，避免用户等了一轮串行处理才发现超限。
 */
export function validateBatch(entries: readonly TargetKbBatchEntry[]): void {
  if (entries.length === 0) throw new Error('请至少选择一张图片')
  if (entries.length > MAX_BATCH_FILES)
    throw new Error(`一次最多 ${MAX_BATCH_FILES} 张图片，请分批处理`)
  if (entries.some((entry) => !Number.isFinite(entry.size) || entry.size < 1))
    throw new Error('存在空文件或无法读取的文件，请重新选择')

  const total = entries.reduce((sum, entry) => sum + entry.size, 0)
  if (total > MAX_BATCH_BYTES)
    throw new Error(`合计 ${toMegabytes(total)}MB，超过 ${toMegabytes(MAX_BATCH_BYTES)}MB 上限，请分批处理`)
}

/** 汇总当前进度；未处理的项不计入输出体积，避免把"还没算"当成 0 字节。 */
export function summarizeBatch(rows: readonly TargetKbBatchRow[]): TargetKbBatchSummary {
  let done = 0
  let failed = 0
  let pending = 0
  let inputBytes = 0
  let doneInputBytes = 0
  let outputBytes = 0
  for (const row of rows) {
    inputBytes += row.size
    if (row.status === 'done') {
      done += 1
      doneInputBytes += row.size
      outputBytes += row.outputBytes ?? 0
    } else if (row.status === 'failed') failed += 1
    else pending += 1
  }
  return { total: rows.length, done, failed, pending, inputBytes, doneInputBytes, outputBytes }
}

/**
 * 清掉文件名里的路径分隔符与 Windows 非法字符。
 * 这些名字会进入 ZIP 与下载文件名，留着会导致解压失败或覆盖同名文件。
 */
export function sanitizeName(name: string): string {
  const cleaned = name
    .replace(/[\\/:*?"<>|]/g, '_')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^\.+/, '')
  return cleaned || 'image'
}

/** 去掉扩展名后的基名；扩展名统一改成 .jpg，因为输出固定是 JPEG。 */
function baseName(name: string): string {
  const base = sanitizeName(name).replace(/\.[^.]+$/, '').trim()
  return base || 'image'
}

/** 按实际字节数取整到 KB，下载名与 ZIP 内名称都用它，方便用户核对体积。 */
function kilobytes(bytes: number): number {
  return Math.max(1, Math.round(bytes / 1024))
}

/** 单张下载名：原名 + 实际体积，保持与页面展示一致。 */
export function outputFileName(originalName: string, bytes: number): string {
  return `${baseName(originalName)}-${kilobytes(bytes)}KB.jpg`
}

/**
 * ZIP 内名称：序号 + 原名 + 体积。
 * 序号同时解决同名文件互相覆盖的问题，并让解压后的顺序与列表一致。
 */
export function zipEntryName(index: number, originalName: string, bytes: number): string {
  const order = String(index + 1).padStart(2, '0')
  return `${order}-${baseName(originalName)}-${kilobytes(bytes)}KB.jpg`
}

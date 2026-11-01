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
  /** 是否原样返回（源文件已在目标区间内，未做压缩）。 */
  passthrough?: boolean
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
    throw new Error(`一次最多 ${MAX_BATCH_FILES} 张图片，请减少选择或先移除部分图片`)
  if (entries.some((entry) => !Number.isFinite(entry.size) || entry.size < 1))
    throw new Error('存在空文件或无法读取的文件，请重新选择')

  const total = entries.reduce((sum, entry) => sum + entry.size, 0)
  if (total > MAX_BATCH_BYTES)
    throw new Error(
      `合计 ${toMegabytes(total)}MB，超过 ${toMegabytes(MAX_BATCH_BYTES)}MB 上限，请减少选择或先移除部分图片`,
    )
}

/** 合并列表时只需要这几个字段，调用方传 File 也满足。 */
export interface TargetKbPickLike {
  /** 文件名。 */
  name: string
  /** 字节数。 */
  size: number
  /** 最后修改时间，用于区分同名同体积的两份不同文件。 */
  lastModified: number
  /** MIME 类型，用来挡掉非图片。 */
  type: string
}

/** 合并结果：真正新增的项，以及被跳过的数量（用于给用户一句说明）。 */
export interface TargetKbMergeResult<T> {
  /** 应当追加到列表末尾的项。 */
  added: T[]
  /** 与已有列表重复的数量。 */
  duplicates: number
  /** 非图片数量。 */
  notImages: number
}

/**
 * 把新拖入的文件合并到已有列表：挡掉非图片、挡掉重复项。
 * 用户常常先拖一张试、再陆续补齐，所以这里是追加语义；重复判定用"同名 + 同体积 + 同修改时间"，
 * 只看名字会把同名不同内容的文件误判成重复。
 */
export function mergePicked<T extends TargetKbPickLike>(
  existing: readonly TargetKbPickLike[],
  incoming: readonly T[],
): TargetKbMergeResult<T> {
  const seen = new Set(existing.map(fileKey))
  const added: T[] = []
  let duplicates = 0
  let notImages = 0
  for (const file of incoming) {
    if (!file.type.startsWith('image/')) {
      notImages += 1
      continue
    }
    const key = fileKey(file)
    // 同一批里重复也要挡：一次拖入两个同路径文件时不该出现两份。
    if (seen.has(key)) {
      duplicates += 1
      continue
    }
    seen.add(key)
    added.push(file)
  }
  return { added, duplicates, notImages }
}

/** 去重用的键；用换行分隔，避免文件名里含分隔符时串味。 */
function fileKey(file: TargetKbPickLike): string {
  return `${file.name}\n${file.size}\n${file.lastModified}`
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

/** 去掉扩展名后的基名；扩展名由调用方按实际输出类型决定。 */
function baseName(name: string): string {
  const base = sanitizeName(name).replace(/\.[^.]+$/, '').trim()
  return base || 'image'
}

/**
 * 按输出类型取下载后缀。
 * 原样交付时输出就是源文件的格式，不能一律写 .jpg——否则用户会拿到一个后缀是
 * jpg、内容却是 PNG 的文件。
 */
export function mimeExtension(mime: string): string {
  switch (mime) {
    case 'image/jpeg':
      return 'jpg'
    case 'image/png':
      return 'png'
    case 'image/webp':
      return 'webp'
    case 'image/avif':
      return 'avif'
    default:
      return 'bin'
  }
}

/** 按实际字节数取整到 KB，下载名与 ZIP 内名称都用它，方便用户核对体积。 */
function kilobytes(bytes: number): number {
  return Math.max(1, Math.round(bytes / 1024))
}

/** 单张下载名：原名 + 实际体积 + 实际后缀，保持与页面展示一致。 */
export function outputFileName(originalName: string, bytes: number, ext = 'jpg'): string {
  return `${baseName(originalName)}-${kilobytes(bytes)}KB.${ext}`
}

/**
 * ZIP 内名称：序号 + 原名 + 体积 + 实际后缀。
 * 序号同时解决同名文件互相覆盖的问题，并让解压后的顺序与列表一致。
 */
export function zipEntryName(
  index: number,
  originalName: string,
  bytes: number,
  ext = 'jpg',
): string {
  const order = String(index + 1).padStart(2, '0')
  return `${order}-${baseName(originalName)}-${kilobytes(bytes)}KB.${ext}`
}

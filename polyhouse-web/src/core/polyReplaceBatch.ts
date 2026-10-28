import { replaceText, type ReplaceRule } from './acReplacer'
import type { VirtualFileEntry } from './types'

/** 处理成功的单个文件，不携带 DOM File 对象。 */
export interface ReplacedFile {
  /** 下载用文件名，多文件时带序号防止重名覆盖。 */
  name: string
  /** 完整 UTF-8 结果，保留原始 BOM 和换行。 */
  content: Uint8Array<ArrayBuffer>
  /** 受限预览不把全部文本重复传回 UI。 */
  preview: string
  /** 替换次数，0 也属于成功。 */
  count: number
  /** 是否因长度上限截断预览。 */
  truncated: boolean
}

/** 选择阶段和 Worker 共用限制，阻止读入过大的整批文件。 */
export function validateBatch(files: readonly { relativePath: string; size: number }[]): void {
  if (!files.length || files.length > 20) throw new Error('请选择 1～20 个文件')
  let total = 0
  for (const file of files) {
    // 此阶段仅支持平面多选；拒绝路径分隔符和控制字符，ZIP 不接受外部路径。
    if (
      !file.relativePath ||
      file.relativePath.length > 200 ||
      /[\\/\x00-\x1f<>:"|?*]/u.test(file.relativePath) ||
      /^\.+$/u.test(file.relativePath)
    )
      throw new Error('文件名不符合要求，请重命名后重试')
    if (!Number.isSafeInteger(file.size) || file.size < 0 || file.size > 2 * 1024 * 1024)
      throw new Error('每个文件不能超过 2 MiB')
    total += file.size
  }
  if (total > 5 * 1024 * 1024) throw new Error('所选文件总大小不能超过 5 MiB')
}

/** 原子处理整批文件：任一文件失败则不返回可下载的部分结果。 */
export function replaceBatch(
  files: readonly VirtualFileEntry[],
  rules: readonly ReplaceRule[],
): ReplacedFile[] {
  validateBatch(
    files.map((file) => ({ relativePath: file.relativePath, size: file.content.byteLength })),
  )
  const output: ReplacedFile[] = []
  let total = 0
  for (const [index, file] of files.entries()) {
    const bytes = new Uint8Array(file.content)
    if (bytes.includes(0)) throw new Error(`第 ${index + 1} 个文件含二进制内容，请移除后重试`)
    let text: string
    try {
      text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes)
    } catch {
      throw new Error(`第 ${index + 1} 个文件不是 UTF-8 文本，请转换编码后重试`)
    }
    const result = replaceText(text, rules)
    const content = new TextEncoder().encode(result.text)
    // 按真实导出字节统计，修正以前把字符数称为 MB 的不准确提示。
    total += content.byteLength
    if (content.byteLength > 8 * 1024 * 1024 || total > 16 * 1024 * 1024)
      throw new Error('结果超过大小限制：单文件 8 MiB，整批 16 MiB')
    const dot = file.relativePath.lastIndexOf('.')
    const base =
      dot > 0
        ? `${file.relativePath.slice(0, dot)}-妙替${file.relativePath.slice(dot)}`
        : `${file.relativePath}-妙替.txt`
    output.push({
      name: files.length > 1 ? `${index + 1}-${base}` : base,
      content,
      preview: result.text.slice(0, 10000),
      count: result.count,
      truncated: result.text.length > 10000,
    })
  }
  return output
}

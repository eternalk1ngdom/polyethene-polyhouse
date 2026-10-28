import { replaceText, type ReplaceRule } from './acReplacer'
import type { VirtualFileEntry } from './types'
import type { InputEncoding, ReplaceMode } from './polyReplaceProtocol'

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

/** 孤立 UTF-16 代理项不能无损编码为 UTF-8，提前拒绝而不是替换成乱码。 */
function hasInvalidUnicode(text: string): boolean {
  for (const character of text) {
    const code = character.charCodeAt(0)
    if (character.length === 1 && code >= 0xd800 && code <= 0xdfff) return true
  }
  return false
}

/** 选择阶段和 Worker 共用限制，阻止读入过大的整批文件。 */
export function validateBatch(
  files: readonly { relativePath: string; size: number }[],
  mode: ReplaceMode = 'files',
): void {
  if (!files.length || files.length > 20) throw new Error('请选择 1～20 个文件')
  let total = 0
  let root = ''
  const paths = new Set<string>()
  for (const file of files) {
    // 只接受浏览器提供的相对路径，阻止 ZIP 路径穿越及跨平台无法解压的名称。
    const segments = file.relativePath.split('/')
    if (
      !file.relativePath ||
      file.relativePath.length > 1024 ||
      (mode === 'files' && segments.length !== 1) ||
      (mode === 'directory' && segments.length < 2) ||
      segments.some(
        (part) =>
          !part ||
          part.length > 200 ||
          /[\\\x00-\x1f<>:"|?*]/u.test(part) ||
          /^\.+$/u.test(part) ||
          /[. ]$/u.test(part) ||
          /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/iu.test(part) ||
          /^(?:__proto__|prototype|constructor)$/iu.test(part),
      )
    )
      throw new Error('文件或文件夹名称不符合要求，请重命名后重试')
    if (mode === 'directory') {
      const normalized = file.relativePath.normalize('NFC').toLowerCase()
      const currentRoot = segments[0]!.normalize('NFC').toLowerCase()
      if (root && root !== currentRoot) throw new Error('请一次选择一个文件夹')
      root = currentRoot
      // 大小写及 Unicode 规范化后冲突时拒绝整批，避免解压覆盖或文件夹被误当文件。
      for (const existing of paths) {
        if (
          existing === normalized ||
          existing.startsWith(`${normalized}/`) ||
          normalized.startsWith(`${existing}/`)
        )
          throw new Error('文件路径存在冲突，请重命名后重试')
      }
      paths.add(normalized)
    }
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
  mode: ReplaceMode = 'files',
  inputEncoding: InputEncoding = 'utf-8',
): ReplacedFile[] {
  if (inputEncoding !== 'utf-8' && inputEncoding !== 'gb18030')
    throw new Error('不支持的输入编码，请重新选择')
  validateBatch(
    files.map((file) => ({ relativePath: file.relativePath, size: file.content.byteLength })),
    mode,
  )
  const output: ReplacedFile[] = []
  let total = 0
  for (const [index, file] of files.entries()) {
    const bytes = new Uint8Array(file.content)
    if (bytes.includes(0)) throw new Error(`第 ${index + 1} 个文件含二进制内容，请移除后重试`)
    // UTF-8 BOM 是确定的标记，在手动选择旧编码时拒绝误解码。
    if (inputEncoding === 'gb18030' && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf)
      throw new Error(`第 ${index + 1} 个文件带 UTF-8 标记，请选择 UTF-8 后重试`)
    let text: string
    try {
      text = new TextDecoder(inputEncoding, { fatal: true, ignoreBOM: true }).decode(bytes)
    } catch {
      throw new Error(`第 ${index + 1} 个文件无法按所选编码读取，请检查文件及输入编码`)
    }
    const result = replaceText(text, rules)
    // TextEncoder 会静默替换孤立代理项；拒绝这类不完整字符以避免导出内容变化。
    if (hasInvalidUnicode(result.text))
      throw new Error(`第 ${index + 1} 个文件的替换结果包含无效字符，请检查规则`)
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
      name:
        mode === 'directory' ? file.relativePath : files.length > 1 ? `${index + 1}-${base}` : base,
      content,
      preview: result.text.slice(0, 10000),
      count: result.count,
      truncated: result.text.length > 10000,
    })
  }
  return output
}

import type { ReplaceRule } from './acReplacer'
import type { VirtualFileEntry } from './types'

/** 区分普通文件多选与需要保留相对路径的文件夹。 */
export type ReplaceMode = 'files' | 'directory'

/** UI 到 Worker 的任务快照，文件内容通过可转移缓冲区发送。 */
export interface ReplaceRequest {
  /** 文件夹始终打包，并保留所选根目录及其子目录。 */
  mode: ReplaceMode
  /** 已验证大小的文件字节，文件读取仍由页面控制。 */
  files: VirtualFileEntry[]
  /** 用户确认的字面规则副本。 */
  rules: ReplaceRule[]
}

/** 单个文件在 UI 的展示摘要。 */
export interface FileSummary {
  /** 实际导出名称，序号防止 ZIP 内重名。 */
  name: string
  /** 最多一万个 UTF-16 代码单元的预览。 */
  preview: string
  /** 替换次数。 */
  count: number
  /** 预览是否截断。 */
  truncated: boolean
}

/** 已完成下载包；文件夹或多文件为 ZIP，单个普通文件为文本。 */
export interface BatchDownload {
  /** 所有文件摘要，用于切换预览。 */
  files: FileSummary[]
  /** 完整下载字节。 */
  bytes: ArrayBuffer
  /** 浏览器下载名称。 */
  name: string
  /** MIME 类型由 Worker 固定选择。 */
  mime: string
}

/** Worker 错误和成功明确区分，不把失败作为部分成功。 */
export interface WorkerReply {
  /** 是否全部处理成功。 */
  ok: boolean
  /** 成功返回整包。 */
  result?: BatchDownload
  /** 失败仅返回可展示提示。 */
  message?: string
}

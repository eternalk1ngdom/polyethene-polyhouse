/** Represents a file independently of browser DOM file-system APIs. */
export interface VirtualFileEntry {
  /** 由输入层提供的相对名称，导出前必须校验。 */
  relativePath: string
  /** 独立字节缓冲区，可跨线程传递而不依赖 DOM。 */
  content: ArrayBuffer
}

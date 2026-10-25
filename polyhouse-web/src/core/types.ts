/** Represents a file independently of browser DOM file-system APIs. */
export interface VirtualFileEntry {
  relativePath: string
  content: ArrayBuffer
}

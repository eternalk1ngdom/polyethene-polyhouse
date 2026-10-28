import { zipSync } from 'fflate'
import { replaceBatch } from '../core/polyReplaceBatch'
import type { ReplaceRequest, WorkerReply } from '../core/polyReplaceProtocol'

// 解码、替换和压缩都在本地线程中，避免压缩时阻塞页面；不接入账号或 HTTP。
self.onmessage = (event: MessageEvent<ReplaceRequest>) => {
  try {
    const files = replaceBatch(event.data.files, event.data.rules)
    const first = files[0]!
    const zip = files.length > 1
    const entries: Record<string, Uint8Array> = Object.create(null)
    for (const file of files) entries[file.name] = file.content
    const bytes = zip ? zipSync(entries) : first.content
    // 单独分配精确大小的 ArrayBuffer，传输后释放 Worker 对下载包的所有权。
    const buffer = new Uint8Array(bytes).buffer
    const reply: WorkerReply = {
      ok: true,
      result: {
        files: files.map(({ name, preview, count, truncated }) => ({
          name,
          preview,
          count,
          truncated,
        })),
        bytes: buffer,
        name: zip ? '妙替结果.zip' : first.name,
        mime: zip ? 'application/zip' : 'text/plain;charset=utf-8',
      },
    }
    self.postMessage(reply, { transfer: [buffer] })
  } catch (error: unknown) {
    self.postMessage({
      ok: false,
      message: error instanceof Error ? error.message : '处理失败，请重试',
    } satisfies WorkerReply)
  }
}

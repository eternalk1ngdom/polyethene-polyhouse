import { inspectLocalCover } from '../core/localCoverCrop'

/** 成功只转移一张静态位图，失败只返回可直接显示的提示。 */
export type LocalCoverDecodeReply = { bitmap: ImageBitmap } | { error: string }

self.onmessage = async (event: MessageEvent<File>) => {
  try {
    const bytes = new Uint8Array(await event.data.arrayBuffer())
    const mime = inspectLocalCover(bytes)
    const bitmap = await createImageBitmap(new Blob([bytes], { type: mime }))
    self.postMessage({ bitmap } satisfies LocalCoverDecodeReply, { transfer: [bitmap] })
  } catch (reason) {
    const error = reason instanceof Error && !(reason instanceof DOMException)
      ? reason.message
      : '无法读取这张图片，请重新选择。'
    self.postMessage({ error } satisfies LocalCoverDecodeReply)
  }
}

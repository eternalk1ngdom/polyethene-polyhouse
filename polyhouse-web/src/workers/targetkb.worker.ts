/**
 * 妙压 Worker：解码、按质量二分编码、按需补齐体积，全部在本地线程完成，不接账号或 HTTP。
 * 取消与过期回调的隔离方式与妙替一致（id + 作废），但不改妙替的协议文件。
 */
import { advanceSearch, startSearch } from '../core/targetKbCompress'
import { padWithComSegments, rewriteJfifDpi } from '../core/jpegComInjector'
import { isCurrentReply, parseRequest } from '../core/targetKbProtocol'
import { checkDecodedPixels, fitWithinLongEdge, inspectSource } from '../core/targetKbSource'
import type {
  TargetKbEncodeRequest,
  TargetKbEncodeResult,
  TargetKbFailureReason,
  TargetKbPadding,
  TargetKbProbeRecord,
  TargetKbReply,
  TargetKbRequest,
} from '../core/targetKbProtocol'
import type { TargetKbProbe } from '../core/targetKbTypes'

/** 当前有效任务编号；0 表示没有任务，任何回复都应丢弃。 */
let activeId = 0

/** 带失败原因的错误；Worker 只用它区分提示文案，不把内部异常结构暴露给页面。 */
class TargetKbError extends Error {
  constructor(
    readonly reason: TargetKbFailureReason,
    message: string,
  ) {
    super(message)
  }
}

/** 把异常收敛成可展示的中文提示，避免把 undefined 或英文堆栈丢给用户。 */
function toMessage(error: unknown): string {
  return error instanceof Error && error.message ? error.message : '处理失败，请重试。'
}

/** 试探的唯一键：同一质量与尺寸只编码一次，最终结果按它取回字节。 */
function probeKey(probe: { quality: number; maxEdge: number | null }): string {
  return `${probe.quality}|${probe.maxEdge ?? 'full'}`
}

/** 转移缓冲区要求独占所有权，视图落在更大缓冲区里时先复制一份精确大小。 */
function toExactBuffer(bytes: Uint8Array): ArrayBuffer {
  if (bytes.byteOffset === 0 && bytes.byteLength === bytes.buffer.byteLength)
    return bytes.buffer as ArrayBuffer
  return bytes.slice().buffer as ArrayBuffer
}

/** 按文件头判断类型，让 createImageBitmap 拿到正确 MIME；认不出就交给浏览器嗅探。 */
function sniffMime(bytes: Uint8Array): string {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff)
    return 'image/jpeg'
  const png = [137, 80, 78, 71, 13, 10, 26, 10]
  if (bytes.length >= 8 && png.every((byte, index) => bytes[index] === byte)) return 'image/png'
  if (
    bytes.length >= 12 &&
    bytes[0] === 0x52 &&
    bytes[1] === 0x49 &&
    bytes[2] === 0x46 &&
    bytes[3] === 0x46 &&
    bytes[8] === 0x57 &&
    bytes[9] === 0x45 &&
    bytes[10] === 0x42 &&
    bytes[11] === 0x50
  )
    return 'image/webp'
  if (
    bytes.length >= 12 &&
    bytes[4] === 0x66 &&
    bytes[5] === 0x74 &&
    bytes[6] === 0x79 &&
    bytes[7] === 0x70 &&
    bytes[8] === 0x61 &&
    bytes[9] === 0x76 &&
    bytes[10] === 0x69 &&
    (bytes[11] === 0x66 || bytes[11] === 0x73)
  )
    return 'image/avif'
  return 'application/octet-stream'
}

/**
 * 解码原图；失败时给出可操作的提示，而不是让页面显示浏览器原始报错。
 * 形参固定为 `Uint8Array<ArrayBuffer>`：Blob 只接受 ArrayBuffer 支撑的视图，
 * 允许 SharedArrayBuffer 会让类型与运行时行为都不确定。
 */
async function decodeImage(bytes: Uint8Array<ArrayBuffer>): Promise<ImageBitmap> {
  try {
    return await createImageBitmap(new Blob([bytes], { type: sniffMime(bytes) }))
  } catch {
    throw new TargetKbError('decode-failed', '无法解码这张图片；请确认它是 JPEG、PNG、WebP 或 AVIF。')
  }
}

/** 按给定质量与最长边编码一帧 JPEG；quality 完全由内核决定，Worker 不自行调整。 */
async function encodeOnce(
  bitmap: ImageBitmap,
  probe: TargetKbProbe,
): Promise<{ bytes: Uint8Array; width: number; height: number }> {
  const size = fitWithinLongEdge(bitmap.width, bitmap.height, probe.maxEdge)
  const canvas = new OffscreenCanvas(size.width, size.height)
  const context = canvas.getContext('2d')
  if (!context) throw new TargetKbError('encode-failed', '浏览器未提供 2D 画布，无法编码。')
  // 先铺白底：JPEG 没有透明通道，带透明区域的 PNG 直接转码会得到黑底。
  context.fillStyle = '#ffffff'
  context.fillRect(0, 0, size.width, size.height)
  // 降档时是缩小采样，用高质量重采样避免锯齿。
  context.imageSmoothingEnabled = true
  context.imageSmoothingQuality = 'high'
  context.drawImage(bitmap, 0, 0, size.width, size.height)
  try {
    const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: probe.quality })
    return { bytes: new Uint8Array(await blob.arrayBuffer()), width: size.width, height: size.height }
  } catch {
    throw new TargetKbError('encode-failed', '编码失败；请重试或换一张图片。')
  }
}

/**
 * 一次完整压缩：校验 → 解码 → 二分搜索 → 写 DPI → 补齐。
 * 返回 null 表示任务已被更新的请求作废，此时不能回复任何结果。
 */
async function compress(request: TargetKbEncodeRequest): Promise<TargetKbEncodeResult | null> {
  const startedAt = performance.now()
  const source = new Uint8Array(request.bytes)
  const inspection = inspectSource(source)
  if (!inspection.ok) throw new TargetKbError(inspection.reason ?? 'internal', inspection.message)

  const bitmap = await decodeImage(source)
  try {
    // WebP/AVIF 等解析不出头部尺寸时只能退到解码后补检；内存已付出，属于降级路径。
    if (inspection.width === null) {
      const decoded = checkDecodedPixels(bitmap.width, bitmap.height)
      if (!decoded.ok) throw new TargetKbError(decoded.reason ?? 'internal', decoded.message)
    }

    const longEdge = request.allowShrink ? Math.max(bitmap.width, bitmap.height) : null
    let step = startSearch({ target: request.target, longEdge })
    const probes: TargetKbProbeRecord[] = []
    // 只留"满足上限"的编码结果：最终采用的必在其中，其余字节没必要占内存。
    const candidates = new Map<string, Uint8Array>()

    while (step.kind === 'probe') {
      if (!isCurrentReply(request.id, activeId)) return null
      const probeStarted = performance.now()
      const encoded = await encodeOnce(bitmap, step.probe)
      probes.push({
        quality: step.probe.quality,
        maxEdge: step.probe.maxEdge,
        bytes: encoded.bytes.byteLength,
        elapsedMs: Math.round(performance.now() - probeStarted),
      })
      if (encoded.bytes.byteLength <= request.target.maxBytes)
        candidates.set(probeKey(step.probe), encoded.bytes)
      step = advanceSearch(step.state, encoded.bytes.byteLength)
    }

    if (step.kind === 'failed')
      throw new TargetKbError(
        'exceeds-target',
        '已降到最低质量并缩到最小尺寸，仍超过目标体积；请放宽目标或先缩小图片。',
      )

    const chosen = candidates.get(probeKey(step.result))
    if (!chosen) throw new TargetKbError('internal', '内部状态异常：找不到最终编码结果。')

    // 必须先写 DPI 再补齐：原图没有 JFIF APP0 时写 DPI 会新增 18 字节，
    // 顺序颠倒会让补齐后的最终体积超出目标。
    // 依赖：Chrome 的 convertToBlob 恒写 JFIF APP0，所以写 DPI 长度不变；
    // 若将来换成不写 APP0 的编码器，插入的 18 字节会让"刚好等于上限"的结果超出 18 字节。
    let output = request.dpi === null ? chosen : rewriteJfifDpi(chosen, request.dpi)
    let padding: TargetKbPadding = { targetBytes: 0, added: 0, satisfied: true }
    if (step.result.needsPadding) {
      const padded = padWithComSegments(output, step.result.padToBytes)
      output = padded.bytes
      padding = {
        targetBytes: step.result.padToBytes,
        added: padded.added,
        satisfied: padded.satisfied,
      }
    }

    const size = fitWithinLongEdge(bitmap.width, bitmap.height, step.result.maxEdge)
    return {
      bytes: toExactBuffer(output),
      width: size.width,
      height: size.height,
      quality: step.result.quality,
      maxEdge: step.result.maxEdge,
      scaleStep: step.result.scaleStep,
      probes,
      padding,
      dpi: request.dpi,
      elapsedMs: Math.round(performance.now() - startedAt),
    }
  } finally {
    bitmap.close()
  }
}

/** 回复统一出口：有结果就转移字节，避免 Worker 与页面各留一份。 */
function postReply(reply: TargetKbReply): void {
  if (reply.result) self.postMessage(reply, { transfer: [reply.result.bytes] })
  else self.postMessage(reply)
}

/** 处理一条消息；取消只作废回调，不中断线程，省掉每次取消重建 Worker 的开销。 */
async function handleMessage(data: unknown): Promise<void> {
  let request: TargetKbRequest
  try {
    request = parseRequest(data)
  } catch (error: unknown) {
    postReply({ id: 0, ok: false, reason: 'invalid-request', message: toMessage(error) })
    return
  }

  if (request.kind === 'cancel') {
    if (request.id === activeId) activeId = 0
    return
  }

  activeId = request.id
  try {
    const result = await compress(request)
    // 迟到的结果一律丢弃：页面已经开了新任务或取消，此时回复只会造成错位。
    if (result === null || !isCurrentReply(request.id, activeId)) return
    postReply({ id: request.id, ok: true, result })
  } catch (error: unknown) {
    if (!isCurrentReply(request.id, activeId)) return
    postReply({
      id: request.id,
      ok: false,
      reason: error instanceof TargetKbError ? error.reason : 'internal',
      message: toMessage(error),
    })
  }
}

self.onmessage = (event: MessageEvent<unknown>) => {
  void handleMessage(event.data)
}

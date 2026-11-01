/**
 * JPEG 字节层工具：COM 段补齐体积、JFIF APP0 写入 DPI。
 * 只做字节读写，不解码像素，因此不依赖 Canvas，可在 Node 中直接测试。
 */

/** 段头固定开销：2 字节标记 + 2 字节长度字段。 */
const SEGMENT_HEADER_BYTES = 4

/** COM 段载荷上限：段长度字段最大 65535，其中 2 字节记录长度本身。 */
export const MAX_COM_PAYLOAD = 65533

/** JFIF APP0 段总长度（含标记与长度字段），由 JFIF 1.01 规范固定。 */
const JFIF_APP0_BYTES = 18

/** 补齐段开头写入的标识，便于排查"文件为何变大"，其余部分用空格填充。 */
const PADDING_PREFIX = 'Polyhouse TargetKB'

/** 一个 JPEG 段的位置信息，供插入与定位复用。 */
export interface JpegSegmentInfo {
  /** 段标记的第二字节，如 0xE0 表示 APP0、0xFE 表示 COM。 */
  marker: number
  /** 段起始偏移，指向 0xFF。 */
  offset: number
  /** 段总长度，含标记与长度字段；无长度字段的独立标记不进入列表。 */
  length: number
}

/** COM 段补齐结果；不足 5 字节时无法精确补齐，返回原数据并标记未满足。 */
export interface ComPaddingResult {
  /** 补齐后的数据；无法补齐时为原始数据。 */
  bytes: Uint8Array
  /** 实际新增字节数。 */
  added: number
  /** 是否已达到目标体积。 */
  satisfied: boolean
}

/**
 * 遍历 SOS 之前的段。
 * SOS 之后是熵编码数据，按段解析会把压缩数据误判为标记，因此在 SOS 处停止。
 */
export function readSegments(bytes: Uint8Array): JpegSegmentInfo[] {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8)
    throw new Error('不是有效的 JPEG 数据')
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const segments: JpegSegmentInfo[] = []
  let offset = 2
  while (offset + 1 < bytes.length) {
    if (bytes[offset] !== 0xff) throw new Error('JPEG 段结构异常')
    const marker = bytes[offset + 1]!
    // 标记前的 0xFF 填充字节属于对齐，不计为独立段。
    if (marker === 0xff) {
      offset += 1
      continue
    }
    // EOI 与 SOS 之后不再有可解析的段。
    if (marker === 0xd9 || marker === 0xda) break
    // TEM 与 RSTn 没有长度字段，跳过两个字节即可。
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      offset += 2
      continue
    }
    if (offset + SEGMENT_HEADER_BYTES > bytes.length) throw new Error('JPEG 段长度越界')
    const length = view.getUint16(offset + 2)
    if (length < 2 || offset + 2 + length > bytes.length) throw new Error('JPEG 段长度越界')
    segments.push({ marker, offset, length: length + 2 })
    offset += length + 2
  }
  return segments
}

/** 找到头部元数据（APPn 与 COM）结束的位置；补齐段插在这里，保证 JFIF 仍是最前面的段。 */
function metadataEndOffset(bytes: Uint8Array): number {
  let end = 2
  for (const segment of readSegments(bytes)) {
    const isMetadata = (segment.marker >= 0xe0 && segment.marker <= 0xef) || segment.marker === 0xfe
    if (!isMetadata) break
    end = segment.offset + segment.length
  }
  return end
}

/**
 * 用 COM 段把 JPEG 补到目标字节数。
 * 只增加无意义的注释段，不改像素、不改编码，因此画质完全不受影响。
 */
export function padWithComSegments(bytes: Uint8Array, targetBytes: number): ComPaddingResult {
  if (!Number.isInteger(targetBytes) || targetBytes < 1) throw new Error('补齐目标必须是正整数')
  const need = targetBytes - bytes.length
  if (need <= 0) return { bytes, added: 0, satisfied: true }
  // 每个 COM 段至少 5 字节（4 字节段头 + 1 字节载荷），更小的差额无法精确补齐。
  if (need < SEGMENT_HEADER_BYTES + 1) return { bytes, added: 0, satisfied: false }

  const segmentCount = Math.ceil(need / (MAX_COM_PAYLOAD + SEGMENT_HEADER_BYTES))
  if (need < (SEGMENT_HEADER_BYTES + 1) * segmentCount) return { bytes, added: 0, satisfied: false }

  // 把载荷平均分配，余数摊到前几段，保证总新增字节与差额完全一致。
  const basePayload = Math.floor((need - SEGMENT_HEADER_BYTES * segmentCount) / segmentCount)
  let remainder = need - SEGMENT_HEADER_BYTES * segmentCount - basePayload * segmentCount
  const payloads: number[] = []
  for (let index = 0; index < segmentCount; index++) {
    const extra = remainder > 0 ? 1 : 0
    remainder -= extra
    payloads.push(basePayload + extra)
  }

  const insertAt = metadataEndOffset(bytes)
  const output = new Uint8Array(bytes.length + need)
  output.set(bytes.subarray(0, insertAt), 0)
  let cursor = insertAt
  for (const payload of payloads) {
    output[cursor] = 0xff
    output[cursor + 1] = 0xfe
    const length = payload + 2
    output[cursor + 2] = (length >> 8) & 0xff
    output[cursor + 3] = length & 0xff
    const body = cursor + SEGMENT_HEADER_BYTES
    // 先写标识再填空格；载荷过短时整体填空格，避免写出半截标识。
    if (payload >= PADDING_PREFIX.length) {
      for (let index = 0; index < payload; index++)
        output[body + index] =
          index < PADDING_PREFIX.length ? PADDING_PREFIX.charCodeAt(index) : 0x20
    } else {
      output.fill(0x20, body, body + payload)
    }
    cursor += SEGMENT_HEADER_BYTES + payload
  }
  output.set(bytes.subarray(insertAt), cursor)
  return { bytes: output, added: need, satisfied: true }
}

/**
 * 写入 JFIF 的 DPI。
 * 已有 JFIF APP0 时原地改写密度字段，文件长度不变；没有时在 SOI 后插入一个。
 * 这里只写 JFIF，不伪造 EXIF，避免同一份数据里出现两个互相矛盾的 DPI 声明。
 */
export function rewriteJfifDpi(bytes: Uint8Array, dpi: number): Uint8Array {
  if (!Number.isInteger(dpi) || dpi < 1 || dpi > 1200) throw new Error('DPI 必须是 1～1200 的整数')
  const app0 = readSegments(bytes).find(
    (segment) =>
      segment.marker === 0xe0 &&
      segment.length >= JFIF_APP0_BYTES &&
      bytes[segment.offset + 4] === 0x4a &&
      bytes[segment.offset + 5] === 0x46 &&
      bytes[segment.offset + 6] === 0x49 &&
      bytes[segment.offset + 7] === 0x46 &&
      bytes[segment.offset + 8] === 0x00,
  )

  if (app0) {
    const output = bytes.slice()
    const view = new DataView(output.buffer, output.byteOffset, output.byteLength)
    // JFIF 结构：标记(2) 长度(2) 标识(5) 版本(2) 单位(1) X 密度(2) Y 密度(2)。
    output[app0.offset + 11] = 1
    view.setUint16(app0.offset + 12, dpi)
    view.setUint16(app0.offset + 14, dpi)
    return output
  }

  const header = new Uint8Array(JFIF_APP0_BYTES)
  const view = new DataView(header.buffer)
  // 固定头部：APP0、长度 16、标识 JFIF、版本 1.1、单位 1（每英寸）。
  header.set([0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01], 0)
  view.setUint16(12, dpi)
  view.setUint16(14, dpi)
  const output = new Uint8Array(bytes.length + JFIF_APP0_BYTES)
  output.set(bytes.subarray(0, 2), 0)
  output.set(header, 2)
  output.set(bytes.subarray(2), 2 + JFIF_APP0_BYTES)
  return output
}

/** 读取 JFIF 声明的 DPI；没有 JFIF APP0 时返回 null，调用方据此决定是否写入。 */
export function readJfifDpi(bytes: Uint8Array): number | null {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  for (const segment of readSegments(bytes)) {
    if (
      segment.marker === 0xe0 &&
      segment.length >= JFIF_APP0_BYTES &&
      bytes[segment.offset + 4] === 0x4a &&
      bytes[segment.offset + 5] === 0x46 &&
      bytes[segment.offset + 6] === 0x49 &&
      bytes[segment.offset + 7] === 0x46 &&
      bytes[segment.offset + 8] === 0x00
    )
      return view.getUint16(segment.offset + 12)
  }
  return null
}

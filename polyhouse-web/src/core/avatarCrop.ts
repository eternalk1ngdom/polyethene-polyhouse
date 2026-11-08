/** 头像视口内的图片位置，坐标与导出尺寸保持一致，不依赖 DOM 或 Vue。 */
export interface AvatarCrop {
  /** 原图像素宽度。 */
  width: number
  /** 原图像素高度。 */
  height: number
  /** 方形导出边长。 */
  size: number
  /** 相对于完整覆盖视口的放大倍数。 */
  zoom: number
  /** 图片左边在视口中的坐标，不允许露出空白。 */
  x: number
  /** 图片上边在视口中的坐标。 */
  y: number
}

/** 先让短边覆盖视口，再按用户选择放大，保证始终能裁出完整方形。 */
export function cropScale(crop: AvatarCrop): number {
  return Math.max(crop.size / crop.width, crop.size / crop.height) * crop.zoom
}

/** 限制拖动边界，防止导出时出现透明或黑色空边。 */
export function clampCrop(crop: AvatarCrop): AvatarCrop {
  const scale = cropScale(crop)
  return {
    ...crop,
    x: Math.max(crop.size - crop.width * scale, Math.min(0, crop.x)),
    y: Math.max(crop.size - crop.height * scale, Math.min(0, crop.y)),
  }
}

/** 初次选图居中显示，长图和横图都无需用户先手动定位。 */
export function initialCrop(width: number, height: number, size = 256): AvatarCrop {
  const crop: AvatarCrop = { width, height, size, zoom: 1, x: 0, y: 0 }
  const scale = cropScale(crop)
  return { ...crop, x: (size - width * scale) / 2, y: (size - height * scale) / 2 }
}

/** 缩放以当前视口中心为支点，避免每次放大都跳回原图中心。 */
export function zoomCrop(crop: AvatarCrop, zoom: number): AvatarCrop {
  const nextZoom = Math.max(1, Math.min(3, zoom))
  const ratio = nextZoom / crop.zoom
  return clampCrop({
    ...crop,
    zoom: nextZoom,
    x: crop.size / 2 - (crop.size / 2 - crop.x) * ratio,
    y: crop.size / 2 - (crop.size / 2 - crop.y) * ratio,
  })
}

/** 从 PNG/JPEG 文件头读取尺寸，先拒绝像素超限的原图，再交给浏览器分配解码内存。 */
export function avatarSourceSize(bytes: Uint8Array): { width: number; height: number } | null {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const png = [137, 80, 78, 71, 13, 10, 26, 10]
  if (bytes.length >= 24 && png.every((byte, index) => bytes[index] === byte)) {
    if (view.getUint32(8) !== 13 || view.getUint32(12) !== 0x49484452) return null
    return { width: view.getUint32(16), height: view.getUint32(20) }
  }
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null
  let offset = 2
  while (offset + 3 < bytes.length) {
    if (bytes[offset] !== 0xff) return null
    while (bytes[offset] === 0xff) offset += 1
    const marker = bytes[offset++]
    if (marker === undefined || marker === 0xda || marker === 0xd9 || offset + 2 > bytes.length)
      return null
    const length = view.getUint16(offset)
    if (length < 2 || offset + length > bytes.length) return null
    // SOF 各变体都在相同位置存储尺寸，排除 DHT/JPG/DAC 等非尺寸段。
    if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) {
      if (length < 8) return null
      return { width: view.getUint16(offset + 5), height: view.getUint16(offset + 3) }
    }
    offset += length
  }
  return null
}

/** 只从 WebP 头读取有界尺寸。动画不适合静态头像，先于浏览器解码拒绝。 */
export function avatarWebpSourceSize(bytes: Uint8Array): { width: number; height: number } | null {
  if (bytes.length < 25) return null
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  if (
    view.getUint32(0) !== 0x52494646 ||
    view.getUint32(4, true) !== bytes.length - 8 ||
    view.getUint32(8) !== 0x57454250
  )
    return null
  const size = view.getUint32(16, true)
  if (size + (size & 1) > bytes.length - 20) return null
  const kind = view.getUint32(12)
  // VP8X 的 24 位尺寸减一存放，VP8L 则把两个尺寸编码到同一 32 位字段。
  if (kind === 0x56503858 && size === 10 && (view.getUint8(20) & 2) === 0)
    return {
      width: 1 + view.getUint16(24, true) + view.getUint8(26) * 65536,
      height: 1 + view.getUint16(27, true) + view.getUint8(29) * 65536,
    }
  if (kind === 0x5650384c && size >= 5 && view.getUint8(20) === 0x2f) {
    const bits = view.getUint32(21, true)
    return { width: 1 + (bits & 0x3fff), height: 1 + ((bits >>> 14) & 0x3fff) }
  }
  if (
    kind === 0x56503820 &&
    size >= 10 &&
    view.getUint8(23) === 0x9d &&
    view.getUint16(24) === 0x012a
  )
    return { width: view.getUint16(26, true) & 0x3fff, height: view.getUint16(28, true) & 0x3fff }
  return null
}

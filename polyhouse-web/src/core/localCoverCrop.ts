import { avatarSourceSize, avatarWebpSourceSize } from './avatarCrop'

export const coverFrames = {
  desktop: { width: 2320, height: 360, label: '桌面' },
  mobile: { width: 1080, height: 420, label: '手机' },
}

export type CoverFrame = keyof typeof coverFrames

/** 用视口比例记录所选位置，预览缩放后仍从原图导出同一个区域。 */
export interface CoverPosition {
  x: number
  y: number
  zoom: number
}

/** 返回图像可移动的范围，始终覆盖视口。 */
export function coverBounds(width: number, height: number, frame: CoverFrame, zoom: number) {
  const size = coverFrames[frame]
  const scale = Math.max(size.width / width, size.height / height) * zoom
  return {
    scale,
    x: Math.max(0, width * scale - size.width),
    y: Math.max(0, height * scale - size.height),
  }
}

/** 解码前检查格式、动画和像素，避免普通大图造成过高的内存峰值。 */
export function inspectLocalCover(bytes: Uint8Array) {
  const png = bytes[0] === 137 && bytes[1] === 80
  const webp = bytes[0] === 82 && bytes[1] === 73
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const staticHint = '卡面暂不支持动图，请选择静态 JPEG、PNG 或 WebP 图片。'
  if (bytes.length >= 6 && view.getUint32(0) === 0x47494638 &&
      (view.getUint16(4) === 0x3761 || view.getUint16(4) === 0x3961))
    throw new Error('卡面暂不支持 GIF，请选择静态 JPEG、PNG 或 WebP 图片。')
  // 静态尺寸读取器会直接拒绝动画，因此先识别 VP8X 的动画标志，才能给出准确提示。
  if (webp && bytes.length >= 30 && view.getUint32(0) === 0x52494646 &&
      view.getUint32(8) === 0x57454250 && view.getUint32(12) === 0x56503858 &&
      view.getUint32(16, true) === 10 && (view.getUint8(20) & 2) !== 0)
    throw new Error(staticHint)
  const size = webp ? avatarWebpSourceSize(bytes) : avatarSourceSize(bytes)
  if (!size || size.width < 1 || size.height < 1)
    throw new Error('请选择有效的静态 JPEG、PNG 或 WebP 图片。')
  if (size.width * size.height > 16_000_000 || Math.max(size.width, size.height) > 8192)
    throw new Error('图片像素过大，请先缩小后再选择。')
  if (png || webp) {
    for (let offset = png ? 8 : 12; offset + 8 <= bytes.length;) {
      const kind = view.getUint32(offset + (png ? 4 : 0))
      const length = view.getUint32(offset + (png ? 0 : 4), webp)
      // PNG 的 acTL 和 WebP 的 ANIM、ANMF 都表示动画。WebP 块在奇数长度后有填充。
      if ([0x6163544c, 0x414e494d, 0x414e4d46].includes(kind))
        throw new Error(staticHint)
      offset += png ? length + 12 : length + 8 + (length & 1)
    }
  }
  return png ? 'image/png' : webp ? 'image/webp' : 'image/jpeg'
}

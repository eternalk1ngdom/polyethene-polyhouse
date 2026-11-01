/**
 * 妙压输入源检查：解码前拒绝超限文件，避免浏览器先把内存吃光。
 * 尺寸解析复用 `avatarCrop.ts` 的 `avatarSourceSize`，不在这里重写一遍段解析。
 */
import { avatarSourceSize } from './avatarCrop'

/** 单张输入文件上限 20 MiB（计划 D5）；再大意味着解码峰值内存不可控。 */
export const MAX_SOURCE_BYTES = 20 * 1024 * 1024

/** 单张输入像素上限 40 MP（计划 D5）；常见 4K 截图约 8 MP，40 MP 已覆盖相机原图。 */
export const MAX_SOURCE_PIXELS = 40 * 1024 * 1024

/** 拒绝原因；调用方据此给出可解释的提示，而不是笼统的"处理失败"。 */
export type SourceRejection = 'file-too-large' | 'pixels-too-large'

/** 输入检查结果；宽高为 null 表示文件头解析不出尺寸，需要解码后补检。 */
export interface SourceInspection {
  /** 是否允许继续解码。 */
  ok: boolean
  /** 头部解析出的像素宽；null 表示该格式不支持头部解析。 */
  width: number | null
  /** 头部解析出的像素高；null 与 width 同时为 null。 */
  height: number | null
  /** 拒绝原因；通过时为 null。 */
  reason: SourceRejection | null
  /** 可直接展示给用户的中文说明。 */
  message: string
}

/** 把字节数换成一位小数的 MB，用于提示文案。 */
function toMegabytes(bytes: number): string {
  return (bytes / (1024 * 1024)).toFixed(1)
}

/** 把像素数换成一位小数的 MP，用于提示文案。 */
function toMegapixels(pixels: number): string {
  return (pixels / (1024 * 1024)).toFixed(1)
}

/** 通过检查，同时带回头部解析结果，供调用方决定是否还要解码后补检。 */
function accept(width: number | null, height: number | null): SourceInspection {
  return { ok: true, width, height, reason: null, message: '' }
}

/** 组装拒绝结果；message 说明超了哪一项、超到多少，用户才知道该缩图还是换图。 */
function reject(reason: SourceRejection, message: string): SourceInspection {
  return { ok: false, width: null, height: null, reason, message }
}

/**
 * 解码前检查：先看文件体积，再看文件头里的像素数。
 * 顺序不能反——体积检查零成本，像素检查要先读字节。
 */
export function inspectSource(bytes: Uint8Array): SourceInspection {
  if (bytes.byteLength > MAX_SOURCE_BYTES)
    return reject(
      'file-too-large',
      `图片 ${toMegabytes(bytes.byteLength)}MB，超过 20MB 上限；请先缩小或压缩后再试。`,
    )

  const size = avatarSourceSize(bytes)
  // WebP/AVIF 等格式解析不出头部尺寸：不在这里拒绝，交给解码后补检，避免误伤可用格式。
  if (size === null) return accept(null, null)

  const pixels = size.width * size.height
  if (pixels > MAX_SOURCE_PIXELS)
    return reject(
      'pixels-too-large',
      `图片为 ${size.width}×${size.height}（${toMegapixels(pixels)}MP），超过 40MP 上限；请先缩小尺寸再试。`,
    )
  return accept(size.width, size.height)
}

/**
 * 解码后补检像素数，只用于文件头解析不出尺寸的格式。
 * 这一步已经付出了解码内存，属于降级路径，不是常规入口。
 */
export function checkDecodedPixels(width: number, height: number): SourceInspection {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1)
    return reject('pixels-too-large', '图片尺寸无效，无法处理。')

  const pixels = width * height
  if (pixels > MAX_SOURCE_PIXELS)
    return reject(
      'pixels-too-large',
      `图片为 ${width}×${height}（${toMegapixels(pixels)}MP），超过 40MP 上限；请先缩小尺寸再试。`,
    )
  return accept(width, height)
}

/**
 * 按最长边等比缩放；maxEdge 为 null 或原图更小时保持原尺寸。
 * 取整后最小 1 像素，避免极端参数下算出 0 宽高导致画布创建失败。
 */
export function fitWithinLongEdge(
  width: number,
  height: number,
  maxEdge: number | null,
): { width: number; height: number } {
  const longEdge = Math.max(width, height)
  if (maxEdge === null || maxEdge >= longEdge || longEdge < 1) return { width, height }
  const scale = maxEdge / longEdge
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

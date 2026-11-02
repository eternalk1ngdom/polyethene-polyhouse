/**
 * 妙压的浏览器能力门槛。
 *
 * 压缩全程在 Worker 里用 `OffscreenCanvas` 编码。缺任一项所需 API 时，Worker 会在
 * 启动或首帧编码时抛错，页面只能显示"本地编码线程出错"，而用户重试多少次都不会好——
 * 因为能力是浏览器版本决定的。所以进页面就先判定，把原因和出路直接说清楚。
 *
 * 判定动作本身留在组件层（`core` 不允许引用浏览器全局对象），这里只负责
 * "缺哪一项、对用户怎么说"，因此可以在 Node 里覆盖各种组合。
 */

/** 本地编码依赖的能力项；由调用方探测后传入。 */
export interface TargetKbCapability {
  /** 能否创建 Worker。 */
  worker: boolean
  /** 能否把 Blob 解码成 `ImageBitmap`。 */
  createImageBitmap: boolean
  /** 是否存在 `OffscreenCanvas` 构造器。 */
  offscreenCanvas: boolean
  /**
   * `OffscreenCanvas` 是否提供 `convertToBlob`。
   * 必须与上一项分开判：Safari 16.4 之前有构造器但没有这个方法，
   * 只看构造器会误判成"支持"，然后在编码时抛 `convertToBlob is not a function`。
   */
  convertToBlob: boolean
}

/** 不支持时给用户的统一说明；不逐项罗列 API 名，用户不需要知道 `convertToBlob` 是什么。 */
const GAP_MESSAGE =
  '当前浏览器不支持本地图片编码，请改用 Chrome、Edge，或 Safari 16.4 及以上版本。'

/**
 * 缺能力时返回可直接展示的说明，全部具备时返回 `null`。
 *
 * 统一给一句提示而不是按缺失项分别报错：四个能力项都指向同一个结论（浏览器太旧），
 * 分项提示只会让用户去猜哪一项重要。
 */
export function describeCapabilityGap(capability: TargetKbCapability): string | null {
  if (
    capability.worker &&
    capability.createImageBitmap &&
    capability.offscreenCanvas &&
    capability.convertToBlob
  ) {
    return null
  }
  return GAP_MESSAGE
}

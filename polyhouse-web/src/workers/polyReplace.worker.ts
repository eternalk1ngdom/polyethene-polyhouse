import { replaceText, type ReplaceRule } from '../core/acReplacer'

/** Worker 请求仅包含文本与规则，避免依赖页面状态或账号。 */
interface ReplaceRequest {
  /** 已解码的原始文本。 */
  text: string
  /** 用户确认的字面规则。 */
  rules: ReplaceRule[]
}

// 独立线程执行替换；异常只传安全提示，避免主线程长时间被占用。
self.onmessage = (event: MessageEvent<ReplaceRequest>) => {
  try {
    self.postMessage({ ok: true, result: replaceText(event.data.text, event.data.rules) })
  } catch (error: unknown) {
    self.postMessage({ ok: false, message: error instanceof Error ? error.message : '处理失败，请重试' })
  }
}

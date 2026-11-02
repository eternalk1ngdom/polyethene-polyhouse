import { computed, ref, shallowRef } from 'vue'
import type { ReplaceRule } from '../core/acReplacer'
import { createRuleShareUrl, parseRuleShare, type SharedRules } from '../utils/ruleShare'

/** 副作用和来源决定均注入，测试不接触真实剪贴板、网址或本机草稿。 */
export interface RuleShareServices {
  /** 当前页面地址，用于保留真实部署路径。 */
  baseUrl(): string
  /** 核对仍是预期片段后清理，不能删掉用户刚打开的另一条链接。 */
  clearHash(expected: string): Promise<boolean>
  /** 只有 Promise 成功才显示已复制，失败保留手动复制入口。 */
  copy(text: string): Promise<void>
  /** 本机来源初始化/既有确认未完成时不能抢先决定。 */
  canChoose(): boolean
  /** 明确载入时才替换编辑，旧草稿应保留。 */
  loadRules(rules: readonly Readonly<ReplaceRule>[]): boolean
  /** 恢复前核对当前待恢复来源，失效 ID 不载入。 */
  restoreDraft(id: string): boolean
  /** 放弃链接和自动恢复，保留当前编辑及旧历史。 */
  keepCurrent(): boolean
}

interface IncomingShare {
  /** 绑定预览所对应的原片段。 */
  readonly hash: string
  /** 严格校验后的规则快照。 */
  readonly data: SharedRules
}

/** 只管理链接候选与确认；不读写词库或草稿存储，不启动替换。 */
export function useRuleShare(services: RuleShareServices) {
  const incoming = shallowRef<IncomingShare | null>(null)
  const notice = ref('')
  const processing = ref(false)
  const cleanupHash = ref('')
  const generated = ref('')
  const generatedCount = ref(0)
  const generationIssue = ref('')
  const copyNotice = ref('')
  const copying = ref(false)
  const pending = computed(() => incoming.value !== null)
  let lastHash: string | undefined
  let disposed = false
  let generation = 0
  const clearing = new Set<string>()
  const cleanupWarning = ' 网址尚未清理，刷新可能再次提示，请重试清理。'

  async function clear(expected: string): Promise<void> {
    clearing.add(expected)
    try {
      if (!(await services.clearHash(expected))) throw new Error('未清理')
      if (!disposed && cleanupHash.value === expected) {
        cleanupHash.value = ''
        notice.value = notice.value.replace(cleanupWarning, '') + ' 网址已清理。'
      }
    } catch {
      if (!disposed && (lastHash === expected || lastHash === '')) {
        cleanupHash.value = expected
        if (!notice.value.includes(cleanupWarning)) notice.value += cleanupWarning
      }
    } finally {
      clearing.delete(expected)
    }
  }

  /** 同路由的新片段也重新解析，但重复通知不重新载入已决定的候选。 */
  async function receive(hash: string): Promise<void> {
    if (disposed || hash === lastHash) return
    const previous = lastHash
    lastHash = hash
    incoming.value = null
    // 自己清理片段触发路由通知时，成功/无效链接提示仍须留给用户看见。
    if (hash === '' && previous && clearing.has(previous)) return
    cleanupHash.value = ''
    notice.value = ''
    try {
      const data = parseRuleShare(hash)
      if (data) incoming.value = Object.freeze({ hash, data })
    } catch {
      notice.value = '分享链接无效、已损坏或过长，已忽略；当前编辑与本机草稿未改动。'
      await clear(hash)
    }
  }

  /** 决定只同步应用一次，随后清理网址；清理失败不能谎称编辑载入失败。 */
  async function choose(action: 'link' | 'draft' | 'keep', draftId?: string): Promise<boolean> {
    const snapshot = incoming.value
    if (disposed || processing.value || !snapshot || !services.canChoose()) return false
    processing.value = true
    try {
      const accepted =
        action === 'link'
          ? services.loadRules(snapshot.data.rules)
          : action === 'draft'
            ? !!draftId && services.restoreDraft(draftId)
            : services.keepCurrent()
      if (!accepted) {
        notice.value = '本机来源状态已变化，请核对后重新选择。'
        return false
      }
      incoming.value = null
      notice.value =
        action === 'link'
          ? `已载入链接中的 ${snapshot.data.rules.length} 条规则；未自动执行或保存词库。`
          : action === 'draft'
            ? '已恢复所选草稿，链接规则未载入。'
            : '暂不载入，当前编辑与历史草稿保留。'
      await clear(snapshot.hash)
      return true
    } catch {
      notice.value = '来源选择未完成，请重试；链接候选仍保留。'
      return false
    } finally {
      processing.value = false
    }
  }

  /** 展示的是点击时的规则快照，后续编辑不偷偷改变已展示链接。 */
  function generate(rules: unknown): boolean {
    generation++
    generated.value = ''
    copyNotice.value = ''
    generationIssue.value = ''
    try {
      generated.value = createRuleShareUrl(services.baseUrl(), rules)
      generatedCount.value = parseRuleShare(new URL(generated.value).hash)!.rules.length
      return true
    } catch (error: unknown) {
      generationIssue.value =
        error instanceof Error ? error.message : '无法生成分享链接，请检查规则。'
      return false
    }
  }

  /** 关闭或生成新快照后，迟到的剪贴板结果不能冒充当前链接复制成功。 */
  async function copy(): Promise<void> {
    if (!generated.value || copying.value || disposed) return
    const ticket = generation
    copying.value = true
    try {
      await services.copy(generated.value)
      if (!disposed && ticket === generation) copyNotice.value = '分享链接已复制。'
    } catch {
      if (!disposed && ticket === generation)
        copyNotice.value = '未能复制，请选中下面的完整链接手动复制。'
    } finally {
      copying.value = false
    }
  }

  function closeGenerated(): void {
    generation++
    generated.value = ''
    copyNotice.value = ''
  }
  function dispose(): void {
    disposed = true
    closeGenerated()
  }

  return {
    incoming,
    notice,
    processing,
    cleanupHash,
    pending,
    generated,
    generatedCount,
    generationIssue,
    copyNotice,
    copying,
    receive,
    choose,
    generate,
    copy,
    closeGenerated,
    dispose,
    retryCleanup: () => (cleanupHash.value ? clear(cleanupHash.value) : Promise.resolve()),
  }
}

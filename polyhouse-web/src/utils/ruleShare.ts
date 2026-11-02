import type { ReplaceRule } from '../core/acReplacer'
import { validateRuleLibrary } from './ruleLibrary'

/** 链接仅携带当前完整规则，不携带词库元数据、账号或文件。 */
export interface SharedRules {
  /** 格式变化时明确拒绝未知版本，不猜测兼容。 */
  readonly version: 1
  /** 深冻结快照防止预览和确认期间被编辑区改写。 */
  readonly rules: readonly Readonly<ReplaceRule>[]
}

/** 同时限制接收的片段及生成的完整网址，拒绝后不截断。 */
export const RULE_SHARE_MAX_LENGTH = 8000

/** 复用词库的规则校验口径；固定容器仅作适配，不生成或保存词库。 */
function checkedRules(rules: unknown): readonly Readonly<ReplaceRule>[] {
  try {
    return validateRuleLibrary({
      version: 1,
      sets: [{ id: 'share', name: 'share', rules, createdAt: 0, updatedAt: 0 }],
    }).sets[0]!.rules
  } catch (error: unknown) {
    const reason =
      error instanceof Error ? error.message.replaceAll('词库', '规则') : '结构不符合要求'
    throw new Error(`当前规则无法分享：${reason}`)
  }
}

/** base64url 只为网址传输编码，绝不作为加密；输入字节已受长度上限约束。 */
function encodeBytes(bytes: Uint8Array): string {
  return btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(''))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/u, '')
}

/** 生成规范片段，完整中文/emoji 和字面空白都必须往返保留。 */
export function encodeRuleShare(rules: unknown): string {
  const snapshot: SharedRules = { version: 1, rules: checkedRules(rules) }
  const hash = `#r=${encodeBytes(new TextEncoder().encode(JSON.stringify(snapshot)))}`
  if (hash.length > RULE_SHARE_MAX_LENGTH)
    throw new Error('规则过长，无法生成分享链接；可先保存到本机词库，再导出 JSON 备份。')
  return hash
}

/** 外部数据只按固定 JSON 结构校验；普通锚点与分享链接分开处理。 */
export function parseRuleShare(hash: string): SharedRules | null {
  if (!hash.startsWith('#r=')) return null
  if (hash.length > RULE_SHARE_MAX_LENGTH) throw new Error('分享链接超过长度限制')
  const payload = hash.slice(3)
  if (!/^[A-Za-z0-9_-]+$/u.test(payload) || payload.length % 4 === 1)
    throw new Error('分享链接编码无效')
  const binary = atob(payload.replaceAll('-', '+').replaceAll('_', '/'))
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0))
  // 拒绝非零填充位等非规范编码，避免同一片段出现多种不一致的解释。
  if (encodeBytes(bytes) !== payload) throw new Error('分享链接编码无效')
  const value: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
  if (
    typeof value !== 'object' ||
    value === null ||
    Object.keys(value).length !== 2 ||
    !Object.hasOwn(value, 'version') ||
    !Object.hasOwn(value, 'rules') ||
    (value as Record<string, unknown>).version !== 1
  )
    throw new Error('分享链接结构或版本无效')
  return Object.freeze({
    version: 1,
    rules: checkedRules((value as Record<string, unknown>).rules),
  })
}

/** 保留部署子路径，移除当前查询和片段，避免把无关网址数据一起分享。 */
export function createRuleShareUrl(baseUrl: string, rules: unknown): string {
  const target = new URL(baseUrl)
  if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password)
    throw new Error('当前网址不支持生成分享链接')
  target.search = ''
  target.hash = encodeRuleShare(rules)
  const result = target.href
  if (result.length > RULE_SHARE_MAX_LENGTH)
    throw new Error('规则过长，无法生成分享链接；可先保存到本机词库，再导出 JSON 备份。')
  return result
}

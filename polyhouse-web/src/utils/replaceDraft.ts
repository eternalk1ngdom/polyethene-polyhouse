import type { ReplaceRule } from '../core/acReplacer'

/** 草稿允许未完成的编辑，不能拿词库的“可执行规则”校验代替。 */
export interface ReplaceDraft {
  /** 固定版本，未知格式保留原数据而不猜测转换。 */
  readonly version: 1
  /** 保留空字段、重复查找和字面空白，恢复后仍由原替换流程验证能否执行。 */
  readonly rules: readonly Readonly<ReplaceRule>[]
  /** 本机 UTC 毫秒时间，仅用于恢复提示，不用于权限或业务判断。 */
  readonly updatedAt: number
}

export const REPLACE_DRAFT_MAX_BYTES = 64 * 1024

/** 单次页面的保存槽位；页面 ID 不作为用户身份，也不与账号绑定。 */
export interface StoredReplaceDraft {
  /** 每次页面实例独立生成，复制标签页也不会共用写入槽位。 */
  readonly id: string
  /** 只含规则编辑，不包含源文件、预览、下载包或凭据。 */
  readonly draft: ReplaceDraft
}

/** 开关与草稿在同一次本机写入中更新，关闭后不会留下可自动继续写入的旧设置。 */
export interface ReplaceDraftBook {
  /** 外层格式与单份草稿均独立验证。 */
  readonly version: 1
  /** 默认关闭，主动开启后作为后续页面的默认选择。 */
  readonly enabled: boolean
  /** 每次由关闭变为开启时更新，拒绝跨越关闭/重开的迟到请求。 */
  readonly generation: string
  /** 最多十份，不静默淘汰其他页面或历史草稿。 */
  readonly drafts: readonly StoredReplaceDraft[]
}

export const REPLACE_DRAFT_BOOK_MAX_BYTES = 768 * 1024

/** 草稿 ID 仅允许 ASCII，避免 Unicode 忽略大小写意外接受外观近似标识。 */
function validId(value: unknown): value is string {
  return (
    typeof value === 'string' && value.length <= 64 && /^[A-Za-z0-9][A-Za-z0-9_-]*$/u.test(value)
  )
}

/** 校验有界历史库，逐字段投影，不将外部 JSON 展开到应用对象。 */
export function validateReplaceDraftBook(value: unknown): ReplaceDraftBook {
  const source = record(value, ['version', 'enabled', 'generation', 'drafts'])
  if (
    source.version !== 1 ||
    typeof source.enabled !== 'boolean' ||
    !(validId(source.generation) || (!source.enabled && source.generation === '')) ||
    !Array.isArray(source.drafts) ||
    source.drafts.length > 10
  )
    throw new Error('草稿库版本、设置或数量不符合要求（最多 10 份）')
  const ids = new Set<string>()
  const drafts: StoredReplaceDraft[] = []
  for (const item of source.drafts as unknown[]) {
    const stored = record(item, ['id', 'draft'])
    if (!validId(stored.id) || ids.has(stored.id)) throw new Error('草稿标识无效或重复')
    ids.add(stored.id)
    drafts.push(Object.freeze({ id: stored.id, draft: validateReplaceDraft(stored.draft) }))
  }
  const book: ReplaceDraftBook = Object.freeze({
    version: 1,
    enabled: source.enabled,
    generation: source.generation as string,
    drafts: Object.freeze(drafts),
  })
  checkBookSize(JSON.stringify(book))
  return book
}

function checkBookSize(raw: string): void {
  if (
    raw.length > REPLACE_DRAFT_BOOK_MAX_BYTES ||
    new TextEncoder().encode(raw).byteLength > REPLACE_DRAFT_BOOK_MAX_BYTES
  )
    throw new Error('草稿库超过 768 KiB，请清理历史草稿')
}

/** 读取原文先限额；损坏或未来版本不被当作空历史。 */
export function parseReplaceDraftBook(raw: string): ReplaceDraftBook {
  checkBookSize(raw)
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    throw new Error('本机草稿库不是有效的 JSON 数据')
  }
  return validateReplaceDraftBook(value)
}

function record(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (
    typeof value !== 'object' ||
    value === null ||
    ![Object.prototype, null].includes(Object.getPrototypeOf(value)) ||
    Reflect.ownKeys(value).length !== keys.length ||
    keys.some((key) => !Object.hasOwn(value, key)) ||
    keys.some((key) => !Object.hasOwn(Object.getOwnPropertyDescriptor(value, key)!, 'value'))
  )
    throw new Error('草稿结构或字段不符合要求')
  return value as Record<string, unknown>
}

function checkSize(serialized: string): void {
  if (
    serialized.length > REPLACE_DRAFT_MAX_BYTES ||
    new TextEncoder().encode(serialized).byteLength > REPLACE_DRAFT_MAX_BYTES
  )
    throw new Error('草稿超过 64 KiB，请减少规则内容')
}

/** 全空才视为清空；空格本身可以是有效的字面替换文本，不能 trim 后判断。 */
export function isEmptyReplaceDraft(rules: readonly Readonly<ReplaceRule>[]): boolean {
  return rules.every((rule) => rule.search === '' && rule.replace === '')
}

/** 只校验编辑容量与固定字段，复制冻结后供异步保存，防止等待期间内容改变。 */
export function validateReplaceDraft(value: unknown): ReplaceDraft {
  const source = record(value, ['version', 'rules', 'updatedAt'])
  if (source.version !== 1) throw new Error('不支持的草稿版本')
  if (!Array.isArray(source.rules) || !source.rules.length || source.rules.length > 20)
    throw new Error('草稿需要 1～20 条规则')
  if (
    typeof source.updatedAt !== 'number' ||
    !Number.isSafeInteger(source.updatedAt) ||
    source.updatedAt < 0 ||
    source.updatedAt > 8.64e15
  )
    throw new Error('草稿时间不符合要求')
  const rules: Readonly<ReplaceRule>[] = []
  for (const item of source.rules as unknown[]) {
    const rule = record(item, ['search', 'replace'])
    if (
      typeof rule.search !== 'string' ||
      typeof rule.replace !== 'string' ||
      rule.search.length > 200 ||
      rule.replace.length > 200
    )
      throw new Error('草稿每项内容最多 200 个字符')
    // JSON 会转义孤立代理项，草稿可无损保留尚未完成的字符；不能因此允许直接执行。
    rules.push(Object.freeze({ search: rule.search, replace: rule.replace }))
  }
  const draft: ReplaceDraft = Object.freeze({
    version: 1,
    rules: Object.freeze(rules),
    updatedAt: source.updatedAt,
  })
  checkSize(JSON.stringify(draft))
  return draft
}

/** 解析前先限额，坏数据不被转换为空草稿而覆盖。 */
export function parseReplaceDraft(serialized: string): ReplaceDraft {
  checkSize(serialized)
  let value: unknown
  try {
    value = JSON.parse(serialized)
  } catch {
    throw new Error('草稿不是有效的 JSON 数据')
  }
  return validateReplaceDraft(value)
}

/** 编解码只生成文本，不访问浏览器、文件或账号缓存。 */
export function serializeReplaceDraft(value: unknown): string {
  return JSON.stringify(validateReplaceDraft(value))
}

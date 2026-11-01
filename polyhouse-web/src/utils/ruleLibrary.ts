import type { ReplaceRule } from '../core/acReplacer'

/** 本机格式独立于账号数据，版本不匹配时拒绝猜测迁移。 */
export interface RuleLibrary {
  /** 固定格式版本，用于识别未来不兼容的数据。 */
  readonly version: 1
  /** 空库合法，单组规则则必须可以交给现有替换内核。 */
  readonly sets: readonly RuleSet[]
}

/** 保存时复制并冻结，避免异步等待存储锁期间被调用方改写。 */
export interface RuleSet {
  /** 与显示名称分离的稳定标识，允许 UUID 和本机生成的短标识。 */
  readonly id: string
  /** 显示名称去除首尾空白；重名按 NFC 与小写形式判断。 */
  readonly name: string
  /** 查找和替换保留原文空白，不将字面匹配偷偷变成归一化匹配。 */
  readonly rules: readonly Readonly<ReplaceRule>[]
  /** UTC 毫秒时间戳，避免依赖本机日期格式解析。 */
  readonly createdAt: number
  /** 不早于创建时间，便于后续界面解释最后保存时间。 */
  readonly updatedAt: number
}

export const RULE_LIBRARY_MAX_BYTES = 256 * 1024

/** 只接受固定字段的普通数据对象，不把外部字段混入应用对象或原型。 */
function record(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (
    typeof value !== 'object' ||
    value === null ||
    ![Object.prototype, null].includes(Object.getPrototypeOf(value)) ||
    Reflect.ownKeys(value).length !== keys.length ||
    keys.some((key) => !Object.hasOwn(value, key)) ||
    keys.some((key) => !Object.hasOwn(Object.getOwnPropertyDescriptor(value, key)!, 'value'))
  )
    throw new Error('词库结构或字段不符合要求')
  return value as Record<string, unknown>
}

/** 孤立代理项无法无损导出为 UTF-8；代码单元长度与现有内核的 200 上限一致。 */
function text(value: unknown, max: number, allowEmpty = false): string {
  if (typeof value !== 'string' || value.length > max || (!allowEmpty && !value.length))
    throw new Error('词库文本为空或超过长度限制')
  for (const character of value) {
    const code = character.charCodeAt(0)
    if (character.length === 1 && code >= 0xd800 && code <= 0xdfff)
      throw new Error('词库包含不完整的 Unicode 字符')
  }
  return value
}

/** 防止 NaN、无穷数和非法日期进入存储，时间不参与权限或业务授权。 */
function timestamp(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0 || value > 8.64e15)
    throw new Error('词库时间不符合要求')
  return value
}

/** 双重限额按 UTF-8 序列化字节计算；30 组不是保证能存满所有最长中文规则。 */
function checkSize(serialized: string): void {
  if (
    serialized.length > RULE_LIBRARY_MAX_BYTES ||
    new TextEncoder().encode(serialized).byteLength > RULE_LIBRARY_MAX_BYTES
  )
    throw new Error('词库超过 256 KiB，请减少规则或词库组数')
}

/** 校验并投影到只读副本，拒绝未知字段而非通过对象展开继承外部数据。 */
export function validateRuleLibrary(value: unknown): RuleLibrary {
  const source = record(value, ['version', 'sets'])
  if (source.version !== 1) throw new Error('不支持的词库版本')
  if (!Array.isArray(source.sets) || source.sets.length > 30)
    throw new Error('本机词库最多保存 30 组')
  const ids = new Set<string>()
  const names = new Set<string>()
  const sets: RuleSet[] = []
  for (const item of source.sets as unknown[]) {
    const set = record(item, ['id', 'name', 'rules', 'createdAt', 'updatedAt'])
    const id = text(set.id, 64)
    // 显式列出 ASCII 大小写；/iu 会额外放行 K、ſ 等 Unicode 折叠字符。
    if (!/^[A-Za-z0-9][A-Za-z0-9_-]*$/u.test(id) || ids.has(id))
      throw new Error('词库标识无效或重复')
    const name = text(typeof set.name === 'string' ? set.name.trim() : set.name, 32)
    const normalizedName = name.normalize('NFC').toLowerCase()
    if (names.has(normalizedName)) throw new Error('词库名称重复，请另存或明确选择覆盖')
    ids.add(id)
    names.add(normalizedName)
    if (!Array.isArray(set.rules) || !set.rules.length || set.rules.length > 20)
      throw new Error('每组词库需要 1～20 条规则')
    const searches = new Set<string>()
    const rules: Readonly<ReplaceRule>[] = []
    for (const item of set.rules as unknown[]) {
      const rule = record(item, ['search', 'replace'])
      const search = text(rule.search, 200)
      const replace = text(rule.replace, 200, true)
      if (searches.has(search)) throw new Error('同组查找内容不能重复')
      searches.add(search)
      rules.push(Object.freeze({ search, replace }))
    }
    const createdAt = timestamp(set.createdAt)
    const updatedAt = timestamp(set.updatedAt)
    if (updatedAt < createdAt) throw new Error('词库更新时间不能早于创建时间')
    sets.push(Object.freeze({ id, name, rules: Object.freeze(rules), createdAt, updatedAt }))
  }
  const library: RuleLibrary = Object.freeze({ version: 1, sets: Object.freeze(sets) })
  checkSize(JSON.stringify(library))
  return library
}

/** 在解析前限制原始体积，损坏数据不会被当作空库覆盖。 */
export function parseRuleLibrary(serialized: string): RuleLibrary {
  checkSize(serialized)
  let value: unknown
  try {
    value = JSON.parse(serialized)
  } catch {
    throw new Error('本机词库不是有效的 JSON 数据')
  }
  return validateRuleLibrary(value)
}

/** 输出唯一的固定字段格式，不执行脚本、不生成文件也不读写存储。 */
export function serializeRuleLibrary(value: unknown): string {
  return JSON.stringify(validateRuleLibrary(value))
}

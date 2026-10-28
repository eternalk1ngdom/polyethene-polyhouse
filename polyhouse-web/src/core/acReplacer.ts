/** 一条替换规则；原文和结果均按字面内容处理，不执行正则。 */
export interface ReplaceRule {
  /** 非空查找文本，用于匹配原始内容。 */
  search: string
  /** 替换结果允许为空，以支持删除文本。 */
  replace: string
}

/** 返回完整结果和匹配次数，方便界面展示并生成下载文件。 */
export interface ReplaceResult {
  /** 基于原始文本生成的新内容。 */
  text: string
  /** 所有规则总命中次数。 */
  count: number
}

/** 对原文从左到右进行单次替换；同一位置优先选择较长规则。 */
export function replaceText(input: string, rules: readonly ReplaceRule[]): ReplaceResult {
  if (!rules.length || rules.some((rule) => !rule.search)) throw new Error('请填写非空的查找内容')
  if (new Set(rules.map((rule) => rule.search)).size !== rules.length) throw new Error('查找内容不能重复')
  // 按长度排序只改变同一位置的优先级，结果绝不参与再次匹配。
  const ordered = [...rules].sort((a, b) => b.search.length - a.search.length)
  const parts: string[] = []
  let cursor = 0
  let count = 0
  let pending = 0
  let outputLength = 0
  while (cursor < input.length) {
    const match = ordered.find((rule) => input.startsWith(rule.search, cursor))
    if (!match) { cursor += 1; continue }
    const unchanged = input.slice(pending, cursor)
    outputLength += unchanged.length + match.replace.length
    if (outputLength > 8 * 1024 * 1024) throw new Error('替换结果超过 8 MB 字符限制，请缩小文件或调整规则')
    parts.push(unchanged, match.replace)
    cursor += match.search.length
    pending = cursor
    count += 1
  }
  outputLength += input.length - pending
  if (outputLength > 8 * 1024 * 1024) throw new Error('替换结果超过 8 MB 字符限制，请缩小文件或调整规则')
  parts.push(input.slice(pending))
  return { text: parts.join(''), count }
}

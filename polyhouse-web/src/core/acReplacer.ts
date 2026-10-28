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

/** 反向字典树节点；编号 0 是根节点。 */
interface MatchNode {
  /** 按 UTF-16 代码单元寻找下一状态，以兼容原有 startsWith 行为。 */
  children: Map<string, number>
  /** 当前状态不匹配时可复用的最长后缀状态。 */
  failure: number
  /** 当前状态及其失败链上最长匹配的规则编号；-1 表示没有命中。 */
  bestRule: number
}

/** 编译一次规则供同批文件复用；匹配与输出只读取原文。 */
export function createTextReplacer(
  rules: readonly ReplaceRule[],
): (input: string) => ReplaceResult {
  if (
    rules.length > 20 ||
    rules.some((rule) => rule.search.length > 200 || rule.replace.length > 200)
  )
    throw new Error('规则最多 20 条，每项内容最多 200 个字符')
  if (!rules.length || rules.some((rule) => !rule.search)) throw new Error('请填写非空的查找内容')
  if (new Set(rules.map((rule) => rule.search)).size !== rules.length)
    throw new Error('查找内容不能重复')

  // 拷贝规则，防止调用方在多个文件之间修改已编译的规则。
  const snapshot = rules.map(({ search, replace }) => ({ search, replace }))
  const nodes: MatchNode[] = [{ children: new Map(), failure: 0, bestRule: -1 }]
  for (const [index, rule] of snapshot.entries()) {
    let state = 0
    // 反向构建使从右到左扫描时，同一个位置可直接挑出最长的起始匹配。
    for (let i = rule.search.length - 1; i >= 0; i--) {
      const character = rule.search[i]!
      let next = nodes[state]!.children.get(character)
      if (next === undefined) {
        next = nodes.length
        nodes.push({ children: new Map(), failure: 0, bestRule: -1 })
        nodes[state]!.children.set(character, next)
      }
      state = next
    }
    nodes[state]!.bestRule = index
  }

  // 广度优先计算失败链接，保证父级的最长候选已先于子级计算完成。
  const queue = [...nodes[0]!.children.values()]
  for (let head = 0; head < queue.length; head++) {
    const state = queue[head]!
    for (const [character, next] of nodes[state]!.children) {
      let fallback = nodes[state]!.failure
      while (fallback !== 0 && !nodes[fallback]!.children.has(character)) {
        fallback = nodes[fallback]!.failure
      }
      const failure = nodes[fallback]!.children.get(character) ?? 0
      nodes[next]!.failure = failure
      const inherited = nodes[failure]!.bestRule
      const own = nodes[next]!.bestRule
      if (
        inherited !== -1 &&
        (own === -1 || snapshot[inherited]!.search.length > snapshot[own]!.search.length)
      )
        nodes[next]!.bestRule = inherited
      queue.push(next)
    }
  }

  return (input: string): ReplaceResult => {
    // 每个位置只记录最长候选；规则最多 20 条，1～20 可装入一个字节，0 表示无命中。
    const bestAt = new Uint8Array(input.length)
    let state = 0
    for (let i = input.length - 1; i >= 0; i--) {
      const character = input[i]!
      while (state !== 0 && !nodes[state]!.children.has(character)) {
        state = nodes[state]!.failure
      }
      state = nodes[state]!.children.get(character) ?? 0
      const rule = nodes[state]!.bestRule
      if (rule !== -1) bestAt[i] = rule + 1
    }
    const parts: string[] = []
    let cursor = 0
    let count = 0
    let pending = 0
    let outputLength = 0
    // 只按原文从左到右消费候选，跳过已替换的区间，不再次匹配替换结果。
    while (cursor < input.length) {
      const candidate = bestAt[cursor]!
      if (!candidate) {
        cursor += 1
        continue
      }
      const rule = snapshot[candidate - 1]!
      const unchanged = input.slice(pending, cursor)
      outputLength += unchanged.length + rule.replace.length
      if (outputLength > 8 * 1024 * 1024)
        throw new Error('替换结果超过字符数量限制，请缩小文件或调整规则')
      parts.push(unchanged, rule.replace)
      cursor += rule.search.length
      pending = cursor
      count += 1
    }
    outputLength += input.length - pending
    if (outputLength > 8 * 1024 * 1024)
      throw new Error('替换结果超过字符数量限制，请缩小文件或调整规则')
    parts.push(input.slice(pending))
    return { text: parts.join(''), count }
  }
}

/** 单次调用兼容入口；多文件批处理应复用编译结果。 */
export function replaceText(input: string, rules: readonly ReplaceRule[]): ReplaceResult {
  return createTextReplacer(rules)(input)
}

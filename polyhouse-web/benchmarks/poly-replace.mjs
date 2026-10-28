import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { performance } from 'node:perf_hooks'
import ts from 'typescript'

// 此基准使用真实内核；直接匹配版本作为行为和速度参照，不计文件读取及 ZIP。
const source = await readFile(new URL('../src/core/acReplacer.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const { createTextReplacer } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
)

function directReplace(input, rules) {
  const ordered = [...rules].sort((a, b) => b.search.length - a.search.length)
  const parts = []
  let cursor = 0,
    pending = 0,
    count = 0
  while (cursor < input.length) {
    const rule = ordered.find((item) => input.startsWith(item.search, cursor))
    if (!rule) {
      cursor++
      continue
    }
    parts.push(input.slice(pending, cursor), rule.replace)
    cursor += rule.search.length
    pending = cursor
    count++
  }
  parts.push(input.slice(pending))
  return { text: parts.join(''), count }
}

const cases = [
  {
    name: '无匹配，1 MiB ASCII',
    input: 'x'.repeat(1024 * 1024),
    rules: Array.from({ length: 20 }, (_, i) => ({ search: `word${i}`, replace: 'z' })),
  },
  {
    name: '高重叠前缀，256 KiB ASCII',
    input: 'a'.repeat(256 * 1024),
    rules: Array.from({ length: 20 }, (_, i) => ({
      search: `${'a'.repeat(32)}${String.fromCharCode(65 + i)}`,
      replace: 'z',
    })),
  },
  {
    name: '密集命中，512 KiB ASCII',
    input: 'abc '.repeat(128 * 1024),
    rules: Array.from({ length: 20 }, (_, i) => ({ search: i ? `word${i}` : 'abc', replace: 'z' })),
  },
]

console.log(`Node ${process.version}；每项预热一次、测量三次并取中位数；仅测内核，单位 ms。`)
for (const item of cases) {
  const apply = createTextReplacer(item.rules)
  assert.deepEqual(apply(item.input), directReplace(item.input, item.rules))
  const times = { direct: [], ac: [] }
  for (let i = 0; i < 3; i++) {
    let start = performance.now()
    directReplace(item.input, item.rules)
    times.direct.push(performance.now() - start)
    start = performance.now()
    apply(item.input)
    times.ac.push(performance.now() - start)
  }
  const median = (values) => values.sort((a, b) => a - b)[1].toFixed(1)
  console.log(`${item.name}：直接匹配 ${median(times.direct)} ms，AC ${median(times.ac)} ms`)
}

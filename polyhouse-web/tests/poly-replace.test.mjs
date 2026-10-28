import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'
const source = await readFile(new URL('../src/core/acReplacer.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const { replaceText, createTextReplacer } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
)
test('仅匹配原文，不发生二次串改', () =>
  assert.deepEqual(
    replaceText('A B A', [
      { search: 'A', replace: 'B' },
      { search: 'B', replace: 'C' },
    ]),
    { text: 'B C B', count: 3 },
  ))
test('同位置最长匹配、空替换及 Unicode', () =>
  assert.deepEqual(
    replaceText('小明小😀明', [
      { search: '小', replace: '大' },
      { search: '小明', replace: '' },
      { search: '😀', replace: '🌸' },
    ]),
    { text: '大🌸明', count: 3 },
  ))
test('无匹配及空文件保留原内容', () => {
  assert.deepEqual(replaceText('abc\r\n', [{ search: 'x', replace: 'y' }]), {
    text: 'abc\r\n',
    count: 0,
  })
  assert.deepEqual(replaceText('', [{ search: 'x', replace: 'y' }]), { text: '', count: 0 })
})
test('拒绝空规则、重复查找与过大输出', () => {
  assert.throws(() => replaceText('a', []))
  assert.throws(() => replaceText('a', [{ search: '', replace: 'x' }]))
  assert.throws(() =>
    replaceText('a', [
      { search: 'a', replace: 'x' },
      { search: 'a', replace: 'y' },
    ]),
  )
  assert.throws(
    () => replaceText('a'.repeat(50000), [{ search: 'a', replace: 'x'.repeat(200) }]),
    /超过/,
  )
})

test('较早起点优先于较早结束，失败链保留最长候选', () => {
  assert.deepEqual(
    replaceText('abcd', [
      { search: 'abc', replace: 'X' },
      { search: 'bc', replace: 'Y' },
      { search: 'abcd', replace: 'Z' },
    ]),
    { text: 'Z', count: 1 },
  )
  assert.deepEqual(
    replaceText('abc', [
      { search: 'ab', replace: 'X' },
      { search: 'bc', replace: 'Y' },
    ]),
    { text: 'Xc', count: 1 },
  )
  assert.deepEqual(
    replaceText('aaaaa', [
      { search: 'aa', replace: 'X' },
      { search: 'aaa', replace: 'Y' },
    ]),
    { text: 'YX', count: 2 },
  )
})

test('编译规则后复用且不受原规则对象后续改动影响', () => {
  const rules = [{ search: '猫', replace: '狗' }]
  const apply = createTextReplacer(rules)
  rules[0].replace = '鸟'
  rules[0].search = '狗'
  assert.deepEqual(apply('猫猫'), { text: '狗狗', count: 2 })
  assert.deepEqual(apply('狗'), { text: '狗', count: 0 })
})

test('固定随机种子对照直接匹配：重叠、Unicode 和替换内容', () => {
  let seed = 0x5eedcafe
  const rand = (max) => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0
    return seed % max
  }
  const alphabet = ['a', 'b', '中', '😀', '\r', '\n', '\ud800', '\udc00']
  const sample = (max) =>
    Array.from({ length: rand(max + 1) }, () => alphabet[rand(alphabet.length)]).join('')
  const reference = (input, rules) => {
    const ordered = [...rules].sort((a, b) => b.search.length - a.search.length)
    let result = '',
      count = 0,
      cursor = 0
    while (cursor < input.length) {
      const rule = ordered.find((item) => input.startsWith(item.search, cursor))
      if (rule) {
        result += rule.replace
        cursor += rule.search.length
        count++
      } else {
        result += input[cursor]
        cursor++
      }
    }
    return { text: result, count }
  }
  for (let iteration = 0; iteration < 3000; iteration++) {
    const searches = new Set()
    while (searches.size < 1 + (iteration % 8)) {
      const word = sample(5)
      if (word) searches.add(word)
    }
    const rules = [...searches].map((search) => ({ search, replace: sample(4) }))
    const input = sample(80)
    assert.deepEqual(replaceText(input, rules), reference(input, rules), `case ${iteration}`)
  }
})

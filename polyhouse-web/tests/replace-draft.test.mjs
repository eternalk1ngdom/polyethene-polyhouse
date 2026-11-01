import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(new URL('../src/utils/replaceDraft.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const {
  validateReplaceDraft,
  parseReplaceDraft,
  serializeReplaceDraft,
  isEmptyReplaceDraft,
  REPLACE_DRAFT_MAX_BYTES,
} = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
const draft = (rules = [{ search: '小明', replace: '小红' }]) => ({
  version: 1,
  rules,
  updatedAt: 100,
})

test('草稿保留半成品、重复项、换行、emoji 和删除型规则', () => {
  const value = draft([
    { search: '', replace: '未写完😀\n' },
    { search: 'x', replace: '' },
    { search: 'x', replace: '另一个' },
  ])
  assert.deepEqual(parseReplaceDraft(serializeReplaceDraft(value)), value)
})
test('编辑中孤立代理项经 JSON 无损保留，不冒充可执行词库', () => {
  const value = draft([{ search: '\ud800', replace: '\udfff' }])
  assert.deepEqual(parseReplaceDraft(serializeReplaceDraft(value)), value)
})
test('全空才是清空，字面空格不能被当作空草稿丢弃', () => {
  assert.equal(isEmptyReplaceDraft([{ search: '', replace: '' }]), true)
  assert.equal(isEmptyReplaceDraft([{ search: ' ', replace: '' }]), false)
  assert.equal(isEmptyReplaceDraft([{ search: '', replace: '\n' }]), false)
})
test('草稿返回深冻结副本，调用方后续编辑不污染待保存快照', () => {
  const input = draft()
  const result = validateReplaceDraft(input)
  input.rules[0].replace = '变更'
  assert.equal(result.rules[0].replace, '小红')
  assert.ok(
    Object.isFrozen(result) && Object.isFrozen(result.rules) && Object.isFrozen(result.rules[0]),
  )
})
test('固定字段/版本拒绝非法结构和危险对象键，普通规则词不受影响', () => {
  for (const value of [
    null,
    [],
    {},
    { version: 2, rules: [], updatedAt: 1 },
    { ...draft(), extra: true },
    draft([null]),
  ])
    assert.throws(() => validateReplaceDraft(value))
  for (const key of ['__proto__', 'constructor', 'prototype']) {
    const value = draft()
    Object.defineProperty(value.rules[0], key, { enumerable: true, value: { polluted: true } })
    assert.throws(() => parseReplaceDraft(JSON.stringify(value)))
  }
  assert.equal(
    parseReplaceDraft(
      serializeReplaceDraft(draft([{ search: 'constructor', replace: '__proto__' }])),
    ).rules[0].search,
    'constructor',
  )
  assert.equal(Object.prototype.polluted, undefined)
})
test('1～20 条及每字段 200 代码单元限额，时间必须有效', () => {
  assert.equal(
    validateReplaceDraft(
      draft(
        Array.from({ length: 20 }, () => ({ search: 'x'.repeat(200), replace: '😀'.repeat(100) })),
      ),
    ).rules.length,
    20,
  )
  for (const rules of [
    [],
    Array.from({ length: 21 }, () => ({ search: '', replace: '' })),
    [{ search: 'x'.repeat(201), replace: '' }],
    [{ search: '', replace: null }],
  ])
    assert.throws(() => validateReplaceDraft(draft(rules)))
  for (const updatedAt of [-1, NaN, Infinity, 1.5, '100', 8.64e15 + 1])
    assert.throws(() => validateReplaceDraft({ ...draft(), updatedAt }))
})
test('解析前拒绝损坏及超大数据，容量按 UTF-8 字节而非字符计', () => {
  for (const raw of ['', '{', 'null']) assert.throws(() => parseReplaceDraft(raw))
  const raw = serializeReplaceDraft(draft())
  const length = raw.length + REPLACE_DRAFT_MAX_BYTES - new TextEncoder().encode(raw).byteLength
  assert.equal(parseReplaceDraft(raw.padEnd(length, ' ')).rules.length, 1)
  assert.throws(() => parseReplaceDraft(raw.padEnd(length + 1, ' ')))
})

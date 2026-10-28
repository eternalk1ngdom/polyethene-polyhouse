import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

async function sourceUrl(path, replacements = {}) {
  const source = await readFile(new URL(path, import.meta.url), 'utf8')
  let code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(replacements)) code = code.replaceAll(from, to)
  return `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
}
const coreUrl = await sourceUrl('../src/core/acReplacer.ts')
const batchUrl = await sourceUrl('../src/core/polyReplaceBatch.ts', {
  "'./acReplacer'": JSON.stringify(coreUrl),
})
const { replaceBatch, validateBatch } = await import(batchUrl)
const file = (name, text) => ({
  relativePath: name,
  content: new TextEncoder().encode(text).buffer,
})
const rules = [
  { search: 'A', replace: 'B' },
  { search: 'B', replace: 'C' },
]

test('多文件各自替换、不串改，重名序号保全', () => {
  const result = replaceBatch([file('中文.txt', 'A B'), file('中文.txt', 'A')], rules)
  assert.deepEqual(
    result.map((x) => x.preview),
    ['B C', 'B'],
  )
  assert.equal(new Set(result.map((x) => x.name)).size, 2)
})
test('字节级保留 BOM、CRLF 和空文件', () => {
  const input = file('a.txt', '\uFEFFA\r\n')
  const result = replaceBatch([input], rules)[0]
  assert.deepEqual([...result.content], [...new TextEncoder().encode('\uFEFFB\r\n')])
  assert.equal(replaceBatch([file('empty.txt', '')], rules)[0].content.length, 0)
})
test('任意文件含二进制或错误 UTF-8 时整批失败', () => {
  for (const bytes of [[0], [0xff, 0xff]])
    assert.throws(() =>
      replaceBatch(
        [file('ok.txt', 'A'), { relativePath: 'bad.txt', content: Uint8Array.from(bytes).buffer }],
        rules,
      ),
    )
})
test('文件数、单文件/总输入、路径均有界', () => {
  assert.throws(() => validateBatch([]))
  assert.throws(() =>
    validateBatch(Array.from({ length: 21 }, () => ({ relativePath: 'a.txt', size: 0 }))),
  )
  assert.throws(() => validateBatch([{ relativePath: 'a.txt', size: 2 * 1024 * 1024 + 1 }]))
  assert.throws(() =>
    validateBatch(
      Array.from({ length: 3 }, () => ({ relativePath: 'a.txt', size: 2 * 1024 * 1024 })),
    ),
  )
  for (const name of ['../bad.txt', 'a\\bad.txt', '..', 'bad\u0000.txt'])
    assert.throws(() => validateBatch([{ relativePath: name, size: 0 }]))
})
test('按 UTF-8 字节限制单个输出，预览截断不截断导出', () => {
  assert.throws(
    () =>
      replaceBatch(
        [file('large.txt', 'A'.repeat(20000))],
        [{ search: 'A', replace: '中'.repeat(200) }],
      ),
    /大小限制/,
  )
  const result = replaceBatch([file('long.txt', 'x'.repeat(11000))], rules)[0]
  assert.equal(result.preview.length, 10000)
  assert.equal(result.content.length, 11000)
  assert.equal(result.truncated, true)
})
test('Worker 生成实际 ZIP 且出错不发成功包', async () => {
  const replies = []
  const oldSelf = globalThis.self
  globalThis.self = { postMessage: (value) => replies.push(value) }
  try {
    const workerUrl = await sourceUrl('../src/workers/polyReplace.worker.ts', {
      "'fflate'": JSON.stringify(import.meta.resolve('fflate')),
      "'../core/polyReplaceBatch'": JSON.stringify(batchUrl),
    })
    await import(workerUrl)
    self.onmessage({ data: { files: [file('a.txt', 'A'), file('b.txt', 'B')], rules } })
    const { unzipSync } = await import('fflate')
    assert.equal(replies[0].ok, true)
    assert.deepEqual(
      Object.values(unzipSync(new Uint8Array(replies[0].result.bytes))).map((bytes) =>
        new TextDecoder().decode(bytes),
      ),
      ['B', 'C'],
    )
    self.onmessage({ data: { files: [file('a.txt', 'A'), file('../bad', 'A')], rules } })
    assert.equal(replies[1].ok, false)
    assert.equal(replies[1].result, undefined)
  } finally {
    if (oldSelf === undefined) delete globalThis.self
    else globalThis.self = oldSelf
  }
})
test('整批输出预算不能被多个合法小文件绕过', () => {
  assert.throws(() => replaceBatch([file('a.txt', 'A'.repeat(10000)), file('b.txt', 'A'.repeat(10000)), file('c.txt', 'A'.repeat(10000))], [{search:'A',replace:'中'.repeat(200)}]), /大小限制/)
})
test('算法入口也校验规则数量和长度，不依赖页面 maxlength', async () => {
  const { replaceText } = await import(coreUrl)
  assert.throws(() => replaceText('a', Array.from({length:21},(_,i)=>({search:String(i),replace:''}))), /规则最多/)
  assert.throws(() => replaceText('a', [{search:'a',replace:'x'.repeat(201)}]), /规则最多/)
})

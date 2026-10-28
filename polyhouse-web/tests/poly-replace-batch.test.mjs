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
test('文件夹保留根目录和嵌套路径、文件名及原始字节格式', () => {
  const result = replaceBatch(
    [file('资料/子目录/一.txt', '\uFEFFA\r\n'), file('资料/二.txt', 'B')],
    rules,
    'directory',
  )
  assert.deepEqual(
    result.map((entry) => entry.name),
    ['资料/子目录/一.txt', '资料/二.txt'],
  )
  assert.deepEqual([...result[0].content], [...new TextEncoder().encode('\uFEFFB\r\n')])
  assert.equal(result[1].preview, 'C')
})
test('文件夹拒绝路径穿越、跨根、跨平台重名及文件与目录冲突', () => {
  for (const path of [
    '根/../a',
    '/根/a',
    '根//a',
    '根/a\\b',
    '根/CON.txt',
    '根/name.',
    '根/__proto__/a',
  ]) {
    assert.throws(() => validateBatch([{ relativePath: path, size: 0 }], 'directory'))
  }
  for (const paths of [
    ['根/a', '另一个/b'],
    ['根/A.txt', '根/a.txt'],
    ['根/é.txt', '根/e\u0301.txt'],
    ['根/a', '根/a/b'],
  ]) {
    assert.throws(() =>
      validateBatch(
        paths.map((relativePath) => ({ relativePath, size: 0 })),
        'directory',
      ),
    )
  }
})
test('字节级保留 BOM、CRLF 和空文件', () => {
  const input = file('a.txt', '\uFEFFA\r\n')
  const result = replaceBatch([input], rules)[0]
  assert.deepEqual([...result.content], [...new TextEncoder().encode('\uFEFFB\r\n')])
  assert.equal(replaceBatch([file('empty.txt', '')], rules)[0].content.length, 0)
})
test('按用户指定的 GB18030 解码，并导出完整 UTF-8 字符与换行', () => {
  const input = {
    relativePath: 'old.txt',
    content: Uint8Array.from([0xd6, 0xd0, 0xce, 0xc4, 13, 10]).buffer,
  }
  const result = replaceBatch([input], [{ search: '中文', replace: '新😀' }], 'files', 'gb18030')[0]
  assert.deepEqual([...result.content], [...new TextEncoder().encode('新😀\r\n')])
  assert.equal(result.preview, '新😀\r\n')
  assert.throws(() => replaceBatch([input], rules), /所选编码/)
})
test('旧编码错误字节、UTF-8 标记及不完整结果均拒绝整批导出', () => {
  const malformed = { relativePath: 'bad.txt', content: Uint8Array.from([0x81]).buffer }
  assert.throws(
    () => replaceBatch([file('ok.txt', 'ok'), malformed], rules, 'files', 'gb18030'),
    /第 2 个文件/,
  )
  assert.throws(
    () => replaceBatch([file('bom.txt', '\uFEFFhello')], rules, 'files', 'gb18030'),
    /UTF-8 标记/,
  )
  assert.throws(
    () => replaceBatch([file('a.txt', 'A')], [{ search: 'A', replace: '\ud800' }]),
    /无效字符/,
  )
  assert.throws(() => replaceBatch([file('a.txt', 'A')], rules, 'files', 'unknown'), /不支持/)
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
    self.onmessage({ data: { mode: 'directory', files: [file('资料/子目录/a.txt', 'A')], rules } })
    assert.equal(replies[2].result.mime, 'application/zip')
    const directoryZip = unzipSync(new Uint8Array(replies[2].result.bytes))
    assert.deepEqual(Object.keys(directoryZip), ['资料/子目录/a.txt'])
    assert.equal(new TextDecoder().decode(directoryZip['资料/子目录/a.txt']), 'B')
    self.onmessage({
      data: {
        mode: 'directory',
        files: [
          file('资料/ok.txt', 'A'),
          { relativePath: '资料/bad', content: Uint8Array.of(255).buffer },
        ],
        rules,
      },
    })
    assert.equal(replies[3].ok, false)
    assert.equal(replies[3].result, undefined)
    self.onmessage({
      data: {
        mode: 'files',
        inputEncoding: 'gb18030',
        files: [
          { relativePath: 'old.txt', content: Uint8Array.from([0xd6, 0xd0, 0xce, 0xc4]).buffer },
        ],
        rules: [{ search: '中文', replace: '测试' }],
      },
    })
    assert.equal(replies[4].ok, true)
    assert.equal(new TextDecoder().decode(replies[4].result.bytes), '测试')
  } finally {
    if (oldSelf === undefined) delete globalThis.self
    else globalThis.self = oldSelf
  }
})
test('整批输出预算不能被多个合法小文件绕过', () => {
  assert.throws(
    () =>
      replaceBatch(
        [
          file('a.txt', 'A'.repeat(10000)),
          file('b.txt', 'A'.repeat(10000)),
          file('c.txt', 'A'.repeat(10000)),
        ],
        [{ search: 'A', replace: '中'.repeat(200) }],
      ),
    /大小限制/,
  )
})
test('算法入口也校验规则数量和长度，不依赖页面 maxlength', async () => {
  const { replaceText } = await import(coreUrl)
  assert.throws(
    () =>
      replaceText(
        'a',
        Array.from({ length: 21 }, (_, i) => ({ search: String(i), replace: '' })),
      ),
    /规则最多/,
  )
  assert.throws(() => replaceText('a', [{ search: 'a', replace: 'x'.repeat(201) }]), /规则最多/)
})

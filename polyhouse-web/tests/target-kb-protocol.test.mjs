import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

// 与现有测试一致：内存编译并执行真实模块，只替换模块地址，不替换被测逻辑。
function toModuleUrl(source, replacements = {}) {
  let output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [specifier, url] of Object.entries(replacements)) {
    const quoted = JSON.stringify(url)
    output = output.replaceAll(`"${specifier}"`, quoted).replaceAll(`'${specifier}'`, quoted)
  }
  return `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`
}

async function loadProtocol() {
  const read = (name) => readFile(new URL(`../src/core/${name}`, import.meta.url), 'utf8')
  const types = toModuleUrl(await read('targetKbTypes.ts'))
  const compress = toModuleUrl(await read('targetKbCompress.ts'), { './targetKbTypes': types })
  const protocol = toModuleUrl(await read('targetKbProtocol.ts'), {
    './targetKbTypes': types,
    './targetKbCompress': compress,
  })
  return import(protocol)
}

const { isCurrentReply, parseRequest } = await loadProtocol()

/** 构造一条合法请求；用例只覆盖需要变化的字段。 */
function request(overrides = {}) {
  return {
    id: 1,
    kind: 'encode',
    bytes: new ArrayBuffer(16),
    target: { minBytes: 0, maxBytes: 200 * 1024 },
    allowShrink: true,
    dpi: null,
    ...overrides,
  }
}

test('过期回调判定：只有 id 相等且存在有效任务时才认', () => {
  assert.equal(isCurrentReply(3, 3), true)
  assert.equal(isCurrentReply(3, 4), false)
  // 0 表示已取消或没有任务，此时任何回复都必须丢弃。
  assert.equal(isCurrentReply(3, 0), false)
  assert.equal(isCurrentReply(0, 0), false)
  assert.equal(isCurrentReply(1.5, 1.5), false)
})

test('合法 encode 请求通过校验并规整字段', () => {
  const parsed = parseRequest(request({ dpi: 300 }))
  assert.equal(parsed.kind, 'encode')
  assert.equal(parsed.id, 1)
  assert.deepEqual(parsed.target, { minBytes: 0, maxBytes: 200 * 1024 })
  assert.equal(parsed.allowShrink, true)
  assert.equal(parsed.dpi, 300)
})

test('cancel 请求只带 id', () => {
  const parsed = parseRequest({ id: 7, kind: 'cancel' })
  assert.equal(parsed.kind, 'cancel')
  assert.equal(parsed.id, 7)
})

test('请求编号必须是正整数', () => {
  assert.throws(() => parseRequest(request({ id: 0 })), /请求编号无效/)
  assert.throws(() => parseRequest(request({ id: 1.5 })), /请求编号无效/)
  assert.throws(() => parseRequest(request({ id: '1' })), /请求编号无效/)
})

test('未知类型与非对象消息一律拒绝', () => {
  assert.throws(() => parseRequest(request({ kind: 'plan' })), /未知的请求类型/)
  assert.throws(() => parseRequest(null), /请求格式无效/)
  assert.throws(() => parseRequest('encode'), /请求格式无效/)
})

test('图片数据必须是可转移的 ArrayBuffer', () => {
  assert.throws(() => parseRequest(request({ bytes: undefined })), /缺少图片数据/)
  assert.throws(() => parseRequest(request({ bytes: new Uint8Array(8) })), /缺少图片数据/)
  assert.throws(() => parseRequest(request({ bytes: new ArrayBuffer(0) })), /缺少图片数据/)
})

test('目标区间沿用内核规则：超 20MB、下限大于上限都拒绝', () => {
  assert.throws(() => parseRequest(request({ target: { minBytes: 0, maxBytes: 21 * 1024 * 1024 } })), /20MB/)
  assert.throws(() => parseRequest(request({ target: { minBytes: 900, maxBytes: 100 } })), /下限不能大于上限/)
  assert.throws(() => parseRequest(request({ target: { minBytes: -1, maxBytes: 100 } })), /非负整数/)
})

test('DPI 只接受 1～1200 的整数，null 表示不改', () => {
  assert.equal(parseRequest(request({ dpi: null })).dpi, null)
  assert.equal(parseRequest(request({ dpi: 1 })).dpi, 1)
  assert.equal(parseRequest(request({ dpi: 1200 })).dpi, 1200)
  assert.throws(() => parseRequest(request({ dpi: 0 })), /DPI/)
  assert.throws(() => parseRequest(request({ dpi: 1201 })), /DPI/)
  assert.throws(() => parseRequest(request({ dpi: 300.5 })), /DPI/)
})

test('缺少是否允许缩小尺寸时拒绝，不默认成某一种', () => {
  assert.throws(() => parseRequest(request({ allowShrink: undefined })), /允许缩小尺寸/)
  assert.throws(() => parseRequest(request({ allowShrink: 'true' })), /允许缩小尺寸/)
})

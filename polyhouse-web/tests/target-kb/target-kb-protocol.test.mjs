import assert from 'node:assert/strict'
import test from 'node:test'
import { typeScriptModuleUrl } from '../helpers/test-runtime.mjs'

const typesUrl = await typeScriptModuleUrl(
  new URL('../../src/core/targetKbTypes.ts', import.meta.url),
)
const compressUrl = await typeScriptModuleUrl(
  new URL('../../src/core/targetKbCompress.ts', import.meta.url),
  {
    "'./targetKbTypes'": `'${typesUrl}'`,
    '"./targetKbTypes"': `"${typesUrl}"`,
  },
)
const { isCurrentReply, parseRequest } = await import(
  await typeScriptModuleUrl(new URL('../../src/core/targetKbProtocol.ts', import.meta.url), {
    "'./targetKbTypes'": `'${typesUrl}'`,
    '"./targetKbTypes"': `"${typesUrl}"`,
    "'./targetKbCompress'": `'${compressUrl}'`,
    '"./targetKbCompress"': `"${compressUrl}"`,
  })
)

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
  assert.equal(isCurrentReply(3, 0), false)
  assert.equal(isCurrentReply(0, 0), false)
  assert.equal(isCurrentReply(1.5, 1.5), false)
})

test('parseRequest 放行合法 encode/cancel 请求并规整字段与 DPI 边界', () => {
  const parsed = parseRequest(request({ dpi: 300 }))
  assert.equal(parsed.kind, 'encode')
  assert.equal(parsed.id, 1)
  assert.deepEqual(parsed.target, { minBytes: 0, maxBytes: 200 * 1024 })
  assert.equal(parsed.allowShrink, true)
  assert.equal(parsed.dpi, 300)

  assert.equal(parseRequest(request({ dpi: null })).dpi, null)
  assert.equal(parseRequest(request({ dpi: 1 })).dpi, 1)
  assert.equal(parseRequest(request({ dpi: 1200 })).dpi, 1200)

  const cancelled = parseRequest({ id: 7, kind: 'cancel' })
  assert.equal(cancelled.kind, 'cancel')
  assert.equal(cancelled.id, 7)
})

test('parseRequest 拒绝各类非法请求消息与越界字段', async (t) => {
  const invalidCases = [
    ['id 为 0', request({ id: 0 }), /请求编号无效/],
    ['id 为小数', request({ id: 1.5 }), /请求编号无效/],
    ['id 为字符串', request({ id: '1' }), /请求编号无效/],
    ['未知请求类型', request({ kind: 'plan' }), /未知的请求类型/],
    ['消息为 null', null, /请求格式无效/],
    ['消息为字符串', 'encode', /请求格式无效/],
    ['缺少 bytes', request({ bytes: undefined }), /缺少图片数据/],
    ['bytes 为 Uint8Array 而非 ArrayBuffer', request({ bytes: new Uint8Array(8) }), /缺少图片数据/],
    ['bytes 为空 ArrayBuffer', request({ bytes: new ArrayBuffer(0) }), /缺少图片数据/],
    ['目标上限超 20 MiB', request({ target: { minBytes: 0, maxBytes: 21 * 1024 * 1024 } }), /20 MiB/],
    ['目标下限大于上限', request({ target: { minBytes: 900, maxBytes: 100 } }), /下限不能大于上限/],
    ['目标下限为负数', request({ target: { minBytes: -1, maxBytes: 100 } }), /非负整数/],
    ['DPI 为 0', request({ dpi: 0 }), /DPI/],
    ['DPI 超 1200', request({ dpi: 1201 }), /DPI/],
    ['DPI 为小数', request({ dpi: 300.5 }), /DPI/],
    ['缺少 allowShrink', request({ allowShrink: undefined }), /允许缩小尺寸/],
    ['allowShrink 非布尔', request({ allowShrink: 'true' }), /允许缩小尺寸/],
  ]

  for (const [label, payload, expectedError] of invalidCases) {
    await t.test(`拒绝${label}`, () => {
      assert.throws(() => parseRequest(payload), expectedError)
    })
  }
})

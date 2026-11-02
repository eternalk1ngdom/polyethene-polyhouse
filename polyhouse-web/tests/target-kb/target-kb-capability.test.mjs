import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

// 与现有测试一致：内存编译并执行真实模块，只替换模块地址，不替换被测逻辑。
function toModuleUrl(source) {
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  return `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`
}

async function loadCapability() {
  const source = await readFile(
    new URL('../../src/core/targetKbCapability.ts', import.meta.url),
    'utf8',
  )
  return import(toModuleUrl(source))
}

const { describeCapabilityGap } = await loadCapability()

const FULL = {
  worker: true,
  createImageBitmap: true,
  offscreenCanvas: true,
  convertToBlob: true,
}

test('四项能力齐全时返回 null，页面不显示警告', () => {
  assert.equal(describeCapabilityGap(FULL), null)
})

test('缺任一项能力都给出同一句可执行提示', () => {
  // 四项指向同一个结论（浏览器太旧），文案统一，避免用户去猜哪一项重要。
  for (const key of Object.keys(FULL)) {
    const gap = describeCapabilityGap({ ...FULL, [key]: false })
    assert.equal(typeof gap, 'string', `缺 ${key} 时应给出提示`)
    assert.match(gap, /Chrome/)
    assert.match(gap, /Safari 16\.4/)
  }
})

test('有 OffscreenCanvas 但没有 convertToBlob 时判为不支持', () => {
  // Safari 16.4 之前的真实形态：构造器存在，convertToBlob 不存在。
  // 只看构造器会误判成支持，然后在编码时抛 convertToBlob is not a function。
  const gap = describeCapabilityGap({ ...FULL, convertToBlob: false })
  assert.notEqual(gap, null)
})

test('多项同时缺失也只返回一条提示', () => {
  const gap = describeCapabilityGap({
    worker: false,
    createImageBitmap: false,
    offscreenCanvas: false,
    convertToBlob: false,
  })
  assert.equal(typeof gap, 'string')
  assert.equal(gap.split('。').filter(Boolean).length, 1)
})

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

async function loadSource() {
  const crop = await readFile(new URL('../src/core/avatarCrop.ts', import.meta.url), 'utf8')
  const source = await readFile(new URL('../src/core/targetKbSource.ts', import.meta.url), 'utf8')
  return import(toModuleUrl(source, { './avatarCrop': toModuleUrl(crop) }))
}

const {
  MAX_SOURCE_BYTES,
  MAX_SOURCE_PIXELS,
  inspectSource,
  checkDecodedPixels,
  fitWithinLongEdge,
} = await loadSource()

/** 构造头部可解析的最小 JPEG：只写 SOF0 尺寸，后面不接真实熵编码数据。 */
function buildJpeg(width, height) {
  return new Uint8Array([
    0xff,
    0xd8,
    0xff,
    0xc0,
    0x00,
    0x0b,
    0x08,
    (height >> 8) & 0xff,
    height & 0xff,
    (width >> 8) & 0xff,
    width & 0xff,
    0x01,
    0x01,
    0x11,
    0x00,
  ])
}

/** 构造 PNG 的签名与 IHDR，用来验证尺寸解析走的是另一条分支。 */
function buildPng(width, height) {
  const bytes = new Uint8Array(33)
  bytes.set([137, 80, 78, 71, 13, 10, 26, 10], 0)
  const view = new DataView(bytes.buffer)
  view.setUint32(8, 13)
  view.setUint32(12, 0x49484452)
  view.setUint32(16, width)
  view.setUint32(20, height)
  return bytes
}

/** 把最小 JPEG 撑到指定字节数，用于验证文件体积上限的边界。 */
function padTo(bytes, size) {
  const output = new Uint8Array(size)
  output.set(bytes, 0)
  return output
}

test('文件体积：正好 20 MiB 放行，多 1 字节拒绝', () => {
  const base = buildJpeg(800, 600)
  assert.equal(inspectSource(padTo(base, MAX_SOURCE_BYTES)).ok, true)

  const over = inspectSource(padTo(base, MAX_SOURCE_BYTES + 1))
  assert.equal(over.ok, false)
  assert.equal(over.reason, 'file-too-large')
  assert.match(over.message, /20MB/)
})

test('像素上限：正好 40 MP 放行，多一个像素拒绝', () => {
  const exact = inspectSource(buildJpeg(8192, 5120))
  assert.equal(exact.ok, true)
  assert.equal(exact.width, 8192)
  assert.equal(exact.height, 5120)

  const over = inspectSource(buildJpeg(8193, 5120))
  assert.equal(over.ok, false)
  assert.equal(over.reason, 'pixels-too-large')
  assert.match(over.message, /40MP/)
})

test('PNG 走 IHDR 分支，同样受像素上限约束', () => {
  assert.equal(inspectSource(buildPng(4000, 3000)).ok, true)
  assert.equal(inspectSource(buildPng(9000, 5000)).reason, 'pixels-too-large')
})

test('解析不出尺寸的格式不拒绝，交给解码后补检', () => {
  // WebP 头部：RIFF....WEBP，avatarSourceSize 只认 PNG/JPEG，因此返回 null。
  const webp = new Uint8Array(32)
  webp.set([0x52, 0x49, 0x46, 0x46], 0)
  webp.set([0x57, 0x45, 0x42, 0x50], 8)
  const result = inspectSource(webp)
  assert.equal(result.ok, true)
  assert.equal(result.width, null)
  assert.equal(result.height, null)
})

test('解码后补检只挡像素超限，非法尺寸一并拒绝', () => {
  assert.equal(checkDecodedPixels(8000, 5000).ok, true)
  assert.equal(checkDecodedPixels(8193, 5120).reason, 'pixels-too-large')
  assert.equal(checkDecodedPixels(0, 100).reason, 'pixels-too-large')
  assert.equal(checkDecodedPixels(100.5, 100).reason, 'pixels-too-large')
})

test('最长边缩放：不超限时原样返回，超限时等比且不小于 1 像素', () => {
  assert.deepEqual(fitWithinLongEdge(4000, 3000, null), { width: 4000, height: 3000 })
  assert.deepEqual(fitWithinLongEdge(4000, 3000, 4000), { width: 4000, height: 3000 })
  assert.deepEqual(fitWithinLongEdge(4000, 3000, 2000), { width: 2000, height: 1500 })
  assert.deepEqual(fitWithinLongEdge(6400, 4000, 5440), { width: 5440, height: 3400 })
  assert.deepEqual(fitWithinLongEdge(1, 10000, 1), { width: 1, height: 1 })
})

test('上限常量与计划 D5 一致', () => {
  assert.equal(MAX_SOURCE_BYTES, 20 * 1024 * 1024)
  assert.equal(MAX_SOURCE_PIXELS, 40 * 1024 * 1024)
})

import assert from 'node:assert/strict'
import test from 'node:test'
import { typeScriptModuleUrl } from '../helpers/test-runtime.mjs'

const avatarCropUrl = await typeScriptModuleUrl(
  new URL('../../src/core/avatarCrop.ts', import.meta.url),
)
const { inspectSource, inspectSourceSize, checkDecodedPixels, fitWithinLongEdge } = await import(
  await typeScriptModuleUrl(new URL('../../src/core/targetKbSource.ts', import.meta.url), {
    "'./avatarCrop'": `'${avatarCropUrl}'`,
    '"./avatarCrop"': `"${avatarCropUrl}"`,
  })
)

const MAX_BYTES = 20 * 1024 * 1024

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

function buildWebpHeader() {
  const webp = new Uint8Array(32)
  webp.set([0x52, 0x49, 0x46, 0x46], 0)
  webp.set([0x57, 0x45, 0x42, 0x50], 8)
  return webp
}

test('inspectSource 校验 20 MiB 体积上限、40MP 像素上限（十进制）与多格式分支', async (t) => {
  await t.test('文件体积正好 20 MiB 放行，多 1 字节拒绝', () => {
    const base = buildJpeg(800, 600)
    assert.equal(inspectSource(padTo(base, MAX_BYTES)).ok, true)
    const over = inspectSource(padTo(base, MAX_BYTES + 1))
    assert.equal(over.ok, false)
    assert.equal(over.reason, 'file-too-large')
    assert.match(over.message, /超过 20 MiB/)
  })

  await t.test('JPEG 像素正好 40MP（8000×5000）放行，多 1 像素拒绝', () => {
    const exact = inspectSource(buildJpeg(8000, 5000))
    assert.equal(exact.ok, true)
    assert.equal(exact.width, 8000)
    assert.equal(exact.height, 5000)
    const over = inspectSource(buildJpeg(8001, 5000))
    assert.equal(over.ok, false)
    assert.equal(over.reason, 'pixels-too-large')
    assert.match(over.message, /40MP/)
  })

  await t.test('PNG 走 IHDR 分支同样受 40MP 像素上限约束', () => {
    assert.equal(inspectSource(buildPng(4000, 3000)).ok, true)
    assert.equal(inspectSource(buildPng(9000, 5000)).reason, 'pixels-too-large')
  })

  await t.test('超限提示按 10^6 报百万像素（43.2MP 而非 41.2MP）', () => {
    const over = inspectSource(buildJpeg(8000, 5400))
    assert.equal(over.ok, false)
    assert.match(over.message, /8000×5400（43\.2MP）/)
  })

  await t.test('解析不出尺寸的 WebP 不拒绝，留给解码后补检', () => {
    const result = inspectSource(buildWebpHeader())
    assert.equal(result.ok, true)
    assert.equal(result.width, null)
    assert.equal(result.height, null)
  })
})

test('解码后补检只挡像素超限，非法尺寸一并拒绝', () => {
  assert.equal(checkDecodedPixels(8000, 5000).ok, true)
  assert.equal(checkDecodedPixels(8001, 5000).reason, 'pixels-too-large')
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

test('只校验体积时不必读整张图，也不判断像素', () => {
  assert.equal(inspectSourceSize(1024).ok, true)
  assert.equal(inspectSourceSize(MAX_BYTES).ok, true)
  const over = inspectSourceSize(MAX_BYTES + 1)
  assert.equal(over.ok, false)
  assert.equal(over.reason, 'file-too-large')
  assert.equal(inspectSourceSize(1).width, null)
})

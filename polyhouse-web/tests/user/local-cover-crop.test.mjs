import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

// 直接验证纯裁剪算法，浏览器的解码、绘制和保存由页面联调覆盖。
async function moduleUrl(name, dependencies = {}) {
  const source = await readFile(new URL(`../../src/core/${name}.ts`, import.meta.url), 'utf8')
  let output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [key, url] of Object.entries(dependencies))
    output = output.replace(`from '${key}'`, `from '${url}'`)
  return `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`
}

const { coverBounds, coverFrames, inspectLocalCover } = await import(
  await moduleUrl('localCoverCrop', { './avatarCrop': await moduleUrl('avatarCrop') })
)

test('同一原图的桌面和手机可独立取景且裁剪范围覆盖整张画幅', () => {
  const desktop = coverBounds(1200, 1600, 'desktop', 1)
  const mobile = coverBounds(1200, 1600, 'mobile', 1)
  assert.equal(desktop.x, 0)
  assert.equal(mobile.x, 0)
  assert.ok(desktop.y > mobile.y)
  for (const [key, size] of Object.entries(coverFrames)) {
    const zoomed = coverBounds(1900, 1200, key, 3)
    assert.ok(zoomed.x >= 0 && zoomed.y >= 0)
    assert.equal(zoomed.x + size.width, 1900 * zoomed.scale)
    assert.equal(zoomed.y + size.height, 1200 * zoomed.scale)
  }
})

// 头部校验无需像素数据。这里构造尺寸和动画标记，避免压入几千万像素的测试素材。
function pngHeader(width, height, animated = false) {
  const bytes = Buffer.alloc(53)
  bytes.set([137, 80, 78, 71, 13, 10, 26, 10])
  bytes.writeUInt32BE(13, 8)
  bytes.write('IHDR', 12)
  bytes.writeUInt32BE(width, 16)
  bytes.writeUInt32BE(height, 20)
  bytes.writeUInt32BE(8, 33)
  bytes.write(animated ? 'acTL' : 'tEXt', 37)
  return bytes
}

test('解码前拒绝过大的像素面积、动画及伪装图片', () => {
  assert.equal(inspectLocalCover(pngHeader(4000, 4000)), 'image/png')
  assert.throws(() => inspectLocalCover(pngHeader(4001, 4000)), /像素过大/)
  assert.throws(() => inspectLocalCover(pngHeader(120, 480, true)), /动画/)
  assert.throws(() => inspectLocalCover(Buffer.from('not an image')), /静态/)
})

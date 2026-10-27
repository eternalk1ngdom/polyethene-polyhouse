import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(new URL('../src/core/avatarCrop.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const { initialCrop, cropScale, clampCrop, zoomCrop, avatarSourceSize } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
)

test('横图纵图居中覆盖且任意拖动不露出空边', () => {
  for (const [width, height] of [
    [1600, 900],
    [600, 1200],
    [32, 32],
  ]) {
    const crop = initialCrop(width, height)
    const scale = cropScale(crop)
    assert.ok(width * scale >= 256 && height * scale >= 256)
    assert.equal(crop.x, (256 - width * scale) / 2)
    for (const position of [-10000, 10000]) {
      const moved = clampCrop({ ...crop, x: position, y: position })
      assert.ok(moved.x <= 0 && moved.y <= 0)
      assert.ok(moved.x + width * scale >= 256 && moved.y + height * scale >= 256)
    }
  }
})

test('缩放保持视口中心并限制在一至三倍', () => {
  const crop = initialCrop(800, 600)
  const enlarged = zoomCrop(crop, 2)
  assert.equal((128 - enlarged.x) / cropScale(enlarged), (128 - crop.x) / cropScale(crop))
  assert.equal(zoomCrop(crop, 100).zoom, 3)
  assert.equal(zoomCrop(crop, 0).zoom, 1)
})

test('在分配像素前读取 PNG/JPEG 尺寸并拒绝截断或伪造格式', () => {
  const png = new Uint8Array(24)
  png.set([137, 80, 78, 71, 13, 10, 26, 10])
  const view = new DataView(png.buffer)
  view.setUint32(8, 13)
  view.setUint32(12, 0x49484452)
  view.setUint32(16, 65535)
  view.setUint32(20, 256)
  assert.deepEqual(avatarSourceSize(png), { width: 65535, height: 256 })
  assert.deepEqual(
    avatarSourceSize(Uint8Array.from([255, 216, 255, 192, 0, 8, 8, 1, 0, 2, 0, 1])),
    { width: 512, height: 256 },
  )
  assert.equal(avatarSourceSize(png.slice(0, 10)), null)
  assert.equal(avatarSourceSize(new Uint8Array([255, 216, 255, 192, 255, 255])), null)
  assert.equal(avatarSourceSize(new TextEncoder().encode('<svg/>')), null)
})

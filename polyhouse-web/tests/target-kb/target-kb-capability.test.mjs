import assert from 'node:assert/strict'
import test from 'node:test'
import { typeScriptModuleUrl } from '../helpers/test-runtime.mjs'

const { describeCapabilityGap } = await import(
  await typeScriptModuleUrl(new URL('../../src/core/targetKbCapability.ts', import.meta.url))
)

const FULL = {
  worker: true,
  createImageBitmap: true,
  offscreenCanvas: true,
  convertToBlob: true,
}

test('能力检测：全齐全返回 null，缺任一项或全缺均返回单条可执行提示', async (t) => {
  await t.test('四项能力齐全时返回 null', () => {
    assert.equal(describeCapabilityGap(FULL), null)
  })

  // 逐项覆盖缺失（含 Safari 16.4 之前有 OffscreenCanvas 但缺 convertToBlob 的形态）。
  for (const key of Object.keys(FULL)) {
    await t.test(`缺失 ${key} 时给出统一提示`, () => {
      const gap = describeCapabilityGap({ ...FULL, [key]: false })
      assert.equal(typeof gap, 'string')
      assert.match(gap, /Chrome/)
      assert.match(gap, /Safari 16\.4/)
    })
  }

  await t.test('多项同时缺失也只返回一条提示', () => {
    const gap = describeCapabilityGap({
      worker: false,
      createImageBitmap: false,
      offscreenCanvas: false,
      convertToBlob: false,
    })
    assert.equal(typeof gap, 'string')
    assert.equal(gap.split('。').filter(Boolean).length, 1)
  })
})

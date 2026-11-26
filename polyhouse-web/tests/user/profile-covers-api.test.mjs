import assert from 'node:assert/strict'
import test from 'node:test'
import { dataUrl, patchGlobal, typeScriptModuleUrl } from '../helpers/test-runtime.mjs'

function makeCoverItem(key, patch = {}) {
  return {
    key,
    reason: key === 'window' ? 'default' : 'coins',
    price: key === 'window' ? 0 : 10,
    missingExperience: 0,
    ...patch,
  }
}

function makeCatalog(covers, equipped = 'window') {
  return {
    userId: '10001',
    coins: '20',
    equipped,
    quoteVersion: '1',
    covers,
  }
}

test('profileCovers 解码器不锁死固定数量 3，同时严格校验非空、去重与当前佩戴项归属', async (t) => {
  const mockIndexUrl = dataUrl(`
    export class ApiError extends Error {
      constructor(message, status) { super(message); this.status = status }
    }
    export function isRecord(value) {
      return typeof value === 'object' && value !== null && !Array.isArray(value)
    }
    export async function apiRequest(_config, decode) {
      return decode(globalThis.__polyhouseCoverCatalogPayload)
    }
  `)
  const coversApiUrl = await typeScriptModuleUrl(
    new URL('../../src/api/profileCovers.ts', import.meta.url),
    { "from './index'": `from '${mockIndexUrl}'` },
  )
  const { readCovers } = await import(coversApiUrl)
  patchGlobal(t, '__polyhouseCoverCatalogPayload', null)

  for (const [label, payload, expectedCount] of [
    ['仅返回 1 款官方卡面', makeCatalog([makeCoverItem('window')], 'window'), 1],
    [
      '返回 2 款官方卡面',
      makeCatalog([makeCoverItem('window'), makeCoverItem('astrolabe')], 'astrolabe'),
      2,
    ],
    [
      '返回完整 3 款官方卡面',
      makeCatalog(
        [makeCoverItem('window'), makeCoverItem('astrolabe'), makeCoverItem('koi')],
        'koi',
      ),
      3,
    ],
  ]) {
    await t.test(`接受${label}`, async () => {
      globalThis.__polyhouseCoverCatalogPayload = payload
      const catalog = await readCovers('token')
      assert.equal(catalog.covers.length, expectedCount)
      assert.equal(catalog.equipped, payload.equipped)
    })
  }

  for (const [label, payload] of [
    ['空卡面列表', makeCatalog([], 'window')],
    [
      '包含重复卡面 key',
      makeCatalog([makeCoverItem('window'), makeCoverItem('window')], 'window'),
    ],
    [
      'equipped 未出现在目录列表中',
      makeCatalog([makeCoverItem('window'), makeCoverItem('astrolabe')], 'koi'),
    ],
    [
      '非法解锁原因',
      makeCatalog([makeCoverItem('window', { reason: 'unknown_reason' })], 'window'),
    ],
  ]) {
    await t.test(`拒绝${label}`, async () => {
      globalThis.__polyhouseCoverCatalogPayload = payload
      await assert.rejects(() => readCovers('token'), (err) => {
        assert.equal(err.status, 502)
        return true
      })
    })
  }
})

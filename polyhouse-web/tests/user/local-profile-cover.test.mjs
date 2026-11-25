import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const codeUrl = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`

const source = await readFile(
  new URL('../../src/utils/localProfileCover.ts', import.meta.url),
  'utf8',
)
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const { deleteLocalCover, enableLocalCover, readLocalCover, saveLocalCover } = await import(
  codeUrl(compiled)
)

function createMockIndexedDb({ abortWrite = false } = {}) {
  const rows = new Map()
  return {
    rows,
    open() {
      const request = {}
      queueMicrotask(() => {
        request.result = {
          close() {},
          transaction(_storeName, mode) {
            const tx = { error: null }
            let settled = false
            const finish = () => {
              if (settled) return
              settled = true
              if (mode === 'readwrite' && abortWrite) {
                tx.error = new Error('quota exceeded')
                tx.onabort?.()
              } else {
                tx.oncomplete?.()
              }
            }
            const store = {
              get(key) {
                const req = { result: rows.get(key) }
                queueMicrotask(() => {
                  req.onsuccess?.()
                  queueMicrotask(finish)
                })
                return req
              },
              put(value, key) {
                if (!abortWrite) rows.set(key, value)
                queueMicrotask(finish)
              },
              delete(key) {
                if (!abortWrite) rows.delete(key)
                queueMicrotask(finish)
              },
            }
            return Object.assign(tx, { objectStore: () => store })
          },
        }
        request.onsuccess?.()
      })
      return request
    },
  }
}

function sampleCover(enabled = true) {
  return {
    desktop: new Blob(['desktop-webp'], { type: 'image/webp' }),
    mobile: new Blob(['mobile-webp'], { type: 'image/webp' }),
    enabled,
  }
}

test('IndexedDB 写入提交后，跨标签 BroadcastChannel 抛错不误报保存、切换或删除失败', async (t) => {
  const prevIdb = globalThis.indexedDB
  const prevChannel = globalThis.BroadcastChannel
  const mockDb = createMockIndexedDb()
  let closedCount = 0

  globalThis.indexedDB = mockDb
  globalThis.BroadcastChannel = class BrokenChannel {
    postMessage() {
      throw new Error('broadcast blocked')
    }
    close() {
      closedCount += 1
    }
  }
  t.after(() => {
    globalThis.indexedDB = prevIdb
    globalThis.BroadcastChannel = prevChannel
  })

  await saveLocalCover('u-1', sampleCover(true))
  const saved = await readLocalCover('u-1')
  assert.equal(saved?.enabled, true)

  await enableLocalCover('u-1', false)
  const toggled = await readLocalCover('u-1')
  assert.equal(toggled?.enabled, false)

  await deleteLocalCover('u-1')
  const removed = await readLocalCover('u-1')
  assert.equal(removed, null)
  assert.equal(closedCount, 3)
})

test('BroadcastChannel 构造失败也不影响已提交写入，而真实 IndexedDB 写事务中断仍报错', async (t) => {
  const prevIdb = globalThis.indexedDB
  const prevChannel = globalThis.BroadcastChannel
  t.after(() => {
    globalThis.indexedDB = prevIdb
    globalThis.BroadcastChannel = prevChannel
  })

  globalThis.indexedDB = createMockIndexedDb()
  globalThis.BroadcastChannel = class UncreatableChannel {
    constructor() {
      throw new Error('security policy')
    }
  }
  await saveLocalCover('u-2', sampleCover(true))
  assert.equal((await readLocalCover('u-2'))?.enabled, true)

  globalThis.indexedDB = createMockIndexedDb({ abortWrite: true })
  await assert.rejects(() => saveLocalCover('u-2', sampleCover(true)), /quota exceeded/)
})

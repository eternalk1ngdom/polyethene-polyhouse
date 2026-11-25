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

function createMockIndexedDb({ abortWrite = false, blocked = false } = {}) {
  const rows = new Map()
  return {
    rows,
    open() {
      const request = {}
      queueMicrotask(() => {
        if (blocked) {
          request.onblocked?.()
          return
        }
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

function patchGlobal(t, key, value) {
  const hadOwn = Object.prototype.hasOwnProperty.call(globalThis, key)
  const previous = globalThis[key]
  globalThis[key] = value
  t.after(() => {
    if (hadOwn) globalThis[key] = previous
    else delete globalThis[key]
  })
}

function sampleCover(enabled = true) {
  return {
    desktop: new Blob(['desktop-webp'], { type: 'image/webp' }),
    mobile: new Blob(['mobile-webp'], { type: 'image/webp' }),
    enabled,
  }
}

test('IndexedDB 写入提交后，跨标签 BroadcastChannel 故障或缺失均不误报保存、切换或删除失败', async (t) => {
  const cases = [
    {
      name: 'postMessage 抛错仍正常提交并关闭通道',
      makeChannel: (counter) =>
        class BrokenPostChannel {
          postMessage() {
            throw new Error('broadcast blocked')
          }
          close() {
            counter.closed += 1
          }
        },
      expectedClosed: 3,
    },
    {
      name: 'BroadcastChannel 构造失败不影响已提交写入',
      makeChannel: () =>
        class UncreatableChannel {
          constructor() {
            throw new Error('security policy')
          }
        },
      expectedClosed: 0,
    },
    {
      name: '环境无 BroadcastChannel 时静默跳过广播',
      makeChannel: () => undefined,
      expectedClosed: 0,
    },
  ]

  for (const item of cases) {
    await t.test(item.name, async (sub) => {
      const counter = { closed: 0 }
      patchGlobal(sub, 'indexedDB', createMockIndexedDb())
      patchGlobal(sub, 'BroadcastChannel', item.makeChannel(counter))

      await saveLocalCover('u-1', sampleCover(true))
      assert.equal((await readLocalCover('u-1'))?.enabled, true)

      await enableLocalCover('u-1', false)
      assert.equal((await readLocalCover('u-1'))?.enabled, false)

      await deleteLocalCover('u-1')
      assert.equal(await readLocalCover('u-1'), null)
      assert.equal(counter.closed, item.expectedClosed)
    })
  }
})

test('本地卡面异常与数据完整性契约：写事务中断、库阻塞、损坏记录与防复活已删记录', async (t) => {
  await t.test('真实 IndexedDB 写事务中断必须向上报错', async (sub) => {
    patchGlobal(sub, 'indexedDB', createMockIndexedDb({ abortWrite: true }))
    patchGlobal(sub, 'BroadcastChannel', undefined)
    await assert.rejects(() => saveLocalCover('u-2', sampleCover(true)), /quota exceeded/)
  })

  await t.test('数据库升级被其他标签阻塞时给出可执行提示', async (sub) => {
    patchGlobal(sub, 'indexedDB', createMockIndexedDb({ blocked: true }))
    patchGlobal(sub, 'BroadcastChannel', undefined)
    await assert.rejects(() => readLocalCover('u-2'), /请关闭此站点的其他标签页/)
  })

  await t.test('损坏或非 WebP 记录读取返回 null，且 enableLocalCover 不会复活已删记录', async (sub) => {
    const mockDb = createMockIndexedDb()
    patchGlobal(sub, 'indexedDB', mockDb)
    patchGlobal(sub, 'BroadcastChannel', undefined)

    // 写入损坏的非 WebP 记录，读取应安全降级为 null。
    mockDb.rows.set('u-corrupt', {
      desktop: new Blob(['not-webp'], { type: 'image/png' }),
      mobile: new Blob(['mobile-webp'], { type: 'image/webp' }),
      enabled: true,
    })
    assert.equal(await readLocalCover('u-corrupt'), null)

    // 另一标签页已删除记录后，再调用开关切换不得复活该记录。
    await enableLocalCover('u-deleted', true)
    assert.equal(mockDb.rows.has('u-deleted'), false)
    assert.equal(await readLocalCover('u-deleted'), null)
  })
})

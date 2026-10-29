import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, nextTick, reactive } from 'vue'
import ts from 'typescript'

const url = (source) => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
async function compile(path, replacements) {
  let output = ts.transpileModule(await readFile(new URL(path, import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(replacements)) output = output.replaceAll(`from '${from}'`, `from '${to}'`)
  return url(output)
}
const apiUrl = await compile('../src/api/index.ts', { axios: import.meta.resolve('axios') })
const { ApiError } = await import(apiUrl)
const realApiUrl = await compile('../src/api/accountProvisioning.ts', { './index': apiUrl })
const { decodeAccountCreation } = await import(realApiUrl)
const userUrl = url('let store; export const useUserStore=()=>store; export const install=value=>{store=value}')
const provisioningUrl = url('let create, confirm; export const install=(a,b)=>{create=a; confirm=b}; export const createAccount=(...args)=>create(...args); export const confirmAccountCreation=(...args)=>confirm(...args)')
const { install: installStore } = await import(userUrl)
const { install: installApi } = await import(provisioningUrl)
const { useAccountProvisioning } = await import(await compile('../src/composables/useAccountProvisioning.ts', {
  vue: import.meta.resolve('vue'), '../api': apiUrl, '../api/accountProvisioning': provisioningUrl, '../stores/useUserStore': userUrl,
}))
const renderer = createRenderer({
  createElement: () => ({}), createText: () => ({}), createComment: () => ({}), insert() {}, remove() {},
  setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null,
})
const credential = { id: '42', username: 'ph000001', nickname: '小屋住户000001', role: 'USER', firstDelivery: true, initialPassword: 'AbCdEfGhJkMnPqRs2345' }
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function fixture(create, confirm, storage = new Map()) {
  globalThis.sessionStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  const store = reactive({ isLoggedIn: true, token: 'fixture-token', user: { id: '100', role: 'OWNER' }, requiresPasswordChange: false })
  installStore(store); installApi(create, confirm)
  let controller
  const app = renderer.createApp({ setup() { controller = useAccountProvisioning(); return () => h('div') } })
  app.mount({})
  return { controller, store, storage, unmount: () => app.unmount() }
}

test('重复点击只发一次请求，密码只驻留内存，确认后清除', async (t) => {
  const pending = deferred(); let calls = 0
  const f = fixture(async (request, token) => { calls++; assert.equal(token, 'fixture-token'); assert.match(request.requestId, /^[0-9a-f-]{36}$/); return pending.promise })
  t.after(f.unmount)
  const a = f.controller.create(), b = f.controller.create()
  assert.equal(calls, 1)
  pending.resolve(credential); await Promise.all([a, b])
  assert.equal(f.controller.result.value.initialPassword, credential.initialPassword)
  assert.equal([...f.storage.values()].some(value => value.includes(credential.initialPassword)), false)
  f.controller.acknowledge()
  assert.equal(f.controller.result.value, null); assert.equal(f.storage.size, 0)
})

test('网络结果未知和刷新继续复用原意图，核对不重新交付密码', async (t) => {
  let firstId
  const f = fixture(async (request) => { firstId = request.requestId; throw new ApiError('offline', 0) })
  await f.controller.create()
  assert.equal(f.controller.attempted.value, true)
  const storage = f.storage; f.unmount()
  const reopened = fixture(async (request) => { assert.equal(request.requestId, firstId); return { ...credential, firstDelivery: false, initialPassword: null } },
    async (requestId) => { assert.equal(requestId, firstId); return { ...credential, firstDelivery: false, initialPassword: null } }, storage)
  t.after(reopened.unmount)
  assert.equal(reopened.controller.attempted.value, true)
  await reopened.controller.confirm()
  assert.equal(reopened.controller.result.value.initialPassword, null)
})

test('换号后旧成功不能交付密码，也不能解除新请求的忙碌状态', async (t) => {
  const old = deferred(), current = deferred(); let calls = 0
  const f = fixture(() => ++calls === 1 ? old.promise : current.promise)
  t.after(f.unmount)
  const before = f.controller.create()
  f.store.token = 'next-token'; f.store.user = { id: '200', role: 'OWNER' }; await nextTick()
  const after = f.controller.create()
  old.resolve(credential); await before
  assert.equal(f.controller.result.value, null); assert.equal(f.controller.busy.value, true)
  current.resolve({ ...credential, id: '43', username: 'ph000002' }); await after
  assert.equal(f.controller.result.value.id, '43')
})

test('卸载和退出清除明文，旧响应不会恢复凭据', async () => {
  const pending = deferred(); const f = fixture(() => pending.promise)
  const operation = f.controller.create(); f.unmount(); pending.resolve(credential); await operation
  assert.equal(f.controller.result.value, null)
  const next = fixture(async () => credential)
  await next.controller.create(); assert.ok(next.controller.result.value)
  next.store.isLoggedIn = false; await nextTick()
  assert.equal(next.controller.result.value, null); next.unmount()
})

test('普通用户、管理员、INSIDER 和首次屋主均不发起开户', async (t) => {
  let calls = 0; const f = fixture(async () => { calls++; return credential }); t.after(f.unmount)
  for (const role of ['USER', 'ADMIN', 'INSIDER']) {
    f.store.user.role = role; await nextTick(); await f.controller.create()
  }
  f.store.user.role = 'OWNER'; f.store.requiresPasswordChange = true; await nextTick(); await f.controller.create()
  assert.equal(calls, 0)
})

test('管理员必须再次验密，400 后允许修正意图且不保存密码', async (t) => {
  let calls = 0
  const f = fixture(async (request) => { calls++; assert.equal(request.ownerPassword, 'owner-fixture'); throw new ApiError('invalid confirmation', 400) })
  t.after(f.unmount); f.controller.role.value = 'ADMIN'
  await f.controller.create(); assert.equal(calls, 0)
  f.controller.ownerPassword.value = 'owner-fixture'; await f.controller.create()
  assert.equal(calls, 1); assert.equal(f.controller.ownerPassword.value, '')
  assert.equal(f.controller.attempted.value, false); assert.equal(f.storage.size, 0)
})

test('确认暂未找到不换请求号，显式重试沿用同一编号', async (t) => {
  const ids = []
  const f = fixture(async (request) => { ids.push(request.requestId); throw new ApiError('unavailable', 503) }, async () => { throw new ApiError('not found', 404) })
  t.after(f.unmount); await f.controller.create(); await f.controller.confirm(); await f.controller.create()
  assert.equal(ids.length, 2); assert.equal(ids[0], ids[1]); assert.equal(f.controller.attempted.value, true)
})

test('响应解码拒绝重放携带密码、首次缺少密码和非法账号角色', () => {
  assert.deepEqual(decodeAccountCreation(credential), credential)
  for (const invalid of [{ ...credential, firstDelivery: false }, { ...credential, initialPassword: null }, { ...credential, role: 'OWNER' }, { ...credential, id: 42 }]) {
    assert.throws(() => decodeAccountCreation(invalid), ApiError)
  }
  assert.equal(decodeAccountCreation({ ...credential, firstDelivery: false, initialPassword: null }).initialPassword, null)
})

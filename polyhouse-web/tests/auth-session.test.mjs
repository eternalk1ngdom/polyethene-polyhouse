import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test, { beforeEach } from 'node:test'
import axios, { AxiosError } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import ts from 'typescript'

// 复用现有 TypeScript 依赖在内存编译真实模块；仅替换导入地址，不需要新增测试框架或构建配置。
async function moduleUrl(path, replacements = {}) {
  const source = await readFile(new URL(path, import.meta.url), 'utf8')
  let output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const dependency of ['axios', 'vue', 'pinia']) {
    output = output.replaceAll(`from '${dependency}'`, `from '${import.meta.resolve(dependency)}'`)
  }
  for (const [dependency, url] of Object.entries(replacements)) {
    output = output.replaceAll(`from '${dependency}'`, `from '${url}'`)
  }
  return `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`
}

const authUrl = await moduleUrl('../src/api/auth.ts')
const storeUrl = await moduleUrl('../src/stores/useUserStore.ts', { '../api/auth': authUrl })
const { useUserStore } = await import(storeUrl)
const { AuthCommandError } = await import(authUrl)
const storage = new Map()
const profile = { id: '42', username: 'fixture', nickname: '测试', avatar: null, role: 'USER' }

function success(config) {
  return { config, data: { code: 0, message: '操作成功', data: null }, status: 200, statusText: 'OK', headers: {} }
}

beforeEach(() => {
  setActivePinia(createPinia())
  storage.clear()
  globalThis.localStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: (key) => storage.delete(key),
  }
  axios.defaults.adapter = async (config) => success(config)
})

test('退出和账号注销分别调用对应接口，成功后清除内存与两个缓存键', async () => {
  for (const command of ['logout', 'deregister']) {
    const store = useUserStore()
    store.setSession('fixture-credential', profile)
    axios.defaults.adapter = async (config) => {
      assert.equal(config.url, `/api/auth/${command}`)
      assert.equal(config.method, 'post')
      assert.deepEqual(JSON.parse(config.data), { token: 'fixture-credential' })
      return success(config)
    }
    await store[command]()
    assert.equal(store.token, '')
    assert.equal(store.user, null)
    assert.equal(storage.size, 0)
  }
})

test('网络失败保留账号和会话，不把账号注销显示为成功', async () => {
  const store = useUserStore()
  store.setSession('fixture-credential', profile)
  axios.defaults.adapter = async () => { throw new Error('private-request-details') }
  await assert.rejects(store.deregister(), (error) => {
    assert.ok(error instanceof AuthCommandError)
    assert.equal(error.sessionExpired, false)
    assert.ok(!error.message.includes('private-request-details'))
    return true
  })
  assert.equal(store.token, 'fixture-credential')
  assert.equal(storage.size, 2)
})

test('401 清除失效会话，同时保留失败结果避免冒充注销完成', async () => {
  const store = useUserStore()
  store.setSession('expired-credential', profile)
  axios.defaults.adapter = async (config) => {
    throw new AxiosError('fixture', 'ERR_BAD_REQUEST', config, null,
      { ...success(config), status: 401 })
  }
  await assert.rejects(store.deregister(), (error) => error instanceof AuthCommandError && error.sessionExpired)
  assert.equal(store.token, '')
  assert.equal(storage.size, 0)
})

test('旧退出请求完成不能清除等待期间建立的新会话', async () => {
  const store = useUserStore()
  store.setSession('old-credential', profile)
  let resolveRequest
  axios.defaults.adapter = (config) => new Promise((resolve) => {
    resolveRequest = () => resolve(success(config))
  })
  const pending = store.logout()
  store.setSession('new-credential', profile)
  resolveRequest()
  await pending
  assert.equal(store.token, 'new-credential')
  assert.equal(storage.get('polyhouse_token'), 'new-credential')
})

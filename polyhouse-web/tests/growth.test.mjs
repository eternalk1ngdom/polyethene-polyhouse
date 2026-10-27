import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test, { afterEach, beforeEach } from 'node:test'
import { AxiosError } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import ts from 'typescript'

// 沿用项目已有的内存编译方式，只替换导入地址，测试执行真实请求和状态模块。
async function moduleUrl(path, replacements = {}) {
  const source = await readFile(new URL(path, import.meta.url), 'utf8')
  let output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const dependency of ['axios', 'vue', 'pinia'])
    output = output.replaceAll(`from '${dependency}'`, `from '${import.meta.resolve(dependency)}'`)
  for (const [dependency, url] of Object.entries(replacements))
    output = output.replaceAll(`from '${dependency}'`, `from '${url}'`)
  return `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`
}

const apiUrl = await moduleUrl('../src/api/index.ts')
const authUrl = await moduleUrl('../src/api/auth.ts', { './index': apiUrl })
const userUrl = await moduleUrl('../src/stores/useUserStore.ts', {
  '../api/auth': authUrl,
  '../api/index': apiUrl,
})
const growthApiUrl = await moduleUrl('../src/api/growth.ts', { './index': apiUrl })
const growthStoreUrl = await moduleUrl('../src/stores/useUserGrowthStore.ts', {
  '../api': apiUrl,
  '../api/growth': growthApiUrl,
  './useUserStore': userUrl,
})
const { apiClient, ApiError, setUnauthorizedHandler } = await import(apiUrl)
const { useUserStore } = await import(userUrl)
const { decodeGrowthSummary } = await import(growthApiUrl)
const { useUserGrowthStore } = await import(growthStoreUrl)
const storage = new Map()
const ownedStores = []
const profile = {
  id: '42',
  username: 'fixture',
  nickname: '成长验收',
  avatar: null,
  role: 'USER',
  experience: '10',
  level: 1,
  coins: '1',
}
const summary = {
  userId: '42',
  experience: '10',
  coins: '1',
  level: 1,
  maxLevel: 6,
  maxLevelReached: false,
  levelStartExperience: '0',
  nextLevelExperience: '30',
  experienceToNextLevel: '20',
  progressPercent: 33,
  rewardDate: '2026-10-27',
  rewardTimeZone: 'Asia/Shanghai',
  dailyLoginRewardClaimed: true,
  dailyLoginExperience: 10,
  dailyLoginCoins: 1,
  observedAt: '2026-10-27T15:59:59Z',
  nextRewardAt: '2026-10-27T16:00:00Z',
  ruleVersion: 'LOGIN_365_V1',
}

function response(config, data = summary) {
  return {
    config,
    status: 200,
    statusText: 'OK',
    headers: {},
    data: { code: 0, message: '操作成功', data },
  }
}

function growthStore() {
  const store = useUserGrowthStore()
  ownedStores.push(store)
  return store
}

beforeEach(() => {
  setActivePinia(createPinia())
  storage.clear()
  globalThis.localStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: (key) => storage.delete(key),
  }
  apiClient.defaults.adapter = async (config) => response(config)
  setUnauthorizedHandler((token) => useUserStore().expireSession(token))
})

afterEach(() => {
  for (const store of ownedStores.splice(0)) store.$dispose()
  delete globalThis.document
})

test('未确认身份不请求成长数据，也不读取本地伪造的奖励状态', async () => {
  storage.set('polyhouse_growth', JSON.stringify({ dailyLoginRewardClaimed: true }))
  let calls = 0
  apiClient.defaults.adapter = async (config) => {
    calls += 1
    return response(config)
  }
  const store = growthStore()
  await store.refresh()
  assert.equal(store.summary, null)
  assert.equal(calls, 0)
})

test('成长 GET 只发送 Bearer，重复刷新不持久化到账标志', async () => {
  const user = useUserStore()
  user.setSession('fixture-credential', profile)
  let calls = 0
  apiClient.defaults.adapter = async (config) => {
    calls += 1
    assert.equal(config.method, 'get')
    assert.equal(config.url, '/auth/growth')
    assert.equal(config.headers.get('Authorization'), 'Bearer fixture-credential')
    assert.equal(config.data, undefined)
    assert.equal(config.params, undefined)
    return response(config)
  }
  const store = growthStore()
  await store.refresh()
  await store.refresh()
  assert.equal(calls, 2)
  assert.equal(store.summary.dailyLoginRewardClaimed, true)
  assert.equal(store.summary.progressPercent, 33)
  assert.equal(storage.size, 2)
  assert.ok(!storage.get('polyhouse_user').includes('dailyLoginRewardClaimed'))
})

test('并发成长查询复用一次请求', async () => {
  useUserStore().setSession('fixture-credential', profile)
  let finish
  let calls = 0
  apiClient.defaults.adapter = (config) => {
    calls += 1
    return new Promise((resolve) => {
      finish = () => resolve(response(config))
    })
  }
  const store = growthStore()
  const first = store.refresh()
  const second = store.refresh()
  assert.equal(calls, 1)
  finish()
  await Promise.all([first, second])
  assert.equal(store.summary.experience, '10')
})

test('满级及超大余额精度不丢失，满级没有下一级门槛', () => {
  const value = decodeGrowthSummary({
    ...summary,
    experience: '9223372036854775807',
    coins: '9223372036854775807',
    level: 6,
    maxLevelReached: true,
    levelStartExperience: '3650',
    nextLevelExperience: null,
    experienceToNextLevel: '0',
    progressPercent: 100,
  })
  assert.equal(value.experience, '9223372036854775807')
  assert.equal(value.nextLevelExperience, null)
  assert.equal(value.progressPercent, 100)
})

test('门槛由服务端定义，客户端不内置另一份升级曲线', () => {
  const changed = decodeGrowthSummary({
    ...summary,
    nextLevelExperience: '50',
    experienceToNextLevel: '40',
    progressPercent: 20,
    ruleVersion: 'future-rule',
  })
  assert.equal(changed.nextLevelExperience, '50')
  assert.equal(changed.progressPercent, 20)
  assert.equal(
    decodeGrowthSummary({ ...summary, dailyLoginRewardClaimed: false }).dailyLoginRewardClaimed,
    false,
  )
})

test('拒绝不一致的门槛、进度、大整数类型、时间和到账标志，不从缺失数据猜测已领取', () => {
  for (const patch of [
    { experience: 10 },
    { coins: -1 },
    { progressPercent: 100 },
    { experienceToNextLevel: '19' },
    { maxLevelReached: true },
    { nextLevelExperience: null },
    { dailyLoginRewardClaimed: 'true' },
    { dailyLoginRewardClaimed: undefined },
    { observedAt: 'invalid' },
    { nextRewardAt: summary.observedAt },
    { rewardTimeZone: 'not-a-zone' },
    { ruleVersion: '' },
  ]) {
    assert.throws(() => decodeGrowthSummary({ ...summary, ...patch }), ApiError)
  }
  const value = decodeGrowthSummary({ ...summary, passwordHash: 'private-detail', role: 'OWNER' })
  assert.equal(value.passwordHash, undefined)
  assert.equal(value.role, undefined)
})

test('成长查询网络失败不取消已确认登录，支持重试且不传播原始异常', async () => {
  const user = useUserStore()
  user.setSession('fixture-credential', profile)
  apiClient.defaults.adapter = async () => {
    throw new Error('private-network-details')
  }
  const store = growthStore()
  await store.refresh()
  assert.equal(user.isLoggedIn, true)
  assert.equal(store.status, 'error')
  assert.equal(store.summary, null)
  assert.ok(!store.errorMessage.includes('private-network-details'))
  apiClient.defaults.adapter = async (config) => response(config)
  await store.refresh()
  assert.equal(store.status, 'ready')
})

test('成长响应必须属于当前用户', async () => {
  const user = useUserStore()
  user.setSession('fixture-credential', profile)
  apiClient.defaults.adapter = async (config) => response(config, { ...summary, userId: '99' })
  const store = growthStore()
  await store.refresh()
  assert.equal(store.summary, null)
  assert.equal(store.status, 'error')
  assert.equal(user.isLoggedIn, true)
})

for (const failOld of [false, true]) {
  test(`换号后旧成长${failOld ? '401' : '成功'}响应不能影响新账号`, async () => {
    const user = useUserStore()
    user.setSession('old-credential', profile)
    let finishOld
    apiClient.defaults.adapter = (config) => {
      if (config.headers.get('Authorization') === 'Bearer new-credential')
        return Promise.resolve(response(config, { ...summary, userId: '99' }))
      return new Promise((resolve, reject) => {
        finishOld = () =>
          failOld
            ? reject(
                new AxiosError('private-failure', 'ERR_BAD_REQUEST', config, null, {
                  ...response(config),
                  status: 401,
                }),
              )
            : resolve(response(config))
      })
    }
    const store = growthStore()
    const old = store.refresh()
    user.setSession('new-credential', { ...profile, id: '99', username: 'new_user' })
    assert.equal(store.summary, null)
    await nextTick()
    await store.refresh()
    finishOld()
    await old
    assert.equal(user.token, 'new-credential')
    assert.equal(store.summary.userId, '99')
  })
}

test('成长401清理身份；403只展示查询失败', async () => {
  const user = useUserStore()
  user.setSession('fixture-credential', profile)
  let status = 403
  apiClient.defaults.adapter = async (config) => {
    throw new AxiosError('fixture', 'ERR_BAD_REQUEST', config, null, {
      ...response(config),
      status,
    })
  }
  const store = growthStore()
  await store.refresh()
  assert.equal(user.isLoggedIn, true)
  assert.equal(store.status, 'error')
  status = 401
  await store.refresh()
  await nextTick()
  assert.equal(user.isLoggedIn, false)
  assert.equal(store.summary, null)
})

test('服务端时间差驱动跨日刷新，即使浏览器日期错误也不提前或延迟一天', async (context) => {
  context.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: Date.UTC(2040, 0, 1) })
  useUserStore().setSession('fixture-credential', profile)
  let calls = 0
  apiClient.defaults.adapter = async (config) => {
    calls += 1
    return response(
      config,
      calls === 1
        ? summary
        : {
            ...summary,
            rewardDate: '2026-10-28',
            observedAt: '2026-10-27T16:00:00Z',
            nextRewardAt: '2026-10-28T16:00:00Z',
          },
    )
  }
  const store = growthStore()
  await store.refresh()
  context.mock.timers.tick(999)
  assert.equal(calls, 1)
  context.mock.timers.tick(1)
  await store.refresh()
  assert.equal(calls, 2)
  assert.equal(store.summary.rewardDate, '2026-10-28')
})

test('页面隐藏时跨日不主动发出活跃请求，再次可见后可刷新', async (context) => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  globalThis.document = { visibilityState: 'hidden' }
  useUserStore().setSession('fixture-credential', profile)
  let calls = 0
  apiClient.defaults.adapter = async (config) => {
    calls += 1
    return response(config)
  }
  const store = growthStore()
  await store.refresh()
  context.mock.timers.tick(1000)
  assert.equal(calls, 1)
  assert.equal(store.summary, null)
  globalThis.document.visibilityState = 'visible'
  await store.refresh()
  assert.equal(calls, 2)
})

test('退出后清理成长快照与跨日定时任务', async (context) => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  const user = useUserStore()
  user.setSession('fixture-credential', profile)
  let calls = 0
  apiClient.defaults.adapter = async (config) => {
    calls += 1
    return response(config)
  }
  const store = growthStore()
  await store.refresh()
  user.clearSession()
  assert.equal(store.summary, null)
  await nextTick()
  context.mock.timers.tick(1000)
  assert.equal(calls, 1)
  assert.equal(store.status, 'idle')
})

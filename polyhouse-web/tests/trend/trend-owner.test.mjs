import assert from 'node:assert/strict'
import test from 'node:test'
import { effectScope, reactive } from 'vue'
import { dataUrl, patchGlobal, typeScriptModuleUrl } from '../helpers/test-runtime.mjs'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function flushMicrotasks(rounds = 4) {
  let chain = Promise.resolve()
  for (let i = 0; i < rounds; i += 1) {
    chain = chain.then(() => new Promise((resolve) => queueMicrotask(resolve)))
  }
  return chain
}

function makeJob(patch = {}) {
  return {
    jobId: '11111111-2222-4333-8444-555555555555',
    state: 'SUCCEEDED',
    phase: 'DONE',
    aiRequested: true,
    createdAt: '2026-11-26T00:00:00Z',
    startedAt: '2026-11-26T00:00:02Z',
    completedAt: '2026-11-26T00:01:00Z',
    deadline: '2026-11-26T00:30:00Z',
    reportId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
    reason: null,
    trigger: 'MANUAL',
    summary: null,
    ...patch,
  }
}

function makeSchedule(patch = {}) {
  return {
    moduleEnabled: true,
    jobsEnabled: true,
    scheduleEnabled: true,
    schedulePaused: false,
    briefingAutoEnabled: true,
    aiReady: true,
    dailyTime: '06:30',
    retryDelayMinutes: 45,
    todayDate: '2026-11-26',
    todayStatus: 'PUBLISHED_MANUAL',
    todayPublished: true,
    recentJobs: [],
    ...patch,
  }
}

function makeBriefing(patch = {}) {
  return {
    status: 'READY',
    anchorDate: '2026-11-26',
    windowStart: '2026-11-20',
    windowEnd: '2026-11-26',
    coveredDays: 5,
    sampleStatus: 'SUFFICIENT',
    currentCoveredDays: 5,
    radar3ReadyCount: 8,
    eligible: true,
    ineligibleReason: null,
    stale: false,
    generatedAt: '2026-11-26T01:00:00Z',
    model: 'deepseek-flash',
    ruleVersion: 'briefing-1',
    headline: '示例简报',
    overview: '示例概述',
    themes: [],
    watchouts: [],
    ...patch,
  }
}

function createMemoryStorage() {
  const data = new Map()
  return {
    getItem(key) {
      return data.has(key) ? data.get(key) : null
    },
    setItem(key, value) {
      data.set(key, String(value))
    },
    removeItem(key) {
      data.delete(key)
    },
    clear() {
      data.clear()
    },
  }
}

async function setupOwnerHarness(t, initialUser) {
  const store = reactive(
    initialUser ?? {
      isLoggedIn: true,
      token: 'owner-token',
      requiresPasswordChange: false,
      user: { id: 42, role: 'OWNER' },
    },
  )
  const storage = createMemoryStorage()
  patchGlobal(t, 'sessionStorage', storage)

  const state = {
    inFlightManaged: 0,
    maxManaged: 0,
    calls: [],
    handlers: {
      getTrendJob: async () => makeJob(),
      submitTrendJob: async () => ({ accepted: true, replayed: false, job: makeJob() }),
      getTrendScheduleStatus: async () => makeSchedule(),
      setTrendSchedulePaused: async (_t, paused) =>
        makeSchedule({ schedulePaused: paused, todayStatus: paused ? 'PAUSED' : 'WAITING_WINDOW' }),
      getTrendBriefing: async () => makeBriefing(),
      generateTrendBriefing: async () => ({
        outcome: 'REUSED',
        charged: false,
        briefing: makeBriefing(),
      }),
    },
  }
  patchGlobal(t, '__ownerHarness', state)
  patchGlobal(t, '__ownerUserStore', store)

  const fakeApiIndexUrl = dataUrl(`
    export class ApiError extends Error {
      constructor(message, status = 0, retryAfterSeconds = undefined) {
        super(message)
        this.status = status
        this.retryAfterSeconds = retryAfterSeconds
      }
    }
  `)
  const fakeTrendApiUrl = dataUrl(`
    const h = () => globalThis.__ownerHarness
    async function managed(name, fn) {
      h().calls.push(name)
      h().inFlightManaged += 1
      if (h().inFlightManaged > h().maxManaged) h().maxManaged = h().inFlightManaged
      try {
        return await fn()
      } finally {
        h().inFlightManaged -= 1
      }
    }
    export function isTrendId(v) {
      return typeof v === 'string' && /^[0-9a-f-]{36}$/.test(v)
    }
    export function getTrendJob(token, jobId, signal) {
      return managed('getTrendJob', () => h().handlers.getTrendJob(token, jobId, signal))
    }
    export function submitTrendJob(token, requestId, signal) {
      return managed('submitTrendJob', () => h().handlers.submitTrendJob(token, requestId, signal))
    }
    export function getTrendScheduleStatus(token, signal) {
      return managed('getTrendScheduleStatus', () => h().handlers.getTrendScheduleStatus(token, signal))
    }
    export function setTrendSchedulePaused(token, paused, signal) {
      return managed('setTrendSchedulePaused', () =>
        h().handlers.setTrendSchedulePaused(token, paused, signal),
      )
    }
    export function getTrendBriefing(anchorDate, signal) {
      h().calls.push('getTrendBriefing')
      return h().handlers.getTrendBriefing(anchorDate, signal)
    }
    export function generateTrendBriefing(token, anchorDate, signal) {
      return managed('generateTrendBriefing', () =>
        h().handlers.generateTrendBriefing(token, anchorDate, signal),
      )
    }
  `)
  const fakeUserStoreUrl = dataUrl(`
    export function useUserStore() {
      return globalThis.__ownerUserStore
    }
  `)

  const apiIndexMod = await import(fakeApiIndexUrl)
  const modUrl = await typeScriptModuleUrl(
    new URL('../../src/features/trend/useTrendOwner.ts', import.meta.url),
    {
      "'vue'": `'${import.meta.resolve('vue')}'`,
      "'../../api/index'": `'${fakeApiIndexUrl}'`,
      "'../../api/trend'": `'${fakeTrendApiUrl}'`,
      "'../../stores/useUserStore'": `'${fakeUserStoreUrl}'`,
    },
  )
  const { useTrendOwner } = await import(modUrl)
  return { useTrendOwner, store, storage, state, ApiError: apiIndexMod.ApiError }
}

test('useTrendOwner 登录初始化严格串行拉取管理接口，零费用请求且避免并发撞穿限流', async (t) => {
  const { useTrendOwner, storage, state } = await setupOwnerHarness(t)
  const knownId = '11111111-2222-4333-8444-555555555555'
  storage.setItem('polyhouse:star-tide:42:job', knownId)

  const jobGate = deferred()
  const scheduleGate = deferred()
  const briefingGate = deferred()

  state.handlers.getTrendJob = () => jobGate.promise
  state.handlers.getTrendScheduleStatus = () => scheduleGate.promise
  state.handlers.getTrendBriefing = () => briefingGate.promise

  const scope = effectScope()
  t.after(() => scope.stop())
  const owner = scope.run(() => useTrendOwner())
  await flushMicrotasks()

  // 公开简报接口可并行，管理接口初始化阶段必须严格一次只发 1 个（先查已知任务）。
  assert.equal(state.inFlightManaged, 1)
  assert.equal(state.maxManaged, 1)
  assert.deepEqual(state.calls, ['getTrendBriefing', 'getTrendJob'])

  jobGate.resolve(makeJob({ jobId: knownId }))
  await flushMicrotasks()
  assert.equal(state.inFlightManaged, 1)
  assert.equal(state.maxManaged, 1)
  assert.deepEqual(state.calls, ['getTrendBriefing', 'getTrendJob', 'getTrendScheduleStatus'])

  scheduleGate.resolve(makeSchedule())
  briefingGate.resolve(makeBriefing())
  await flushMicrotasks()
  assert.equal(state.inFlightManaged, 0)
  assert.equal(state.maxManaged, 1)
  assert.equal(owner.job.value?.jobId, knownId)
  assert.equal(owner.scheduleStatus.value?.todayStatus, 'PUBLISHED_MANUAL')
})

test('useTrendOwner 并发闸门保证同时点击多个管理动作时在途严格不超过 2', async (t) => {
  const { useTrendOwner, storage, state } = await setupOwnerHarness(t)
  const knownId = '11111111-2222-4333-8444-555555555555'
  storage.setItem('polyhouse:star-tide:42:job', knownId)

  const scope = effectScope()
  t.after(() => scope.stop())
  const owner = scope.run(() => useTrendOwner())
  await flushMicrotasks()

  state.calls.length = 0
  state.maxManaged = 0

  const g1 = deferred()
  const g2 = deferred()
  const g3 = deferred()
  const g4 = deferred()
  state.handlers.getTrendJob = () => g1.promise
  state.handlers.getTrendScheduleStatus = () => g2.promise
  state.handlers.generateTrendBriefing = () => g3.promise
  state.handlers.setTrendSchedulePaused = () => g4.promise

  const p1 = owner.refreshJob({ cascade: false })
  const p2 = owner.refreshScheduleStatus()
  const p3 = owner.generateBriefing()
  const p4 = owner.toggleSchedulePaused(true)
  await flushMicrotasks()

  assert.equal(state.inFlightManaged, 2)
  assert.equal(state.maxManaged, 2)
  assert.deepEqual(state.calls, ['getTrendJob', 'getTrendScheduleStatus'])

  g1.resolve(makeJob({ jobId: knownId }))
  await flushMicrotasks()
  assert.equal(state.inFlightManaged, 2)
  assert.equal(state.maxManaged, 2)
  assert.deepEqual(state.calls, ['getTrendJob', 'getTrendScheduleStatus', 'generateTrendBriefing'])

  g2.resolve(makeSchedule())
  await flushMicrotasks()
  assert.equal(state.inFlightManaged, 2)
  assert.equal(state.maxManaged, 2)
  assert.deepEqual(state.calls, [
    'getTrendJob',
    'getTrendScheduleStatus',
    'generateTrendBriefing',
    'setTrendSchedulePaused',
  ])

  g3.resolve({ outcome: 'REUSED', charged: false, briefing: makeBriefing() })
  g4.resolve(makeSchedule({ schedulePaused: true, todayStatus: 'PAUSED' }))
  await Promise.all([p1, p2, p3, p4])
  assert.equal(state.inFlightManaged, 0)
  assert.equal(state.maxManaged, 2)
})

test('useTrendOwner 提交遇明确拒绝清理新建 intent，遇未知错误与重放 429 保留原 intent', async (t) => {
  const cases = [
    {
      name: '新建提交遇 429 立即清除 intent，不再残留「本次更新待确认」',
      priorIntent: null,
      makeError: (ApiError) => new ApiError('too many requests', 429, 10),
      expectIntentCleared: true,
      expectWaiting: true,
      expectErrorPattern: /冷却|频繁/,
    },
    {
      name: '新建提交遇 403 立即清除 intent 并展示明确提示',
      priorIntent: null,
      makeError: (ApiError) => new ApiError('仅屋主可执行', 403),
      expectIntentCleared: true,
      expectWaiting: false,
      expectErrorPattern: /仅屋主可执行/,
    },
    {
      name: '新建提交遇网络异常保留 intent 以便原编号核对或重放',
      priorIntent: null,
      makeError: () => new Error('network lost'),
      expectIntentCleared: false,
      expectWaiting: false,
      expectErrorPattern: /未收到更新确认/,
    },
    {
      name: '已有旧 intent 重放遇 429 保留原 intent 不丢失幂等追踪号',
      priorIntent: '99999999-2222-4333-8444-555555555555',
      makeError: (ApiError) => new ApiError('rate limited', 429, 10),
      expectIntentCleared: false,
      expectWaiting: true,
      expectErrorPattern: /冷却|频繁/,
    },
  ]

  for (const item of cases) {
    await t.test(item.name, async (st) => {
      const { useTrendOwner, storage, state, ApiError } = await setupOwnerHarness(st)
      if (item.priorIntent) {
        storage.setItem('polyhouse:star-tide:42:intent', item.priorIntent)
        // 初始化查旧 intent 返回 404 时保留 intent，模拟响应丢失后准备重放。
        state.handlers.getTrendJob = async () => {
          throw new ApiError('not found', 404)
        }
      }
      const scope = effectScope()
      st.after(() => scope.stop())
      const owner = scope.run(() => useTrendOwner())
      await flushMicrotasks()

      state.handlers.submitTrendJob = async () => {
        throw item.makeError(ApiError)
      }
      await owner.submit()

      if (item.expectIntentCleared) {
        assert.equal(owner.intent.value, null)
        assert.equal(storage.getItem('polyhouse:star-tide:42:intent'), null)
      } else {
        assert.ok(owner.intent.value, '不确定或重放场景应保留 intent')
        if (item.priorIntent) assert.equal(owner.intent.value, item.priorIntent)
        assert.equal(storage.getItem('polyhouse:star-tide:42:intent'), owner.intent.value)
      }
      assert.equal(owner.waiting.value, item.expectWaiting)
      assert.match(owner.jobError.value, item.expectErrorPattern)
    })
  }
})

test('useTrendOwner 任务从运行中变为结束后自动联动刷新自动采集状态与简报', async (t) => {
  const { useTrendOwner, state } = await setupOwnerHarness(t)
  const scope = effectScope()
  t.after(() => scope.stop())
  const owner = scope.run(() => useTrendOwner())
  await flushMicrotasks()

  const runningJob = makeJob({ state: 'RUNNING', phase: 'ANALYZING', completedAt: null, reportId: null })
  state.handlers.submitTrendJob = async () => ({ accepted: true, replayed: false, job: runningJob })
  await owner.submit()
  assert.equal(owner.active.value, true)

  state.calls.length = 0
  state.maxManaged = 0
  state.handlers.getTrendJob = async () => makeJob({ state: 'SUCCEEDED', phase: 'DONE' })
  state.handlers.getTrendScheduleStatus = async () =>
    makeSchedule({ todayStatus: 'PUBLISHED_MANUAL', recentJobs: [makeJob()] })
  state.handlers.getTrendBriefing = async () => makeBriefing({ stale: true })

  await owner.refreshJob()
  await flushMicrotasks()
  await flushMicrotasks()

  assert.equal(owner.active.value, false)
  assert.equal(owner.scheduleStatus.value?.recentJobs.length, 1)
  assert.equal(owner.briefing.value?.stale, true)
  assert.ok(state.calls.includes('getTrendScheduleStatus'))
  assert.ok(state.calls.includes('getTrendBriefing'))
  assert.ok(state.maxManaged <= 2)
})

test('useTrendOwner 切换账号或退出登录时中止在途请求并释放排队队列', async (t) => {
  const { useTrendOwner, storage, store, state } = await setupOwnerHarness(t)
  const knownId = '11111111-2222-4333-8444-555555555555'
  storage.setItem('polyhouse:star-tide:42:job', knownId)

  const scope = effectScope()
  t.after(() => scope.stop())
  const owner = scope.run(() => useTrendOwner())
  await flushMicrotasks()

  let capturedSignal
  const slowJob = deferred()
  const slowSchedule = deferred()
  state.handlers.getTrendJob = (_t, _id, signal) => {
    capturedSignal = signal
    return slowJob.promise
  }
  state.handlers.getTrendScheduleStatus = () => slowSchedule.promise
  state.handlers.generateTrendBriefing = async () => {
    throw new Error('退出登录后排队任务绝不应继续发送')
  }

  const p1 = owner.refreshJob({ cascade: false })
  const p2 = owner.refreshScheduleStatus()
  const p3 = owner.generateBriefing()
  await flushMicrotasks()

  store.isLoggedIn = false
  store.token = ''
  await flushMicrotasks()

  assert.equal(capturedSignal?.aborted, true)
  slowJob.resolve(makeJob({ jobId: knownId }))
  slowSchedule.resolve(makeSchedule())
  await Promise.all([p1, p2, p3])
  assert.equal(owner.job.value, null)
  assert.equal(owner.scheduleStatus.value, null)
})

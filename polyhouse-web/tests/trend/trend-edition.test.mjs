import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { computed, effectScope, ref } from 'vue'
import ts from 'typescript'

const codeUrl = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`

function compile(source, replacements = {}) {
  let output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(replacements)) {
    output = output.replaceAll(`from '${from}'`, `from '${to}'`)
    output = output.replaceAll(`from "${from}"`, `from "${to}"`)
  }
  return codeUrl(output)
}

const apiIndexUrl = compile(
  await readFile(new URL('../../src/api/index.ts', import.meta.url), 'utf8'),
  { axios: import.meta.resolve('axios') },
)

const trendStubUrl = codeUrl(`
  const defaults = {
    getTrendReport: async () => ({
      reportId: 'r-1',
      periodType: 'DAILY',
      periodKey: '2026-11-25',
      capturedAt: '2026-11-25T00:00:00Z',
      publishedAt: '2026-11-25T00:00:00Z',
      sourceUrl: 'https://github.com/trending',
      insightComplete: true,
      items: [],
    }),
    getTrendPeriods: async () => ({
      periodType: 'DAILY',
      items: [],
      nextBefore: null,
      earliestDate: null,
      latestDate: null,
    }),
    getTrendPeriodsByMonth: async () => ({
      periodType: 'DAILY',
      items: [],
      nextBefore: null,
      earliestDate: null,
      latestDate: null,
    }),
    getTrendWindow: async () => null,
    getTrendBriefing: async () => ({ status: 'ABSENT' }),
  }
  export const handlers = { ...defaults }
  export const resetHandlers = () => Object.assign(handlers, defaults)
  export const isTrendDate = (v) => typeof v === 'string' && /^\\d{4}-\\d{2}-\\d{2}$/.test(v)
  export const getTrendReport = (...args) => handlers.getTrendReport(...args)
  export const getTrendPeriods = (...args) => handlers.getTrendPeriods(...args)
  export const getTrendPeriodsByMonth = (...args) => handlers.getTrendPeriodsByMonth(...args)
  export const getTrendWindow = (...args) => handlers.getTrendWindow(...args)
  export const getTrendBriefing = (...args) => handlers.getTrendBriefing(...args)
`)

const calendarUrl = compile(
  await readFile(new URL('../../src/core/calendar.ts', import.meta.url), 'utf8'),
)

const { useTrendEdition } = await import(
  compile(await readFile(new URL('../../src/features/trend/useTrendEdition.ts', import.meta.url), 'utf8'), {
    vue: import.meta.resolve('vue'),
    '../../api/index': apiIndexUrl,
    '../../api/trend': trendStubUrl,
    '../../core/calendar': calendarUrl,
  })
)
const { ApiError } = await import(apiIndexUrl)
const { handlers, resetHandlers } = await import(trendStubUrl)

// 用微任务冲刷代替真实 setTimeout(0)，确保异步 watch 与 Promise 链确定性收敛。
async function flushMicrotasks() {
  for (let i = 0; i < 6; i += 1) await Promise.resolve()
}

test('首屏历史失败后重试仍拉取第一页，追加分页失败后重试保留游标并继续追加', async (t) => {
  t.after(resetHandlers)
  const cursors = []
  let callIndex = 0
  handlers.getTrendPeriods = async (before) => {
    cursors.push(before ?? null)
    callIndex += 1
    if (callIndex === 1) throw new Error('first page down')
    if (callIndex === 2) {
      return { periodType: 'DAILY', items: ['2026-11-25', '2026-11-24'], nextBefore: '2026-11-24' }
    }
    if (callIndex === 3) throw new Error('second page down')
    return { periodType: 'DAILY', items: ['2026-11-23', '2026-11-22'], nextBefore: null }
  }

  const scope = effectScope()
  t.after(() => scope.stop())
  const edition = ref(undefined)
  const inDirectory = ref(true)
  const windowVisible = ref(false)
  const state = scope.run(() =>
    useTrendEdition(
      computed(() => edition.value),
      computed(() => inDirectory.value),
      computed(() => windowVisible.value),
    ),
  )

  // 1. 首屏历史失败 → 重试历史仍请求无游标首页。
  await flushMicrotasks()
  assert.equal(state.dateError.value, true)
  assert.deepEqual(cursors, [null])

  await state.retryDates()
  assert.equal(state.dateError.value, false)
  assert.deepEqual(state.dates.value, ['2026-11-25', '2026-11-24'])
  assert.equal(state.cursor.value, '2026-11-24')
  assert.deepEqual(cursors, [null, null])

  // 2. 点击更早日期失败 → 重试历史应沿用原游标并保留首页结果。
  await state.loadDates(true)
  assert.equal(state.dateError.value, true)
  assert.deepEqual(state.dates.value, ['2026-11-25', '2026-11-24'])
  assert.equal(state.cursor.value, '2026-11-24')

  await state.retryDates()
  assert.equal(state.dateError.value, false)
  assert.deepEqual(cursors, [null, null, '2026-11-24', '2026-11-24'])
  assert.deepEqual(state.dates.value, ['2026-11-25', '2026-11-24', '2026-11-23', '2026-11-22'])
  assert.equal(state.cursor.value, null)
})

test('追加分页触发 429 限流时，重试历史按可控时钟严格遵守冷却边界并沿用原游标', async (t) => {
  t.after(resetHandlers)
  // 使用可控时间源代替真实 setTimeout，精准覆盖 2000ms 冷却的差一边界（1999ms 拦截、2000ms 放行）。
  let now = 1_764_000_000_000
  t.mock.method(Date, 'now', () => now)

  const cursors = []
  let callIndex = 0
  handlers.getTrendPeriods = async (before) => {
    cursors.push(before ?? null)
    callIndex += 1
    if (callIndex === 1) {
      return { periodType: 'DAILY', items: ['2026-11-25'], nextBefore: '2026-11-25' }
    }
    if (callIndex === 2) {
      throw new ApiError('请求过于频繁', 429, undefined, 2)
    }
    return { periodType: 'DAILY', items: ['2026-11-24'], nextBefore: null }
  }

  const scope = effectScope()
  t.after(() => scope.stop())
  const state = scope.run(() =>
    useTrendEdition(
      computed(() => undefined),
      computed(() => true),
      computed(() => false),
    ),
  )

  await flushMicrotasks()
  await state.loadDates(true)
  assert.equal(state.dateError.value, true)
  assert.deepEqual(cursors, [null, '2026-11-25'])

  // 冷却期内（含差一边界 1999ms）点击重试均不会提前发请求。
  for (const offsetMs of [0, 1999]) {
    now = 1_764_000_000_000 + offsetMs
    await state.retryDates()
    assert.deepEqual(cursors, [null, '2026-11-25'])
  }

  // 恰好到达 2000ms 冷却结束边界，再次重试携带原游标并成功追加。
  now = 1_764_000_000_000 + 2000
  await state.retryDates()
  assert.equal(state.dateError.value, false)
  assert.deepEqual(cursors, [null, '2026-11-25', '2026-11-25'])
  assert.deepEqual(state.dates.value, ['2026-11-25', '2026-11-24'])
})

test('按月查询自动缓存已读月份、更新全局边界并支持跨空月解析邻期', async (t) => {
  t.after(resetHandlers)
  const monthCalls = []
  handlers.getTrendPeriods = async () => ({
    periodType: 'DAILY',
    items: ['2026-11-02'],
    nextBefore: null,
    earliestDate: '2026-09-15',
    latestDate: '2026-11-25',
  })
  handlers.getTrendPeriodsByMonth = async (month) => {
    monthCalls.push(month)
    if (month === '2026-11') {
      return {
        periodType: 'DAILY',
        items: ['2026-11-25', '2026-11-02'],
        nextBefore: null,
        earliestDate: '2026-09-15',
        latestDate: '2026-11-25',
      }
    }
    if (month === '2026-10') {
      return {
        periodType: 'DAILY',
        items: [],
        nextBefore: null,
        earliestDate: '2026-09-15',
        latestDate: '2026-11-25',
      }
    }
    if (month === '2026-09') {
      return {
        periodType: 'DAILY',
        items: ['2026-09-28', '2026-09-15'],
        nextBefore: null,
        earliestDate: '2026-09-15',
        latestDate: '2026-11-25',
      }
    }
    return { periodType: 'DAILY', items: [], nextBefore: null, earliestDate: '2026-09-15', latestDate: '2026-11-25' }
  }

  const scope = effectScope()
  t.after(() => scope.stop())
  const state = scope.run(() =>
    useTrendEdition(
      computed(() => undefined),
      computed(() => true),
      computed(() => false),
    ),
  )

  await flushMicrotasks()
  assert.equal(state.earliestDate.value, '2026-09-15')
  assert.equal(state.latestDate.value, '2026-11-25')
  assert.deepEqual(monthCalls, ['2026-11'])

  // 重复请求已缓存的 2026-11 不再发网络请求
  const cached = await state.loadMonth('2026-11')
  assert.deepEqual(cached, ['2026-11-25', '2026-11-02'])
  assert.deepEqual(monthCalls, ['2026-11'])

  // 从 2026-11-02 向前跨月探测：跳过空的 2026-10，直达 2026-09-28
  const prev = await state.resolveAdjacentDate('2026-11-02', 'prev')
  assert.equal(prev, '2026-09-28')
  assert.deepEqual(monthCalls, ['2026-11', '2026-10', '2026-09'])
})

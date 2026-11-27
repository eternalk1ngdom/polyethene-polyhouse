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

test('首屏已完整覆盖当月时免二次按月请求，且同月并发请求自动去重', async (t) => {
  const cases = [
    {
      name: '全站数据少于一页（nextBefore 为 null）时首屏仅请求一次 periods，不发按月查询',
      periodsResponse: {
        periodType: 'DAILY',
        items: ['2026-11-25', '2026-11-24', '2026-11-18'],
        nextBefore: null,
        earliestDate: '2026-11-18',
        latestDate: '2026-11-25',
      },
      expectLoadedMonths: ['2026-11'],
    },
    {
      name: '跨月分页但最旧项不晚于当月 1 日时，同样标记当月已完整覆盖免二次请求',
      periodsResponse: {
        periodType: 'DAILY',
        items: ['2026-11-25', '2026-11-01', '2026-10-28'],
        nextBefore: '2026-10-28',
        earliestDate: '2026-09-15',
        latestDate: '2026-11-25',
      },
      expectLoadedMonths: ['2026-11'],
    },
  ]

  for (const item of cases) {
    await t.test(item.name, async (st) => {
      st.after(resetHandlers)
      let periodsCalls = 0
      const monthCalls = []
      handlers.getTrendPeriods = async () => {
        periodsCalls += 1
        return item.periodsResponse
      }
      handlers.getTrendPeriodsByMonth = async (month) => {
        monthCalls.push(month)
        return {
          periodType: 'DAILY',
          items: [],
          nextBefore: null,
          earliestDate: item.periodsResponse.earliestDate,
          latestDate: item.periodsResponse.latestDate,
        }
      }

      const scope = effectScope()
      st.after(() => scope.stop())
      const state = scope.run(() =>
        useTrendEdition(
          computed(() => undefined),
          computed(() => true),
          computed(() => false),
        ),
      )

      await flushMicrotasks()
      assert.equal(periodsCalls, 1)
      assert.deepEqual(monthCalls, [])
      for (const ym of item.expectLoadedMonths) {
        assert.equal(state.loadedMonths.value.has(ym), true)
      }
    })
  }

  await t.test('同月并发调用 loadMonth 复用在途 Promise，严格只发 1 次网络请求', async (st) => {
    st.after(resetHandlers)
    let resolveMonth
    let monthReqCount = 0
    handlers.getTrendPeriods = async () => ({
      periodType: 'DAILY',
      items: ['2026-11-25'],
      nextBefore: null,
      earliestDate: '2026-10-01',
      latestDate: '2026-11-25',
    })
    handlers.getTrendPeriodsByMonth = () => {
      monthReqCount += 1
      return new Promise((resolve) => {
        resolveMonth = resolve
      })
    }

    const scope = effectScope()
    st.after(() => scope.stop())
    const state = scope.run(() =>
      useTrendEdition(
        computed(() => undefined),
        computed(() => true),
        computed(() => false),
      ),
    )

    await flushMicrotasks()
    const p1 = state.loadMonth('2026-10')
    const p2 = state.loadMonth('2026-10')
    assert.equal(monthReqCount, 1)
    assert.equal(state.loadingMonth.value, true)

    resolveMonth({
      periodType: 'DAILY',
      items: ['2026-10-20', '2026-10-15'],
      nextBefore: null,
      earliestDate: '2026-10-01',
      latestDate: '2026-11-25',
    })
    const [r1, r2] = await Promise.all([p1, p2])
    assert.deepEqual(r1, ['2026-10-20', '2026-10-15'])
    assert.deepEqual(r2, ['2026-10-20', '2026-10-15'])
    assert.equal(state.loadingMonth.value, false)
    assert.equal(state.loadedMonths.value.has('2026-10'), true)
  })
})

test('按月查询自动补全未覆盖月份、缓存已读月并支持跨空月解析邻期与手输核验', async (t) => {
  t.after(resetHandlers)
  const monthCalls = []
  handlers.getTrendPeriods = async () => ({
    periodType: 'DAILY',
    items: ['2026-11-25', '2026-11-18'],
    nextBefore: '2026-11-18',
    earliestDate: '2026-09-15',
    latestDate: '2026-11-25',
  })
  handlers.getTrendPeriodsByMonth = async (month) => {
    monthCalls.push(month)
    if (month === '2026-11') {
      return {
        periodType: 'DAILY',
        items: ['2026-11-25', '2026-11-18', '2026-11-02'],
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
  // 首屏最旧项 11-18 晚于 11-01，故自动补发一次 2026-11 按月请求
  assert.deepEqual(monthCalls, ['2026-11'])

  // 重复请求已缓存的 2026-11 不再发网络请求
  const cached = await state.loadMonth('2026-11')
  assert.deepEqual(cached, ['2026-11-25', '2026-11-18', '2026-11-02'])
  assert.deepEqual(monthCalls, ['2026-11'])

  // 手输核验未加载的历史月 2026-09-15：按月拉取后命中 available
  const hit = await state.ensureDateAvailable('2026-09-15')
  assert.deepEqual(hit, { status: 'available', date: '2026-09-15' })
  assert.deepEqual(monthCalls, ['2026-11', '2026-09'])

  // 手输核验 2026-09-20（缺采日）：零二次请求直接返回 missing 并给出最近一期 2026-09-15（距 5 天 < 距 28 日 8 天）
  const miss = await state.ensureDateAvailable('2026-09-20')
  assert.deepEqual(miss, { status: 'missing', date: '2026-09-20', nearestDate: '2026-09-15' })
  assert.deepEqual(monthCalls, ['2026-11', '2026-09'])

  // 从 2026-11-02 向前跨月探测：2026-09 已在内存，直接跳过缺采日到达 2026-09-28
  const prev = await state.resolveAdjacentDate('2026-11-02', 'prev')
  assert.equal(prev, '2026-09-28')

  // 手输核验空的 2026-10-15：拉取 2026-10 为空后返回 missing 并定位最近有值日 2026-09-28
  const missOct = await state.ensureDateAvailable('2026-10-15')
  assert.deepEqual(missOct, { status: 'missing', date: '2026-10-15', nearestDate: '2026-09-28' })
  assert.deepEqual(monthCalls, ['2026-11', '2026-09', '2026-10'])

  // 拉取历史月网络异常时返回 error，不误将未读月判为缺采
  handlers.getTrendPeriodsByMonth = async () => {
    throw new Error('network down')
  }
  const errRes = await state.ensureDateAvailable('2026-08-10')
  assert.equal(errRes.status, 'error')
  assert.equal(state.loadedMonths.value.has('2026-08'), false)
})

test('访问 404 缺采日期时设置 noReport 且不将该日期混入已发布日期列表', async (t) => {
  t.after(resetHandlers)
  handlers.getTrendReport = async () => {
    throw new ApiError('not found', 404)
  }
  handlers.getTrendPeriods = async () => ({
    periodType: 'DAILY',
    items: ['2026-11-27', '2026-11-26'],
    nextBefore: null,
    earliestDate: '2026-11-18',
    latestDate: '2026-11-27',
  })

  const scope = effectScope()
  t.after(() => scope.stop())
  const state = scope.run(() =>
    useTrendEdition(
      computed(() => '2026-11-10'),
      computed(() => true),
      computed(() => false),
    ),
  )

  await flushMicrotasks()
  assert.equal(state.noReport.value, true)
  assert.equal(state.report.value, null)
  assert.equal(state.dates.value.includes('2026-11-10'), false)
})

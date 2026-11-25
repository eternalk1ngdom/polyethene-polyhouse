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
  export const handlers = {
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
    getTrendPeriods: async () => ({ periodType: 'DAILY', items: [], nextBefore: null }),
    getTrendWindow: async () => null,
    getTrendBriefing: async () => ({ status: 'ABSENT' }),
  }
  export const isTrendDate = (v) => typeof v === 'string' && /^\\d{4}-\\d{2}-\\d{2}$/.test(v)
  export const getTrendReport = (...args) => handlers.getTrendReport(...args)
  export const getTrendPeriods = (...args) => handlers.getTrendPeriods(...args)
  export const getTrendWindow = (...args) => handlers.getTrendWindow(...args)
  export const getTrendBriefing = (...args) => handlers.getTrendBriefing(...args)
`)

const { useTrendEdition } = await import(
  compile(await readFile(new URL('../../src/features/trend/useTrendEdition.ts', import.meta.url), 'utf8'), {
    vue: import.meta.resolve('vue'),
    '../../api/index': apiIndexUrl,
    '../../api/trend': trendStubUrl,
  })
)
const { ApiError } = await import(apiIndexUrl)
const { handlers } = await import(trendStubUrl)

const settle = () => new Promise((resolve) => setTimeout(resolve, 0))

test('首屏历史失败后重试仍拉取第一页，追加分页失败后重试保留游标并继续追加', async (t) => {
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
  await settle()
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

test('追加分页触发 429 限流时，重试历史遵守冷却等待且冷却结束后沿用原游标', async (t) => {
  const cursors = []
  let callIndex = 0
  handlers.getTrendPeriods = async (before) => {
    cursors.push(before ?? null)
    callIndex += 1
    if (callIndex === 1) {
      return { periodType: 'DAILY', items: ['2026-11-25'], nextBefore: '2026-11-25' }
    }
    if (callIndex === 2) {
      throw new ApiError('请求过于频繁', 429, undefined, 0.05)
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

  await settle()
  await state.loadDates(true)
  assert.equal(state.dateError.value, true)
  assert.deepEqual(cursors, [null, '2026-11-25'])

  // 冷却期内点击重试不会提前发请求。
  await state.retryDates()
  assert.deepEqual(cursors, [null, '2026-11-25'])

  // 冷却结束后再次重试，携带原游标并成功追加。
  await new Promise((resolve) => setTimeout(resolve, 65))
  await state.retryDates()
  assert.equal(state.dateError.value, false)
  assert.deepEqual(cursors, [null, '2026-11-25', '2026-11-25'])
  assert.deepEqual(state.dates.value, ['2026-11-25', '2026-11-24'])
})

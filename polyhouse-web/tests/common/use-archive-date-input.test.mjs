import assert from 'node:assert/strict'
import test from 'node:test'
import { effectScope, ref } from 'vue'
import { typeScriptModuleUrl } from '../helpers/test-runtime.mjs'

const vueUrl = import.meta.resolve('vue')
const inputModuleUrl = await typeScriptModuleUrl(
  new URL('../../src/components/archive-date-picker/useArchiveDateInput.ts', import.meta.url),
  {
    "from 'vue'": `from '${vueUrl}'`,
    'from "vue"': `from '${vueUrl}'`,
  },
)
const { useArchiveDateInput } = await import(inputModuleUrl)

function createDeferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

test('手动输入日期 4 类分级校验：非必要零网络请求，保留原文并提供一键修复', async (t) => {
  const cases = [
    {
      name: '格式非法（2026-02-30）本地拦截、保留原文并提示格式示例',
      input: '2026-02-30',
      expectKind: 'invalid',
      expectDraft: '2026-02-30',
      expectMessageIncludes: '请输入有效日期',
      expectActionDate: undefined,
    },
    {
      name: '早于最早边界本地零请求拦截并提供跳到最早一期',
      input: '2026-10-01',
      expectKind: 'out-of-bounds',
      expectDraft: '2026-10-01',
      expectMessageIncludes: '超出归档范围',
      expectActionDate: '2026-11-18',
    },
    {
      name: '晚于最新边界本地零请求拦截并提供跳到最新一期',
      input: '2026/12/01',
      expectKind: 'out-of-bounds',
      expectDraft: '2026/12/01',
      expectMessageIncludes: '超出归档范围',
      expectActionDate: '2026-11-27',
    },
    {
      name: '已加载月份内的缺采日零请求拦截，定位该月并引导转到最近一期（同距取旧期）',
      input: '20261120',
      expectKind: 'missing',
      expectDraft: '20261120',
      expectMessageIncludes: '2026-11-20 当日暂无日报',
      expectActionDate: '2026-11-19',
      expectLocatedMonth: '2026-11',
    },
  ]

  for (const item of cases) {
    await t.test(item.name, async (st) => {
      const committed = []
      const located = []
      let resolveCalls = 0
      const scope = effectScope()
      st.after(() => scope.stop())

      const state = scope.run(() =>
        useArchiveDateInput({
          getActiveDate: () => '2026-11-27',
          getEarliestDate: () => '2026-11-18',
          getLatestDate: () => '2026-11-27',
          getAvailableDates: () => ['2026-11-18', '2026-11-19', '2026-11-21', '2026-11-27'],
          getLoadedMonths: () => new Set(['2026-11']),
          getResolveDate: () => async () => {
            resolveCalls += 1
            return { status: 'missing', date: item.input, nearestDate: null }
          },
          getItemNoun: () => '日报',
          onCommit: (iso) => committed.push(iso),
          onLocateMonth: (ym) => located.push(ym),
        }),
      )

      state.updateDraft(item.input)
      const ok = await state.submitInput()
      assert.equal(ok, false)
      assert.equal(resolveCalls, 0)
      assert.deepEqual(committed, [])
      assert.equal(state.draftText.value, item.expectDraft)
      assert.equal(state.feedback.value?.kind, item.expectKind)
      assert.ok(state.feedback.value?.message.includes(item.expectMessageIncludes))
      assert.equal(state.feedback.value?.actionDate, item.expectActionDate)
      if (item.expectLocatedMonth) {
        assert.deepEqual(located, [item.expectLocatedMonth])
      }

      if (item.expectActionDate) {
        state.applyFeedbackAction()
        assert.deepEqual(committed, [item.expectActionDate])
        assert.equal(state.draftText.value, item.expectActionDate)
        assert.equal(state.feedback.value, null)
      }
    })
  }
})

test('未加载历史月份走异步核验、支持失败原位重试且严格隔离过期异步回包', async (t) => {
  await t.test('异步核验命中、缺采与失败后原位重试', async (st) => {
    const committed = []
    let mode = 'error'
    const scope = effectScope()
    st.after(() => scope.stop())

    const state = scope.run(() =>
      useArchiveDateInput({
        getActiveDate: () => '2026-11-27',
        getEarliestDate: () => '2026-09-01',
        getLatestDate: () => '2026-11-27',
        getAvailableDates: () => ['2026-11-27'],
        getLoadedMonths: () => new Set(['2026-11']),
        getResolveDate: () => async (iso) => {
          if (mode === 'error') return { status: 'error', message: '查询频繁，请稍后重试' }
          if (mode === 'missing') return { status: 'missing', date: iso, nearestDate: '2026-10-18' }
          return { status: 'available', date: iso }
        },
        getItemNoun: () => '日报',
        onCommit: (iso) => committed.push(iso),
      }),
    )

    // 1. 失败态保留原文并提供原位重试
    state.updateDraft('2026-10-15')
    assert.equal(await state.submitInput(), false)
    assert.equal(state.feedback.value?.kind, 'error')
    assert.equal(state.feedback.value?.retryDate, '2026-10-15')
    assert.equal(state.draftText.value, '2026-10-15')

    // 2. 点击重试后命中可用日期，自动提交并清空反馈
    mode = 'available'
    state.applyFeedbackAction()
    await Promise.resolve()
    await Promise.resolve()
    assert.deepEqual(committed, ['2026-10-15'])
    assert.equal(state.feedback.value, null)

    // 3. 缺采态给出最近一期建议
    mode = 'missing'
    state.updateDraft('2026-10-16')
    assert.equal(await state.submitInput(), false)
    assert.equal(state.feedback.value?.kind, 'missing')
    assert.equal(state.feedback.value?.actionDate, '2026-10-18')
  })

  await t.test('取消隔离：旧异步核验晚于新编辑/重置返回时被彻底丢弃', async (st) => {
    const committed = []
    const slow = createDeferred()
    const scope = effectScope()
    st.after(() => scope.stop())

    const state = scope.run(() =>
      useArchiveDateInput({
        getActiveDate: () => '2026-11-27',
        getEarliestDate: () => '2026-09-01',
        getLatestDate: () => '2026-11-27',
        getAvailableDates: () => ['2026-11-27'],
        getLoadedMonths: () => new Set(['2026-11']),
        getResolveDate: () => () => slow.promise,
        getItemNoun: () => '日报',
        onCommit: (iso) => committed.push(iso),
      }),
    )

    state.updateDraft('2026-10-10')
    const pending = state.submitInput()
    assert.equal(state.resolving.value, true)

    // 在请求未回来前用户按 Esc 还原
    state.resetDraft()
    assert.equal(state.resolving.value, false)
    assert.equal(state.draftText.value, '2026-11-27')

    slow.resolve({ status: 'available', date: '2026-10-10' })
    const result = await pending
    assert.equal(result, false)
    assert.deepEqual(committed, [])
    assert.equal(state.draftText.value, '2026-11-27')
  })
})

test('无害失焦策略：合法已知日平滑提交，半成品静默还原，报错态保持可读', (t) => {
  const active = ref('2026-11-27')
  const committed = []
  const scope = effectScope()
  t.after(() => scope.stop())

  const state = scope.run(() =>
    useArchiveDateInput({
      getActiveDate: () => active.value,
      getEarliestDate: () => '2026-11-18',
      getLatestDate: () => '2026-11-27',
      getAvailableDates: () => ['2026-11-18', '2026-11-24', '2026-11-27'],
      getLoadedMonths: () => new Set(['2026-11']),
      getResolveDate: () => null,
      getItemNoun: () => '日报',
      onCommit: (iso) => {
        committed.push(iso)
        active.value = iso
      },
    }),
  )

  // 敲了一半直接失焦 → 静默恢复当前选中日期，绝不误跳
  state.updateDraft('2026-11-')
  state.handleBlur()
  assert.equal(state.draftText.value, '2026-11-27')
  assert.equal(state.feedback.value, null)
  assert.deepEqual(committed, [])

  // 敲了合法且已知可用日期（8 位纯数字）后失焦 → 平滑归一化并提交
  state.updateDraft('20261124')
  state.handleBlur()
  assert.deepEqual(committed, ['2026-11-24'])
  assert.equal(state.draftText.value, '2026-11-24')
})

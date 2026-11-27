import assert from 'node:assert/strict'
import test from 'node:test'
import { nextTick } from 'vue'
import {
  collectText,
  compileSfcComponent,
  dataUrl,
  findAll,
  mountHeadless,
  typeScriptModuleUrl,
} from '../helpers/test-runtime.mjs'

const calendarUrl = await typeScriptModuleUrl(new URL('../../src/core/calendar.ts', import.meta.url))
const elementPlusStubUrl = dataUrl(`
import { h } from '${import.meta.resolve('vue')}'
export const ElIcon = {
  name: 'ElIcon',
  setup(_, { slots, attrs }) {
    return () => h('span', { ...attrs, 'data-el-icon': 'true' }, slots.default ? slots.default() : [])
  },
}
`)
const iconsStubUrl = dataUrl(`
import { h } from '${import.meta.resolve('vue')}'
const makeIcon = (name) => ({ name, render: () => h('svg', { 'data-icon': name }) })
export const ArrowDown = makeIcon('ArrowDown')
export const ArrowLeft = makeIcon('ArrowLeft')
export const ArrowRight = makeIcon('ArrowRight')
export const Calendar = makeIcon('Calendar')
export const Close = makeIcon('Close')
`)

const ArchiveDatePicker = await compileSfcComponent(
  new URL('../../src/components/ArchiveDatePicker.vue', import.meta.url),
  'archive-date-picker-test',
  {
    "'../core/calendar'": `'${calendarUrl}'`,
    "'element-plus'": `'${elementPlusStubUrl}'`,
    "'@element-plus/icons-vue'": `'${iconsStubUrl}'`,
  },
)

function hasClass(node, className) {
  const raw = node.props?.class
  if (typeof raw === 'string') return raw.split(/\s+/).includes(className)
  return false
}

function findButtonByLabel(root, textOrPrefix) {
  return findAll(
    root,
    (node) =>
      node.type === 'el' &&
      node.tag === 'button' &&
      typeof node.props['aria-label'] === 'string' &&
      node.props['aria-label'].startsWith(textOrPrefix),
  )[0]
}

function findDayButton(root, isoDate) {
  return findAll(
    root,
    (node) => node.type === 'el' && node.tag === 'button' && node.props['data-date'] === isoDate,
  )[0]
}

test('三段式触发器正确展示日期、最新标签与边界禁用状态', async (t) => {
  const cases = [
    {
      name: '空值默认最新：显示最新标签，禁用下一期，启用上一期',
      props: {
        modelValue: '',
        latestDate: '2026-11-27',
        earliestDate: '2026-10-01',
        availableDates: ['2026-11-27', '2026-11-25'],
      },
      expectText: '2026-11-27',
      expectLatestPill: true,
      expectPrevDisabled: false,
      expectNextDisabled: true,
    },
    {
      name: '处于最早一期：不显示最新标签，禁用上一期，启用下一期',
      props: {
        modelValue: '2026-10-01',
        latestDate: '2026-11-27',
        earliestDate: '2026-10-01',
        availableDates: ['2026-10-01', '2026-10-05'],
      },
      expectText: '2026-10-01',
      expectLatestPill: false,
      expectPrevDisabled: true,
      expectNextDisabled: false,
    },
    {
      name: '关闭左右箭头模式：仅渲染主触发器胶囊',
      props: {
        modelValue: '2026-11-25',
        latestDate: '2026-11-27',
        earliestDate: '2026-10-01',
        availableDates: ['2026-11-27', '2026-11-25'],
        showStepButtons: false,
      },
      expectText: '2026-11-25',
      expectLatestPill: false,
      expectNoSteps: true,
    },
  ]

  for (const item of cases) {
    await t.test(item.name, () => {
      const { root } = mountHeadless(t, ArchiveDatePicker, item.props)
      const text = collectText(root)
      assert.ok(text.includes(item.expectText))
      assert.equal(text.includes('最新'), item.expectLatestPill)
      const prevBtn = findButtonByLabel(root, '上一有内容期')
      const nextBtn = findButtonByLabel(root, '下一有内容期')
      if (item.expectNoSteps) {
        assert.equal(prevBtn, undefined)
        assert.equal(nextBtn, undefined)
      } else {
        assert.equal(Boolean(prevBtn.props.disabled), item.expectPrevDisabled)
        assert.equal(Boolean(nextBtn.props.disabled), item.expectNextDisabled)
      }
    })
  }
})

test('面板打开后禁点缺采日、支持点选有值日与区间高亮带、且支持加载/重试', async (t) => {
  const updated = []
  const monthChanges = []
  const retries = []
  let triggerFocused = false

  const { root } = mountHeadless(t, ArchiveDatePicker, {
    modelValue: '2026-11-27',
    latestDate: '2026-11-27',
    earliestDate: '2026-10-01',
    availableDates: ['2026-11-27', '2026-11-24', '2026-11-21'],
    highlightRange: { start: '2026-11-21', end: '2026-11-27' },
    unitLabel: '期有日报',
    'onUpdate:modelValue': (val) => updated.push(val),
    onMonthChange: (ym) => monthChanges.push(ym),
    onRetryMonth: (ym) => retries.push(ym),
  })

  const mainBtn = findButtonByLabel(root, '归档日期选择：')
  mainBtn.focus = () => {
    triggerFocused = true
  }
  mainBtn.props.onClick()
  await nextTick()

  assert.deepEqual(monthChanges, ['2026-11'])
  const dayButtons = findAll(root, (node) => node.type === 'el' && node.tag === 'button' && node.props['data-date'])
  assert.equal(dayButtons.length, 42)

  // 缺采日 11-25 必须禁用且点击不触发选中
  const missingBtn = findDayButton(root, '2026-11-25')
  assert.equal(missingBtn.props.disabled, true)
  assert.equal(missingBtn.props['aria-disabled'], 'true')
  assert.equal(hasClass(missingBtn, 'is-in-range'), true)
  missingBtn.props.onClick()
  assert.deepEqual(updated, [])

  // 区间起点与终点样式标记正确
  const startBtn = findDayButton(root, '2026-11-21')
  const endBtn = findDayButton(root, '2026-11-27')
  assert.equal(hasClass(startBtn, 'is-range-start'), true)
  assert.equal(hasClass(endBtn, 'is-range-end'), true)

  // 底栏统计正确（本月截至 11-27 有 3 期，缺采 24 天）
  assert.ok(collectText(root).includes('本月 3 期有日报'))
  assert.ok(collectText(root).includes('缺采 24 天'))

  // 点击有内容日 11-24 触发更新、自动收起面板并还原焦点到主触发按钮
  const availBtn = findDayButton(root, '2026-11-24')
  assert.equal(availBtn.props.disabled, false)
  availBtn.props.onClick()
  await nextTick()
  assert.deepEqual(updated, ['2026-11-24'])
  assert.equal(findDayButton(root, '2026-11-24'), undefined)
  assert.equal(triggerFocused, true)

  // 单独验证错误态底栏重试按钮触发 retryMonth
  const errRetries = []
  const { root: errRoot } = mountHeadless(t, ArchiveDatePicker, {
    modelValue: '2026-11-27',
    availableDates: [],
    monthError: '本月历史读取失败',
    onRetryMonth: (ym) => errRetries.push(ym),
  })
  findButtonByLabel(errRoot, '归档日期选择：').props.onClick()
  await nextTick()
  assert.ok(collectText(errRoot).includes('本月历史读取失败'))
  const retryBtn = findAll(errRoot, (node) => node.tag === 'button' && collectText(node).trim() === '重试')[0]
  retryBtn.props.onClick()
  assert.deepEqual(errRetries, ['2026-11'])
})

test('左右跳期支持已知邻期直接跳与跨月未加载交由调用方解析', async (t) => {
  await t.test('已知集合中自动跳过缺采日直达上一期', () => {
    const updated = []
    const steps = []
    const { root } = mountHeadless(t, ArchiveDatePicker, {
      modelValue: '2026-11-27',
      latestDate: '2026-11-27',
      earliestDate: '2026-10-01',
      availableDates: ['2026-11-27', '2026-11-23'],
      'onUpdate:modelValue': (val) => updated.push(val),
      onStep: (dir, resolved) => steps.push([dir, resolved]),
    })
    findButtonByLabel(root, '上一有内容期').props.onClick()
    assert.deepEqual(updated, ['2026-11-23'])
    assert.deepEqual(steps, [['prev', '2026-11-23']])
  })

  await t.test('已知集合已到底但仍大于 earliestDate 时交由调用方跨月懒加载', () => {
    const updated = []
    const steps = []
    const { root } = mountHeadless(t, ArchiveDatePicker, {
      modelValue: '2026-11-02',
      latestDate: '2026-11-27',
      earliestDate: '2026-10-15',
      availableDates: ['2026-11-02', '2026-11-27'],
      'onUpdate:modelValue': (val) => updated.push(val),
      onStep: (dir, resolved) => steps.push([dir, resolved]),
    })
    const prevBtn = findButtonByLabel(root, '上一有内容期')
    assert.equal(Boolean(prevBtn.props.disabled), false)
    prevBtn.props.onClick()
    assert.deepEqual(updated, [])
    assert.deepEqual(steps, [['prev', null]])
  })
})

test('方案 A1 年月分离胶囊：单年静态退化、多年真实年份与钳位、Esc 退回及快捷跳转', async (t) => {
  await t.test('单年归档时年份退化为静态文本，仅月份胶囊可切换展开与收起', async () => {
    const { root } = mountHeadless(t, ArchiveDatePicker, {
      modelValue: '2026-11-27',
      latestDate: '2026-11-27',
      earliestDate: '2026-11-18',
      availableDates: ['2026-11-18', '2026-11-27'],
    })

    findButtonByLabel(root, '归档日期选择：').props.onClick()
    await nextTick()

    // 单年归档不渲染年份下拉按钮，仅以静态文本展示 2026 年
    assert.equal(findButtonByLabel(root, '选择年份'), undefined)
    assert.ok(collectText(root).includes('2026 年'))

    // 点击月份胶囊展开 12 月网格，按钮进入激活态且上下年箭头均禁用
    const monthPill = findButtonByLabel(root, '选择月份')
    assert.ok(monthPill)
    monthPill.props.onClick()
    await nextTick()

    const expandedMonthPill = findButtonByLabel(root, '收起月份选择')
    assert.ok(expandedMonthPill)
    assert.equal(hasClass(expandedMonthPill, 'is-active'), true)
    assert.equal(expandedMonthPill.props['aria-expanded'], true)
    assert.equal(findButtonByLabel(root, '上一年').props.disabled, true)
    assert.equal(findButtonByLabel(root, '下一年').props.disabled, true)

    // 10 月与 12 月越界禁用，11 月可用（限定在月份网格内查找，避免命中头部月份胶囊）
    const monthGrid = findAll(root, (n) => n.props?.role === 'grid' && n.props['aria-label'] === '选择月份')[0]
    assert.ok(monthGrid)
    const octBtn = findAll(monthGrid, (n) => n.tag === 'button' && collectText(n).trim() === '10 月')[0]
    const novBtn = findAll(monthGrid, (n) => n.tag === 'button' && collectText(n).trim() === '11 月')[0]
    const decBtn = findAll(monthGrid, (n) => n.tag === 'button' && collectText(n).trim() === '12 月')[0]
    assert.equal(octBtn.props.disabled, true)
    assert.equal(novBtn.props.disabled, false)
    assert.equal(decBtn.props.disabled, true)

    // 再次点击高亮月份胶囊直接收起回到月历视图
    expandedMonthPill.props.onClick()
    await nextTick()
    assert.equal(
      findAll(root, (node) => node.type === 'el' && node.tag === 'button' && node.props['data-date']).length,
      42,
    )
  })

  await t.test('多年归档分离双胶囊仅渲染真实年份、跨年钳位、方向键跨月、Esc 退回与快捷跳转', async () => {
    const updated = []
    const monthChanges = []
    let restored = false

    const { root } = mountHeadless(t, ArchiveDatePicker, {
      modelValue: '2026-11-01',
      latestDate: '2026-11-27',
      earliestDate: '2025-05-01',
      availableDates: ['2026-11-01', '2026-11-27', '2025-06-15'],
      shortcuts: [
        { label: '最新一期', value: '' },
        { label: '半年前', value: '2025-06-15' },
      ],
      'onUpdate:modelValue': (val) => updated.push(val),
      onMonthChange: (ym) => monthChanges.push(ym),
    })

    const mainBtn = findButtonByLabel(root, '归档日期选择：')
    mainBtn.focus = () => {
      restored = true
    }
    mainBtn.props.onClick()
    await nextTick()

    // 1. 方向键 ArrowLeft 从 2026-11-01 跨月回退到 2026-10-31，自动触发 monthChange('2026-10')
    const nov1Btn = findDayButton(root, '2026-11-01')
    nov1Btn.props.onKeydown({ key: 'ArrowLeft', preventDefault() {} })
    await nextTick()
    assert.ok(monthChanges.includes('2026-10'))

    // 2. 点击月份胶囊选到 2026-02，验证 2026-12 禁用
    findButtonByLabel(root, '选择月份').props.onClick()
    await nextTick()
    const month12Btn = findAll(root, (n) => n.tag === 'button' && collectText(n).trim() === '12 月')[0]
    assert.equal(month12Btn.props.disabled, true)
    const month2Btn = findAll(root, (n) => n.tag === 'button' && collectText(n).trim() === '2 月')[0]
    month2Btn.props.onClick()
    await nextTick()
    assert.ok(monthChanges.includes('2026-02'))

    // 3. 点击年份胶囊：仅渲染真实归档年份（2025 年、2026 年），不凑 12 年空网格
    const yearPill = findButtonByLabel(root, '选择年份')
    assert.ok(yearPill)
    yearPill.props.onClick()
    await nextTick()

    const expandedYearPill = findButtonByLabel(root, '收起年份选择')
    assert.equal(hasClass(expandedYearPill, 'is-active'), true)
    const yearGrid = findAll(root, (n) => n.props?.role === 'grid' && n.props['aria-label'] === '选择年份')[0]
    const yearBtns = findAll(yearGrid, (n) => n.tag === 'button')
    assert.deepEqual(
      yearBtns.map((b) => collectText(b).trim()),
      ['2025 年', '2026 年'],
    )

    // 从 2026-02 切换至 2025 年：2025-02 早于 earliestDate 2025-05-01，自动钳位到 2025-05 并直达月历
    yearBtns[0].props.onClick()
    await nextTick()
    assert.ok(monthChanges.includes('2025-05'))
    assert.equal(
      findAll(root, (node) => node.type === 'el' && node.tag === 'button' && node.props['data-date']).length,
      42,
    )

    // 4. Esc 最多两步退回：打开月份网格 -> 1 次 Esc 回月历 -> 2 次 Esc 关闭并还原焦点
    findButtonByLabel(root, '选择月份').props.onClick()
    await nextTick()
    const panel = findAll(root, (n) => n.props?.role === 'dialog')[0]
    const esc = { key: 'Escape', preventDefault() {}, stopPropagation() {} }
    panel.props.onKeydown(esc)
    await nextTick()
    assert.ok(findButtonByLabel(root, '选择月份'))
    assert.equal(findButtonByLabel(root, '收起月份选择'), undefined)
    panel.props.onKeydown(esc)
    await nextTick()
    assert.equal(findAll(root, (n) => n.props?.role === 'dialog').length, 0)
    assert.equal(restored, true)

    // 5. 快捷跳转胶囊点击直接选中并关闭面板
    mainBtn.props.onClick()
    await nextTick()
    const shortcutBtn = findAll(root, (n) => n.tag === 'button' && collectText(n).trim() === '最新一期')[0]
    shortcutBtn.props.onClick()
    await nextTick()
    assert.deepEqual(updated, [''])
    assert.equal(findAll(root, (n) => n.props?.role === 'dialog').length, 0)
  })
})

test('空月底栏文案降噪与迷路兜底“回到最新月”按需显隐', async (t) => {
  await t.test('停留在最新月但当月无内容时仅显示克制文案，不出现缺采计数或回到最新月按钮', async () => {
    const { root } = mountHeadless(t, ArchiveDatePicker, {
      modelValue: '2026-11-27',
      latestDate: '2026-11-27',
      earliestDate: '2026-10-01',
      availableDates: [],
      unitLabel: '期有日报',
      emptyMonthText: '本月暂无日报',
    })

    findButtonByLabel(root, '归档日期选择：').props.onClick()
    await nextTick()

    const text = collectText(root)
    assert.ok(text.includes('本月暂无日报'))
    assert.equal(text.includes('缺采'), false)
    assert.equal(text.includes('回到最新月'), false)
  })

  await t.test('翻到非最新空月时显示“本月暂无日报”与“回到最新月”，点击后切回最新月并隐藏按钮', async () => {
    const monthChanges = []
    const { root } = mountHeadless(t, ArchiveDatePicker, {
      modelValue: '2026-11-27',
      latestDate: '2026-11-27',
      earliestDate: '2026-10-01',
      availableDates: ['2026-11-27', '2026-11-26'],
      unitLabel: '期有日报',
      emptyMonthText: '本月暂无日报',
      onMonthChange: (ym) => monthChanges.push(ym),
    })

    findButtonByLabel(root, '归档日期选择：').props.onClick()
    await nextTick()

    // 在有内容的最新月，不显示回到最新月按钮
    assert.equal(collectText(root).includes('回到最新月'), false)

    // 翻到上个月 2026-10（无任何归档日）
    findButtonByLabel(root, '上个月').props.onClick()
    await nextTick()

    const emptyMonthViewText = collectText(root)
    assert.ok(emptyMonthViewText.includes('本月暂无日报'))
    assert.equal(emptyMonthViewText.includes('缺采'), false)
    const jumpBtn = findAll(root, (n) => n.tag === 'button' && collectText(n).trim() === '回到最新月')[0]
    assert.ok(jumpBtn)

    // 点击“回到最新月”，切回 2026-11 并重新隐藏按钮
    jumpBtn.props.onClick()
    await nextTick()
    assert.equal(monthChanges[monthChanges.length - 1], '2026-11')
    assert.equal(collectText(root).includes('回到最新月'), false)
    assert.ok(collectText(root).includes('本月 2 期有日报'))
  })
})

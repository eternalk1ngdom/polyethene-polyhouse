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

test('年月双层速选网格、快捷跳转、键盘方向键跨月与 Esc 分层关闭', async (t) => {
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

  // 2. 点击标题进入 12 个月网格，再点进入年份网格；验证越界年份与月份禁用
  const titleBtn = findButtonByLabel(root, '切换月份选择')
  titleBtn.props.onClick()
  await nextTick()
  const month12Btn = findAll(root, (n) => n.tag === 'button' && collectText(n).trim() === '12 月')[0]
  assert.equal(month12Btn.props.disabled, true) // 2026-12 晚于 latestDate 2026-11-27

  findButtonByLabel(root, '切换年份选择').props.onClick()
  await nextTick()
  const year2024Btn = findAll(root, (n) => n.tag === 'button' && collectText(n).trim() === '2024')[0]
  assert.equal(year2024Btn.props.disabled, true) // 早于 earliestDate 2025-05-01

  // 3. Esc 分层退回：years -> months -> days -> 关闭并还原焦点
  const panel = findAll(root, (n) => n.props?.role === 'dialog')[0]
  const esc = { key: 'Escape', preventDefault() {}, stopPropagation() {} }
  panel.props.onKeydown(esc)
  await nextTick()
  assert.ok(findButtonByLabel(root, '切换年份选择'))
  panel.props.onKeydown(esc)
  await nextTick()
  assert.ok(findButtonByLabel(root, '切换月份选择'))
  panel.props.onKeydown(esc)
  await nextTick()
  assert.equal(findAll(root, (n) => n.props?.role === 'dialog').length, 0)
  assert.equal(restored, true)

  // 4. 快捷跳转胶囊点击直接选中并关闭面板
  mainBtn.props.onClick()
  await nextTick()
  const shortcutBtn = findAll(root, (n) => n.tag === 'button' && collectText(n).trim() === '最新一期')[0]
  shortcutBtn.props.onClick()
  await nextTick()
  assert.deepEqual(updated, [''])
  assert.equal(findAll(root, (n) => n.props?.role === 'dialog').length, 0)
})

import assert from 'node:assert/strict'
import test from 'node:test'
import { nextTick } from 'vue'
import {
  collectText,
  compileSfcComponent,
  dataUrl,
  findAll,
  mountHeadless,
  patchGlobal,
  typeScriptModuleUrl,
} from '../helpers/test-runtime.mjs'

const vueUrl = import.meta.resolve('vue')
const calendarUrl = await typeScriptModuleUrl(new URL('../../src/core/calendar.ts', import.meta.url))
const inputComposableUrl = await typeScriptModuleUrl(
  new URL('../../src/components/archive-date-picker/useArchiveDateInput.ts', import.meta.url),
  {
    "from 'vue'": `from '${vueUrl}'`,
    'from "vue"': `from '${vueUrl}'`,
    "'../../core/calendar'": `'${calendarUrl}'`,
    '"../../core/calendar"': `'${calendarUrl}'`,
  },
)
const elementPlusStubUrl = dataUrl(`
import { h } from '${vueUrl}'
export const ElIcon = {
  name: 'ElIcon',
  setup(_, { slots, attrs }) {
    return () => h('span', { ...attrs, 'data-el-icon': 'true' }, slots.default ? slots.default() : [])
  },
}
`)
const iconsStubUrl = dataUrl(`
import { h } from '${vueUrl}'
const makeIcon = (name) => ({ name, render: () => h('svg', { 'data-icon': name }) })
export const ArrowDown = makeIcon('ArrowDown')
export const ArrowLeft = makeIcon('ArrowLeft')
export const ArrowRight = makeIcon('ArrowRight')
export const Close = makeIcon('Close')
`)

const ArchiveDatePicker = await compileSfcComponent(
  new URL('../../src/components/ArchiveDatePicker.vue', import.meta.url),
  'archive-date-picker-test',
  {
    "'../core/calendar'": `'${calendarUrl}'`,
    "'./archive-date-picker/useArchiveDateInput'": `'${inputComposableUrl}'`,
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

function findTriggerInput(root) {
  return findAll(
    root,
    (node) => node.type === 'el' && node.tag === 'input' && hasClass(node, 'adp-date-input'),
  )[0]
}

function findCalendarToggleButton(root) {
  return findAll(
    root,
    (node) => node.type === 'el' && node.tag === 'button' && hasClass(node, 'adp-cal-btn'),
  )[0]
}

function findDayButton(root, isoDate) {
  return findAll(
    root,
    (node) => node.type === 'el' && node.tag === 'button' && node.props['data-date'] === isoDate,
  )[0]
}

test('范式 A 紧凑触发器恒定无最新撑宽胶囊、单期隐藏箭头、多期边界正确禁用', async (t) => {
  const cases = [
    {
      name: '空值默认最新：输入框展示最新日期且无“最新”撑宽胶囊，语义保留在 aria-label，禁用下一期',
      props: {
        modelValue: '',
        latestDate: '2026-11-27',
        earliestDate: '2026-10-01',
        availableDates: ['2026-11-27', '2026-11-25'],
      },
      expectInputValue: '2026-11-27',
      expectAriaLatest: true,
      expectPrevDisabled: false,
      expectNextDisabled: true,
      expectNextTitle: '已是最新一期',
    },
    {
      name: '处于最早一期：禁用上一期并附带清晰边界说明，启用下一期',
      props: {
        modelValue: '2026-10-01',
        latestDate: '2026-11-27',
        earliestDate: '2026-10-01',
        availableDates: ['2026-10-01', '2026-10-05'],
      },
      expectInputValue: '2026-10-01',
      expectAriaLatest: false,
      expectPrevDisabled: true,
      expectNextDisabled: false,
      expectPrevTitle: '已是归档最早一期',
    },
    {
      name: '整站仅有 1 期归档：自动隐藏外层左右翻期箭头',
      props: {
        modelValue: '2026-11-27',
        latestDate: '2026-11-27',
        earliestDate: '2026-11-27',
        availableDates: ['2026-11-27'],
      },
      expectInputValue: '2026-11-27',
      expectAriaLatest: true,
      expectNoSteps: true,
    },
    {
      name: '显式关闭左右箭头模式：仅渲染输入+日历一体化外框',
      props: {
        modelValue: '2026-11-25',
        latestDate: '2026-11-27',
        earliestDate: '2026-10-01',
        availableDates: ['2026-11-27', '2026-11-25'],
        showStepButtons: false,
      },
      expectInputValue: '2026-11-25',
      expectAriaLatest: false,
      expectNoSteps: true,
    },
  ]

  for (const item of cases) {
    await t.test(item.name, () => {
      const { root } = mountHeadless(t, ArchiveDatePicker, item.props)
      const input = findTriggerInput(root)
      assert.ok(input)
      assert.equal(input.props.value, item.expectInputValue)
      assert.equal(collectText(root).includes('最新'), false, '触发器内不应再渲染撑宽的“最新”文字胶囊')
      assert.equal(String(input.props['aria-label']).includes('最新一期'), item.expectAriaLatest)

      const prevBtn = findButtonByLabel(root, '上一有内容期')
      const nextBtn = findButtonByLabel(root, '下一有内容期')
      if (item.expectNoSteps) {
        assert.equal(Boolean(prevBtn), false)
        assert.equal(Boolean(nextBtn), false)
      } else {
        assert.equal(Boolean(prevBtn.props.disabled), item.expectPrevDisabled)
        assert.equal(Boolean(nextBtn.props.disabled), item.expectNextDisabled)
        if (item.expectPrevTitle) assert.equal(prevBtn.props.title, item.expectPrevTitle)
        if (item.expectNextTitle) assert.equal(nextBtn.props.title, item.expectNextTitle)
      }
    })
  }
})

test('单月归档静默静态标题且隐藏无效翻月箭头，跨多月与多年自动恢复交互层级', async (t) => {
  await t.test('单月归档：头部干净展示“2026 年 11 月”，无左右翻月按钮与下拉箭头', async () => {
    const { root } = mountHeadless(t, ArchiveDatePicker, {
      modelValue: '2026-11-27',
      latestDate: '2026-11-27',
      earliestDate: '2026-11-18',
      availableDates: ['2026-11-18', '2026-11-27'],
    })

    findCalendarToggleButton(root).props.onClick()
    await nextTick()

    assert.equal(Boolean(findButtonByLabel(root, '上个月')), false)
    assert.equal(Boolean(findButtonByLabel(root, '下个月')), false)
    assert.equal(Boolean(findButtonByLabel(root, '选择年份')), false)
    assert.equal(Boolean(findButtonByLabel(root, '选择月份')), false)
    assert.ok(collectText(root).includes('2026 年 11 月'))
  })

  await t.test('同年跨多月：年份静态展示，月份胶囊与头部翻月箭头自动激活', async () => {
    const { root } = mountHeadless(t, ArchiveDatePicker, {
      modelValue: '2026-11-27',
      latestDate: '2026-11-27',
      earliestDate: '2026-10-01',
      availableDates: ['2026-10-15', '2026-11-27'],
    })

    findCalendarToggleButton(root).props.onClick()
    await nextTick()

    assert.equal(Boolean(findButtonByLabel(root, '选择年份')), false)
    const prevMonthBtn = findButtonByLabel(root, '上个月')
    const nextMonthBtn = findButtonByLabel(root, '下个月')
    assert.ok(prevMonthBtn && nextMonthBtn)
    assert.equal(Boolean(prevMonthBtn.props.disabled), false)
    assert.equal(Boolean(nextMonthBtn.props.disabled), true)
    assert.equal(nextMonthBtn.props.title, '已是归档最新月份')

    // 展开月份网格：单年态下不渲染无意义上下年按钮，10/11 月可选，12 月越界禁用
    const monthPill = findButtonByLabel(root, '选择月份')
    assert.ok(monthPill)
    monthPill.props.onClick()
    await nextTick()

    assert.equal(Boolean(findButtonByLabel(root, '上一年')), false)
    assert.equal(Boolean(findButtonByLabel(root, '下一年')), false)
    const monthGrid = findAll(root, (n) => n.props?.role === 'grid' && n.props['aria-label'] === '选择月份')[0]
    const octBtn = findAll(monthGrid, (n) => n.tag === 'button' && collectText(n).trim() === '10 月')[0]
    const decBtn = findAll(monthGrid, (n) => n.tag === 'button' && collectText(n).trim() === '12 月')[0]
    assert.equal(octBtn.props.disabled, false)
    assert.equal(decBtn.props.disabled, true)
  })

  await t.test('跨多年：分离双胶囊仅渲染真实年份、跨年钳位、方向键跨月、Esc 两步退回与快捷跳转', async () => {
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

    const calBtn = findCalendarToggleButton(root)
    calBtn.focus = () => {
      restored = true
    }
    calBtn.props.onClick()
    await nextTick()

    // 方向键 ArrowLeft 从 2026-11-01 跨月回退到 2026-10-31
    findDayButton(root, '2026-11-01').props.onKeydown({ key: 'ArrowLeft', preventDefault() {} })
    await nextTick()
    assert.ok(monthChanges.includes('2026-10'))

    // 切换到 2026-02，再打开年份网格验证仅渲染真实年份且切到 2025 自动钳位到 2025-05
    findButtonByLabel(root, '选择月份').props.onClick()
    await nextTick()
    const month2Btn = findAll(root, (n) => n.tag === 'button' && collectText(n).trim() === '2 月')[0]
    month2Btn.props.onClick()
    await nextTick()

    findButtonByLabel(root, '选择年份').props.onClick()
    await nextTick()
    // 年份列表态下无意义翻页箭头自动隐藏
    assert.equal(Boolean(findButtonByLabel(root, '上个月')), false)
    assert.equal(Boolean(findButtonByLabel(root, '上一年')), false)

    const yearGrid = findAll(root, (n) => n.props?.role === 'grid' && n.props['aria-label'] === '选择年份')[0]
    const yearBtns = findAll(yearGrid, (n) => n.tag === 'button')
    assert.deepEqual(
      yearBtns.map((b) => collectText(b).trim()),
      ['2025 年', '2026 年'],
    )

    yearBtns[0].props.onClick()
    await nextTick()
    assert.ok(monthChanges.includes('2025-05'))

    // Esc 两步退回：打开月份网格 -> 1 次 Esc 回月历 -> 2 次 Esc 关闭并还原焦点到日历按钮
    findButtonByLabel(root, '选择月份').props.onClick()
    await nextTick()
    const panel = findAll(root, (n) => n.props?.role === 'dialog')[0]
    const esc = { key: 'Escape', preventDefault() {}, stopPropagation() {} }
    panel.props.onKeydown(esc)
    await nextTick()
    assert.ok(findButtonByLabel(root, '选择月份'))
    panel.props.onKeydown(esc)
    await nextTick()
    assert.equal(findAll(root, (n) => n.props?.role === 'dialog').length, 0)
    assert.equal(restored, true)

    // 快捷跳转胶囊点击直接选中并关闭面板
    calBtn.props.onClick()
    await nextTick()
    const shortcutBtn = findAll(root, (n) => n.tag === 'button' && collectText(n).trim() === '最新一期')[0]
    shortcutBtn.props.onClick()
    await nextTick()
    assert.deepEqual(updated, [''])
    assert.equal(findAll(root, (n) => n.props?.role === 'dialog').length, 0)
  })
})

test('手动输入融合：宽容格式直达、非法/越界/缺采内联反馈与一键修复、Esc 还原', async (t) => {
  const updated = []
  const { root } = mountHeadless(t, ArchiveDatePicker, {
    modelValue: '2026-11-27',
    latestDate: '2026-11-27',
    earliestDate: '2026-11-18',
    availableDates: ['2026-11-18', '2026-11-19', '2026-11-21', '2026-11-24', '2026-11-27'],
    loadedMonths: new Set(['2026-11']),
    itemNoun: '日报',
    'onUpdate:modelValue': (val) => updated.push(val),
  })

  const input = findTriggerInput(root)
  const enterEvt = { key: 'Enter', preventDefault() {} }
  const escEvt = { key: 'Escape', preventDefault() {}, stopPropagation() {} }

  // 1. 8 位纯数字回车直达
  input.props.onInput({ target: { value: '20261124' } })
  input.props.onKeydown(enterEvt)
  await nextTick()
  assert.deepEqual(updated, ['2026-11-24'])
  assert.equal(input.props['aria-invalid'], undefined)

  // 2. 非法自然日 2026-02-30：保留原文 + aria-invalid="true" + 报错条，按 Esc 立即还原
  input.props.onInput({ target: { value: '2026-02-30' } })
  input.props.onKeydown(enterEvt)
  await nextTick()
  assert.equal(input.props.value, '2026-02-30')
  assert.equal(input.props['aria-invalid'], 'true')
  assert.ok(collectText(root).includes('请输入有效日期'))

  input.props.onKeydown(escEvt)
  await nextTick()
  assert.equal(input.props.value, '2026-11-27')
  assert.equal(input.props['aria-invalid'], undefined)

  // 3. 缺采日 2026-11-20：提示“2026-11-20 当日暂无日报”并提供一键“转到最近一期 (2026-11-19)”
  input.props.onInput({ target: { value: '2026/11/20' } })
  input.props.onKeydown(enterEvt)
  await nextTick()
  assert.ok(collectText(root).includes('2026-11-20 当日暂无日报'))
  const jumpNearestBtn = findAll(
    root,
    (n) => n.tag === 'button' && collectText(n).includes('转到最近一期 (2026-11-19)'),
  )[0]
  assert.ok(jumpNearestBtn)
  jumpNearestBtn.props.onClick()
  await nextTick()
  assert.deepEqual(updated, ['2026-11-24', '2026-11-19'])
  assert.equal(input.props['aria-invalid'], undefined)
})

test('面板交互：缺采日禁点、区间高亮、loadedMonths 四态底栏与左右跨月步进', async (t) => {
  await t.test('loadedMonths 未包含当前浏览月时显示加载态，绝不误闪“本月暂无日报”', async () => {
    const { root } = mountHeadless(t, ArchiveDatePicker, {
      modelValue: '2026-11-27',
      latestDate: '2026-11-27',
      earliestDate: '2026-10-01',
      availableDates: ['2026-11-27'],
      loadedMonths: new Set(['2026-11']),
      unitLabel: '期有日报',
      emptyMonthText: '本月暂无日报',
    })

    findCalendarToggleButton(root).props.onClick()
    await nextTick()
    assert.ok(collectText(root).includes('本月 1 期有日报'))

    // 翻到未加载的 2026-10：应显示“正在读取本月归档…”，不闪现“本月暂无日报”
    findButtonByLabel(root, '上个月').props.onClick()
    await nextTick()
    const text = collectText(root)
    assert.ok(text.includes('正在读取本月归档…'))
    assert.equal(text.includes('本月暂无日报'), false)
  })

  await t.test('已加载空月显示克制文案与按需“回到最新月”，区间高亮与错误重试完备', async () => {
    const updated = []
    const retries = []
    const { root } = mountHeadless(t, ArchiveDatePicker, {
      modelValue: '2026-11-27',
      latestDate: '2026-11-27',
      earliestDate: '2026-10-01',
      availableDates: ['2026-11-27', '2026-11-24', '2026-11-21'],
      loadedMonths: new Set(['2026-11', '2026-10']),
      highlightRange: { start: '2026-11-21', end: '2026-11-27' },
      unitLabel: '期有日报',
      emptyMonthText: '本月暂无日报',
      'onUpdate:modelValue': (val) => updated.push(val),
    })

    findCalendarToggleButton(root).props.onClick()
    await nextTick()

    const missingBtn = findDayButton(root, '2026-11-25')
    assert.equal(missingBtn.props.disabled, true)
    assert.equal(missingBtn.props['aria-disabled'], 'true')
    assert.equal(hasClass(missingBtn, 'is-in-range'), true)
    missingBtn.props.onClick()
    assert.deepEqual(updated, [])

    assert.equal(hasClass(findDayButton(root, '2026-11-21'), 'is-range-start'), true)
    assert.equal(hasClass(findDayButton(root, '2026-11-27'), 'is-range-end'), true)

    // 翻到已加载的空月 2026-10，展示“本月暂无日报”与“回到最新月”
    findButtonByLabel(root, '上个月').props.onClick()
    await nextTick()
    assert.ok(collectText(root).includes('本月暂无日报'))
    assert.equal(collectText(root).includes('缺采'), false)
    const jumpLatest = findAll(root, (n) => n.tag === 'button' && collectText(n).trim() === '回到最新月')[0]
    assert.ok(jumpLatest)
    jumpLatest.props.onClick()
    await nextTick()
    assert.ok(collectText(root).includes('本月 3 期有日报'))

    // 单独验证错误态重试
    const { root: errRoot } = mountHeadless(t, ArchiveDatePicker, {
      modelValue: '2026-11-27',
      availableDates: [],
      monthError: '本月历史读取失败',
      onRetryMonth: (ym) => retries.push(ym),
    })
    findCalendarToggleButton(errRoot).props.onClick()
    await nextTick()
    findAll(errRoot, (n) => n.tag === 'button' && collectText(n).trim() === '重试')[0].props.onClick()
    assert.deepEqual(retries, ['2026-11'])
  })

  await t.test('左右跳期：已知邻期直跳，未加载跨月交由调用方解析', () => {
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
    findButtonByLabel(root, '上一有内容期').props.onClick()
    assert.deepEqual(updated, [])
    assert.deepEqual(steps, [['prev', null]])

    findButtonByLabel(root, '下一有内容期').props.onClick()
    assert.deepEqual(updated, ['2026-11-27'])
    assert.deepEqual(steps, [
      ['prev', null],
      ['next', '2026-11-27'],
    ])
  })
})

test('架构优化 A5：按需挂载/解绑全局 pointerdown 监听与 Popover 右边缘自动防溢出对齐', async (t) => {
  const listeners = new Map()
  patchGlobal(t, 'document', {
    addEventListener(type, fn) {
      if (!listeners.has(type)) listeners.set(type, new Set())
      listeners.get(type).add(fn)
    },
    removeEventListener(type, fn) {
      listeners.get(type)?.delete(fn)
    },
  })
  patchGlobal(t, 'window', {
    innerWidth: 1000,
    matchMedia: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }),
  })

  const { root } = mountHeadless(t, ArchiveDatePicker, {
    modelValue: '2026-11-27',
    latestDate: '2026-11-27',
    earliestDate: '2026-11-18',
    availableDates: ['2026-11-27'],
  })

  // 1. 初始关闭态零全局监听
  assert.equal(listeners.get('pointerdown')?.size ?? 0, 0)

  // 2. 模拟触发器位于视口右侧（left=800, right=990, innerWidth=1000 → 向右展开 324px 会溢出）
  const rootEl = findAll(root, (n) => hasClass(n, 'adp-root'))[0]
  rootEl.getBoundingClientRect = () => ({ left: 800, right: 990 })

  const calBtn = findCalendarToggleButton(root)
  calBtn.props.onClick()
  await nextTick()

  // 打开后挂载了 1 个 pointerdown 监听，且 Popover 自动启用右对齐类 is-align-right
  assert.equal(listeners.get('pointerdown')?.size ?? 0, 1)
  const panel = findAll(root, (n) => n.props?.role === 'dialog')[0]
  assert.equal(hasClass(panel, 'is-align-right'), true)

  // 关闭面板后立即解绑全局监听
  calBtn.props.onClick()
  await nextTick()
  assert.equal(listeners.get('pointerdown')?.size ?? 0, 0)
})

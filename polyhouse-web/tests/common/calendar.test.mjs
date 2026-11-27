import assert from 'node:assert/strict'
import test from 'node:test'
import { typeScriptModuleUrl } from '../helpers/test-runtime.mjs'

const calendarUrl = await typeScriptModuleUrl(new URL('../../src/core/calendar.ts', import.meta.url))
const calendar = await import(calendarUrl)

test('日期与年月解析严格校验闰年、非法日号与年份下界', async (t) => {
  const validDates = [
    { name: '普通日期', input: '2026-11-27', expected: { year: 2026, month: 11, day: 27 } },
    { name: '闰年 2 月 29 日', input: '2024-02-29', expected: { year: 2024, month: 2, day: 29 } },
    { name: '400 年闰年 2000-02-29', input: '2000-02-29', expected: { year: 2000, month: 2, day: 29 } },
  ]
  for (const item of validDates) {
    await t.test(item.name, () => {
      assert.deepEqual(calendar.parseIsoDate(item.input), item.expected)
      assert.equal(calendar.extractYearMonth(item.input), item.input.slice(0, 7))
    })
  }

  const invalidDates = [
    { name: '平年 2 月 29 日', input: '2026-02-29' },
    { name: '整百年非闰年 1900-02-29', input: '1900-02-29' },
    { name: '4 月 31 日', input: '2026-04-31' },
    { name: '13 月', input: '2026-13-01' },
    { name: '未补零格式', input: '2026-1-01' },
    { name: '年份小于 1000', input: '0999-12-31' },
    { name: '空值', input: null },
  ]
  for (const item of invalidDates) {
    await t.test(`拒绝 ${item.name}`, () => {
      assert.equal(calendar.parseIsoDate(item.input), null)
      assert.equal(calendar.extractYearMonth(item.input), null)
    })
  }
})

test('纯整数日期与月份平移正确跨越闰年与年末边界', async (t) => {
  const dateShiftCases = [
    { name: '跨年回退 6 天（7 日窗口起点）', base: '2026-01-03', delta: -6, expected: '2025-12-28' },
    { name: '闰年 2 月底前进 1 天', base: '2024-02-28', delta: 1, expected: '2024-02-29' },
    { name: '闰年 3 月 1 日回退 1 天', base: '2024-03-01', delta: -1, expected: '2024-02-29' },
    { name: '平年 3 月 1 日回退 1 天', base: '2026-03-01', delta: -1, expected: '2026-02-28' },
    { name: '年末前进 1 天', base: '2026-12-31', delta: 1, expected: '2027-01-01' },
  ]
  for (const item of dateShiftCases) {
    await t.test(item.name, () => {
      assert.equal(calendar.shiftIsoDate(item.base, item.delta), item.expected)
    })
  }

  const monthShiftCases = [
    { name: '1 月回退 1 个月跨到上年 12 月', base: '2026-01', delta: -1, expected: '2025-12' },
    { name: '12 月前进 1 个月跨到次年 1 月', base: '2026-12', delta: 1, expected: '2027-01' },
    { name: '跨年回退 14 个月', base: '2026-02', delta: -14, expected: '2024-12' },
  ]
  for (const item of monthShiftCases) {
    await t.test(item.name, () => {
      assert.equal(calendar.shiftMonth(item.base, item.delta), item.expected)
    })
  }
})

test('42 格月历按周一首列对齐、标出区间高亮带且不将未来日期计入缺采', async (t) => {
  await t.test('2026-11 月 1 日为周日，周一首列需前置 6 天（10-26～10-31）', () => {
    const range = calendar.buildTrailingDateRange('2026-11-27', 7)
    assert.deepEqual(range, { start: '2026-11-21', end: '2026-11-27' })

    const grid = calendar.buildMonthGrid('2026-11', {
      selectedDate: '2026-11-27',
      latestDate: '2026-11-27',
      availableDates: ['2026-11-27', '2026-11-25', '2026-11-21', '2026-10-31'],
      highlightRange: range,
    })

    assert.equal(grid.cells.length, 42)
    assert.equal(grid.daysInMonth, 30)
    assert.equal(grid.cells[0].date, '2026-10-26')
    assert.equal(grid.cells[0].inCurrentMonth, false)
    assert.equal(grid.cells[5].date, '2026-10-31')
    assert.equal(grid.cells[5].available, true)
    assert.equal(grid.cells[6].date, '2026-11-01')
    assert.equal(grid.cells[6].inCurrentMonth, true)

    // 截至 11-27 共 27 天，其中 3 天有日报，故缺采应为 24 天（11-28/29/30 为未来不计缺采）
    assert.equal(grid.availableCount, 3)
    assert.equal(grid.missingCount, 24)

    const startCell = grid.cells.find((c) => c.date === '2026-11-21')
    const midCell = grid.cells.find((c) => c.date === '2026-11-24')
    const endCell = grid.cells.find((c) => c.date === '2026-11-27')
    assert.equal(startCell.inRange, true)
    assert.equal(startCell.isRangeStart, true)
    assert.equal(midCell.inRange, true)
    assert.equal(midCell.available, false)
    assert.equal(endCell.inRange, true)
    assert.equal(endCell.isRangeEnd, true)
    assert.equal(endCell.selected, true)
    assert.equal(endCell.isLatest, true)
  })

  await t.test('首月建档之前（早于 earliestDate）与未来自然日均不计入缺采', () => {
    const grid = calendar.buildMonthGrid('2026-11', {
      earliestDate: '2026-11-18',
      latestDate: '2026-11-27',
      availableDates: [
        '2026-11-18',
        '2026-11-19',
        '2026-11-21',
        '2026-11-22',
        '2026-11-23',
        '2026-11-24',
        '2026-11-25',
        '2026-11-26',
        '2026-11-27',
      ],
    })
    // 11-18～11-27 共 10 天，仅 11-20 真正缺采 1 天；11-01～11-17 建档前与 11-28～11-30 未来均不计入缺采。
    assert.equal(grid.availableCount, 9)
    assert.equal(grid.missingCount, 1)
  })

  await t.test('闰年 2024-02 月包含 29 日且无 latestDate 时按整月计缺采', () => {
    const grid = calendar.buildMonthGrid('2024-02', {
      availableDates: new Set(['2024-02-29']),
    })
    assert.equal(grid.daysInMonth, 29)
    assert.equal(grid.availableCount, 1)
    assert.equal(grid.missingCount, 28)
    assert.ok(grid.cells.some((c) => c.date === '2024-02-29' && c.inCurrentMonth && c.available))
  })
})

test('相邻有值日期查找自动跳过缺采日并正确处理边界与月界判断', async (t) => {
  const dates = ['2026-11-27', '2026-11-24', '2026-11-20', '2026-10-31']
  const cases = [
    { name: '从最新期向前跳，跳过 26/25 缺采日直达 24', cur: '2026-11-27', dir: 'prev', expected: '2026-11-24' },
    { name: '从最新期向后跳到达上界返回 null', cur: '2026-11-27', dir: 'next', expected: null },
    { name: '从 11-20 向前跨月直达 10-31', cur: '2026-11-20', dir: 'prev', expected: '2026-10-31' },
    { name: '从 10-31 向后跨月直达 11-20', cur: '2026-10-31', dir: 'next', expected: '2026-11-20' },
    { name: '从最早日向前跳返回 null', cur: '2026-10-31', dir: 'prev', expected: null },
    { name: '当前位于缺采日时也能定位最近前后期', cur: '2026-11-25', dir: 'prev', expected: '2026-11-24' },
  ]
  for (const item of cases) {
    await t.test(item.name, () => {
      assert.equal(calendar.findAdjacentAvailableDate(item.cur, dates, item.dir), item.expected)
    })
  }

  await t.test('归档月边界判定', () => {
    assert.equal(calendar.isMonthOutOfBounds('2026-09', '2026-10-31', '2026-11-27'), true)
    assert.equal(calendar.isMonthOutOfBounds('2026-10', '2026-10-31', '2026-11-27'), false)
    assert.equal(calendar.isMonthOutOfBounds('2026-11', '2026-10-31', '2026-11-27'), false)
    assert.equal(calendar.isMonthOutOfBounds('2026-12', '2026-10-31', '2026-11-27'), true)
  })
})

test('有界年份推导与跨年年月钳位不凑空年份且防越界', async (t) => {
  const yearCases = [
    {
      name: '同年双边界仅返回单年',
      input: { earliestDate: '2026-11-18', latestDate: '2026-11-27' },
      expected: [2026],
    },
    {
      name: '跨多年双边界按升序返回真实年份区间',
      input: { earliestDate: '2024-12-01', latestDate: '2026-11-27' },
      expected: [2024, 2025, 2026],
    },
    {
      name: '缺失 earliestDate 时结合 availableDates 与 latestDate 推导跨年',
      input: { latestDate: '2026-11-27', availableDates: ['2025-12-30', '2026-11-27'] },
      expected: [2025, 2026],
    },
    {
      name: '全部线索缺失时回退 fallbackYear',
      input: { availableDates: [], fallbackYear: 2026 },
      expected: [2026],
    },
    {
      name: '边界倒置时容错按最小到最大年份覆盖',
      input: { earliestDate: '2026-05-01', latestDate: '2025-05-01' },
      expected: [2025, 2026],
    },
  ]
  for (const item of yearCases) {
    await t.test(item.name, () => {
      assert.deepEqual(calendar.listArchiveYears(item.input), item.expected)
    })
  }

  const clampCases = [
    { name: '合法范围内原样返回', ym: '2025-08', earliest: '2025-05-01', latest: '2026-11-27', expected: '2025-08' },
    { name: '早于最早月时钳位到最早月', ym: '2025-02', earliest: '2025-05-01', latest: '2026-11-27', expected: '2025-05' },
    { name: '晚于最新月时钳位到最新月', ym: '2026-12', earliest: '2025-05-01', latest: '2026-11-27', expected: '2026-11' },
    { name: '非法年月返回 null', ym: '2026-13', earliest: '2025-05-01', latest: '2026-11-27', expected: null },
  ]
  for (const item of clampCases) {
    await t.test(item.name, () => {
      assert.equal(calendar.clampYearMonthToBounds(item.ym, item.earliest, item.latest), item.expected)
    })
  }
})

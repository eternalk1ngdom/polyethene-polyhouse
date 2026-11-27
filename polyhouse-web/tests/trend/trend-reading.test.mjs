import assert from 'node:assert/strict'
import test from 'node:test'
import {
  collectText,
  compileSfcComponent,
  dataUrl,
  findAll,
  mountHeadless,
  typeScriptModuleUrl,
} from '../helpers/test-runtime.mjs'

const presentationUrl = await typeScriptModuleUrl(
  new URL('../../src/features/trend/presentation.ts', import.meta.url),
)
const presentation = await import(presentationUrl)

test('isTrendCategoryFilter 仅接受八大类白名单与未分类，拒绝非法参数', () => {
  const { isTrendCategoryFilter, trendCategoryKeys, UNCATEGORIZED } = presentation

  assert.equal(trendCategoryKeys.length, 8)
  for (const key of trendCategoryKeys) {
    assert.equal(isTrendCategoryFilter(key), true)
  }
  assert.equal(isTrendCategoryFilter(UNCATEGORIZED), true)
  for (const invalid of ['', 'UNKNOWN', 'ai_agent', null, undefined]) {
    assert.equal(isTrendCategoryFilter(invalid), false, `非法筛选值应被拒绝: ${String(invalid)}`)
  }
})

test('projectCategory 与 categoryCounts 正确归类八大类、未分类及未知扩展代号', () => {
  const { categoryCounts, projectCategory, trendCategoryKeys, UNCATEGORIZED } = presentation

  assert.equal(projectCategory('AI_AGENT'), 'AI_AGENT')
  assert.equal(projectCategory(null), UNCATEGORIZED)
  assert.equal(projectCategory(undefined), UNCATEGORIZED)
  assert.equal(projectCategory('FUTURE_CAT'), 'OTHER')

  const counts = categoryCounts(['AI_AGENT', 'AI_AGENT', 'DEVTOOLS', null, undefined, 'NEW_ENUM'])
  assert.equal(counts.get('AI_AGENT'), 2)
  assert.equal(counts.get('DEVTOOLS'), 1)
  assert.equal(counts.get('OTHER'), 1)
  assert.equal(counts.get(UNCATEGORIZED), 2)
  for (const key of trendCategoryKeys) {
    assert.equal(counts.has(key), true)
  }
  assert.equal(counts.get('FRONTEND'), 0)
})

test('siblingProjects 在筛选列表与首尾位置计算准确，不在筛选内时回退整期顺序', () => {
  const { siblingProjects } = presentation

  const all = [
    { fullName: 'org/alpha', rankNum: 1 },
    { fullName: 'org/beta', rankNum: 2 },
    { fullName: 'org/gamma', rankNum: 3 },
    { fullName: 'org/delta', rankNum: 4 },
  ]
  const filtered = [all[0], all[2], all[3]]

  const mid = siblingProjects(filtered, all, 'ORG/GAMMA')
  assert.deepEqual(mid, {
    previous: all[0],
    next: all[3],
    position: 2,
    total: 3,
    filtered: true,
  })

  const first = siblingProjects(filtered, all, 'org/alpha')
  assert.equal(first?.previous, null)
  assert.equal(first?.next, all[2])
  assert.equal(first?.position, 1)

  const last = siblingProjects(filtered, all, 'org/delta')
  assert.equal(last?.previous, all[2])
  assert.equal(last?.next, null)
  assert.equal(last?.position, 3)

  const fallback = siblingProjects(filtered, all, 'org/beta')
  assert.deepEqual(fallback, {
    previous: all[0],
    next: all[2],
    position: 2,
    total: 4,
    filtered: false,
  })

  const unfiltered = siblingProjects(all, all, 'org/beta')
  assert.equal(unfiltered?.filtered, false)
  assert.equal(unfiltered?.total, 4)

  assert.equal(siblingProjects(filtered, all, 'org/missing'), null)
})

test('presenceBadge 与 isNewFacePresence 覆盖 4 类异动身份与连榜降噪阈值', async (t) => {
  const { isNewFacePresence, presenceBadge } = presentation

  const cases = [
    {
      name: '空降前 5 判为新面孔且输出强提醒徽标',
      presence: { status: 'NEW_TOP', streakDays: 1, previousRank: null, rankDelta: null },
      isNew: true,
      badge: { label: '空降前 5', tone: 'new-top', rankRise: null },
    },
    {
      name: '普通新上榜判为新面孔',
      presence: { status: 'NEW', streakDays: 1, previousRank: null, rankDelta: null },
      isNew: true,
      badge: { label: '新上榜', tone: 'new', rankRise: null },
    },
    {
      name: '重回榜单不计入新面孔',
      presence: { status: 'RETURNING', streakDays: 1, previousRank: null, rankDelta: null },
      isNew: false,
      badge: { label: '重回榜单', tone: 'returning', rankRise: null },
    },
    {
      name: '连榜 3 天且排名上升 4 位附轻量上升箭头',
      presence: { status: 'STREAK', streakDays: 3, previousRank: 8, rankDelta: 4 },
      isNew: false,
      badge: { label: '连榜 3 天', tone: 'streak', rankRise: '↑4' },
    },
    {
      name: '连榜 2 天但排名仅上升 2 位不显示上升箭头',
      presence: { status: 'STREAK', streakDays: 2, previousRank: 5, rankDelta: 2 },
      isNew: false,
      badge: { label: '连榜 2 天', tone: 'streak', rankRise: null },
    },
    {
      name: '连榜 7 天仍显示精确天数',
      presence: { status: 'STREAK', streakDays: 7, previousRank: 4, rankDelta: 1 },
      isNew: false,
      badge: { label: '连榜 7 天', tone: 'streak', rankRise: null },
    },
    {
      name: '超过 7 天（前 7 日全勤加今日）统一显示连榜 7+ 天避免认知冲突',
      presence: { status: 'STREAK', streakDays: 8, previousRank: 6, rankDelta: 4 },
      isNew: false,
      badge: { label: '连榜 7+ 天', tone: 'streak', rankRise: '↑4' },
    },
    {
      name: '无历史基线时返回 null 避免误标',
      presence: null,
      isNew: false,
      badge: null,
    },
  ]

  for (const item of cases) {
    await t.test(item.name, () => {
      assert.equal(isNewFacePresence(item.presence), item.isNew)
      assert.deepEqual(presenceBadge(item.presence), item.badge)
    })
  }
})

test('TrendProjectEntry 正确渲染新面孔、连榜上升与无基线隐藏状态', async (t) => {
  const languageColorsUrl = await typeScriptModuleUrl(
    new URL('../../src/features/trend/languageColors.ts', import.meta.url),
  )
  const stubElUrl = dataUrl(`export const ElIcon = { name: 'ElIcon', template: '<span class="icon"><slot /></span>' }`)
  const stubIconsUrl = dataUrl(`export const StarFilled = { name: 'StarFilled', template: '<i />' }`)
  const Entry = await compileSfcComponent(
    new URL('../../src/features/trend/TrendProjectEntry.vue', import.meta.url),
    'trend-project-entry-test',
    {
      "'element-plus'": `'${stubElUrl}'`,
      "'@element-plus/icons-vue'": `'${stubIconsUrl}'`,
      "'./languageColors'": `'${languageColorsUrl}'`,
      "'./presentation'": `'${presentationUrl}'`,
    },
  )

  const baseProject = {
    rankNum: 2,
    repositoryId: '101',
    fullName: 'acme/alpha',
    repoUrl: 'https://github.com/acme/alpha',
    description: 'desc',
    primaryLanguage: 'TypeScript',
    totalStars: 1200,
    starsAdded: 340,
    forksCount: 80,
    license: 'MIT',
    archived: false,
    repoType: 'CODE_PROJECT',
    classificationReason: 'HAS_LANGUAGE',
    metadataStatus: 'AVAILABLE',
    readmeStatus: 'AVAILABLE',
    insightStatus: 'ANALYZED',
    insightReason: null,
    insight: null,
    presence: null,
  }

  const cases = [
    {
      name: '空降前 5 置于标题区，底部元信息区保持分类胶囊在前、语言在后',
      presence: { status: 'NEW_TOP', streakDays: 1, previousRank: null, rankDelta: null },
      insight: {
        category: 'AI_AGENT',
        definition: '测试定义',
        painPoint: '痛点',
        highlights: [],
        limitations: [],
        risks: [],
        trendSignal: null,
        generatedAt: '2026-11-26T00:00:00Z',
        model: 'deepseek-flash',
        ruleVersion: 'v1',
      },
      expectTitleText: ['空降前 5'],
      expectMetaOrder: ['AI 智能体', 'TypeScript'],
      forbidMetaText: ['空降前 5', '连榜'],
      expectMetricsText: ['+340'],
      forbidText: ['连榜', '↑'],
    },
    {
      name: '连榜 8 天且上升 5 位降噪置于右侧热度列显示连榜 7+ 天与上升箭头，不混入底部元信息',
      presence: { status: 'STREAK', streakDays: 8, previousRank: 7, rankDelta: 5 },
      insight: null,
      expectTitleText: ['acme/', 'alpha'],
      expectMetaOrder: ['TypeScript'],
      forbidMetaText: ['连榜', '↑5'],
      expectMetricsText: ['+340', '连榜 7+ 天', '↑5'],
      forbidText: ['新上榜', '空降前 5', '连榜 8 天'],
    },
    {
      name: '无前序历史时不渲染任何异动徽标',
      presence: null,
      insight: null,
      expectTitleText: ['acme/', 'alpha'],
      expectMetaOrder: ['TypeScript'],
      forbidMetaText: ['新上榜', '连榜'],
      expectMetricsText: ['+340'],
      forbidText: ['新上榜', '空降前 5', '重回榜单', '连榜'],
    },
  ]

  for (const item of cases) {
    await t.test(item.name, async (st) => {
      const mounted = await mountHeadless(st, Entry, {
        project: { ...baseProject, insight: item.insight, presence: item.presence },
        href: '/star-tide/2026-11-26/acme/alpha',
      })
      const text = collectText(mounted.root)
      const titleNode = findAll(mounted.root, (n) => n.props?.class === 'title-line')[0]
      const metaNode = findAll(mounted.root, (n) => n.props?.class === 'meta-tags')[0]
      const metricsNode = findAll(mounted.root, (n) => n.props?.class === 'metrics')[0]
      assert.ok(titleNode && metaNode && metricsNode, '应存在标题区、元信息区与右侧热度列')

      const titleText = collectText(titleNode)
      const metaText = collectText(metaNode)
      const metricsText = collectText(metricsNode)
      for (const expected of item.expectTitleText) {
        assert.ok(titleText.includes(expected), `标题区应包含 "${expected}"，实际：${titleText}`)
      }
      let cursor = -1
      for (const expected of item.expectMetaOrder) {
        const idx = metaText.indexOf(expected)
        assert.ok(idx > cursor, `底部元信息应按序包含 "${expected}"，实际：${metaText}`)
        cursor = idx
      }
      for (const forbidden of item.forbidMetaText) {
        assert.ok(!metaText.includes(forbidden), `底部元信息不应包含 "${forbidden}"，实际：${metaText}`)
      }
      for (const expected of item.expectMetricsText) {
        assert.ok(metricsText.includes(expected), `右侧热度列应包含 "${expected}"，实际：${metricsText}`)
      }
      for (const forbidden of item.forbidText) {
        assert.ok(!text.includes(forbidden), `不应包含 "${forbidden}"，实际文本：${text}`)
      }
    })
  }
})

test('buildTrendHeaderMeta 完整适配开源日报与近 7 日风向两视图及跨年/空期号边界', async (t) => {
  const { buildTrendHeaderMeta } = presentation
  const cases = [
    {
      name: '开源日报带有效日期：输出期号副文与年份+月日水印',
      input: { isWindowView: false, date: '2026-11-27' },
      expected: {
        subtitle: '开源日报 · 2026-11-27 期',
        watermark: { eyebrow: '2026', dayMark: '11.27' },
      },
    },
    {
      name: '开源日报尚未加载日期：降级纯视图名且不渲染水印',
      input: { isWindowView: false, date: '' },
      expected: {
        subtitle: '开源日报',
        watermark: null,
      },
    },
    {
      name: '近 7 日风向同年窗口：右侧日期省去年份且水印标明 7D',
      input: {
        isWindowView: true,
        date: '2026-11-27',
        windowStart: '2026-11-21',
        windowEnd: '2026-11-27',
      },
      expected: {
        subtitle: '近 7 日风向 · 2026-11-21 — 11-27',
        watermark: { eyebrow: '7D · 2026', dayMark: '11.27' },
      },
    },
    {
      name: '近 7 日风向跨年窗口：完整保留起止年份避免跨年歧义',
      input: {
        isWindowView: true,
        date: '2027-01-03',
        windowStart: '2026-12-28',
        windowEnd: '2027-01-03',
      },
      expected: {
        subtitle: '近 7 日风向 · 2026-12-28 — 2027-01-03',
        watermark: { eyebrow: '7D · 2027', dayMark: '01.03' },
      },
    },
    {
      name: '近 7 日风向尚无窗口区间：优雅回退视图名并保留锚点水印',
      input: { isWindowView: true, date: '2026-11-27', windowStart: null, windowEnd: null },
      expected: {
        subtitle: '近 7 日风向',
        watermark: { eyebrow: '7D · 2026', dayMark: '11.27' },
      },
    },
  ]

  for (const item of cases) {
    await t.test(item.name, () => {
      assert.deepEqual(buildTrendHeaderMeta(item.input), item.expected)
    })
  }
})

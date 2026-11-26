import assert from 'node:assert/strict'
import test from 'node:test'
import {
  collectText,
  compileSfcComponent,
  dataUrl,
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
      name: '空降前 5 展示强提醒徽标',
      presence: { status: 'NEW_TOP', streakDays: 1, previousRank: null, rankDelta: null },
      expectText: ['空降前 5'],
      forbidText: ['连榜', '↑'],
    },
    {
      name: '连榜 4 天且上升 5 位展示连榜天数与上升幅度',
      presence: { status: 'STREAK', streakDays: 4, previousRank: 7, rankDelta: 5 },
      expectText: ['连榜 4 天', '↑5'],
      forbidText: ['新上榜', '空降前 5'],
    },
    {
      name: '无前序历史时不渲染任何异动徽标',
      presence: null,
      expectText: ['acme/', 'alpha', 'TypeScript'],
      forbidText: ['新上榜', '空降前 5', '重回榜单', '连榜'],
    },
  ]

  for (const item of cases) {
    await t.test(item.name, async (st) => {
      const mounted = await mountHeadless(st, Entry, {
        project: { ...baseProject, presence: item.presence },
        href: '/star-tide/2026-11-26/acme/alpha',
      })
      const text = collectText(mounted.root)
      for (const expected of item.expectText) {
        assert.ok(text.includes(expected), `应包含 "${expected}"，实际文本：${text}`)
      }
      for (const forbidden of item.forbidText) {
        assert.ok(!text.includes(forbidden), `不应包含 "${forbidden}"，实际文本：${text}`)
      }
    })
  }
})

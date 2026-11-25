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

const presentationUrl = await typeScriptModuleUrl(
  new URL('../../src/features/trend/presentation.ts', import.meta.url),
)
const languageColorsUrl = await typeScriptModuleUrl(
  new URL('../../src/features/trend/languageColors.ts', import.meta.url),
)

function makeWindowPayload(patch = {}) {
  return {
    periodType: 'DAILY',
    anchorDate: '2026-11-22',
    windowStart: '2026-11-16',
    windowEnd: '2026-11-22',
    windowDays: 7,
    coveredDays: 5,
    coveredDates: ['2026-11-22', '2026-11-21', '2026-11-19', '2026-11-18', '2026-11-17'],
    missingDates: ['2026-11-20', '2026-11-16'],
    sampleStatus: 'SUFFICIENT',
    anchorSummary: {
      totalItems: 4,
      classifiedItems: 2,
      unclassifiedItems: 2,
      docListItems: 1,
      missingInsightItems: 1,
    },
    windowSummary: {
      uniqueRepoCount: 7,
      totalAppearances: 10,
      classifiedRepoCount: 5,
      unclassifiedRepoCount: 2,
      docListRepoCount: 1,
      missingInsightRepoCount: 1,
      starsKnownRepoCount: 6,
      staleClassifiedRepoCount: 1,
      oldestStaleAnalyzedAt: '2026-11-15T08:00:00Z',
      unverifiedIdentityCount: 1,
      radar3ReadyCount: 3,
    },
    categories: [
      {
        category: 'DEVTOOLS',
        anchorDayCount: 1,
        anchorDayShare: 0.5,
        windowUniqueCount: 2,
        windowAppearances: 4,
        windowShare: 0.4,
        shareDelta: 0.25,
        starsSampleCount: 2,
        medianStarsAdded: 200.5,
        maxRepoStarsAdded: 300,
        staleClassifiedCount: 0,
        oldestStaleAnalyzedAt: null,
        sampleRepos: [
          {
            repositoryId: '101',
            identityVerified: true,
            fullName: 'acme/agent-new',
            repoUrl: 'https://github.com/acme/agent-new',
            inAnchorDay: true,
            appearanceDays: 3,
            latestRank: 1,
            latestStarsAdded: 300,
          },
          {
            repositoryId: '103',
            identityVerified: true,
            fullName: 'acme/cli',
            repoUrl: 'https://github.com/acme/cli',
            inAnchorDay: false,
            appearanceDays: 1,
            latestRank: 2,
            latestStarsAdded: 100,
          },
          {
            repositoryId: '104',
            identityVerified: true,
            fullName: 'acme/lint',
            repoUrl: 'https://github.com/acme/lint',
            inAnchorDay: true,
            appearanceDays: 1,
            latestRank: 3,
            latestStarsAdded: 90,
          },
          {
            repositoryId: '109',
            identityVerified: true,
            fullName: 'acme/fourth-tool',
            repoUrl: 'https://github.com/acme/fourth-tool',
            inAnchorDay: false,
            appearanceDays: 1,
            latestRank: 4,
            latestStarsAdded: 80,
          },
        ],
      },
    ],
    recurringRepos: [
      {
        repositoryId: '101',
        identityVerified: true,
        fullName: 'acme/agent-new',
        repoUrl: 'https://github.com/acme/agent-new',
        inAnchorDay: true,
        category: 'DEVTOOLS',
        definition: '用于整理代理插件目录。',
        appearanceDays: 3,
        consecutiveDays: 2,
        anchorStreakDays: 2,
        latestRank: 1,
        bestRank: 1,
        latestStarsAdded: 300,
        medianStarsAdded: 200,
        appearedDates: ['2026-11-22', '2026-11-21', '2026-11-19'],
        insightStatus: 'ANALYZED',
        insightAnalyzedAt: '2026-11-22T02:00:00Z',
      },
      {
        repositoryId: '105',
        identityVerified: true,
        fullName: 'acme/ui',
        repoUrl: 'https://github.com/acme/ui',
        inAnchorDay: false,
        category: 'FRONTEND',
        definition: '轻量前端组件集。',
        appearanceDays: 2,
        consecutiveDays: 2,
        anchorStreakDays: 0,
        latestRank: 2,
        bestRank: 1,
        latestStarsAdded: 400,
        medianStarsAdded: 375.5,
        appearedDates: ['2026-11-19', '2026-11-18'],
        insightStatus: 'ANALYZED',
        insightAnalyzedAt: '2026-11-19T02:00:00Z',
      },
    ],
    ...patch,
  }
}

test('presentation 分类与统计格式化函数处理缺失值、.5 中位数与占比变化', async () => {
  const {
    categoryLabel,
    categoryTone,
    trendCategoryKeys,
    trendPercent,
    trendDeltaPercent,
    trendMedianNumber,
    shortTrendDate,
  } = await import(presentationUrl)

  assert.equal(categoryLabel('DEVTOOLS'), '开发工具')
  assert.equal(categoryLabel('AI_AGENT'), 'AI 智能体')
  assert.equal(categoryLabel(null), '未分类')
  assert.equal(categoryLabel('UNKNOWN_NEW'), '其他方向')

  for (const key of trendCategoryKeys) {
    const tone = categoryTone(key)
    assert.match(tone.accent, /^#[0-9a-f]{6}$/i)
    assert.match(tone.bg, /^#[0-9a-f]{6}$/i)
    assert.match(tone.ink, /^#[0-9a-f]{6}$/i)
  }
  assert.deepEqual(categoryTone('UNKNOWN_NEW'), categoryTone('OTHER'))
  assert.deepEqual(categoryTone(null), categoryTone('OTHER'))

  assert.equal(trendPercent(0.4), '40%')
  assert.equal(trendPercent(null), '—')
  assert.equal(trendDeltaPercent(0.25), '+25 个百分点')
  assert.equal(trendDeltaPercent(-0.12), '-12 个百分点')
  assert.equal(trendDeltaPercent(0), '持平')
  assert.equal(trendDeltaPercent(null), '—')

  assert.equal(trendMedianNumber(null), '未知')
  assert.equal(trendMedianNumber(1200), '1,200')
  assert.equal(trendMedianNumber(1200.5), '1,200.5')
  assert.equal(shortTrendDate('2026-11-22'), '11-22')
})

test('languageColor 返回 GitHub Linguist 官方主色与未知/冷门语言兜底色', async () => {
  const { languageColor } = await import(languageColorsUrl)

  assert.equal(languageColor('Python'), '#3572A5')
  assert.equal(languageColor('typescript'), '#3178C6')
  assert.equal(languageColor('C++'), '#f34b7d')
  assert.equal(languageColor('Shell'), '#89e051')
  assert.equal(languageColor(null), '#b7c3ca')
  assert.equal(languageColor('   '), '#b7c3ca')
  assert.equal(languageColor('ObscureLangXYZ'), '#6e7f8d')
})

test('getTrendWindow 严格解码 7 日统计结构并拒绝越界比例或非法仓库链接', async (t) => {
  const mockIndexUrl = dataUrl(`
    export class ApiError extends Error {
      constructor(message, status) { super(message); this.status = status }
    }
    export function isRecord(value) {
      return typeof value === 'object' && value !== null && !Array.isArray(value)
    }
    export async function apiRequest(_config, decode) {
      return decode(globalThis.__polyhouseWindowTestPayload)
    }
  `)
  const trendApiUrl = await typeScriptModuleUrl(
    new URL('../../src/api/trend.ts', import.meta.url),
    { "from './index'": `from '${mockIndexUrl}'` },
  )
  const { getTrendWindow } = await import(trendApiUrl)
  patchGlobal(t, '__polyhouseWindowTestPayload', makeWindowPayload())

  const ok = await getTrendWindow('2026-11-22')
  assert.equal(ok.coveredDays, 5)
  assert.equal(ok.categories[0].medianStarsAdded, 200.5)
  assert.equal(ok.recurringRepos[1].medianStarsAdded, 375.5)

  for (const [label, mutate] of [
    [
      '非法比例 > 1',
      (payload) => {
        payload.categories[0].windowShare = 1.5
      },
    ],
    [
      '非白名单 GitHub 链接',
      (payload) => {
        payload.recurringRepos[0].repoUrl = 'https://evil.example/acme/agent-new'
      },
    ],
  ]) {
    await t.test(`拒绝${label}`, async () => {
      const bad = makeWindowPayload()
      mutate(bad)
      globalThis.__polyhouseWindowTestPayload = bad
      await assert.rejects(() => getTrendWindow('2026-11-22'), /数据不完整/)
    })
  }
})

test('TrendWindowOverview 整版报刊渲染区分充足样本与小样本展示、占比条及链接去向', async (t) => {
  const iconsUrl = dataUrl('export const ElIcon = {}, TopRight = {}, StarFilled = {};')
  const briefingStubUrl = dataUrl('export default { render() { return null } };')
  const OverviewComp = await compileSfcComponent(
    new URL('../../src/features/trend/TrendWindowOverview.vue', import.meta.url),
    'trend-window-overview-test',
    {
      "from 'element-plus'": `from '${iconsUrl}'`,
      "from '@element-plus/icons-vue'": `from '${iconsUrl}'`,
      "from './presentation'": `from '${presentationUrl}'`,
      "from './TrendWindowBriefing.vue'": `from '${briefingStubUrl}'`,
    },
  )

  await t.test('充足样本默认折叠超长列表，展开后露出全部项目并区分站内/外链', async (st) => {
    const { root } = mountHeadless(st, OverviewComp, {
      stats: makeWindowPayload(),
      loading: false,
      error: '',
      resolveDetailHref: (name) => `#/tools/star-tide/2026-11-22/${name}?view=window`,
    })
    const fullText = collectText(root)
    assert.ok(fullText.includes('近 7 日榜内风向观察'))
    assert.ok(fullText.includes('开发工具'))
    assert.ok(fullText.includes('较前序 +25 个百分点'))
    assert.ok(fullText.includes('+375.5'))
    assert.ok(fullText.includes('旧稿归类提示'))
    assert.ok(fullText.includes('缺采日期：11-20、11-16'))
    assert.ok(findAll(root, (n) => n.props?.class === 'share-track').length > 0)
    assert.ok(fullText.includes('acme/lint'))
    assert.ok(!fullText.includes('acme/fourth-tool'))

    const expandBtn = findAll(
      root,
      (n) => n.tag === 'button' && n.props?.class === 'more-repos-btn',
    )[0]
    assert.ok(expandBtn)
    assert.equal(collectText(expandBtn).trim(), '展开全部 4 个')
    expandBtn.props.onClick()
    await nextTick()
    assert.ok(collectText(root).includes('acme/fourth-tool'))
    assert.equal(collectText(expandBtn).trim(), '收起')

    const links = findAll(root, (n) => n.tag === 'a')
    const inDayLink = links.find((a) => collectText(a).includes('acme/agent-new'))
    const offDayLink = links.find((a) => collectText(a).includes('acme/ui'))
    assert.equal(inDayLink.props.href, '#/tools/star-tide/2026-11-22/acme/agent-new?view=window')
    assert.equal(offDayLink.props.href, 'https://github.com/acme/ui')
    assert.equal(offDayLink.props.target, '_blank')
  })

  await t.test('小样本展示温和警告且不显示占比条与较前序变化', (st) => {
    const { root } = mountHeadless(st, OverviewComp, {
      stats: makeWindowPayload({
        coveredDays: 2,
        coveredDates: ['2026-11-22', '2026-11-21'],
        missingDates: ['2026-11-20', '2026-11-19', '2026-11-18', '2026-11-17', '2026-11-16'],
        sampleStatus: 'INSUFFICIENT',
      }),
      loading: false,
      error: '',
      resolveDetailHref: (name) => `#/tools/star-tide/2026-11-22/${name}?view=window`,
    })
    const smallText = collectText(root)
    assert.ok(smallText.includes('样本偏少'))
    assert.ok(smallText.includes('不足 3 天'))
    assert.ok(!smallText.includes('较前序'))
    assert.equal(findAll(root, (n) => n.props?.class === 'share-track').length, 0)
  })
})

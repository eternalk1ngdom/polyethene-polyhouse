import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h } from 'vue'
import { compileScript, compileTemplate, parse } from 'vue/compiler-sfc'
import ts from 'typescript'

const dataUrl = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`

async function transpileTs(relativePath, replacements = {}) {
  const source = await readFile(new URL(relativePath, import.meta.url), 'utf8')
  let code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(replacements)) code = code.replaceAll(from, to)
  return dataUrl(code)
}

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
  const presentationUrl = await transpileTs('../../src/features/trend/presentation.ts')
  const {
    categoryLabel,
    trendPercent,
    trendDeltaPercent,
    trendMedianNumber,
    shortTrendDate,
  } = await import(presentationUrl)

  assert.equal(categoryLabel('DEVTOOLS'), '开发工具')
  assert.equal(categoryLabel('AI_AGENT'), 'AI 智能体')
  assert.equal(categoryLabel(null), '未分类')
  assert.equal(categoryLabel('UNKNOWN_NEW'), '其他方向')

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

test('getTrendWindow 严格解码 7 日统计结构并拒绝越界比例或非法仓库链接', async () => {
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
  const trendApiUrl = await transpileTs('../../src/api/trend.ts', {
    "from './index'": `from '${mockIndexUrl}'`,
  })
  const { getTrendWindow } = await import(trendApiUrl)

  globalThis.__polyhouseWindowTestPayload = makeWindowPayload()
  const ok = await getTrendWindow('2026-11-22')
  assert.equal(ok.coveredDays, 5)
  assert.equal(ok.categories[0].medianStarsAdded, 200.5)
  assert.equal(ok.recurringRepos[1].medianStarsAdded, 375.5)

  // 非法比例 > 1 必须被拒绝
  const badRatio = makeWindowPayload()
  badRatio.categories[0].windowShare = 1.5
  globalThis.__polyhouseWindowTestPayload = badRatio
  await assert.rejects(() => getTrendWindow('2026-11-22'), /数据不完整/)

  // 非白名单 GitHub 链接必须被拒绝
  const badRepo = makeWindowPayload()
  badRepo.recurringRepos[0].repoUrl = 'https://evil.example/acme/agent-new'
  globalThis.__polyhouseWindowTestPayload = badRepo
  await assert.rejects(() => getTrendWindow('2026-11-22'), /数据不完整/)

  delete globalThis.__polyhouseWindowTestPayload
})

test('TrendWindowOverview 整版报刊渲染区分充足样本与小样本展示、占比条及链接去向', async () => {
  const presentationUrl = await transpileTs('../../src/features/trend/presentation.ts')
  const iconsUrl = dataUrl('export const ElIcon = {}, TopRight = {}, StarFilled = {};')
  const { descriptor } = parse(
    await readFile(new URL('../../src/features/trend/TrendWindowOverview.vue', import.meta.url), 'utf8'),
  )
  const id = 'trend-window-overview-test'
  const compiledScript = compileScript(descriptor, { id })
  const scriptCode = ts
    .transpileModule(compiledScript.content, {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    })
    .outputText.replaceAll("from 'vue'", `from '${import.meta.resolve('vue')}'`)
    .replaceAll("from 'element-plus'", `from '${iconsUrl}'`)
    .replaceAll("from '@element-plus/icons-vue'", `from '${iconsUrl}'`)
    .replaceAll("from './presentation'", `from '${presentationUrl}'`)

  const templateCode = compileTemplate({
    id,
    filename: 'TrendWindowOverview.vue',
    source: descriptor.template.content,
    compilerOptions: { bindingMetadata: compiledScript.bindings },
  }).code.replaceAll('from "vue"', `from '${import.meta.resolve('vue')}'`)

  const { default: component } = await import(dataUrl(scriptCode))
  const { render } = await import(dataUrl(templateCode))

  function mountOverview(stats) {
    const createNode = (tag) => ({ tag, attrs: {}, children: [], text: '', parent: null })
    const renderer = createRenderer({
      createElement: (tag) => createNode(tag),
      createText: (text) => ({ tag: '#text', text, parent: null }),
      createComment: () => ({ tag: '#comment', text: '', parent: null }),
      insert(child, parent, anchor) {
        if (!parent) return
        child.parent = parent
        if (anchor) {
          const index = parent.children.indexOf(anchor)
          if (index >= 0) {
            parent.children.splice(index, 0, child)
            return
          }
        }
        parent.children.push(child)
      },
      remove(child) {
        if (!child.parent) return
        const idx = child.parent.children.indexOf(child)
        if (idx >= 0) child.parent.children.splice(idx, 1)
        child.parent = null
      },
      setText(node, text) {
        node.text = text
      },
      setElementText(node, text) {
        node.children = [{ tag: '#text', text, parent: node }]
      },
      patchProp(node, key, _prev, next) {
        node.attrs[key] = next
      },
      parentNode: (node) => node.parent ?? null,
      nextSibling: () => null,
    })
    const root = createNode('root')
    const app = renderer.createApp({
      render: () =>
        h(
          { ...component, render },
          {
            stats,
            loading: false,
            error: '',
            resolveDetailHref: (name) => `#/tools/star-tide/2026-11-22/${name}?view=window`,
          },
        ),
    })
    app.mount(root)
    return { root, unmount: () => app.unmount() }
  }

  function findAll(node, predicate, out = []) {
    if (predicate(node)) out.push(node)
    for (const child of node.children || []) findAll(child, predicate, out)
    return out
  }
  const textOf = (node) =>
    node.tag === '#text'
      ? node.text
      : (node.children || []).map((child) => textOf(child)).join('')

  // 1. 充足样本：直接铺开 KPI、占比条、较前序变化、旧稿提示、多日上榜条目与站内/外链分化
  const fullMount = mountOverview(makeWindowPayload())
  const fullText = textOf(fullMount.root)
  assert.ok(fullText.includes('近 7 日榜内风向观察'))
  assert.ok(fullText.includes('开发工具'))
  assert.ok(fullText.includes('较前序 +25 个百分点'))
  assert.ok(fullText.includes('+375.5'))
  assert.ok(fullText.includes('旧稿归类提示'))
  assert.ok(fullText.includes('缺采日期：11-20、11-16'))
  assert.ok(findAll(fullMount.root, (n) => n.attrs?.class === 'share-track').length > 0)

  const links = findAll(fullMount.root, (n) => n.tag === 'a')
  const inDayLink = links.find((a) => textOf(a).includes('acme/agent-new'))
  const offDayLink = links.find((a) => textOf(a).includes('acme/ui'))
  assert.equal(inDayLink.attrs.href, '#/tools/star-tide/2026-11-22/acme/agent-new?view=window')
  assert.equal(offDayLink.attrs.href, 'https://github.com/acme/ui')
  assert.equal(offDayLink.attrs.target, '_blank')
  fullMount.unmount()

  // 2. 小样本（INSUFFICIENT）：展示温和警告，不显示占比条与较前序变化
  const smallMount = mountOverview(
    makeWindowPayload({
      coveredDays: 2,
      coveredDates: ['2026-11-22', '2026-11-21'],
      missingDates: ['2026-11-20', '2026-11-19', '2026-11-18', '2026-11-17', '2026-11-16'],
      sampleStatus: 'INSUFFICIENT',
    }),
  )
  const smallText = textOf(smallMount.root)
  assert.ok(smallText.includes('样本偏少'))
  assert.ok(smallText.includes('不足 3 天'))
  assert.ok(!smallText.includes('较前序'))
  assert.equal(findAll(smallMount.root, (n) => n.attrs?.class === 'share-track').length, 0)
  smallMount.unmount()
})

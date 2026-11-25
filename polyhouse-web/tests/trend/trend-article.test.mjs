import assert from 'node:assert/strict'
import test from 'node:test'
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

function makeReportPayload(insightPatch = {}, omitOptional = false, projectPatch = {}) {
  const insight = {
    category: 'DEVTOOLS',
    definition: '开发者可以用这个仓库把工具打包成 Cursor 插件。',
    painPoint: '第一段交代手头情况与卡点。\n\n第二段交代处理过程与拿到什么。',
    comparison: null,
    highlights: ['单插件独立目录结构'],
    documentedLimitations: [],
    inferredRisks: ['具体插件效果需自行核对'],
    trendSignal: 'AI 编程工具正走向团队可复用的插件生态。',
    descriptionZh: '开发者可将工具打包为 Cursor 插件的示例与目录仓库。',
    analyzedAt: '2026-11-21T10:00:00Z',
    readmeVersion: 'a'.repeat(40),
    model: 'deepseek-flash',
    ruleVersion: 'radar-4',
    ...insightPatch,
  }
  if (omitOptional) {
    delete insight.trendSignal
    delete insight.descriptionZh
  }
  return {
    reportId: '11111111-2222-3333-4444-555555555555',
    periodType: 'DAILY',
    periodKey: '2026-11-21',
    capturedAt: '2026-11-21T09:50:00Z',
    publishedAt: '2026-11-21T10:01:00Z',
    sourceUrl: 'https://github.com/trending?since=daily',
    insightComplete: true,
    items: [
      {
        rankNum: 1,
        repositoryId: '1140229822',
        fullName: 'cursor/plugins',
        repoUrl: 'https://github.com/cursor/plugins',
        description: 'Developers can package tools for Cursor as plugins.',
        primaryLanguage: 'TypeScript',
        totalStars: 17639,
        starsAdded: 292,
        forksCount: 120,
        license: null,
        archived: false,
        repoType: 'CODE_PROJECT',
        classificationReason: 'MANIFEST',
        metadataStatus: 'AVAILABLE',
        readmeStatus: 'AVAILABLE',
        insightStatus: 'ANALYZED',
        insightReason: null,
        insight,
        ...projectPatch,
      },
    ],
  }
}

test('trendParagraphs 正确拆分单段、多段和 Windows 换行，不丢弃纯空白兜底', async () => {
  const { trendParagraphs } = await import(presentationUrl)

  assert.deepEqual(trendParagraphs('单段说明'), ['单段说明'])
  assert.deepEqual(trendParagraphs('第一段内容。\n\n第二段内容。'), [
    '第一段内容。',
    '第二段内容。',
  ])
  assert.deepEqual(trendParagraphs('  第一段  \r\n\r\n\r\n  第二段  '), ['第一段', '第二段'])
  assert.deepEqual(trendParagraphs('   '), ['   '])
})

test('trend API 解码支持 radar-4 原始简介译文与技术风向字段并兼容省略该字段的历史归档', async (t) => {
  const mockIndexUrl = dataUrl(`
    export class ApiError extends Error {
      constructor(message, status) { super(message); this.status = status }
    }
    export function isRecord(value) {
      return typeof value === 'object' && value !== null && !Array.isArray(value)
    }
    export async function apiRequest(_config, decode) {
      return decode(globalThis.__polyhouseTrendTestPayload)
    }
  `)
  const trendApiUrl = await typeScriptModuleUrl(
    new URL('../../src/api/trend.ts', import.meta.url),
    { "from './index'": `from '${mockIndexUrl}'` },
  )
  const { getTrendReport } = await import(trendApiUrl)
  patchGlobal(t, '__polyhouseTrendTestPayload', makeReportPayload())

  // 新规则 radar-4 包含 trendSignal 与 descriptionZh
  const v4 = await getTrendReport('2026-11-21')
  assert.equal(v4.items[0].insight.trendSignal, 'AI 编程工具正走向团队可复用的插件生态。')
  assert.equal(
    v4.items[0].insight.descriptionZh,
    '开发者可将工具打包为 Cursor 插件的示例与目录仓库。',
  )

  // 旧规则 radar-1 / radar-2 / radar-3 缺少可选字段时归一化为 null
  globalThis.__polyhouseTrendTestPayload = makeReportPayload({ ruleVersion: 'radar-1' }, true)
  const v1 = await getTrendReport('2026-11-21')
  assert.equal(v1.items[0].insight.trendSignal, null)
  assert.equal(v1.items[0].insight.descriptionZh, null)

  // 非字符串类型的 trendSignal 或 descriptionZh 必须被拒绝
  for (const [label, patch] of [
    ['非字符串 trendSignal', { trendSignal: 123 }],
    ['非字符串 descriptionZh', { descriptionZh: 456 }],
  ]) {
    await t.test(`拒绝${label}`, async () => {
      globalThis.__polyhouseTrendTestPayload = makeReportPayload(patch)
      await assert.rejects(() => getTrendReport('2026-11-21'), /数据不完整/)
    })
  }
})

test('TrendProjectDetail 渲染分段正文、新标题与可选的技术风向观察卡片', async (t) => {
  const iconsUrl = dataUrl('export const ElIcon = {}, TopRight = {}, StarFilled = {};')
  const assetUrl = dataUrl('export default "voyage.webp";')
  const DetailComp = await compileSfcComponent(
    new URL('../../src/features/trend/TrendProjectDetail.vue', import.meta.url),
    'trend-project-detail-test',
    {
      "from 'element-plus'": `from '${iconsUrl}'`,
      "from '@element-plus/icons-vue'": `from '${iconsUrl}'`,
      "from '../../assets/startide-voyage.webp'": `from '${assetUrl}'`,
      "from './languageColors'": `from '${languageColorsUrl}'`,
      "from './presentation'": `from '${presentationUrl}'`,
    },
  )

  // 1. 新规则 radar-4：双段正文 + 新标题 + 技术风向观察 + 双语原始简介 + 紧凑报头结构
  await t.test('新规则 radar-4 展示分段正文、技术风向观察与双语简介', (st) => {
    const reportV4 = makeReportPayload()
    const { root } = mountHeadless(st, DetailComp, { report: reportV4, project: reportV4.items[0] })

    const headingH1 = findAll(root, (n) => n.tag === 'h1')[0]
    assert.ok(headingH1)
    assert.equal(collectText(headingH1).trim(), 'cursor/plugins')
    assert.equal(findAll(headingH1, (n) => n.tag === 'small').length, 1)
    assert.equal(findAll(headingH1, (n) => n.tag === 'wbr').length, 1)

    const headingsV4 = findAll(root, (n) => n.tag === 'h2').map((n) => collectText(n).trim())
    assert.ok(headingsV4.includes('它能帮你做什么'))
    assert.ok(!headingsV4.includes('解决什么问题'))
    assert.ok(headingsV4.includes('技术风向观察'))

    const usageSection = findAll(root, (n) => n.tag === 'section' && n.props.class === 'usage-body')[0]
    const usageParagraphs = findAll(usageSection, (n) => n.tag === 'p').map((n) =>
      collectText(n).trim(),
    )
    assert.deepEqual(usageParagraphs, [
      '第一段交代手头情况与卡点。',
      '第二段交代处理过程与拿到什么。',
    ])

    const signalSection = findAll(
      root,
      (n) => n.tag === 'section' && n.props.class === 'trend-signal',
    )[0]
    assert.ok(signalSection)
    assert.ok(collectText(signalSection).includes('AI 趋势解读'))
    assert.ok(collectText(signalSection).includes('AI 编程工具正走向团队可复用的插件生态。'))

    const originalSection = findAll(
      root,
      (n) => n.tag === 'section' && n.props.class === 'original',
    )[0]
    assert.ok(originalSection)
    const rawDesc = findAll(originalSection, (n) => n.tag === 'p' && n.props.class === 'original-raw')[0]
    const zhDesc = findAll(originalSection, (n) => n.tag === 'p' && n.props.class === 'original-zh')[0]
    assert.equal(collectText(rawDesc).trim(), 'Developers can package tools for Cursor as plugins.')
    assert.ok(collectText(zhDesc).includes('参考译文'))
    assert.ok(collectText(zhDesc).includes('开发者可将工具打包为 Cursor 插件的示例与目录仓库。'))
  })

  // 2. 历史规则（trendSignal 与 descriptionZh 均为 null）：不渲染风向与译文，单段正文正常渲染
  await t.test('历史规则 radar-1 省略风向与译文时单段正文正常渲染', (st) => {
    const reportV1 = makeReportPayload({
      ruleVersion: 'radar-1',
      painPoint: '单段历史正文。',
      trendSignal: null,
      descriptionZh: null,
    })
    const { root } = mountHeadless(st, DetailComp, { report: reportV1, project: reportV1.items[0] })
    const headingsV1 = findAll(root, (n) => n.tag === 'h2').map((n) => collectText(n).trim())
    assert.ok(headingsV1.includes('它能帮你做什么'))
    assert.ok(!headingsV1.includes('技术风向观察'))
    const usageV1 = findAll(root, (n) => n.tag === 'section' && n.props.class === 'usage-body')[0]
    assert.deepEqual(
      findAll(usageV1, (n) => n.tag === 'p').map((n) => collectText(n).trim()),
      ['单段历史正文。'],
    )
    assert.equal(findAll(root, (n) => n.tag === 'p' && n.props.class === 'original-zh').length, 0)
  })

  // 3. 原简介为空或原简介与译文相同时，不重复展示译文
  await t.test('原简介与译文相同或原简介为空时不重复展示译文', (st) => {
    const reportSame = makeReportPayload(
      { descriptionZh: '中文原始简介' },
      false,
      { description: '中文原始简介' },
    )
    const { root: sameRoot } = mountHeadless(st, DetailComp, {
      report: reportSame,
      project: reportSame.items[0],
    })
    assert.equal(
      findAll(sameRoot, (n) => n.tag === 'p' && n.props.class === 'original-zh').length,
      0,
    )

    const reportNoDesc = makeReportPayload({}, false, { description: null })
    const { root: noDescRoot } = mountHeadless(st, DetailComp, {
      report: reportNoDesc,
      project: reportNoDesc.items[0],
    })
    assert.equal(
      findAll(noDescRoot, (n) => n.tag === 'p' && n.props.class === 'original-zh').length,
      0,
    )
    const origNoDesc = findAll(
      noDescRoot,
      (n) => n.tag === 'section' && n.props.class === 'original',
    )[0]
    assert.ok(collectText(origNoDesc).includes('仓库未提供简介。'))
  })
})

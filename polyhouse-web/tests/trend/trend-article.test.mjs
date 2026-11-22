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

function makeReportPayload(insightPatch = {}, omitTrendSignal = false) {
  const insight = {
    category: 'DEVTOOLS',
    definition: '开发者可以用这个仓库把工具打包成 Cursor 插件。',
    painPoint: '第一段交代手头情况与卡点。\n\n第二段交代处理过程与拿到什么。',
    comparison: null,
    highlights: ['单插件独立目录结构'],
    documentedLimitations: [],
    inferredRisks: ['具体插件效果需自行核对'],
    trendSignal: 'AI 编程工具正走向团队可复用的插件生态。',
    analyzedAt: '2026-11-21T10:00:00Z',
    readmeVersion: 'a'.repeat(40),
    model: 'deepseek-chat',
    ruleVersion: 'radar-3',
    ...insightPatch,
  }
  if (omitTrendSignal) delete insight.trendSignal
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
      },
    ],
  }
}

test('trendParagraphs 正确拆分单段、多段和 Windows 换行，不丢弃纯空白兜底', async () => {
  const presentationUrl = await transpileTs('../../src/features/trend/presentation.ts')
  const { trendParagraphs } = await import(presentationUrl)

  assert.deepEqual(trendParagraphs('单段说明'), ['单段说明'])
  assert.deepEqual(trendParagraphs('第一段内容。\n\n第二段内容。'), [
    '第一段内容。',
    '第二段内容。',
  ])
  assert.deepEqual(trendParagraphs('  第一段  \r\n\r\n\r\n  第二段  '), ['第一段', '第二段'])
  assert.deepEqual(trendParagraphs('   '), ['   '])
})

test('trend API 解码支持 radar-3 技术风向字段并兼容省略该字段的历史归档', async () => {
  let nextPayload = makeReportPayload()
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
  const trendApiUrl = await transpileTs('../../src/api/trend.ts', {
    "from './index'": `from '${mockIndexUrl}'`,
  })
  const { getTrendReport } = await import(trendApiUrl)

  // 新规则 radar-3 包含 trendSignal
  globalThis.__polyhouseTrendTestPayload = nextPayload
  const v3 = await getTrendReport('2026-11-21')
  assert.equal(v3.items[0].insight.trendSignal, 'AI 编程工具正走向团队可复用的插件生态。')

  // 旧规则 radar-1 / radar-2 缺少 trendSignal 字段时归一化为 null
  globalThis.__polyhouseTrendTestPayload = makeReportPayload({ ruleVersion: 'radar-1' }, true)
  const v1 = await getTrendReport('2026-11-21')
  assert.equal(v1.items[0].insight.trendSignal, null)

  // 非字符串类型的 trendSignal 必须被拒绝
  globalThis.__polyhouseTrendTestPayload = makeReportPayload({ trendSignal: 123 })
  await assert.rejects(() => getTrendReport('2026-11-21'), /数据不完整/)
  delete globalThis.__polyhouseTrendTestPayload
})

test('TrendProjectDetail 渲染分段正文、新标题与可选的技术风向观察卡片', async () => {
  const presentationUrl = await transpileTs('../../src/features/trend/presentation.ts')
  const iconsUrl = dataUrl('export const ElIcon = {}, TopRight = {}, StarFilled = {};')
  const assetUrl = dataUrl('export default "voyage.webp";')
  const { descriptor } = parse(
    await readFile(new URL('../../src/features/trend/TrendProjectDetail.vue', import.meta.url), 'utf8'),
  )
  const id = 'trend-project-detail-test'
  const scriptCode = ts
    .transpileModule(compileScript(descriptor, { id }).content, {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    })
    .outputText.replaceAll("from 'vue'", `from '${import.meta.resolve('vue')}'`)
    .replaceAll("from 'element-plus'", `from '${iconsUrl}'`)
    .replaceAll("from '@element-plus/icons-vue'", `from '${iconsUrl}'`)
    .replaceAll("from '../../assets/startide-voyage.webp'", `from '${assetUrl}'`)
    .replaceAll("from './presentation'", `from '${presentationUrl}'`)

  const templateCode = compileTemplate({
    id,
    filename: 'TrendProjectDetail.vue',
    source: descriptor.template.content,
    compilerOptions: { bindingMetadata: compileScript(descriptor, { id }).bindings },
  }).code.replaceAll('from "vue"', `from '${import.meta.resolve('vue')}'`)

  const { default: component } = await import(dataUrl(scriptCode))
  const { render } = await import(dataUrl(templateCode))

  // 构造最小内存 DOM 树，收集渲染出的元素标签、类名与文本内容。
  function mountDetail(report) {
    const createNode = (tag) => ({ tag, attrs: {}, children: [], text: '' })
    const renderer = createRenderer({
      createElement: (tag) => createNode(tag),
      createText: (text) => ({ tag: '#text', text }),
      createComment: () => ({ tag: '#comment', text: '' }),
      insert(child, parent) {
        parent.children.push(child)
      },
      remove() {},
      setText(node, text) {
        node.text = text
      },
      setElementText(node, text) {
        node.children = [{ tag: '#text', text }]
      },
      patchProp(node, key, _prev, next) {
        node.attrs[key] = next
      },
      parentNode: () => null,
      nextSibling: () => null,
    })
    const root = createNode('root')
    const app = renderer.createApp({
      render: () => h({ ...component, render }, { report, project: report.items[0] }),
    })
    app.mount(root)
    app.unmount()
    return root
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

  // 1. 新规则 radar-3：双段正文 + 新标题 + 技术风向观察
  const reportV3 = makeReportPayload()
  const treeV3 = mountDetail(reportV3)
  const headingsV3 = findAll(treeV3, (n) => n.tag === 'h2').map((n) => textOf(n).trim())
  assert.ok(headingsV3.includes('它能帮你做什么'))
  assert.ok(!headingsV3.includes('解决什么问题'))
  assert.ok(headingsV3.includes('技术风向观察'))

  const usageSection = findAll(treeV3, (n) => n.tag === 'section' && n.attrs.class === 'usage-body')[0]
  const usageParagraphs = findAll(usageSection, (n) => n.tag === 'p').map((n) => textOf(n).trim())
  assert.deepEqual(usageParagraphs, ['第一段交代手头情况与卡点。', '第二段交代处理过程与拿到什么。'])

  const signalSection = findAll(treeV3, (n) => n.tag === 'section' && n.attrs.class === 'trend-signal')[0]
  assert.ok(signalSection)
  assert.ok(textOf(signalSection).includes('AI 趋势解读'))
  assert.ok(textOf(signalSection).includes('AI 编程工具正走向团队可复用的插件生态。'))

  // 2. 历史规则（trendSignal 为 null）：不渲染技术风向观察，单段正文正常渲染
  const reportV1 = makeReportPayload({
    ruleVersion: 'radar-1',
    painPoint: '单段历史正文。',
    trendSignal: null,
  })
  const treeV1 = mountDetail(reportV1)
  const headingsV1 = findAll(treeV1, (n) => n.tag === 'h2').map((n) => textOf(n).trim())
  assert.ok(headingsV1.includes('它能帮你做什么'))
  assert.ok(!headingsV1.includes('技术风向观察'))
  const usageV1 = findAll(treeV1, (n) => n.tag === 'section' && n.attrs.class === 'usage-body')[0]
  assert.deepEqual(
    findAll(usageV1, (n) => n.tag === 'p').map((n) => textOf(n).trim()),
    ['单段历史正文。'],
  )
})

import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, nextTick } from 'vue'
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

function makeBriefingPayload(patch = {}) {
  return {
    status: 'READY',
    anchorDate: '2026-11-23',
    windowStart: '2026-11-17',
    windowEnd: '2026-11-23',
    coveredDays: 5,
    sampleStatus: 'SUFFICIENT',
    currentCoveredDays: 5,
    radar3ReadyCount: 8,
    eligible: true,
    ineligibleReason: null,
    stale: false,
    generatedAt: '2026-11-23T02:10:00Z',
    model: 'deepseek-flash',
    ruleVersion: 'briefing-1',
    headline: '本地代理编排与离线配置校验成为本周集中方向',
    overview:
      '本期覆盖 5 天日榜并缺采 2 天，已分类项目中开发工具与智能体框架占比居前，整体仍偏早期工具形态。',
    themes: [
      {
        title: '终端编码助手的插件化封装',
        signalType: 'CONCENTRATION',
        summary:
          '多款上榜仓库把大模型编码能力收进本地 CLI（命令行工具），通过统一配置接入代码搜索与补丁生成。',
        evidenceRepos: [
          {
            fullName: 'acme/alpha',
            repositoryId: '101',
            repoUrl: 'https://github.com/acme/alpha',
            inAnchorDay: true,
          },
          {
            fullName: 'acme/beta',
            repositoryId: '102',
            repoUrl: 'https://github.com/acme/beta',
            inAnchorDay: false,
          },
        ],
      },
      {
        title: '提交前离线配置校验工具回暖',
        signalType: 'RECURRING',
        summary:
          '围绕微服务配置与流水线检查的工具连续多日上榜，共同目标是在提交代码前暴露格式与字段错误。',
        evidenceRepos: [
          {
            fullName: 'acme/gamma',
            repositoryId: '103',
            repoUrl: 'https://github.com/acme/gamma',
            inAnchorDay: true,
          },
          {
            fullName: 'acme/delta',
            repositoryId: '104',
            repoUrl: 'https://github.com/acme/delta',
            inAnchorDay: false,
          },
        ],
      },
    ],
    watchouts: ['本期存在 2 个缺采日，分类占比变化不宜直接等同于全周真实趋势。'],
    ...patch,
  }
}

function createFakeDom() {
  const makeNode = (type, tag = '') => ({
    type,
    tag,
    text: '',
    props: {},
    children: [],
    parent: null,
  })
  const renderer = createRenderer({
    patchProp(el, key, _prev, next) {
      el.props[key] = next
    },
    insert(child, parent, anchor) {
      child.parent = parent
      const idx = anchor ? parent.children.indexOf(anchor) : -1
      if (idx >= 0) parent.children.splice(idx, 0, child)
      else parent.children.push(child)
    },
    remove(child) {
      if (!child.parent) return
      const idx = child.parent.children.indexOf(child)
      if (idx >= 0) child.parent.children.splice(idx, 1)
    },
    createElement: (tag) => makeNode('el', tag),
    createText: (text) => {
      const node = makeNode('text')
      node.text = text
      return node
    },
    createComment: (text) => {
      const node = makeNode('comment')
      node.text = text
      return node
    },
    setText: (node, text) => {
      node.text = text
    },
    setElementText: (el, text) => {
      el.children = [{ type: 'text', tag: '', text, props: {}, children: [], parent: el }]
    },
    parentNode: (node) => node.parent,
    nextSibling: (node) => {
      if (!node.parent) return null
      const idx = node.parent.children.indexOf(node)
      return idx >= 0 ? (node.parent.children[idx + 1] ?? null) : null
    },
  })
  return { root: makeNode('root', 'div'), renderer }
}

function collectText(node) {
  if (node.type === 'text') return node.text
  if (node.type === 'comment') return ''
  return node.children.map(collectText).join('')
}

function findAll(node, predicate, out = []) {
  if (predicate(node)) out.push(node)
  for (const child of node.children) findAll(child, predicate, out)
  return out
}

async function compileSfc(relativePath, id, replacements = {}) {
  const source = await readFile(new URL(relativePath, import.meta.url), 'utf8')
  const { descriptor } = parse(source, { filename: relativePath })
  const script = compileScript(descriptor, { id })
  const vueUrl = import.meta.resolve('vue')
  let scriptCode = ts
    .transpileModule(script.content, {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    })
    .outputText.replaceAll("from 'vue'", `from '${vueUrl}'`)
    .replaceAll('from "vue"', `from '${vueUrl}'`)
  for (const [from, to] of Object.entries(replacements))
    scriptCode = scriptCode.replaceAll(from, to)

  const templateCode = compileTemplate({
    source: descriptor.template.content,
    filename: relativePath,
    id,
    compilerOptions: { bindingMetadata: script.bindings },
  })
    .code.replaceAll('from "vue"', `from '${vueUrl}'`)
    .replaceAll("from 'vue'", `from '${vueUrl}'`)

  const { default: component } = await import(dataUrl(scriptCode))
  const { render } = await import(dataUrl(templateCode))
  return { ...component, render }
}

test('trend.ts 严格解码 7 日风口简报、触发结果与合并待核对 chargeKind', async () => {
  const calls = []
  let nextResponse = makeBriefingPayload()
  const fakeIndexUrl = dataUrl(`
    export class ApiError extends Error {
      constructor(message, status = 0) { super(message); this.status = status }
    }
    export function isRecord(v) { return typeof v === 'object' && v !== null && !Array.isArray(v) }
    export async function apiRequest(config, decode, token) {
      globalThis.__briefingCalls.push({ config, token })
      return decode(globalThis.__briefingResp)
    }
  `)
  globalThis.__briefingCalls = calls
  globalThis.__briefingResp = nextResponse

  const trendMod = await import(
    await transpileTs('../../src/api/trend.ts', { "'./index'": `'${fakeIndexUrl}'` })
  )

  const ready = await trendMod.getTrendBriefing('2026-11-23')
  assert.equal(ready.status, 'READY')
  assert.equal(ready.themes.length, 2)
  assert.equal(calls[0].config.url, '/trend/briefing')
  assert.equal(calls[0].token, undefined)

  // ABSENT 空简报合法解码。
  globalThis.__briefingResp = makeBriefingPayload({
    status: 'ABSENT',
    headline: null,
    overview: null,
    generatedAt: null,
    model: null,
    ruleVersion: null,
    themes: [],
    watchouts: [],
  })
  const absent = await trendMod.getTrendBriefing('2026-11-23')
  assert.equal(absent.status, 'ABSENT')

  // READY 缺少正文或证据仓携带非法非 GitHub 链接时必须拒绝。
  globalThis.__briefingResp = makeBriefingPayload({ headline: '' })
  await assert.rejects(() => trendMod.getTrendBriefing('2026-11-23'))

  globalThis.__briefingResp = makeBriefingPayload({
    themes: [
      {
        ...makeBriefingPayload().themes[0],
        evidenceRepos: [
          {
            fullName: 'acme/alpha',
            repositoryId: '101',
            repoUrl: 'javascript:alert(1)',
            inAnchorDay: true,
          },
          makeBriefingPayload().themes[0].evidenceRepos[1],
        ],
      },
      makeBriefingPayload().themes[1],
    ],
  })
  await assert.rejects(() => trendMod.getTrendBriefing('2026-11-23'))

  // 屋主触发接口带 token 且设置 65s 超时。
  globalThis.__briefingResp = {
    outcome: 'GENERATED',
    charged: true,
    briefing: makeBriefingPayload(),
  }
  const generated = await trendMod.generateTrendBriefing('owner-token', '2026-11-23')
  assert.equal(generated.outcome, 'GENERATED')
  assert.equal(generated.charged, true)
  const genCall = calls.at(-1)
  assert.equal(genCall.config.url, '/manage/trend/briefings')
  assert.equal(genCall.config.method, 'POST')
  assert.equal(genCall.config.timeout, 65000)
  assert.equal(genCall.token, 'owner-token')

  // 费用面板兼容单仓缺省 chargeKind 与简报 BRIEFING 类型。
  globalThis.__briefingResp = {
    month: '2026-11',
    budgetMicros: 10_000_000,
    accountedMicros: 5000,
    heldMicros: 3000,
    remainingMicros: 9_992_000,
    conservativeMicros: 0,
    suspended: false,
    pending: [
      {
        requestId: '11111111-2222-4333-8444-555555555555',
        repositoryId: '101',
        state: 'UNKNOWN',
        reservedMicros: 1500,
        createdAt: '2026-11-23T02:00:00Z',
      },
      {
        requestId: '22222222-2222-4333-8444-555555555555',
        repositoryId: 'briefing:2026-11-23',
        chargeKind: 'BRIEFING',
        state: 'RESERVED',
        reservedMicros: 1500,
        createdAt: '2026-11-23T02:05:00Z',
      },
    ],
    nextAfter: null,
  }
  const fees = await trendMod.getTrendFees('owner-token')
  assert.equal(fees.pending[0].chargeKind, 'REPO')
  assert.equal(fees.pending[1].chargeKind, 'BRIEFING')
})

test('TrendWindowBriefing.vue 在 ABSENT 时零占位渲染，在 READY 时渲染导读、证据链接与 stale 提示', async () => {
  const fakeEl = dataUrl(`export const ElIcon = { render() { return null } }`)
  const fakeIcons = dataUrl(`export const TopRight = { render() { return null } }`)
  const fakePres = dataUrl(`export function trendTime(v) { return '2026/11/23 10:10' }`)
  const Comp = await compileSfc(
    '../../src/features/trend/TrendWindowBriefing.vue',
    'briefing-reader',
    {
      "'element-plus'": `'${fakeEl}'`,
      "'@element-plus/icons-vue'": `'${fakeIcons}'`,
      "'./presentation'": `'${fakePres}'`,
    },
  )

  // ABSENT 时完全不输出空态卡片噪音。
  const absentDom = createFakeDom()
  absentDom.renderer.render(
    h(Comp, {
      briefing: makeBriefingPayload({ status: 'ABSENT', themes: [], watchouts: [] }),
      resolveDetailHref: (name) => `#/trend/2026-11-23/${name}`,
    }),
    absentDom.root,
  )
  await nextTick()
  assert.equal(collectText(absentDom.root).trim(), '')

  // READY + stale=true 渲染标题、导语、双仓链接（站内/外链区分）、边界提醒与较早快照提示。
  const readyDom = createFakeDom()
  const opened = []
  readyDom.renderer.render(
    h(Comp, {
      briefing: makeBriefingPayload({ stale: true, sampleStatus: 'LIMITED', coveredDays: 4 }),
      resolveDetailHref: (name) => `#/trend/2026-11-23/${name}`,
      onOpenProject: (_e, name) => opened.push(name),
    }),
    readyDom.root,
  )
  await nextTick()
  const text = collectText(readyDom.root)
  assert.match(text, /AI 风口速览/)
  assert.match(text, /有限样本/)
  assert.match(text, /基于较早日报快照生成/)
  assert.match(text, /本地代理编排与离线配置校验成为本周集中方向/)
  assert.match(text, /多仓聚集/)
  assert.match(text, /持续霸榜/)
  assert.match(text, /边界提醒/)

  const links = findAll(readyDom.root, (n) => n.tag === 'a')
  assert.equal(links.length, 4)
  assert.equal(links[0].props.href, '#/trend/2026-11-23/acme/alpha')
  assert.equal(links[1].props.href, 'https://github.com/acme/beta')
  assert.equal(links[1].props.target, '_blank')
  links[0].props.onClick({})
  assert.deepEqual(opened, ['acme/alpha'])
})

test('TrendBriefingControl.vue 按门槛与最新状态控制按钮可用性', async () => {
  const fakeEl = dataUrl(`
    import { h } from '${import.meta.resolve('vue')}'
    export const ElButton = {
      props: ['disabled', 'loading', 'type'],
      emits: ['click'],
      render() {
        return h('button', { disabled: this.disabled, onClick: () => this.$emit('click') }, this.$slots.default?.())
      }
    }
  `)
  const fakePres = dataUrl(`export function trendTime(v) { return '2026/11/23 10:10' }`)
  const Comp = await compileSfc(
    '../../src/features/trend/TrendBriefingControl.vue',
    'briefing-owner',
    {
      "'element-plus'": `'${fakeEl}'`,
      "'./presentation'": `'${fakePres}'`,
    },
  )

  // 未达门槛：生成按钮禁用，展示明确原因。
  const dom1 = createFakeDom()
  dom1.renderer.render(
    h(Comp, {
      briefing: makeBriefingPayload({
        status: 'ABSENT',
        eligible: false,
        currentCoveredDays: 2,
        radar3ReadyCount: 4,
        ineligibleReason: 'INSUFFICIENT_COVERED_DAYS',
        themes: [],
        watchouts: [],
      }),
      busy: false,
      generating: false,
      waiting: false,
      error: '',
      note: '',
    }),
    dom1.root,
  )
  await nextTick()
  assert.match(collectText(dom1.root), /需至少覆盖 3 天已发布日报/)
  const buttons1 = findAll(dom1.root, (n) => n.tag === 'button')
  assert.equal(buttons1[1].props.disabled, true)

  // 达门槛且未生成：生成按钮可用并可触发。
  const dom2 = createFakeDom()
  let triggered = 0
  dom2.renderer.render(
    h(Comp, {
      briefing: makeBriefingPayload({
        status: 'ABSENT',
        eligible: true,
        currentCoveredDays: 3,
        radar3ReadyCount: 5,
        themes: [],
        watchouts: [],
      }),
      busy: false,
      generating: false,
      waiting: false,
      error: '',
      note: '',
      onGenerate: () => {
        triggered++
      },
    }),
    dom2.root,
  )
  await nextTick()
  const buttons2 = findAll(dom2.root, (n) => n.tag === 'button')
  assert.equal(buttons2[1].props.disabled, false)
  buttons2[1].props.onClick()
  assert.equal(triggered, 1)

  // 已是最新：禁用重复触发（防误点）。
  const dom3 = createFakeDom()
  dom3.renderer.render(
    h(Comp, {
      briefing: makeBriefingPayload({ status: 'READY', stale: false }),
      busy: false,
      generating: false,
      waiting: false,
      error: '',
      note: '',
    }),
    dom3.root,
  )
  await nextTick()
  const buttons3 = findAll(dom3.root, (n) => n.tag === 'button')
  assert.equal(buttons3[1].props.disabled, true)
  assert.match(collectText(dom3.root), /本期已是最新/)
})

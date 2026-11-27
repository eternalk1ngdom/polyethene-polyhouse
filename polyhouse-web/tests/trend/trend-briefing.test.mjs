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

test('trend.ts 严格解码 7 日风口简报与触发结果', async (t) => {
  const calls = []
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
  patchGlobal(t, '__briefingCalls', calls)
  patchGlobal(t, '__briefingResp', makeBriefingPayload())

  const trendMod = await import(
    await typeScriptModuleUrl(new URL('../../src/api/trend.ts', import.meta.url), {
      "'./index'": `'${fakeIndexUrl}'`,
    })
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
  for (const [label, payload] of [
    ['空 headline', makeBriefingPayload({ headline: '' })],
    [
      '非法 javascript 证据链接',
      makeBriefingPayload({
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
      }),
    ],
  ]) {
    await t.test(`拒绝${label}`, async () => {
      globalThis.__briefingResp = payload
      await assert.rejects(() => trendMod.getTrendBriefing('2026-11-23'))
    })
  }

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
})

test('TrendWindowBriefing.vue 在 ABSENT 时零占位渲染，在 READY 时渲染导读、证据链接与 stale 提示', async (t) => {
  const fakeEl = dataUrl(`export const ElIcon = { render() { return null } }`)
  const fakeIcons = dataUrl(`export const TopRight = { render() { return null } }`)
  const fakePres = dataUrl(`export function trendTime() { return '2026/11/23 10:10' }`)
  const Comp = await compileSfcComponent(
    new URL('../../src/features/trend/TrendWindowBriefing.vue', import.meta.url),
    'briefing-reader',
    {
      "'element-plus'": `'${fakeEl}'`,
      "'@element-plus/icons-vue'": `'${fakeIcons}'`,
      "'./presentation'": `'${fakePres}'`,
    },
  )

  await t.test('ABSENT 时完全不输出空态卡片噪音', async (st) => {
    const { root } = mountHeadless(st, Comp, {
      briefing: makeBriefingPayload({ status: 'ABSENT', themes: [], watchouts: [] }),
      resolveDetailHref: (name) => `#/trend/2026-11-23/${name}`,
    })
    await nextTick()
    assert.equal(collectText(root).trim(), '')
  })

  await t.test('READY + stale 渲染标题、导语、双仓链接与较早快照提示', async (st) => {
    const opened = []
    const { root } = mountHeadless(st, Comp, {
      briefing: makeBriefingPayload({ stale: true, sampleStatus: 'LIMITED', coveredDays: 4 }),
      resolveDetailHref: (name) => `#/trend/2026-11-23/${name}`,
      onOpenProject: (_e, name) => opened.push(name),
    })
    await nextTick()
    const text = collectText(root)
    assert.match(text, /AI 风口速览/)
    assert.match(text, /有限样本/)
    assert.match(text, /基于较早日报快照生成/)
    assert.match(text, /本地代理编排与离线配置校验成为本周集中方向/)
    assert.match(text, /多仓聚集/)
    assert.match(text, /持续霸榜/)
    assert.match(text, /边界提醒/)

    const links = findAll(root, (n) => n.tag === 'a')
    assert.equal(links.length, 4)
    assert.equal(links[0].props.href, '#/trend/2026-11-23/acme/alpha')
    assert.equal(links[1].props.href, 'https://github.com/acme/beta')
    assert.equal(links[1].props.target, '_blank')
    links[0].props.onClick({})
    assert.deepEqual(opened, ['acme/alpha'])
  })
})

test('TrendBriefingControl.vue 按门槛与最新状态控制按钮可用性', async (t) => {
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
  const fakePres = dataUrl(`export function trendTime() { return '2026/11/23 10:10' }`)
  const Comp = await compileSfcComponent(
    new URL('../../src/features/trend/TrendBriefingControl.vue', import.meta.url),
    'briefing-owner',
    {
      "'element-plus'": `'${fakeEl}'`,
      "'./presentation'": `'${fakePres}'`,
    },
  )

  const cases = [
    {
      name: '未达门槛时禁用生成按钮并展示明确原因',
      briefing: makeBriefingPayload({
        status: 'ABSENT',
        eligible: false,
        currentCoveredDays: 2,
        radar3ReadyCount: 4,
        ineligibleReason: 'INSUFFICIENT_COVERED_DAYS',
        themes: [],
        watchouts: [],
      }),
      buttonLabel: '生成本期风口简报',
      expectedDisabled: true,
      expectedText: /需至少覆盖 3 天已发布日报/,
      clickTriggers: false,
    },
    {
      name: '达门槛且未生成时启用按钮并可触发 generate',
      briefing: makeBriefingPayload({
        status: 'ABSENT',
        eligible: true,
        currentCoveredDays: 3,
        radar3ReadyCount: 5,
        themes: [],
        watchouts: [],
      }),
      buttonLabel: '生成本期风口简报',
      expectedDisabled: false,
      expectedText: /已达门槛/,
      clickTriggers: true,
    },
    {
      name: '已是最新时禁用重复触发并提示本期已是最新',
      briefing: makeBriefingPayload({ status: 'READY', stale: false }),
      buttonLabel: '本期已是最新',
      expectedDisabled: true,
      expectedText: /本期已是最新/,
      clickTriggers: false,
    },
  ]

  for (const item of cases) {
    await t.test(item.name, async (st) => {
      let triggered = 0
      const { root } = mountHeadless(st, Comp, {
        briefing: item.briefing,
        busy: false,
        generating: false,
        waiting: false,
        error: '',
        note: '',
        onGenerate: () => {
          triggered++
        },
      })
      await nextTick()
      assert.match(collectText(root), item.expectedText)
      const actionBtn = findAll(
        root,
        (n) => n.tag === 'button' && collectText(n).trim() === item.buttonLabel,
      )[0]
      assert.ok(actionBtn, `未找到文案为「${item.buttonLabel}」的操作按钮`)
      assert.equal(actionBtn.props.disabled, item.expectedDisabled)
      if (item.clickTriggers) {
        actionBtn.props.onClick()
        assert.equal(triggered, 1)
      }
    })
  }
})

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

test('TrendFeeLedger 并列展示安全预算、预计平台实扣与 Token 汇总并兼容无 usage 旧响应', async (t) => {
  const stubElUrl = dataUrl(
    `export const ElButton = { name: 'ElButton', template: '<button type="button"><slot /></button>' }`,
  )
  const Ledger = await compileSfcComponent(
    new URL('../../src/features/trend/TrendFeeLedger.vue', import.meta.url),
    'trend-fee-ledger-test',
    {
      "'element-plus'": `'${stubElUrl}'`,
      "'./presentation'": `'${presentationUrl}'`,
    },
  )

  const cases = [
    {
      name: '含 usage 时展示双口径金额、缓存命中率与核账提示',
      fees: {
        month: '2026-11',
        budgetMicros: 10_000_000,
        accountedMicros: 1_200_000,
        heldMicros: 0,
        remainingMicros: 8_800_000,
        conservativeMicros: 0,
        suspended: false,
        pending: [],
        nextAfter: null,
        usage: {
          settledRequests: 18,
          promptTokens: 50_000,
          cacheHitTokens: 35_000,
          cacheMissTokens: 15_000,
          completionTokens: 8_000,
          estimatedActualMicros: 420_000,
        },
      },
      expectText: [
        '安全预算已用',
        '¥1.20',
        '预计平台实扣',
        '¥0.42',
        '不含法定调休节假日',
        '本月 Token 汇总明细',
        '18 次',
        '50,000',
        '35,000（70%）',
        '8,000',
        'API Key',
      ],
    },
    {
      name: '未迁移 017（usage=null）时安全回退横线且隐藏 Token 汇总块',
      fees: {
        month: '2026-11',
        budgetMicros: 10_000_000,
        accountedMicros: 500_000,
        heldMicros: 0,
        remainingMicros: 9_500_000,
        conservativeMicros: 0,
        suspended: false,
        pending: [],
        nextAfter: null,
        usage: null,
      },
      expectText: ['安全预算已用', '¥0.50', '预计平台实扣', '—'],
      forbidText: ['本月 Token 汇总明细'],
    },
  ]

  for (const item of cases) {
    await t.test(item.name, () => {
      const mounted = mountHeadless(t, Ledger, { fees: item.fees, busy: false, error: '' })
      const text = collectText(mounted.root)
      for (const expected of item.expectText) {
        assert.ok(text.includes(expected), `应包含 "${expected}"，实际文本：${text}`)
      }
      for (const forbidden of item.forbidText ?? []) {
        assert.ok(!text.includes(forbidden), `不应包含 "${forbidden}"，实际文本：${text}`)
      }
    })
  }
})

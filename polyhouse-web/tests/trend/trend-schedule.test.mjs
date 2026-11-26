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

test('TrendScheduleCard 清晰展示自动采集开关、今日状态与最近任务来源区分', async (t) => {
  const stubElUrl = dataUrl(
    `export const ElButton = { name: 'ElButton', template: '<button type="button"><slot /></button>' }`,
  )
  const Card = await compileSfcComponent(
    new URL('../../src/features/trend/TrendScheduleCard.vue', import.meta.url),
    'trend-schedule-card-test',
    {
      "'element-plus'": `'${stubElUrl}'`,
      "'./presentation'": `'${presentationUrl}'`,
    },
  )

  const cases = [
    {
      name: '开启自动日报与自动简报且展示定时自动与屋主手动记录',
      props: {
        status: {
          moduleEnabled: true,
          jobsEnabled: true,
          scheduleEnabled: true,
          briefingAutoEnabled: true,
          aiReady: true,
          dailyTime: '06:30',
          retryDelayMinutes: 45,
          todayDate: '2026-11-26',
          todayStatus: 'PUBLISHED_AUTO',
          todayPublished: true,
          recentJobs: [
            {
              jobId: '11111111-2222-4333-8444-555555555555',
              state: 'SUCCEEDED',
              phase: 'DONE',
              aiRequested: true,
              createdAt: '2026-11-25T22:31:00Z',
              startedAt: '2026-11-25T22:31:05Z',
              completedAt: '2026-11-25T22:33:00Z',
              deadline: '2026-11-25T23:01:00Z',
              reportId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
              reason: null,
              trigger: 'SCHEDULED',
              summary: null,
            },
            {
              jobId: '22222222-2222-4333-8444-555555555555',
              state: 'PARTIAL',
              phase: 'DONE',
              aiRequested: false,
              createdAt: '2026-11-24T12:00:00Z',
              startedAt: null,
              completedAt: null,
              deadline: '2026-11-24T12:30:00Z',
              reportId: null,
              reason: null,
              trigger: 'MANUAL',
              summary: null,
            },
          ],
        },
        busy: false,
        error: '',
      },
      expectText: ['每日自动采集状态', '已开启', '06:30', '今日已自动发布', '定时自动', '屋主手动', '含 AI 解读', '仅基础资料'],
    },
    {
      name: '自动采集未开启且无历史任务时展示温和空态',
      props: {
        status: {
          moduleEnabled: true,
          jobsEnabled: true,
          scheduleEnabled: false,
          briefingAutoEnabled: false,
          aiReady: false,
          dailyTime: '06:30',
          retryDelayMinutes: 45,
          todayDate: '2026-11-26',
          todayStatus: 'DISABLED',
          todayPublished: false,
          recentJobs: [],
        },
        busy: false,
        error: '',
      },
      expectText: ['未开启', '自动采集未开启', '暂无历史任务记录'],
    },
    {
      name: '查询失败时展示错误提示',
      props: {
        status: null,
        busy: false,
        error: '暂时无法读取自动采集状态，请稍后重试。',
      },
      expectText: ['暂时无法读取自动采集状态，请稍后重试。'],
    },
  ]

  for (const item of cases) {
    await t.test(item.name, () => {
      const mounted = mountHeadless(t, Card, item.props)
      const text = collectText(mounted.root)
      for (const expected of item.expectText) {
        assert.ok(text.includes(expected), `应包含 "${expected}"，实际文本：${text}`)
      }
    })
  }
})

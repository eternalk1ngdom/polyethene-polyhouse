import assert from 'node:assert/strict'
import test from 'node:test'
import { nextTick } from 'vue'
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

test('TrendScheduleCard 清晰展示自动采集开关、今日状态、人工急停二级确认与来源区分', async (t) => {
  const stubElUrl = dataUrl(`
    import { h } from '${import.meta.resolve('vue')}'
    export const ElButton = {
      name: 'ElButton',
      props: ['disabled', 'loading', 'type', 'plain'],
      emits: ['click'],
      render() {
        return h('button', { type: 'button', disabled: this.disabled, onClick: () => this.$emit('click') }, this.$slots.default?.())
      },
    }
    export const ElDialog = {
      name: 'ElDialog',
      props: ['modelValue', 'title'],
      emits: ['update:modelValue'],
      render() {
        if (!this.modelValue) return null
        return h('div', { class: 'mock-dialog' }, [
          h('h4', null, this.title),
          this.$slots.default?.(),
          this.$slots.footer?.(),
        ])
      },
    }
  `)
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
          schedulePaused: false,
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
      expectText: [
        '每日自动采集状态',
        '已开启',
        '暂停自动采集',
        '06:30',
        '今日已自动发布',
        '定时自动',
        '屋主手动',
        '含 AI 解读',
        '仅基础资料',
      ],
    },
    {
      name: '服务端总闸未开启时不展示急停按钮并说明防本地误扣费',
      props: {
        status: {
          moduleEnabled: true,
          jobsEnabled: true,
          scheduleEnabled: false,
          schedulePaused: false,
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
      expectText: ['未开启', '自动采集未开启', '由服务端配置控制，防本地调试误扣费', '暂无历史任务记录'],
      forbidText: ['暂停自动采集', '恢复自动采集'],
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
      for (const forbidden of item.forbidText ?? []) {
        assert.ok(!text.includes(forbidden), `不应包含 "${forbidden}"，实际文本：${text}`)
      }
    })
  }

  await t.test('暂停与恢复必须经二级确认弹窗点击确认后才派发事件', async (st) => {
    const emitted = []
    const mounted = mountHeadless(st, Card, {
      status: {
        moduleEnabled: true,
        jobsEnabled: true,
        scheduleEnabled: true,
        schedulePaused: false,
        briefingAutoEnabled: true,
        aiReady: true,
        dailyTime: '06:30',
        retryDelayMinutes: 45,
        todayDate: '2026-11-26',
        todayStatus: 'WAITING_WINDOW',
        todayPublished: false,
        recentJobs: [],
      },
      busy: false,
      toggling: false,
      error: '',
      onTogglePause: (paused) => emitted.push(paused),
    })

    // 1. 点击「暂停自动采集」只打开弹窗，绝不直接派发。
    const pauseBtn = findAll(mounted.root, (n) => n.tag === 'button' && collectText(n).trim() === '暂停自动采集')[0]
    assert.ok(pauseBtn)
    pauseBtn.props.onClick()
    await nextTick()
    assert.deepEqual(emitted, [])
    assert.ok(collectText(mounted.root).includes('确认暂停每日自动采集？'))

    // 2. 点击「取消」关闭弹窗且不派发。
    const cancelBtn = findAll(mounted.root, (n) => n.tag === 'button' && collectText(n).trim() === '取消')[0]
    cancelBtn.props.onClick()
    await nextTick()
    assert.deepEqual(emitted, [])
    assert.ok(!collectText(mounted.root).includes('确认暂停每日自动采集？'))

    // 3. 重新点「暂停自动采集」并点「确认暂停」才派发 true。
    pauseBtn.props.onClick()
    await nextTick()
    const confirmBtn = findAll(mounted.root, (n) => n.tag === 'button' && collectText(n).trim() === '确认暂停')[0]
    confirmBtn.props.onClick()
    await nextTick()
    assert.deepEqual(emitted, [true])
  })
})

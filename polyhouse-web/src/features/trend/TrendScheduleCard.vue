<script setup lang="ts">
import { computed } from 'vue'
import { ElButton } from 'element-plus'
import type { TrendJob, TrendScheduleStatus, TrendScheduleTodayStatus } from '../../api/trend'
import { trendTime } from './presentation'

interface Props {
  status: TrendScheduleStatus | null
  busy: boolean
  error: string
}
interface Emits {
  refresh: []
}
const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const todayLabels: Record<
  TrendScheduleTodayStatus,
  { text: string; tone: string; pill: 'ready' | 'active' | 'warn' | 'idle' }
> = {
  DISABLED: { text: '自动采集未开启', tone: 'muted', pill: 'idle' },
  WAITING_WINDOW: { text: '等待今日采集窗口', tone: 'info', pill: 'active' },
  RUNNING: { text: '今日自动采集进行中', tone: 'info', pill: 'active' },
  PUBLISHED_AUTO: { text: '今日已自动发布', tone: 'ok', pill: 'ready' },
  PUBLISHED_MANUAL: { text: '今日已有日报，自动跳过', tone: 'ok', pill: 'ready' },
  RETRY_PENDING: { text: '首次采集失败，等待补偿窗口', tone: 'warn', pill: 'warn' },
  FAILED: { text: '今日自动采集未成功', tone: 'warn', pill: 'warn' },
  MISSED_WINDOW: { text: '已过今日采集窗口', tone: 'muted', pill: 'idle' },
}

const jobStateLabels: Record<TrendJob['state'], string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '已发布',
  PARTIAL: '已发布（部分缺稿）',
  FAILED: '失败',
  INTERRUPTED: '已中断',
}

const pillMeta = computed(() => {
  if (!props.status) return { text: props.busy ? '读取中' : '暂未读取', pill: 'idle' as const }
  return todayLabels[props.status.todayStatus]
})
</script>

<template>
  <section class="schedule-panel" aria-labelledby="schedule-heading">
    <header class="panel-head">
      <div>
        <div class="title-row">
          <h2 id="schedule-heading">每日自动采集状态</h2>
          <span class="status-pill" :class="pillMeta.pill">{{ pillMeta.text }}</span>
        </div>
        <p class="subtitle">
          后端定时受理与 GitHub Actions 唤醒共用单槽与 10 元月预算红线；本卡仅作只读展示。
        </p>
      </div>
      <ElButton :loading="busy" @click="emit('refresh')">刷新状态</ElButton>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else-if="!status" class="empty" role="status">正在读取自动采集状态…</p>

    <template v-else>
      <div class="grid">
        <div class="metric">
          <span class="label">每日自动日报</span>
          <strong class="value" :class="status.scheduleEnabled ? 'tone-ok' : 'tone-muted'">
            {{ status.scheduleEnabled ? '已开启' : '未开启' }}
          </strong>
          <span class="hint">
            北京时间 {{ status.dailyTime }}（补偿 +{{ status.retryDelayMinutes }} 分钟）
          </span>
        </div>

        <div class="metric">
          <span class="label">顺路自动生成 7 日简报</span>
          <strong class="value" :class="status.briefingAutoEnabled ? 'tone-ok' : 'tone-muted'">
            {{ status.briefingAutoEnabled ? '已开启' : '未开启' }}
          </strong>
          <span class="hint">
            {{ status.aiReady ? 'AI 配置就绪，失败不影响日报' : 'AI 未启用或未就绪' }}
          </span>
        </div>

        <div class="metric">
          <span class="label">今日受理状态（{{ status.todayDate }}）</span>
          <strong class="value" :class="`tone-${todayLabels[status.todayStatus].tone}`">
            {{ todayLabels[status.todayStatus].text }}
          </strong>
          <span class="hint">
            {{ status.todayPublished ? '今日已有公开日报' : '今日尚未发布公开日报' }}
          </span>
        </div>
      </div>

      <div class="recent">
        <h3>最近 5 条任务记录</h3>
        <p v-if="!status.recentJobs.length" class="empty">暂无历史任务记录。</p>
        <ul v-else class="job-list">
          <li v-for="item in status.recentJobs" :key="item.jobId" class="job-row">
            <span
              class="trigger"
              :class="item.trigger === 'SCHEDULED' ? 'trigger-auto' : 'trigger-manual'"
            >
              {{ item.trigger === 'SCHEDULED' ? '定时自动' : '屋主手动' }}
            </span>
            <span class="state">{{ jobStateLabels[item.state] }}</span>
            <span class="ai-flag">{{ item.aiRequested ? '含 AI 解读' : '仅基础资料' }}</span>
            <time class="time" :datetime="item.createdAt">{{ trendTime(item.createdAt) }}</time>
          </li>
        </ul>
      </div>
    </template>
  </section>
</template>

<style scoped>
.schedule-panel {
  margin: 20px 0;
  border: 1px solid #c9d8d9;
  border-radius: 14px;
  background: #fff;
  padding: clamp(18px, 2.4vw, 26px);
}
.panel-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px 16px;
}
.title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 10px;
}
h2 {
  margin: 0;
  font-size: 22px;
  line-height: 1.3;
}
.status-pill {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.4;
  white-space: nowrap;
}
.status-pill.idle {
  background: #edf2f4;
  color: #475c69;
}
.status-pill.active {
  background: #dbeaf5;
  color: #16486b;
}
.status-pill.ready {
  background: #d9ece3;
  color: #1c5840;
}
.status-pill.warn {
  background: #faebcf;
  color: #815619;
}
.subtitle,
.empty,
.error {
  margin: 6px 0 0;
  font-size: 14px;
  line-height: 1.55;
  color: #475c69;
}
.error {
  color: #943e31;
}
.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 15px;
}
.metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid #dce6ea;
  border-radius: 10px;
  background: #f8fbfc;
}
.label {
  font-size: 12.5px;
  color: #48606e;
}
.value {
  font-size: 16px;
  font-weight: 700;
}
.hint {
  font-size: 12px;
  line-height: 1.45;
  color: #536978;
  font-variant-numeric: tabular-nums;
}
.tone-ok {
  color: #1c5840;
}
.tone-info {
  color: #16486b;
}
.tone-warn {
  color: #815619;
}
.tone-muted {
  color: #475c69;
}
.recent {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #d6e0df;
}
.recent h3 {
  margin: 0 0 8px;
  font-size: 14px;
  color: #213951;
}
.job-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.job-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px 12px;
  padding: 9px 0;
  border-bottom: 1px dashed #dce6ea;
  font-size: 13.5px;
}
.job-row:last-child {
  border-bottom: none;
}
.trigger {
  font-size: 11.5px;
  font-weight: 650;
  padding: 2px 9px;
  border-radius: 999px;
  white-space: nowrap;
}
.trigger-auto {
  background: #e2f5f4;
  color: #0e5a63;
}
.trigger-manual {
  background: #edf2f4;
  color: #3a5261;
}
.state {
  font-weight: 600;
  color: #1d3548;
}
.ai-flag,
.time {
  font-size: 12.5px;
  color: #536978;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
@media (max-width: 768px) {
  .grid {
    grid-template-columns: 1fr;
  }
  .job-row {
    grid-template-columns: auto minmax(0, 1fr);
  }
}
</style>

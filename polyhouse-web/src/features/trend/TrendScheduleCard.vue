<script setup lang="ts">
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
defineProps<Props>()
const emit = defineEmits<Emits>()

const todayLabels: Record<TrendScheduleTodayStatus, { text: string; tone: string }> = {
  DISABLED: { text: '自动采集未开启', tone: 'muted' },
  WAITING_WINDOW: { text: '等待今日采集窗口', tone: 'info' },
  RUNNING: { text: '今日自动采集进行中', tone: 'info' },
  PUBLISHED_AUTO: { text: '今日已自动发布', tone: 'ok' },
  PUBLISHED_MANUAL: { text: '今日已有日报，自动跳过', tone: 'ok' },
  RETRY_PENDING: { text: '首次采集失败，等待补偿窗口', tone: 'warn' },
  FAILED: { text: '今日自动采集未成功', tone: 'warn' },
  MISSED_WINDOW: { text: '已过今日采集窗口', tone: 'muted' },
}

const jobStateLabels: Record<TrendJob['state'], string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '已发布',
  PARTIAL: '已发布（部分缺稿）',
  FAILED: '失败',
  INTERRUPTED: '已中断',
}
</script>

<template>
  <section class="panel" aria-labelledby="schedule-heading">
    <div class="head">
      <div>
        <h2 id="schedule-heading">每日自动采集状态</h2>
        <p class="sub">
          后端定时受理与 GitHub Actions 唤醒共用单槽与 10 元月预算红线；本卡仅作只读展示。
        </p>
      </div>
      <ElButton :loading="busy" @click="emit('refresh')">刷新状态</ElButton>
    </div>

    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-else-if="!status" class="empty">正在读取自动采集状态…</p>

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
.panel {
  margin: 18px 0 0;
  padding: 20px;
  border: 1px solid #d4e0e4;
  border-radius: 14px;
  background: #ffffff;
}
.head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.head h2 {
  margin: 0;
  font-size: 19px;
  color: #142f46;
}
.sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: #526877;
}
.alert {
  margin: 12px 0 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fdf0ed;
  color: #8f2d1b;
  font-size: 13px;
}
.empty {
  margin: 12px 0 0;
  font-size: 13px;
  color: #5d7280;
}
.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 14px;
}
.metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid #e1eaee;
  border-radius: 10px;
  background: #f8fbfc;
}
.label {
  font-size: 12px;
  color: #536978;
}
.value {
  font-size: 16px;
  font-weight: 700;
}
.hint {
  font-size: 12px;
  color: #5f7483;
  font-variant-numeric: tabular-nums;
}
.tone-ok {
  color: #0e5a63;
}
.tone-info {
  color: #164e78;
}
.tone-warn {
  color: #9a4716;
}
.tone-muted {
  color: #5b6d7a;
}
.recent {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #e4edf1;
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
  padding: 8px 0;
  border-bottom: 1px dashed #e1eaee;
  font-size: 13px;
}
.job-row:last-child {
  border-bottom: none;
}
.trigger {
  font-size: 11.5px;
  font-weight: 650;
  padding: 2px 8px;
  border-radius: 999px;
}
.trigger-auto {
  background: #e2f5f4;
  color: #0e5a63;
}
.trigger-manual {
  background: #eef3f7;
  color: #2f4b63;
}
.state {
  font-weight: 600;
  color: #1d3548;
}
.ai-flag,
.time {
  font-size: 12px;
  color: #5a7080;
  font-variant-numeric: tabular-nums;
}
@media (max-width: 720px) {
  .grid {
    grid-template-columns: 1fr;
  }
  .job-row {
    grid-template-columns: auto minmax(0, 1fr);
  }
}
</style>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { ElButton, ElIcon } from 'element-plus'
import { Clock, Collection, MagicStick, Promotion, Right } from '@element-plus/icons-vue'
import type { TrendJob } from '../../api/trend'
import { starTide } from '../../config/starTide'
import { trendTime } from './presentation'

interface Props {
  job: TrendJob | null
  tracking: boolean
  busy: boolean
  waiting: boolean
  error: string
  note: string
}
interface Emits {
  refresh: []
}
const props = defineProps<Props>()
defineEmits<Emits>()

const states: Record<TrendJob['state'], string> = {
  QUEUED: '已排队',
  RUNNING: '进行中',
  SUCCEEDED: '已发布',
  PARTIAL: '已发布，部分解读待补充',
  FAILED: '更新失败',
  INTERRUPTED: '任务已中断',
}
const steps = [
  { phase: 'QUEUED', label: '排队', icon: Clock },
  { phase: 'COLLECTING', label: '采集', icon: Collection },
  { phase: 'ANALYZING', label: 'AI 解读', icon: MagicStick },
  { phase: 'PUBLISHING', label: '发布', icon: Promotion },
] as const
const reasons: Record<string, string> = {
  LEASE_EXPIRED: '执行时限已过，到期后任务已停止。',
  EXECUTION_REVOKED: '任务的执行权限已收回。',
  EXECUTION_FAILED: '执行期间出错，本次更新未发布。',
}
const headline = computed(() => {
  if (props.job) return states[props.job.state]
  if (props.tracking) return props.busy ? '正在核对本次更新' : '本次更新待确认'
  return '暂无本页跟踪的任务'
})
const published = computed(() => props.job?.state === 'SUCCEEDED' || props.job?.state === 'PARTIAL')
const stopped = computed(() => props.job?.state === 'FAILED' || props.job?.state === 'INTERRUPTED')
const statusClass = computed(() => {
  const state = props.job?.state
  if (state === 'SUCCEEDED') return 'ready'
  if (state === 'PARTIAL') return 'warn'
  if (state === 'FAILED' || state === 'INTERRUPTED') return 'danger'
  if (state === 'QUEUED' || state === 'RUNNING') return 'active'
  return 'idle'
})
</script>

<template>
  <section class="task" aria-labelledby="task-title">
    <header class="panel-head">
      <div class="title-row">
        <h2 id="task-title">本次任务</h2>
        <span class="status-pill" :class="statusClass" aria-live="polite">{{ headline }}</span>
      </div>
      <ElButton v-if="tracking" :disabled="busy || waiting" @click="$emit('refresh')"
        >重查任务</ElButton
      >
    </header>
    <div class="body">
      <div class="result" aria-live="polite">
        <template v-if="published && job?.summary">
          <p>
            收录 {{ job.summary.total }} 项 · 新生成 {{ job.summary.generated }} 篇 · 有效复用
            {{ job.summary.reused }} 篇 · 沿用旧稿 {{ job.summary.stale }} 篇
          </p>
          <p v-if="job.summary.skipped || job.summary.missing">
            无需解读 {{ job.summary.skipped }} 项 · 缺少正文 {{ job.summary.missing }} 项
          </p>
          <p v-if="job.summary.rejected || job.summary.pending">
            本次生成记录：未通过校验 {{ job.summary.rejected }} 项 · 结果待核对
            {{ job.summary.pending }} 项
          </p>
          <details>
            <summary>资料与正文覆盖</summary>
            <p>取得 README {{ job.summary.readmeAvailable }} / {{ job.summary.total }} 项</p>
            <p v-if="job.summary.eligible">
              材料齐全且需要分析的 {{ job.summary.eligible }} 项中，{{
                job.summary.currentEligible
              }}
              项有当前有效解读。
            </p>
            <p v-else>本期没有材料齐全且需要分析的项目。</p>
            <p v-if="job.summary.missingDescription">
              缺少正文的项目中，{{ job.summary.missingDescription }} 项也没有仓库简介。
            </p>
          </details>
        </template>
        <p v-if="note">{{ note }}</p>
        <p v-if="job">
          {{ job.trigger === 'MANUAL' ? '手动触发' : '定时触发' }} ·
          {{ job.aiRequested ? '请求 AI 解读' : '仅采集资料' }}
        </p>
        <p v-if="job">受理于 {{ trendTime(job.createdAt) }}（北京时间）</p>
        <details v-if="job?.startedAt || job?.completedAt">
          <summary>执行时间</summary>
          <p v-if="job.startedAt">开始于 {{ trendTime(job.startedAt) }}（北京时间）</p>
          <p v-if="job.completedAt">结束于 {{ trendTime(job.completedAt) }}（北京时间）</p>
        </details>
        <p v-if="job?.reason">{{ reasons[job.reason] ?? '任务返回原因码：' + job.reason }}</p>
        <p v-if="stopped">此前发布的日报仍可阅读。</p>
        <RouterLink v-if="job?.reportId" :to="starTide.route"
          >阅读最新日报 <ElIcon aria-hidden="true"><Right /></ElIcon
        ></RouterLink>
      </div>
      <ol aria-label="日报发布阶段">
        <li
          v-for="step in steps"
          :key="step.phase"
          :aria-current="job?.phase === step.phase ? 'step' : undefined"
          :class="{ published: published && step.phase === 'PUBLISHING' }"
        >
          <span class="marker"
            ><ElIcon aria-hidden="true"><component :is="step.icon" /></ElIcon
          ></span>
          <strong>{{ step.label }}</strong>
          <small v-if="step.phase === job?.phase">{{ stopped ? '停止于此' : '当前阶段' }}</small>
          <small v-else-if="step.phase === 'ANALYZING'">{{
            job && !job.aiRequested ? '本次跳过' : '按需'
          }}</small>
          <small v-else-if="published && step.phase === 'PUBLISHING'">已发布</small>
        </li>
      </ol>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.task {
  margin: 20px 0;
  border: 1px solid #c9d8d9;
  border-radius: 14px;
  background: #fff;
  padding: clamp(18px, 2.4vw, 26px);
}
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px 16px;
  flex-wrap: wrap;
}
.title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 10px;
}
.body {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-top: 14px;
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
.status-pill.danger {
  background: #f9e2de;
  color: #943e31;
}
.result {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.result p,
.error {
  margin: 6px 0 0;
  font-size: 14px;
  line-height: 1.55;
  font-variant-numeric: tabular-nums;
}
.result > p:first-child {
  margin-top: 0;
}
.result p {
  color: #435765;
}
.error {
  color: #903624;
}
summary {
  width: fit-content;
  padding-block: 12px;
  min-height: 44px;
  font-size: 14px;
  color: #28566f;
  cursor: pointer;
}
a {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 44px;
  color: #155c72;
  font-weight: 650;
}
a:hover {
  text-decoration: underline;
}
ol {
  flex: 1.3;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
  padding: 0;
  list-style: none;
}
li {
  position: relative;
  text-align: center;
  font-size: 14px;
  color: #465e6a;
}
li:not(:last-child)::after {
  content: '';
  position: absolute;
  left: calc(50% + 20px);
  right: calc(-50% + 20px);
  top: 17px;
  border-top: 1px solid #adbfbd;
}
.marker {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  margin: 0 auto 6px;
  border: 1px solid #bdcecd;
  border-radius: 50%;
  background: #f4f7f5;
}
li[aria-current] .marker {
  background: #174f61;
  color: white;
  border-color: #174f61;
}
.published .marker {
  background: #ceeadc;
  color: #145448;
}
small {
  display: block;
  font-size: 12px;
}
@media (max-width: 700px) {
  .body {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }
  ol {
    padding-top: 12px;
    border-top: 1px solid #dce5e2;
  }
}
</style>

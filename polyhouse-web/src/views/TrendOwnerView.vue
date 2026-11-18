<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { ElButton } from 'element-plus'
import { starTide } from '../config/starTide'
import { type TrendJob } from '../api/trend'
import { trendTime } from '../features/trend/presentation'
import { useTrendOwner } from '../features/trend/useTrendOwner'
import { useAccountUiStore } from '../stores/useAccountUiStore'
import { useUserStore } from '../stores/useUserStore'

const user = useUserStore()
const accountUi = useAccountUiStore()
const {
  allowed,
  job,
  fees,
  intent,
  knownJobId,
  jobBusy,
  feesBusy,
  jobError,
  feeError,
  submissionNote,
  active,
  waiting,
  refreshJob,
  submit,
  refreshFees,
} = useTrendOwner()

const state: Record<TrendJob['state'], string> = {
  QUEUED: '已排队',
  RUNNING: '进行中',
  SUCCEEDED: '已发布',
  PARTIAL: '已发布，部分解读待补充',
  FAILED: '更新失败',
  INTERRUPTED: '任务已中断',
}
const phase: Record<TrendJob['phase'], string> = {
  QUEUED: '等待执行',
  COLLECTING: '采集 GitHub 项目',
  ANALYZING: '生成项目解读',
  PUBLISHING: '发布日报',
  DONE: '完成',
}
const stateTitle = computed(() => (job.value ? state[job.value.state] : '暂无本页跟踪的任务'))
const formatter = new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  minimumFractionDigits: 2,
  maximumFractionDigits: 6,
})

function yuan(micros: number): string {
  return formatter.format(micros / 1_000_000)
}
</script>

<template>
  <div class="owner-page">
    <RouterLink :to="starTide.route" class="back">返回星潮日报</RouterLink>
    <header>
      <h1>星潮屋主工作台</h1>
      <p>手动更新日报与查看本月 AI 费用。</p>
    </header>
    <p v-if="user.status === 'checking'" role="status">正在核对登录状态…</p>
    <section v-else-if="!allowed" class="panel" aria-labelledby="owner-gate">
      <h2 id="owner-gate">屋主专用</h2>
      <p v-if="user.status === 'unavailable'">暂时无法核对会话，请重试连接。</p>
      <p v-else-if="user.requiresPasswordChange">请先修改初始密码。</p>
      <p v-else>请使用屋主账号登录。</p>
      <ElButton v-if="user.status === 'unavailable'" @click="user.restoreSession()"
        >重试连接</ElButton
      >
      <ElButton v-else-if="!user.isLoggedIn" @click="accountUi.openLogin">登录</ElButton>
    </section>
    <template v-else>
      <section class="panel" aria-labelledby="update-title">
        <h2 id="update-title">日报更新</h2>
        <p>手动更新和定时更新共用任务队列。启用 AI 时，更新可能产生费用。</p>
        <div class="actions">
          <ElButton type="primary" :loading="jobBusy" :disabled="active || waiting" @click="submit">
            {{ intent ? '继续同一次更新' : '手动更新日报' }}
          </ElButton>
          <ElButton v-if="intent || knownJobId" :disabled="jobBusy || waiting" @click="refreshJob"
            >重查任务</ElButton
          >
        </div>
        <p v-if="jobError" class="error" role="alert">{{ jobError }}</p>
        <div class="task" aria-live="polite">
          <h3>{{ stateTitle }}</h3>
          <p v-if="submissionNote">{{ submissionNote }}</p>
          <template v-if="job">
            <p>
              处理阶段：{{ phase[job.phase] }} ·
              {{ job.trigger === 'MANUAL' ? '手动触发' : '定时触发' }} ·
              {{ job.aiRequested ? '请求 AI 解读' : '仅采集资料' }}
            </p>
            <p>受理于 {{ trendTime(job.createdAt) }}（北京时间）</p>
            <p v-if="job.state === 'FAILED' || job.state === 'INTERRUPTED'">
              此前发布的日报仍可阅读。
            </p>
            <RouterLink v-if="job.reportId" :to="starTide.route">阅读最新日报</RouterLink>
          </template>
        </div>
      </section>
      <section class="panel" aria-labelledby="fees-title">
        <div class="panel-heading">
          <h2 id="fees-title">
            AI 费用<span v-if="fees"> · {{ fees.month }}</span>
          </h2>
          <ElButton :loading="feesBusy" @click="refreshFees()">刷新费用</ElButton>
        </div>
        <p v-if="feeError" class="error" role="alert">{{ feeError }}</p>
        <p v-else-if="!fees && feesBusy" role="status">正在读取费用账本…</p>
        <template v-if="fees">
          <p>
            应用账本按人民币计算，可与平台控制台核对。{{
              fees.suspended ? '本月 AI 费用闸门已暂停。' : ''
            }}
          </p>
          <dl class="amounts">
            <div>
              <dt>预算上限</dt>
              <dd>{{ yuan(fees.budgetMicros) }}</dd>
            </div>
            <div>
              <dt>已入账</dt>
              <dd>{{ yuan(fees.accountedMicros) }}</dd>
            </div>
            <div>
              <dt>在途及待核对占用</dt>
              <dd>{{ yuan(fees.heldMicros) }}</dd>
            </div>
            <div>
              <dt>剩余可用</dt>
              <dd>{{ yuan(fees.remainingMicros) }}</dd>
            </div>
          </dl>
          <p v-if="fees.conservativeMicros">
            已入账中有 {{ yuan(fees.conservativeMicros) }} 按保守金额计入。
          </p>
          <h3>在途及待核对请求</h3>
          <p v-if="!fees.pending.length">当前没有在途或待核对的请求。</p>
          <ul v-else class="pending">
            <li v-for="entry in fees.pending" :key="entry.requestId">
              <span>仓库编号 {{ entry.repositoryId }}</span>
              <span
                >{{ entry.state === 'UNKNOWN' ? '待核对' : '在途' }} ·
                {{ yuan(entry.reservedMicros) }}</span
              >
              <span>{{ trendTime(entry.createdAt) }}（北京时间）</span>
            </li>
          </ul>
          <ElButton v-if="fees.nextAfter" :loading="feesBusy" @click="refreshFees(true)"
            >查看下一页请求</ElButton
          >
        </template>
      </section>
    </template>
  </div>
</template>

<style scoped>
.owner-page {
  width: min(970px, calc(100% - 40px));
  margin: auto;
  padding-block: 24px 48px;
  color: #2b2336;
}
.back,
.task a {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  color: #8a2955;
  text-decoration: underline;
  text-underline-offset: 3px;
}
header {
  margin-block: 6px 20px;
}
h1,
h2,
h3,
p,
dl,
dd {
  margin: 0;
}
h1 {
  font-size: clamp(29px, 6vw, 48px);
  line-height: 1.2;
}
h2 {
  font-size: 21px;
}
h3 {
  font-size: 16px;
}
p {
  margin-block: 8px;
  line-height: 1.65;
  color: #574d61;
}
.panel {
  border: 1px solid #ded5df;
  border-radius: 14px;
  padding: clamp(16px, 4vw, 26px);
  margin-top: 16px;
  min-width: 0;
}
.actions,
.panel-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.panel-heading {
  justify-content: space-between;
}
.panel-heading h2 span {
  color: #574d61;
  font-weight: 400;
}
.actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.task {
  margin-top: 14px;
  padding: 14px 16px;
  background: #f6f1f6;
  border-radius: 8px;
}
.task p {
  font-size: 14px;
}
.error {
  color: #9a2753;
}
.amounts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-block: 14px 20px;
}
.amounts > div {
  padding: 11px;
  background: #f6f1f6;
  border-radius: 8px;
}
dt {
  font-size: 13px;
  color: #574d61;
}
dd {
  font-weight: 650;
  margin-top: 4px;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}
.pending {
  padding: 0;
  margin-block: 8px;
  list-style: none;
}
.pending li {
  padding-block: 7px;
  border-top: 1px solid #ded5df;
  display: flex;
  flex-wrap: wrap;
  gap: 6px 20px;
  overflow-wrap: anywhere;
}
@media (max-width: 640px) {
  .amounts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>

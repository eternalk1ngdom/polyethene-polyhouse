<script setup lang="ts">
import { computed } from 'vue'
import { ElButton } from 'element-plus'
import type { TrendFees } from '../../api/trend'
import { trendTime } from './presentation'

interface Props {
  fees: TrendFees | null
  busy: boolean
  error: string
}
interface Emits {
  refresh: [append?: boolean]
}
const props = defineProps<Props>()
defineEmits<Emits>()

const formatter = new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  minimumFractionDigits: 2,
  maximumFractionDigits: 6,
})
const intFormatter = new Intl.NumberFormat('zh-CN')

function yuan(micros: number): string {
  return formatter.format(micros / 1_000_000)
}

function count(value: number): string {
  return intFormatter.format(value)
}

const cacheHitRate = computed(() => {
  const usage = props.fees?.usage
  if (!usage || usage.promptTokens <= 0) return '—'
  return `${Math.round((usage.cacheHitTokens / usage.promptTokens) * 100)}%`
})
</script>

<template>
  <section class="ledger" aria-labelledby="fees-title">
    <header class="panel-head">
      <div>
        <div class="title-row">
          <h2 id="fees-title">本月 AI 费用</h2>
          <span v-if="fees?.suspended" class="status-pill warn">已暂停</span>
        </div>
        <p>
          <template v-if="fees">{{ fees.month }} · </template>双口径对账，人民币（CNY）
        </p>
      </div>
      <ElButton :loading="busy" @click="$emit('refresh')">刷新费用</ElButton>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else-if="!fees && busy" role="status">正在读取费用账本…</p>
    <template v-if="fees">
      <dl class="amounts">
        <div class="accountedMicros">
          <dt>安全预算已用</dt>
          <dd>{{ yuan(fees.accountedMicros) }}</dd>
          <span class="sub-hint">按高峰全价控制 10 元闸门</span>
        </div>
        <div class="estimatedActual">
          <dt>预计平台实扣</dt>
          <dd>{{ fees.usage ? yuan(fees.usage.estimatedActualMicros) : '—' }}</dd>
          <span class="sub-hint">含缓存命中与工作日空闲 5 折，不含法定调休节假日</span>
        </div>
        <div class="heldMicros">
          <dt>在途及待核对占用</dt>
          <dd>{{ yuan(fees.heldMicros) }}</dd>
        </div>
        <div class="remainingMicros">
          <dt>剩余安全可用</dt>
          <dd>{{ yuan(fees.remainingMicros) }}</dd>
          <span class="sub-hint">月上限 {{ yuan(fees.budgetMicros) }}</span>
        </div>
      </dl>

      <section v-if="fees.usage" class="usage-box" aria-label="本月 Token 汇总明细">
        <h3>本月 Token 汇总明细</h3>
        <dl class="usage-grid">
          <div>
            <dt>已结算调用</dt>
            <dd>{{ count(fees.usage.settledRequests) }} 次</dd>
          </div>
          <div>
            <dt>输入总计</dt>
            <dd>{{ count(fees.usage.promptTokens) }}</dd>
          </div>
          <div>
            <dt>缓存命中（命中率）</dt>
            <dd>{{ count(fees.usage.cacheHitTokens) }}（{{ cacheHitRate }}）</dd>
          </div>
          <div>
            <dt>输出总计</dt>
            <dd>{{ count(fees.usage.completionTokens) }}</dd>
          </div>
        </dl>
        <p class="reconcile-note">
          便于直接对照 DeepSeek 控制台用量明细核账；若本地与其他环境共用同一 API
          Key，平台账单可能高于本应用统计。
        </p>
      </section>

      <p v-if="fees.conservativeMicros">
        其中 {{ yuan(fees.conservativeMicros) }} 已按保守金额入账。
      </p>
      <details v-if="fees.suspended" class="suspended">
        <summary>本月 AI 已暂停 · 查看原因</summary>
        <p>账本记录了异常用量，本月的 AI 费用闸门已暂停。可与平台账单核对。</p>
      </details>
      <details class="requests">
        <summary>在途及待核对请求</summary>
        <p v-if="!fees.pending.length">当前没有在途或待核对的请求。</p>
        <ul v-else>
          <li v-for="entry in fees.pending" :key="entry.requestId">
            <span>{{
              entry.chargeKind === 'BRIEFING'
                ? `7 日风口简报 ${entry.repositoryId.replace(/^briefing:/, '')}`
                : `仓库编号 ${entry.repositoryId}`
            }}</span>
            <strong
              >{{ entry.state === 'UNKNOWN' ? '待核对' : '在途' }} ·
              {{ yuan(entry.reservedMicros) }}</strong
            >
            <span>{{ trendTime(entry.createdAt) }}（北京时间）</span>
          </li>
        </ul>
        <ElButton v-if="fees.nextAfter" :loading="busy" @click="$emit('refresh', true)"
          >查看下一页请求</ElButton
        >
      </details>
    </template>
  </section>
</template>

<style scoped>
.ledger {
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
.status-pill.warn {
  background: #faebcf;
  color: #815619;
}
p {
  margin: 5px 0;
  color: #475c69;
  line-height: 1.55;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}
.amounts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 15px 0;
}
.amounts div {
  display: flex;
  flex-direction: column;
  padding: 14px clamp(10px, 1.6vw, 20px);
  background: #e6eef0;
  border-radius: 10px;
}
.amounts .estimatedActual {
  background: #e2f5f4;
}
.amounts .heldMicros {
  background: #faebcf;
}
.amounts .remainingMicros {
  background: #d9ece3;
}
.sub-hint {
  margin-top: 4px;
  font-size: 11.5px;
  line-height: 1.4;
  color: #48606e;
}
dt {
  font-size: 13px;
  color: #384e5d;
}
dd {
  margin: 5px 0 0;
  font-weight: 650;
  font-size: clamp(16px, 2vw, 24px);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.usage-box {
  margin: 14px 0;
  padding: 14px 16px;
  border: 1px solid #dce6ea;
  border-radius: 10px;
  background: #f8fbfc;
}
.usage-box h3 {
  margin: 0 0 10px;
  font-size: 14px;
  color: #213951;
}
.usage-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px 14px;
  margin: 0;
}
.usage-grid dd {
  font-size: 15px;
  margin-top: 2px;
  color: #142f46;
}
.reconcile-note {
  margin: 10px 0 0;
  font-size: 12px;
  color: #536978;
}
details {
  padding: 0 3px 4px;
  border-bottom: 1px solid #c8d6d7;
}
details:last-of-type {
  border-bottom: 0;
  padding-bottom: 0;
}
summary {
  width: fit-content;
  max-width: 100%;
  min-height: 44px;
  padding: 12px 0;
  cursor: pointer;
  font-size: 14px;
  font-weight: 650;
}
summary:hover {
  color: #116274;
}
.suspended {
  color: #815619;
}
.error {
  color: #943e31;
}
ul {
  margin: 0;
  padding: 0;
  list-style: none;
}
li {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
  padding: 9px 0;
  border-top: 1px solid #d6e0df;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
li span:last-child {
  color: #475c69;
}
@media (max-width: 640px) {
  .amounts,
  .usage-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>

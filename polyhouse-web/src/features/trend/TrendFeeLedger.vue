<script setup lang="ts">
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
defineProps<Props>()
defineEmits<Emits>()
const amounts = [
  { key: 'accountedMicros', label: '已入账' },
  { key: 'heldMicros', label: '在途及待核对占用' },
  { key: 'remainingMicros', label: '剩余可用' },
  { key: 'budgetMicros', label: '月预算上限' },
] as const
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
  <section class="ledger" aria-labelledby="fees-title">
    <header>
      <div>
        <h2 id="fees-title">本月 AI 费用</h2>
        <p>
          <template v-if="fees">{{ fees.month }} · </template>应用估算，人民币（CNY）
        </p>
      </div>
      <ElButton :loading="busy" @click="$emit('refresh')">刷新费用</ElButton>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else-if="!fees && busy" role="status">正在读取费用账本…</p>
    <template v-if="fees">
      <dl class="amounts">
        <div v-for="amount in amounts" :key="amount.key" :class="amount.key">
          <dt>{{ amount.label }}</dt>
          <dd>{{ yuan(fees[amount.key]) }}</dd>
        </div>
      </dl>
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
  border-top: 2px solid #254554;
  padding-top: 17px;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
h2 {
  margin: 0;
  font-size: 22px;
}
p {
  margin: 5px 0;
  color: #475c69;
  line-height: 1.55;
  font-size: 14px;
}
.amounts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 15px 0;
}
.amounts div {
  padding: 14px clamp(10px, 1.6vw, 20px);
  background: #e6eef0;
  border-radius: 10px;
}
.amounts .heldMicros {
  background: #faebcf;
}
.amounts .remainingMicros {
  background: #d9ece3;
}
.amounts .budgetMicros {
  background: #e9e7ee;
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
details {
  padding: 0 3px 4px;
  border-bottom: 1px solid #c8d6d7;
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
  overflow-wrap: anywhere;
}
li span:last-child {
  color: #475c69;
}
@media (max-width: 640px) {
  .amounts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>

<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElIcon, ElProgress } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import type { TargetKbBatchRow, TargetKbBatchSummary } from '../core/targetKbBatch'
import { formatBytes } from '../utils/byteSize'

/** 批量结果的只读视图；调度、打包与取消都由面板负责。 */
interface Props {
  /** 逐张状态，顺序与选择顺序一致。 */
  rows: readonly TargetKbBatchRow[]
  /** 当前汇总。 */
  summary: TargetKbBatchSummary
  /** 是否正在逐张处理；没有它就无法区分"等着开始"和"正在跑"。 */
  running: boolean
  /** 是否正在打包 ZIP。 */
  packing: boolean
  /** ZIP 是否已就绪。 */
  zipReady: boolean
  /** ZIP 字节数，就绪后展示。 */
  zipBytes: number
}

const props = defineProps<Props>()

/** 下载交给面板，子组件不持有 Blob 地址；放弃结果同样由面板清理。 */
interface Emits {
  download: []
  discard: []
}
const emit = defineEmits<Emits>()

/** 进度按"已出结果（成功或失败）"计算，正在跑的那张不算完成。 */
const percent = computed(() => {
  if (props.summary.total === 0) return 0
  return Math.round(((props.summary.done + props.summary.failed) / props.summary.total) * 100)
})

/** 失败项单独列出，用户才知道 ZIP 里少了什么。 */
const failedRows = computed(() => props.rows.filter((row) => row.status === 'failed'))

/** 当前正在处理第几张（从 1 开始），用于"正在处理 3 / 8"。 */
const activeOrder = computed(() => props.summary.done + props.summary.failed + 1)

/**
 * 体积对比只统计成功项。
 * 把失败文件的体积算进"压缩前"，会让人以为整体压缩率很高，属于误导。
 */
const totalsText = computed(() =>
  props.summary.done > 0
    ? `成功项 ${formatBytes(props.summary.doneInputBytes)} → ${formatBytes(props.summary.outputBytes)}`
    : '尚无成功项',
)

function stateText(row: TargetKbBatchRow): string {
  // 原样返回的项输出体积等于输入，写"无需压缩"比再报一遍同样的数字清楚。
  if (row.status === 'done') return row.passthrough ? '无需压缩' : formatBytes(row.outputBytes ?? 0)
  if (row.status === 'failed') return '失败'
  if (row.status === 'running') return '处理中'
  return '等待'
}
</script>

<template>
  <div class="batch">
    <p v-if="!running && summary.pending === summary.total" role="status" class="headline">
      待处理 {{ summary.total }} 张，点「开始压缩」开始
    </p>
    <p v-else-if="summary.pending > 0" role="status" class="headline">
      正在处理第 {{ Math.min(activeOrder, summary.total) }} / {{ summary.total }} 张
    </p>
    <p v-else-if="packing" role="status" class="headline">正在打包 ZIP…</p>
    <p v-else role="status" class="headline">
      成功 {{ summary.done }} 张<template v-if="summary.failed"
        >，失败 {{ summary.failed }} 张</template
      >
    </p>

    <ElProgress
      v-if="(running && summary.pending > 0) || packing"
      :percentage="percent"
      :show-text="false"
      :stroke-width="8"
    />

    <p class="totals">{{ totalsText }}</p>

    <ul class="rows">
      <li v-for="(row, index) in rows" :key="index" :class="row.status">
        <span class="order" aria-hidden="true">{{ index + 1 }}</span>
        <span class="name">{{ row.name }}</span>
        <span class="size">{{ formatBytes(row.size) }}</span>
        <span class="state">{{ stateText(row) }}</span>
      </li>
    </ul>

    <div v-if="failedRows.length" class="failures" role="alert">
      <p>以下 {{ failedRows.length }} 张未包含在 ZIP 中：</p>
      <ul>
        <li v-for="row in failedRows" :key="row.name">
          <strong>{{ row.name }}</strong
          >：{{ row.message }}
        </li>
      </ul>
    </div>

    <div class="actions">
      <ElButton v-if="zipReady" type="primary" @click="emit('download')">
        <ElIcon><Download /></ElIcon>下载 ZIP（{{ formatBytes(zipBytes) }}）
      </ElButton>
      <!-- 不下载也应该能收场：结果会一直占着内存与右侧区域，没有出口就只能靠改参数或换图绕开。 -->
      <ElButton v-if="summary.done > 0 || summary.failed > 0" text @click="emit('discard')">
        放弃结果
      </ElButton>
    </div>
  </div>
</template>

<style scoped>
.headline {
  font-size: 18px;
  color: var(--ph-ink);
  margin: 4px 0 16px;
}
.totals {
  margin: 14px 0 0;
  font-size: 13px;
  color: var(--ph-muted);
}
.rows {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
  max-height: 320px;
  overflow: auto;
}
.rows li {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) 76px 68px;
  gap: 10px;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--ph-line);
  font-size: 13px;
}
.rows li:last-child {
  border-bottom: 0;
}
.order {
  color: var(--ph-muted);
  font-variant-numeric: tabular-nums;
}
.name {
  overflow-wrap: anywhere;
}
.size {
  color: var(--ph-muted);
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.state {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.rows li.done .state {
  color: var(--ph-ink);
}
.rows li.failed .state {
  color: var(--ph-accent-strong);
}
.rows li.running .state,
.rows li.pending .state {
  color: var(--ph-muted);
}
.failures {
  margin-top: 16px;
  padding: 14px 16px;
  border: 1px solid var(--ph-line);
  border-radius: 10px;
  background: var(--ph-soft);
  overflow-wrap: anywhere;
}
.failures p {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--ph-ink);
}
.failures ul {
  margin: 0;
  padding-left: 18px;
  color: var(--ph-muted);
  font-size: 13px;
  line-height: 1.8;
}
.failures strong {
  color: var(--ph-ink);
  font-weight: 500;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}
.actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
@media (max-width: 700px) {
  .rows li {
    grid-template-columns: 20px minmax(0, 1fr) 66px 60px;
    gap: 8px;
    font-size: 12px;
  }
}
</style>

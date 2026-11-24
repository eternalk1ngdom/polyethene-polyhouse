<script setup lang="ts">
import { computed } from 'vue'
import { ElButton } from 'element-plus'
import type { TrendBriefing } from '../../api/trend'
import { trendTime } from './presentation'

interface Props {
  /** 最新一期 7 日风口简报状态；尚未有已发布日报或加载失败时为 null。 */
  briefing: TrendBriefing | null
  /** 正在刷新简报状态。 */
  busy: boolean
  /** 正在同步生成风口简报（通常 3～12 秒）。 */
  generating: boolean
  /** 处于 15 秒付费冷却期。 */
  waiting: boolean
  /** 状态读取或触发失败的提示。 */
  error: string
  /** 最近一次生成动作的结果说明。 */
  note: string
}

interface Emits {
  refresh: []
  generate: []
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const statusText = computed(() => {
  if (!props.briefing) return '暂无日报'
  if (props.briefing.status === 'ABSENT') return '未生成'
  return props.briefing.stale ? '快照已更新' : '已是最新'
})

const statusClass = computed(() => {
  if (!props.briefing || props.briefing.status === 'ABSENT') return 'absent'
  return props.briefing.stale ? 'stale' : 'ready'
})

const upToDate = computed(() => props.briefing?.status === 'READY' && !props.briefing.stale)

const canGenerate = computed(
  () =>
    Boolean(props.briefing) &&
    Boolean(props.briefing?.eligible) &&
    !upToDate.value &&
    !props.generating &&
    !props.waiting,
)

const buttonLabel = computed(() => {
  if (props.generating) return '正在生成风口简报…'
  if (props.waiting) return '触发冷却中'
  if (upToDate.value) return '本期已是最新'
  if (props.briefing?.status === 'READY' && props.briefing.stale) return '更新本期风口简报'
  return '生成本期风口简报'
})

const thresholdHint = computed(() => {
  if (!props.briefing) return ''
  const { currentCoveredDays, radar3ReadyCount, eligible, ineligibleReason } = props.briefing
  const base = `近 7 日已覆盖 ${currentCoveredDays}/7 天 · 含风向观察仓库 ${radar3ReadyCount} 个`
  if (eligible) {
    const tier = currentCoveredDays >= 5 ? '充分样本' : '有限样本'
    return `${base}（已达门槛 · ${tier}）`
  }
  if (ineligibleReason === 'INSUFFICIENT_COVERED_DAYS') {
    return `${base}（未达门槛：需至少覆盖 3 天已发布日报）`
  }
  if (ineligibleReason === 'INSUFFICIENT_RADAR3_READY') {
    return `${base}（未达门槛：需至少 5 个含风向观察的解读仓库）`
  }
  return `${base}（当前候选样本不足）`
})
</script>

<template>
  <section class="briefing-panel" aria-labelledby="briefing-control-title">
    <header class="panel-head">
      <div>
        <div class="title-row">
          <h2 id="briefing-control-title">7 日风口简报</h2>
          <span class="status-pill" :class="statusClass">{{ statusText }}</span>
        </div>
        <p class="subtitle">
          <template v-if="briefing">
            锚点日 {{ briefing.anchorDate }} · 单次模型调用，同指纹输入零费用复用
          </template>
          <template v-else> 按需生成近 7 日跨项目技术风口导读（需至少 3 天样本） </template>
        </p>
      </div>
      <div class="actions">
        <ElButton :loading="busy" :disabled="generating" @click="emit('refresh')"
          >刷新状态</ElButton
        >
        <ElButton
          class="trigger-btn"
          type="primary"
          :loading="generating"
          :disabled="!canGenerate"
          @click="emit('generate')"
        >
          {{ buttonLabel }}
        </ElButton>
      </div>
    </header>

    <p v-if="briefing" class="threshold">{{ thresholdHint }}</p>
    <p v-if="note" class="note" role="status">{{ note }}</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <div v-if="briefing?.status === 'READY' && briefing.headline" class="current-preview">
      <strong class="preview-headline">{{ briefing.headline }}</strong>
      <span v-if="briefing.generatedAt" class="preview-time">
        生成于 {{ trendTime(briefing.generatedAt) }}（北京时间）
      </span>
    </div>
  </section>
</template>

<style scoped>
.briefing-panel {
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

.status-pill.absent {
  background: #edf2f4;
  color: #475c69;
}

.status-pill.ready {
  background: #d9ece3;
  color: #1c5840;
}

.status-pill.stale {
  background: #faebcf;
  color: #815619;
}

.subtitle,
.threshold,
.note,
.error {
  margin: 6px 0 0;
  font-size: 14px;
  line-height: 1.55;
  color: #475c69;
}

.threshold {
  font-variant-numeric: tabular-nums;
}

.note {
  color: #16486b;
  font-weight: 600;
}

.error {
  color: #943e31;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.trigger-btn {
  --el-button-bg-color: #173e55;
  --el-button-border-color: #173e55;
  --el-button-hover-bg-color: #102d40;
  --el-button-hover-border-color: #102d40;
  --el-button-active-bg-color: #102d40;
  --el-button-active-border-color: #081f30;
  --el-button-disabled-bg-color: #546d75;
  --el-button-disabled-border-color: #546d75;
}

.current-preview {
  margin-top: 12px;
  padding: 10px 14px;
  border-radius: 9px;
  background: #f4f8f9;
  border: 1px solid #d5e0e2;
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 6px 14px;
}

.preview-headline {
  font-size: 14.5px;
  color: #142f46;
  overflow-wrap: anywhere;
}

.preview-time {
  font-size: 12.5px;
  color: #475c69;
  font-variant-numeric: tabular-nums;
}
</style>

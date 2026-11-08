<script setup lang="ts">
import { ElButton, ElIcon } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'

interface Props {
  /** 每个工具/结果区域有独立目标，确保展开按钮能被读屏正确关联。 */
  id: string
  /** 可由待恢复来源主动展开；收起不改变工具参数。 */
  modelValue: boolean
  /** 标题描述这组低频功能，不把所有按钮塞进菜单。 */
  title?: string
  /** 收起时仍展示已生效的非默认参数或待处理事项。 */
  summary: string
}
interface Emits {
  /** 只管理展示状态，不销毁内部草稿/分享生命周期。 */
  'update:modelValue': [value: boolean]
}
withDefaults(defineProps<Props>(), { title: '高级选项' })
const emit = defineEmits<Emits>()
</script>

<template>
  <section class="advanced-options" :aria-labelledby="`${id}-title`">
    <ElButton
      class="advanced-toggle"
      :aria-expanded="modelValue"
      :aria-controls="id"
      @click="emit('update:modelValue', !modelValue)"
    >
      <span class="toggle-copy">
        <span :id="`${id}-title`" class="toggle-title">{{ title }}</span>
        <span class="toggle-summary">{{ summary }}</span>
      </span>
      <ElIcon class="toggle-icon" :class="{ expanded: modelValue }" aria-hidden="true"
        ><ArrowDown
      /></ElIcon>
    </ElButton>
    <!-- v-show 保留内部服务的初始化、来源确认与离页保存，只将收起内容移出可见/Tab区域。 -->
    <div v-show="modelValue" :id="id" class="advanced-content"><slot /></div>
  </section>
</template>

<style scoped>
.advanced-options {
  margin-top: 22px;
}
.advanced-toggle {
  width: 100%;
  height: auto;
  padding: 12px 14px;
  border-radius: 10px;
  color: var(--ph-ink);
  white-space: normal;
  text-align: left;
}
.advanced-toggle :deep(> span) {
  display: flex;
  width: 100%;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}
.toggle-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
}
.toggle-title {
  font-size: 14px;
}
.toggle-summary {
  color: var(--ph-muted);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.toggle-icon {
  flex: none;
  transition: transform 0.18s;
}
.toggle-icon.expanded {
  transform: rotate(180deg);
}
.advanced-content {
  margin-top: 16px;
}
</style>

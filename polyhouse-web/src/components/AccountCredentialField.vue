<script setup lang="ts">
import { ref, watch } from 'vue'
import { CopyDocument, Hide, View } from '@element-plus/icons-vue'
import { ElButton, ElInput } from 'element-plus'

interface Props {
  label: string
  value: string
  secret?: boolean
}

const props = defineProps<Props>()
const visible = ref(false)
const feedback = ref('')
watch(
  () => props.value,
  () => {
    visible.value = false
    feedback.value = ''
  },
  { flush: 'sync' },
)

async function copy(): Promise<void> {
  const value = props.value
  try {
    await navigator.clipboard.writeText(value)
    if (props.value === value) feedback.value = `已复制${props.label}`
  } catch {
    if (props.value === value) {
      feedback.value = props.secret ? '复制失败，请显示密码后手动复制。' : '复制失败，请手动复制。'
    }
  }
}
</script>

<template>
  <div class="credential-field">
    <span>{{ label }}</span>
    <div class="credential-controls">
      <ElInput
        :aria-label="label"
        :model-value="secret && !visible ? '••••••••' : value"
        :type="secret && !visible ? 'password' : 'text'"
        readonly
        autocomplete="off"
      />
      <ElButton
        v-if="secret"
        :icon="visible ? Hide : View"
        :aria-label="visible ? '隐藏密码' : '显示密码'"
        :title="visible ? '隐藏密码' : '显示密码'"
        @click="visible = !visible"
      />
      <ElButton
        :icon="CopyDocument"
        :aria-label="`复制${label}`"
        :title="`复制${label}`"
        @click="copy"
      />
    </div>
    <div class="credential-feedback" role="status">{{ feedback }}</div>
  </div>
</template>

<style scoped>
.credential-field {
  color: var(--ph-muted);
  font-size: 14px;
}
.credential-controls {
  display: flex;
  gap: 8px;
  margin-top: 6px;
}
.credential-controls :deep(.el-input) {
  min-width: 0;
}
.credential-controls :deep(.el-button) {
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  margin: 0;
  padding: 0;
}
.credential-feedback:not(:empty) {
  margin-top: 6px;
}
</style>

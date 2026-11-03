<script setup lang="ts">
import { ElIcon } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'

interface Props {
  /** 文件类型由工具声明；最终限额和解码校验仍由各工具负责。 */
  accept: string
  /** 显式命名原生文件入口，键盘和读屏不依赖装饰文字猜测用途。 */
  label: string
  /** 简短格式说明，避免把所有边界塞进投放区。 */
  description: string
  /** 关联工具自身的容量和本机处理说明。 */
  helpId: string
  /** 原生禁用与拖放保持一致，不能绕过处理中的限制。 */
  disabled?: boolean
}
interface Emits {
  /** 点击和拖入都只交付引用，不读取、上传或截断文件。 */
  select: [files: File[]]
  /** 拖入目录不能悄悄当作空文件，原选择由工具保留。 */
  reject: [message: string]
}
const props = defineProps<Props>()
const emit = defineEmits<Emits>()

/** 清空入口值以允许重选同一文件；取消选择不产生状态变更。 */
function onSelect(event: Event): void {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!props.disabled && files.length) emit('select', files)
}
/** 文件只能通过浏览器 Drag and Drop API 接收；不自行递归枚举目录。 */
function onDrop(event: DragEvent): void {
  event.preventDefault()
  if (props.disabled) return
  const items = Array.from(event.dataTransfer?.items ?? [])
  if (items.some((item) => item.kind === 'file' && item.webkitGetAsEntry?.()?.isDirectory)) {
    emit('reject', '暂不支持拖入文件夹，请直接选择其中的文件。')
    return
  }
  const files = Array.from(event.dataTransfer?.files ?? [])
  if (files.length) emit('select', files)
}
</script>

<template>
  <label class="file-drop" :class="{ disabled }" @dragover.prevent @drop="onDrop">
    <input
      type="file"
      multiple
      :accept="accept"
      :aria-label="label"
      :aria-describedby="helpId"
      :disabled="disabled"
      @change="onSelect"
    />
    <ElIcon class="drop-icon" aria-hidden="true"><Upload /></ElIcon>
    <span class="drop-title">{{ label }}</span>
    <span class="drop-description">{{ description }}</span>
  </label>
</template>

<style scoped>
.file-drop {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 28px 18px;
  border: 2px dashed var(--ph-line);
  border-radius: 12px;
  background: var(--ph-soft);
  color: var(--ph-ink);
  cursor: pointer;
  text-align: center;
  overflow-wrap: anywhere;
  transition:
    border-color 0.18s,
    background 0.18s;
}
.file-drop:not(.disabled):hover {
  border-color: color-mix(in srgb, var(--ph-accent) 45%, var(--ph-line));
  background: color-mix(in srgb, var(--ph-pink-soft) 40%, var(--ph-surface));
}
/* 透明原生入口的键盘焦点转给原边框；没有额外外圈，也不会重复描边或改变尺寸。 */
.file-drop:not(.disabled):has(input:focus-visible) {
  border-style: solid;
  border-color: var(--ph-accent-strong);
}
.file-drop input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: inherit;
}
.drop-icon {
  font-size: 26px;
  color: var(--ph-muted);
}
.drop-title {
  font-size: 14px;
  line-height: 1.6;
}
.drop-description {
  color: var(--ph-muted);
  font-size: 12px;
  line-height: 1.6;
}
.disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
</style>

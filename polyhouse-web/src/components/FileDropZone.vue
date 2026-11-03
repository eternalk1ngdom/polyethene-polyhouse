<script setup lang="ts">
import { ref } from 'vue'
import {
  ElButton,
  ElButtonGroup,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElIcon,
} from 'element-plus'
import { ArrowDown, Upload } from '@element-plus/icons-vue'

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
  /** 目录仍由工具拥有原生入口和路径规则，共享组件只提供相关菜单动作。 */
  directoryEnabled?: boolean
  /** 图片工具可明确写“选择图片”，不把处理动作误叫上传。 */
  buttonLabel?: string
}
interface Emits {
  /** 点击和拖入都只交付引用，不读取、上传或截断文件。 */
  select: [files: File[]]
  /** 拖入目录不能悄悄当作空文件，原选择由工具保留。 */
  reject: [message: string]
  /** 只请求打开目录选择器，不在组件中枚举或改写相对路径。 */
  directory: []
}
const props = withDefaults(defineProps<Props>(), { buttonLabel: '选择文件' })
const emit = defineEmits<Emits>()
const picker = ref<HTMLInputElement>()

/** 原生选择器由当前用户点击同步唤起，不能先异步等待再丢失浏览器手势授权。 */
function openFiles(): void {
  if (!props.disabled) picker.value?.click()
}
/** 菜单事件是通用输入，只接受本组件声明且当前可用的目录动作。 */
function chooseAlternative(command: unknown): void {
  if (command === 'directory' && props.directoryEnabled && !props.disabled) emit('directory')
}

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
  <div class="file-drop" :class="{ disabled }" @dragover.prevent @drop="onDrop">
    <!-- 主区域可鼠标点击；键盘使用下方真实按钮，隐藏 input 不重复占据 Tab 顺序。 -->
    <label class="file-pick">
      <input
        ref="picker"
        type="file"
        hidden
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
    <div class="drop-actions">
      <ElButtonGroup v-if="directoryEnabled" class="picker-buttons">
        <ElButton
          class="pick-main"
          :disabled="disabled"
          :aria-describedby="helpId"
          @click="openFiles"
          >{{ buttonLabel }}</ElButton
        >
        <ElDropdown
          trigger="click"
          placement="bottom-end"
          :disabled="disabled"
          @command="chooseAlternative"
        >
          <ElButton class="pick-more" :disabled="disabled" aria-label="更多文件选择方式">
            <ElIcon aria-hidden="true"><ArrowDown /></ElIcon>
          </ElButton>
          <template #dropdown>
            <ElDropdownMenu>
              <ElDropdownItem command="directory">
                <span class="directory-option"
                  ><span>选择文件夹</span><small>保留子目录</small></span
                >
              </ElDropdownItem>
            </ElDropdownMenu>
          </template>
        </ElDropdown>
      </ElButtonGroup>
      <ElButton
        v-else
        class="pick-single"
        :disabled="disabled"
        :aria-describedby="helpId"
        @click="openFiles"
        >{{ buttonLabel }}</ElButton
      >
    </div>
  </div>
</template>

<style scoped>
.file-drop {
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
.file-drop:not(.disabled):hover,
.file-drop:not(.disabled):focus-within {
  border-color: color-mix(in srgb, var(--ph-accent) 45%, var(--ph-line));
  background: color-mix(in srgb, var(--ph-line) 35%, var(--ph-soft));
}
/* 外框只改变原边框，内部真实按钮保留各自清楚的键盘提示。 */
.file-drop:not(.disabled):has(button:focus-visible) {
  border-color: var(--ph-accent-strong);
}
.file-pick {
  display: flex;
  width: 100%;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px 18px 0;
  cursor: inherit;
}
.drop-actions {
  display: flex;
  justify-content: center;
  padding: 16px 18px 24px;
}
.picker-buttons .pick-main {
  border-radius: 10px 0 0 10px;
}
.pick-single {
  border-radius: 10px;
}
.pick-more {
  min-width: 44px;
  margin-left: -1px;
  padding-inline: 0;
  border-radius: 0 10px 10px 0;
}
.directory-option {
  display: flex;
  flex-direction: column;
  padding-block: 4px;
  line-height: 1.6;
}
.directory-option small {
  color: var(--ph-muted);
  font-size: 12px;
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

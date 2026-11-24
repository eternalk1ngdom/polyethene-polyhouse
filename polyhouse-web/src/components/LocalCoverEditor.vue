<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watchEffect } from 'vue'
import { ElButton } from 'element-plus'
import {
  coverBounds,
  coverFrames,
  type CoverFrame,
  type CoverPosition,
} from '../core/localCoverCrop'
import type { LocalProfileCover } from '../utils/localProfileCover'
import type { LocalCoverDecodeReply } from '../workers/localCoverDecode.worker'

interface Props {
  save: (cover: LocalProfileCover) => Promise<void>
}
interface Emits {
  cancel: []
  busy: [value: boolean]
}
const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const input = ref<HTMLInputElement>()
const canvas = ref<HTMLCanvasElement>()
const bitmap = shallowRef<ImageBitmap>()
const frame = ref<CoverFrame>('desktop')
const frames: CoverFrame[] = ['desktop', 'mobile']
const centered = (): CoverPosition => ({ x: 0.5, y: 0.5, zoom: 1 })
const positions = ref<Record<CoverFrame, CoverPosition>>({
  desktop: centered(),
  mobile: centered(),
})
const position = computed(() => positions.value[frame.value])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
let reader: Worker | undefined
let readTimeout: number | undefined
let active = true
let drag: { id: number; x: number; y: number } | undefined

onMounted(() => input.value?.focus())
onBeforeUnmount(() => {
  active = false
  stopReading()
  bitmap.value?.close()
})

function draw(
  target: HTMLCanvasElement,
  image: ImageBitmap,
  key: CoverFrame,
  point: CoverPosition,
) {
  const context = target.getContext('2d', { alpha: false })
  if (!context) throw new Error('当前浏览器无法绘制卡面。')
  const size = coverFrames[key]
  const bounds = coverBounds(image.width, image.height, key, point.zoom)
  context.fillStyle = '#f4efe7'
  context.fillRect(0, 0, target.width, target.height)
  // 预览和导出共用同一原图选区，不将任何预览截图放大保存。
  context.drawImage(
    image,
    (bounds.x * point.x) / bounds.scale,
    (bounds.y * point.y) / bounds.scale,
    size.width / bounds.scale,
    size.height / bounds.scale,
    0,
    0,
    target.width,
    target.height,
  )
}

watchEffect(
  () => {
    if (canvas.value && bitmap.value) draw(canvas.value, bitmap.value, frame.value, position.value)
  },
  { flush: 'post' },
)

function stopReading() {
  reader?.terminate()
  reader = undefined
  window.clearTimeout(readTimeout)
  loading.value = false
}

function rejectFile(message: string) {
  stopReading()
  error.value = message
  if (input.value) input.value.value = ''
}

function choose(event: Event) {
  if (!(event.target instanceof HTMLInputElement) || saving.value) return
  const file = event.target.files?.[0]
  if (!file) return
  stopReading()
  // 已选择新文件，旧草稿不能再被保存。浏览器里正式使用的卡面只在确认保存后替换。
  bitmap.value?.close()
  bitmap.value = undefined
  error.value = ''
  if (file.size > 8 * 1024 * 1024) {
    rejectFile('请选择 8 MiB 以内的图片。')
    return
  }
  loading.value = true
  try {
    const worker = new Worker(new URL('../workers/localCoverDecode.worker.ts', import.meta.url), {
      type: 'module',
    })
    reader = worker
    worker.onmessage = ({ data }: MessageEvent<LocalCoverDecodeReply>) => {
      if (reader !== worker) {
        if ('bitmap' in data) data.bitmap.close()
        return
      }
      if ('error' in data) {
        rejectFile(data.error)
        return
      }
      stopReading()
      bitmap.value = data.bitmap
      positions.value = { desktop: centered(), mobile: centered() }
    }
    worker.onerror = worker.onmessageerror = () => {
      if (reader === worker) rejectFile('无法读取这张图片，请重新选择。')
    }
    // 个别文件或浏览器解码器可能一直没有响应，终止这次任务后仍可继续选图。
    readTimeout = window.setTimeout(() => {
      if (reader === worker) rejectFile('读取图片超时，请重新选择或换一张较小的静态图片。')
    }, 15_000)
    worker.postMessage(file)
  } catch {
    rejectFile('当前浏览器无法启动图片读取，请刷新页面后重试。')
  }
}

function move(horizontal: number, vertical: number) {
  const clamp = (value: number) => Math.max(0, Math.min(1, value))
  position.value.x = clamp(position.value.x - horizontal)
  position.value.y = clamp(position.value.y - vertical)
}

function start(event: PointerEvent) {
  if (drag || event.button !== 0 || !canvas.value) return
  canvas.value.setPointerCapture(event.pointerId)
  drag = { id: event.pointerId, x: event.clientX, y: event.clientY }
}

function pan(event: PointerEvent) {
  if (drag?.id !== event.pointerId || !bitmap.value || !canvas.value) return
  const bounds = coverBounds(
    bitmap.value.width,
    bitmap.value.height,
    frame.value,
    position.value.zoom,
  )
  const scale = canvas.value.getBoundingClientRect().width / coverFrames[frame.value].width
  move(
    bounds.x ? (event.clientX - drag.x) / scale / bounds.x : 0,
    bounds.y ? (event.clientY - drag.y) / scale / bounds.y : 0,
  )
  drag.x = event.clientX
  drag.y = event.clientY
}

const directions = [
  { key: 'ArrowLeft', label: '向左移', x: -0.05, y: 0 },
  { key: 'ArrowRight', label: '向右移', x: 0.05, y: 0 },
  { key: 'ArrowUp', label: '向上移', x: 0, y: -0.05 },
  { key: 'ArrowDown', label: '向下移', x: 0, y: 0.05 },
]

function keyMove(event: KeyboardEvent) {
  const direction = directions.find(({ key }) => key === event.key)
  if (direction) {
    event.preventDefault()
    move(direction.x, direction.y)
  }
}

async function encode(image: ImageBitmap, key: CoverFrame): Promise<Blob> {
  const target = document.createElement('canvas')
  target.width = coverFrames[key].width
  target.height = coverFrames[key].height
  draw(target, image, key, positions.value[key])
  // 尽量在一套 1 MiB 的目标内保留画质，两图各分配一半空间。
  for (const quality of [0.9, 0.8, 0.65, 0.5]) {
    const blob = await new Promise<Blob | null>((resolve) =>
      target.toBlob(resolve, 'image/webp', quality),
    )
    if (blob?.type !== 'image/webp') throw new Error('当前浏览器无法导出 WebP。')
    if (blob.size <= 512 * 1024) return blob
  }
  throw new Error('裁剪后的图片文件仍然太大，请换一张图片。')
}

async function save() {
  const image = bitmap.value
  if (!image || saving.value || loading.value) return
  saving.value = true
  emit('busy', true)
  error.value = ''
  try {
    const desktop = await encode(image, 'desktop')
    if (!active) return
    const mobile = await encode(image, 'mobile')
    if (active) await props.save({ desktop, mobile, enabled: true })
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '无法保存卡面，请重试。'
  } finally {
    saving.value = false
    if (active) emit('busy', false)
  }
}
</script>

<template>
  <form class="cover-editor" @submit.prevent="save">
    <label for="local-cover-file">选择本地图片</label>
    <input
      id="local-cover-file"
      ref="input"
      type="file"
      accept="image/jpeg,image/png,image/webp"
      :disabled="saving"
      @change="choose"
    />
    <p class="editor-note">
      支持静态 JPEG、PNG 或 WebP，最大 8 MiB。桌面卡面较宽，建议选择横向图片。桌面和手机可分别取景，保存后如需重新裁剪，请再次选择原图。
    </p>
    <p v-if="loading" role="status">正在读取图片…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <fieldset v-if="bitmap" class="editor-controls" :disabled="saving || loading">
      <div class="editor-buttons frame-buttons">
        <ElButton
          v-for="key in frames"
          :key="key"
          :aria-pressed="frame === key"
          @click="frame = key"
        >
          {{ coverFrames[key].label }}取景
        </ElButton>
      </div>
      <div class="edit-preview" :data-frame="frame">
        <canvas
          ref="canvas"
          tabindex="0"
          aria-label="裁剪预览，方向键移动图片"
          :width="coverFrames[frame].width / 2"
          :height="coverFrames[frame].height / 2"
          @pointerdown="start"
          @pointermove="pan"
          @pointerup="drag = undefined"
          @lostpointercapture="drag = undefined"
          @keydown="keyMove"
        />
        <div class="edit-avatar"><slot name="avatar" /></div>
      </div>
      <label class="editor-zoom"
        >缩放 <input v-model.number="position.zoom" type="range" min="1" max="3" step="0.05"
      /></label>
      <div class="editor-buttons">
        <ElButton
          v-for="direction in directions"
          :key="direction.key"
          @click="move(direction.x, direction.y)"
          >{{ direction.label }}</ElButton
        >
        <ElButton @click="positions[frame] = centered()">重置取景</ElButton>
      </div>
    </fieldset>
    <div class="editor-buttons">
      <ElButton :disabled="saving" @click="emit('cancel')">取消</ElButton>
      <ElButton native-type="submit" type="primary" :loading="saving" :disabled="!bitmap || loading"
        >保存并使用本地图片</ElButton
      >
    </div>
  </form>
</template>

<style scoped>
.cover-editor,
.editor-controls {
  display: grid;
  gap: 12px;
}
.cover-editor input[type='file'] {
  display: block;
  min-width: 0;
  max-width: 100%;
  color: var(--ph-ink);
}
.cover-editor input::file-selector-button {
  margin-right: 8px;
  padding: 8px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 99px;
  background: var(--el-fill-color-light);
  color: inherit;
  cursor: pointer;
}
.cover-editor input::file-selector-button:hover {
  background: var(--el-fill-color);
}
.frame-buttons :deep([aria-pressed='true']) {
  background: var(--el-fill-color);
  color: var(--ph-ink);
  border-color: var(--ph-ink);
}
.editor-note {
  margin: 0;
  line-height: 1.5;
}
.editor-controls {
  border: 0;
  margin: 0;
  padding: 0;
  min-width: 0;
}
.editor-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.editor-buttons :deep(.el-button) {
  margin: 0;
}
.edit-preview {
  position: relative;
  margin-bottom: 12px;
}
.edit-preview[data-frame='mobile'] {
  width: min(100%, 365px);
}
.edit-preview canvas {
  display: block;
  width: 100%;
  height: auto;
  border-radius: 10px;
  touch-action: none;
  cursor: grab;
}
.edit-avatar {
  position: absolute;
  left: 12px;
  bottom: -12px;
  pointer-events: none;
}
.editor-zoom {
  display: flex;
  align-items: center;
  gap: 8px;
}
.editor-zoom input {
  flex: 1;
  min-width: 0;
}
</style>

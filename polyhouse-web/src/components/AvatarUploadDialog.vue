<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElIcon } from 'element-plus'
import { Minus, Plus, RefreshLeft, Upload } from '@element-plus/icons-vue'
import { ApiError } from '../api'
import { newProfileRequestId } from '../api/auth'
import {
  avatarSourceSize,
  clampCrop,
  cropScale,
  initialCrop,
  zoomCrop,
  type AvatarCrop,
} from '../core/avatarCrop'
import { useUserStore } from '../stores/useUserStore'

/** 弹窗只控制当前用户的一次编辑，页面不传递可伪造的用户编号。 */
interface Props {
  /** 保存期间保留弹窗，避免重复提交或失去失败提示。 */
  modelValue: boolean
}
/** 成功提示交由个人中心展示，取消不会产生后端请求。 */
interface Emits {
  (event: 'update:modelValue', value: boolean): void
  (event: 'saved', remainingToday: number, status: string): void
}
const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const userStore = useUserStore()
const picker = ref<HTMLInputElement>()
const canvas = ref<HTMLCanvasElement>()
const image = shallowRef<ImageBitmap>()
const crop = ref<AvatarCrop>(initialCrop(256, 256))
const error = ref('')
const loading = ref(false)
const saving = ref(false)
const fileName = ref('')
const zoomLabel = computed(() => `${Math.round(crop.value.zoom * 100)}%`)
let selection = 0
let submissionRequestId = ''
let drag: { id: number; x: number; y: number; cropX: number; cropY: number } | undefined

function releaseImage(): void {
  // 作废整个编辑过程，包括尚未结束的编码和上传；旧回调不能再修改新弹窗。
  selection += 1
  submissionRequestId = ''
  image.value?.close()
  image.value = undefined
  drag = undefined
  fileName.value = ''
  loading.value = false
  saving.value = false
  error.value = ''
}

function draw(): void {
  const context = canvas.value?.getContext('2d')
  if (!context || !image.value) return
  const scale = cropScale(crop.value)
  context.fillStyle = '#ffffff'
  context.fillRect(0, 0, 256, 256)
  context.drawImage(
    image.value,
    crop.value.x,
    crop.value.y,
    image.value.width * scale,
    image.value.height * scale,
  )
}

async function choose(event: Event): Promise<void> {
  if (!props.modelValue || !(event.target instanceof HTMLInputElement) || saving.value) return
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  error.value = ''
  if (!['image/jpeg', 'image/png'].includes(file.type)) {
    error.value = '请选择 JPEG 或 PNG 静态图片，暂不支持动图和 SVG。'
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    error.value = '原图不能超过 5 MB，请选择较小的图片。'
    return
  }
  submissionRequestId = ''
  const current = ++selection
  loading.value = true
  try {
    const dimensions = avatarSourceSize(new Uint8Array(await file.arrayBuffer()))
    if (
      !dimensions ||
      dimensions.width < 1 ||
      dimensions.height < 1 ||
      dimensions.width > 4096 ||
      dimensions.height > 4096
    )
      throw new Error('dimensions')
    if (current !== selection) return
    // 解码在本机完成；不创建长期对象 URL，关闭弹窗及时释放图像内存。
    const decoded = await createImageBitmap(file)
    if (current !== selection) {
      decoded.close()
      return
    }
    if (decoded.width > 4096 || decoded.height > 4096 || decoded.width < 1 || decoded.height < 1) {
      decoded.close()
      throw new Error('dimensions')
    }
    image.value?.close()
    image.value = decoded
    crop.value = initialCrop(decoded.width, decoded.height)
    fileName.value = file.name
    await nextTick()
    draw()
  } catch {
    if (current === selection)
      error.value = '图片无法读取或尺寸超过 4096×4096，请换一张 JPEG / PNG。'
  } finally {
    if (current === selection) loading.value = false
  }
}

function changeZoom(delta: number): void {
  submissionRequestId = ''
  crop.value = zoomCrop(crop.value, crop.value.zoom + delta)
  draw()
}

function reset(): void {
  submissionRequestId = ''
  if (!image.value) return
  crop.value = initialCrop(image.value.width, image.value.height)
  draw()
}

function startDrag(event: PointerEvent): void {
  if (!image.value || saving.value || (event.pointerType === 'mouse' && event.button !== 0)) return
  if (!(event.currentTarget instanceof HTMLElement)) return
  submissionRequestId = ''
  event.currentTarget.setPointerCapture(event.pointerId)
  drag = {
    id: event.pointerId,
    x: event.clientX,
    y: event.clientY,
    cropX: crop.value.x,
    cropY: crop.value.y,
  }
}

function moveDrag(event: PointerEvent): void {
  if (!drag || drag.id !== event.pointerId || !(event.currentTarget instanceof HTMLElement)) return
  const factor = 256 / event.currentTarget.getBoundingClientRect().width
  crop.value = clampCrop({
    ...crop.value,
    x: drag.cropX + (event.clientX - drag.x) * factor,
    y: drag.cropY + (event.clientY - drag.y) * factor,
  })
  draw()
}

function stopDrag(event: PointerEvent): void {
  if (drag?.id === event.pointerId) drag = undefined
}

function keyboardMove(event: KeyboardEvent): void {
  if (!image.value || saving.value) return
  const steps: Record<string, [number, number]> = {
    ArrowLeft: [-8, 0],
    ArrowRight: [8, 0],
    ArrowUp: [0, -8],
    ArrowDown: [0, 8],
  }
  const step = steps[event.key]
  if (!step) return
  event.preventDefault()
  submissionRequestId = ''
  crop.value = clampCrop({ ...crop.value, x: crop.value.x + step[0], y: crop.value.y + step[1] })
  draw()
}

function close(): void {
  if (!saving.value) emit('update:modelValue', false)
}

async function save(): Promise<void> {
  if (!props.modelValue || !image.value || !canvas.value || saving.value || loading.value) return
  saving.value = true
  const submittedToken = userStore.token
  const submittedSelection = selection
  // 同一账号关闭再打开也属于新编辑，不能仅凭 Token 判断回调仍然有效。
  const isCurrent = (): boolean =>
    props.modelValue && selection === submittedSelection && userStore.token === submittedToken
  error.value = ''
  let uploadStarted = false
  try {
    const element = canvas.value
    const blob = await new Promise<Blob>((resolve, reject) =>
      element.toBlob(
        (value) => (value ? resolve(value) : reject(new Error('encode'))),
        'image/jpeg',
        0.9,
      ),
    )
    if (!isCurrent()) return
    uploadStarted = true
    submissionRequestId ||= newProfileRequestId()
    const result = await userStore.uploadAvatar(blob, submissionRequestId)
    if (!isCurrent()) return
    if (!result) {
      emit('update:modelValue', false)
      return
    }
    emit('saved', result.remainingToday, result.status)
    emit('update:modelValue', false)
  } catch (caught: unknown) {
    if (!isCurrent()) return
    if (!uploadStarted) {
      error.value = '头像编码失败，请重新选择图片后重试。'
      return
    }
    // 超时或服务端异常可能发生在提交之后，先核对资料，避免盲目重试消耗每日次数。
    error.value =
      caught instanceof ApiError && caught.status >= 400 && caught.status < 500
        ? caught.status === 429
          ? '今天已提交 5 次头像申请，请明天再试。'
          : caught.message
        : '结果暂不确定，请先刷新申请状态；原图片重试会核对同一请求。'
  } finally {
    if (isCurrent()) saving.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) releaseImage()
  },
  { flush: 'sync' },
)
watch(
  () => userStore.token,
  () => {
    releaseImage()
    emit('update:modelValue', false)
  },
  { flush: 'sync' },
)
onBeforeUnmount(releaseImage)
</script>

<template>
  <ElDialog
    :model-value="modelValue"
    title="换一张头像"
    width="min(460px, calc(100vw - 32px))"
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @update:model-value="close"
  >
    <div class="avatar-editor">
      <p class="editor-intro">图片将先私有保存，屋主审核通过后才会公开。</p>
      <input
        ref="picker"
        class="file-input"
        type="file"
        accept="image/jpeg,image/png"
        aria-label="选择头像图片"
        :disabled="saving || loading"
        @change="choose"
      />
      <div
        v-if="image"
        class="crop-stage"
        tabindex="0"
        role="group"
        aria-label="拖动图片调整位置，也可以使用方向键"
        @pointerdown.prevent="startDrag"
        @pointermove="moveDrag"
        @pointerup="stopDrag"
        @pointercancel="stopDrag"
        @lostpointercapture="stopDrag"
        @keydown="keyboardMove"
      >
        <canvas ref="canvas" width="256" height="256" aria-label="头像裁剪预览" />
        <span class="circle-guide" aria-hidden="true" />
      </div>
      <div v-else class="empty-preview">
        <ElIcon><Upload /></ElIcon><span>从喜欢的一张图片开始</span>
      </div>
      <template v-if="image">
        <p class="file-name">{{ fileName }}</p>
        <div class="zoom-controls">
          <ElButton
            circle
            :disabled="saving || crop.zoom <= 1"
            aria-label="缩小头像"
            @click="changeZoom(-0.1)"
            ><ElIcon><Minus /></ElIcon
          ></ElButton>
          <output aria-label="缩放比例">{{ zoomLabel }}</output>
          <ElButton
            circle
            :disabled="saving || crop.zoom >= 3"
            aria-label="放大头像"
            @click="changeZoom(0.1)"
            ><ElIcon><Plus /></ElIcon
          ></ElButton>
          <ElButton text :disabled="saving" @click="reset"
            ><ElIcon><RefreshLeft /></ElIcon>重置</ElButton
          >
        </div>
        <p class="crop-hint">拖动调整位置 · 圆框是头像显示范围</p>
      </template>
      <ElButton
        class="choose-button"
        :loading="loading"
        :disabled="saving"
        @click="picker?.click()"
        >{{ image ? '重新选择图片' : '选择图片' }}</ElButton
      >
      <p class="upload-rules">
        JPEG / PNG · 原图不超过 5 MB、4096×4096<br />每天最多提交 5
        次，驳回与替代也计次；取消不上传。
      </p>
      <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    </div>
    <template #footer
      ><ElButton :disabled="saving" @click="close">取消</ElButton
      ><ElButton type="primary" :disabled="!image || loading" :loading="saving" @click="save"
        >提交头像审核</ElButton
      ></template
    >
  </ElDialog>
</template>

<style scoped>
.avatar-editor {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.editor-intro {
  color: var(--ph-muted);
  margin: 0 0 4px;
  font-size: 13px;
}
.file-input {
  display: none;
}
.crop-stage,
.empty-preview {
  width: min(256px, 100%);
  aspect-ratio: 1;
  border-radius: 12px;
  overflow: hidden;
}
.crop-stage {
  position: relative;
  touch-action: none;
  cursor: grab;
  background: var(--ph-soft);
}
.crop-stage:active {
  cursor: grabbing;
}
.crop-stage:focus-visible {
  outline: 3px solid var(--ph-brand);
  outline-offset: 4px;
}
.crop-stage canvas {
  display: block;
  width: 100%;
  height: 100%;
}
.circle-guide {
  position: absolute;
  inset: 1px;
  border-radius: 50%;
  border: 1px solid #ffffffb3;
  box-shadow: 0 0 0 60px #3e2e3859;
  pointer-events: none;
}
.empty-preview {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
  background: var(--ph-soft);
  color: var(--ph-muted);
  font-size: 12px;
  border: 1px dashed var(--ph-line);
}
.empty-preview .el-icon {
  font-size: 36px;
}
.zoom-controls {
  display: flex;
  gap: 12px;
  align-items: center;
}
.zoom-controls output {
  width: 46px;
  text-align: center;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.zoom-controls .el-button + .el-button {
  margin-left: 0;
}
.zoom-controls .el-button .el-icon {
  margin-right: 2px;
}
.file-name {
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: center;
  font-size: 12px;
  color: var(--ph-muted);
  margin: 0;
}
.crop-hint,
.upload-rules {
  margin: 0;
  color: var(--ph-muted);
  font-size: 11px;
  line-height: 1.9;
  text-align: center;
}
.choose-button {
  min-width: 150px;
}
</style>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElButton } from 'element-plus'
import { privateImage } from '../api/profileModeration'
import { ApiError } from '../api'
import { useUserStore } from '../stores/useUserStore'
/** 只接受申请编号，不接收私有文件路径或 Token 查询参数。 */
interface Props {
  /** 仅有效待审图才传编号。 */ submissionId: string
}
/** 父弹窗只有成功读到当前图片，才允许批准头像。 */
interface Emits {
  (event: 'ready', value: boolean): void
}
const emit = defineEmits<Emits>()
const props = defineProps<Props>(),
  store = useUserStore(),
  url = ref(''),
  error = ref(''),
  loading = ref(false),
  retry = ref(0)
let generation = 0
function release(): void {
  emit('ready', false)
  generation++
  if (url.value) URL.revokeObjectURL(url.value)
  url.value = ''
}
watch(
  [
    () => props.submissionId,
    () => store.token,
    () => store.isLoggedIn,
    () => store.requiresPasswordChange,
    retry,
  ],
  async () => {
    release()
    error.value = ''
    loading.value = false
    if (!store.isLoggedIn || store.requiresPasswordChange || !props.submissionId) return
    const current = generation
    loading.value = true
    try {
      const blob = await privateImage(props.submissionId, store.token)
      if (current === generation) {
        url.value = URL.createObjectURL(blob)
      }
    } catch (e: unknown) {
      if (current === generation)
        error.value = e instanceof ApiError ? e.message : '图片暂时无法预览'
    } finally {
      if (current === generation) loading.value = false
    }
  },
  { immediate: true },
)
// 读到 Blob 不代表图片已能显示，必须等当前图片真实解码完成，旧图片事件不能放行新目标。
function loaded(event: Event): void {
  const image = event.currentTarget
  if (image instanceof HTMLImageElement && image.currentSrc === url.value && image.naturalWidth > 0)
    emit('ready', true)
}
function failed(): void {
  emit('ready', false)
  error.value = '图片无法显示，请重试预览或驳回申请。'
}
onBeforeUnmount(release)
</script>
<template>
  <div class="private-preview">
    <p v-if="loading" role="status">正在读取私有预览…</p>
    <img
      v-if="url"
      :src="url"
      @load="loaded"
      @error="failed"
      alt="仅本人和屋主可查看的待审头像"
      width="160"
      height="160"
    />
    <p v-if="error" role="alert">{{ error }} <ElButton @click="retry++">重试预览</ElButton></p>
  </div>
</template>
<style scoped>
.private-preview {
  margin: 16px 0;
  max-width: 100%;
}
.private-preview img {
  display: block;
  max-width: 100%;
  height: auto;
  border-radius: 16px;
}
.private-preview p {
  font-size: 14px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
</style>

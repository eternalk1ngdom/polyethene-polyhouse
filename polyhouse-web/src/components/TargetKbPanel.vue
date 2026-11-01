<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { zip } from 'fflate'
import { ElAlert, ElButton, ElIcon, ElInput, ElProgress, ElSwitch } from 'element-plus'
import { Download, Picture, Upload } from '@element-plus/icons-vue'
import TargetKbBatchResult from './TargetKbBatchResult.vue'
import {
  MAX_BATCH_BYTES,
  MAX_BATCH_FILES,
  outputFileName,
  summarizeBatch,
  validateBatch,
  zipEntryName,
} from '../core/targetKbBatch'
import type { TargetKbBatchRow } from '../core/targetKbBatch'
import { createTargetRange, parseTargetSize } from '../core/targetKbCompress'
import type { TargetKbEncodeResult, TargetKbReply } from '../core/targetKbProtocol'
import { inspectSource, inspectSourceSize } from '../core/targetKbSource'
import type { TargetKbTarget } from '../core/targetKbTypes'
import { formatBytes } from '../utils/byteSize'

/** 文件头只读前 256 KiB：EXIF 缩略图可能很长，切太小会解析不到 SOF。 */
const HEADER_BYTES = 256 * 1024

/** 页面侧只做格式与范围提示，真正校验仍在内核与 Worker。 */
const DPI_RANGE = '1～1200'

const picked = ref<File[]>([])
const sourceUrl = ref('')
const sourceSize = ref<{ width: number; height: number } | null>(null)
const rows = ref<TargetKbBatchRow[]>([])
const targetInput = ref('')
const minInput = ref('')
const dpiInput = ref('')
const allowShrink = ref(true)
const busy = ref(false)
const packing = ref(false)
const message = ref('')
const progress = ref<{ attempts: number; quality: number; bytes: number } | null>(null)
const result = ref<TargetKbEncodeResult | null>(null)
const resultUrl = ref('')
const zipUrl = ref('')
const zipBytes = ref(0)

// 输出字节单独用普通 Map 存放：放进响应式数组会被代理包住，大数组的代理开销没必要。
const outputs = new Map<number, Uint8Array>()

// 单线程复用：取消只作废当前 id，不重建 Worker；卸载时再终止。
let worker: Worker | undefined
let activeId = 0
let nextId = 1
let pendingReply: ((reply: TargetKbReply) => void) | null = null

/** 多选即批量；单张仍走原来的详细结果视图。 */
const isBatch = computed(() => picked.value.length > 1)
const batchSummary = computed(() => summarizeBatch(rows.value))
/** 单张时才做预览与详细结果，模板里据此切换，不依赖非空断言。 */
const singleFile = computed(() => (picked.value.length === 1 ? (picked.value[0] ?? null) : null))
/** 提示文案用的整数 MB，避免在模板里做换算。 */
const MAX_BATCH_MB = Math.round(MAX_BATCH_BYTES / 1024 / 1024)

/** 释放所有临时地址，避免连续换图时旧 Blob 一直占着内存。 */
function clearUrls(): void {
  if (sourceUrl.value) URL.revokeObjectURL(sourceUrl.value)
  if (resultUrl.value) URL.revokeObjectURL(resultUrl.value)
  if (zipUrl.value) URL.revokeObjectURL(zipUrl.value)
  sourceUrl.value = ''
  resultUrl.value = ''
  zipUrl.value = ''
  zipBytes.value = 0
}

/** 结果、进度与列表状态一起失效，避免用户改完参数后仍下载到旧文件。 */
function invalidate(): void {
  if (worker && activeId > 0) worker.postMessage({ id: activeId, kind: 'cancel' })
  const resolve = pendingReply
  pendingReply = null
  activeId = 0
  busy.value = false
  packing.value = false
  progress.value = null
  result.value = null
  outputs.clear()
  clearUrls()
  for (const row of rows.value) {
    row.status = 'pending'
    row.outputBytes = undefined
    row.message = undefined
  }
  resolve?.({ id: 0, ok: false, reason: 'cancelled', message: '已取消' })
}

/** 收下选中的图片：先过滤非图片、再校验批量上限，最后才建列表。 */
async function acceptFiles(list: File[]): Promise<void> {
  invalidate()
  message.value = ''
  picked.value = []
  rows.value = []
  sourceSize.value = null

  const images = list.filter((file) => file.type.startsWith('image/'))
  if (images.length === 0) {
    message.value = '请选择图片文件（JPEG、PNG、WebP 或 AVIF）。'
    return
  }
  if (images.length !== list.length)
    message.value = `已忽略 ${list.length - images.length} 个非图片文件。`

  try {
    validateBatch(images.map((file) => ({ name: file.name, size: file.size })))
  } catch (error: unknown) {
    message.value = error instanceof Error ? error.message : '选择失败，请重试'
    return
  }

  picked.value = images
  rows.value = images.map((file) => ({ name: file.name, size: file.size, status: 'pending' }))
  // 多张时不逐张解析文件头：解析结果只用于单图预览，批量交给 Worker 逐张判断。
  if (images.length > 1) return

  const only = images[0]!
  const sizeCheck = inspectSourceSize(only.size)
  if (!sizeCheck.ok) {
    message.value = sizeCheck.message
    picked.value = []
    rows.value = []
    return
  }
  const head = new Uint8Array(await only.slice(0, HEADER_BYTES).arrayBuffer())
  const inspection = inspectSource(head)
  if (!inspection.ok) {
    message.value = inspection.message
    picked.value = []
    rows.value = []
    return
  }
  sourceUrl.value = URL.createObjectURL(only)
  sourceSize.value =
    inspection.width === null || inspection.height === null
      ? null
      : { width: inspection.width, height: inspection.height }
}

/** 文件选择与拖放走同一个入口，避免两条路径行为不一致。 */
function onSelect(event: Event): void {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length) void acceptFiles(files)
}

/** 拖放只能由 Drag and Drop API 提供文件，Pointer Events 拿不到 File 对象。 */
function onDrop(event: DragEvent): void {
  event.preventDefault()
  const files = Array.from(event.dataTransfer?.files ?? [])
  if (files.length) void acceptFiles(files)
}

function onDragOver(event: DragEvent): void {
  event.preventDefault()
}

/** 目标体积与下限都交给内核解析，页面不重复实现单位换算。 */
function readTarget(): TargetKbTarget {
  const upper = parseTargetSize(targetInput.value)
  const lower = minInput.value.trim() ? parseTargetSize(minInput.value) : null
  return createTargetRange(lower ? lower.maxBytes : 0, upper.maxBytes)
}

function readDpi(): number | null {
  const text = dpiInput.value.trim()
  if (!text) return null
  const value = Number(text)
  if (!Number.isInteger(value) || value < 1 || value > 1200)
    throw new Error(`DPI 必须是 ${DPI_RANGE} 的整数，留空表示不改动`)
  return value
}

function ensureWorker(): Worker {
  if (worker) return worker
  const instance = new Worker(new URL('../workers/targetkb.worker.ts', import.meta.url), {
    type: 'module',
  })
  instance.onmessage = (event: MessageEvent<TargetKbReply>) => handleReply(event.data)
  instance.onerror = () => {
    invalidate()
    message.value = '本地编码线程出错，请重试'
  }
  worker = instance
  return instance
}

/** 只按 id 判断归属：换图或改参数会换 id，旧任务的迟到消息自然被丢弃。 */
function handleReply(reply: TargetKbReply): void {
  if (activeId === 0 || reply.id !== activeId) return
  if (reply.progress) {
    progress.value = reply.progress
    return
  }
  const resolve = pendingReply
  pendingReply = null
  activeId = 0
  progress.value = null
  resolve?.(reply)
}

/** 串行发送一张图并等待结果；批量与单图共用同一条路径。 */
function sendEncode(
  bytes: ArrayBuffer,
  target: TargetKbTarget,
  dpi: number | null,
): Promise<TargetKbReply> {
  const id = nextId++
  activeId = id
  return new Promise((resolve) => {
    pendingReply = resolve
    ensureWorker().postMessage(
      { id, kind: 'encode', bytes, target, allowShrink: allowShrink.value, dpi },
      [bytes],
    )
  })
}

async function runSingle(target: TargetKbTarget, dpi: number | null): Promise<void> {
  const file = picked.value[0]
  if (!file) return
  try {
    const bytes = await file.arrayBuffer()
    const reply = await sendEncode(bytes, target, dpi)
    if (reply.reason === 'cancelled') return
    if (reply.ok && reply.result) {
      result.value = reply.result
      resultUrl.value = URL.createObjectURL(new Blob([reply.result.bytes], { type: 'image/jpeg' }))
    } else {
      message.value = reply.message ?? '处理失败，请重试'
    }
  } catch {
    message.value = '读取图片失败，请重试'
  } finally {
    busy.value = false
  }
}

async function runBatch(target: TargetKbTarget, dpi: number | null): Promise<void> {
  try {
    for (let index = 0; index < picked.value.length; index += 1) {
      if (!busy.value) return
      const file = picked.value[index]
      const row = rows.value[index]
      if (!file || !row) continue
      row.status = 'running'
      progress.value = null
      const bytes = await file.arrayBuffer()
      const reply = await sendEncode(bytes, target, dpi)
      if (reply.reason === 'cancelled' || !busy.value) {
        row.status = 'pending'
        return
      }
      if (reply.ok && reply.result) {
        row.status = 'done'
        row.outputBytes = reply.result.bytes.byteLength
        outputs.set(index, new Uint8Array(reply.result.bytes))
      } else {
        row.status = 'failed'
        row.message = reply.message ?? '处理失败'
      }
    }
    await packZip()
  } catch {
    message.value = '批量处理中断，请重试'
  } finally {
    busy.value = false
  }
}

/** 只打包成功项；有失败就单独列出，不把半成品混进 ZIP。 */
async function packZip(): Promise<void> {
  const done = rows.value
    .map((row, index) => ({ row, index }))
    .filter((item) => item.row.status === 'done')
  if (done.length === 0) {
    message.value = '所有图片都未能压缩，未生成 ZIP。'
    return
  }
  packing.value = true
  try {
    const entries: Record<string, Uint8Array> = {}
    for (const { row, index } of done) {
      const bytes = outputs.get(index)
      if (bytes) entries[zipEntryName(index, row.name, bytes.byteLength)] = bytes
    }
    const archive = await new Promise<Uint8Array>((resolve, reject) => {
      // JPEG 已经压缩过，deflate 几乎不再变小；用 store 换取更短的等待。
      zip(entries, { level: 0 }, (error, data) => (error ? reject(error) : resolve(data)))
    })
    zipUrl.value = URL.createObjectURL(new Blob([archive.slice()], { type: 'application/zip' }))
    zipBytes.value = archive.byteLength
  } catch {
    message.value = '打包 ZIP 失败，请重试'
  } finally {
    packing.value = false
  }
}

async function run(): Promise<void> {
  if (!picked.value.length || busy.value) return
  message.value = ''

  let target: TargetKbTarget
  let dpi: number | null
  try {
    target = readTarget()
    dpi = readDpi()
  } catch (error: unknown) {
    message.value = error instanceof Error ? error.message : '参数填写有误'
    return
  }

  invalidate()
  busy.value = true
  if (isBatch.value) await runBatch(target, dpi)
  else await runSingle(target, dpi)
}

/** 取消只作废回调，线程留着给下一次用；真正释放内存靠卸载时终止。 */
function cancel(): void {
  if (!busy.value) return
  const id = activeId
  if (worker && id > 0) worker.postMessage({ id, kind: 'cancel' })
  activeId = 0
  const resolve = pendingReply
  pendingReply = null
  busy.value = false
  packing.value = false
  progress.value = null
  resolve?.({ id, ok: false, reason: 'cancelled', message: '已取消' })
  // 批量取消后不保留半成品：已完成的也回到"等待"，用户重跑一次即可。
  invalidate()
  message.value = isBatch.value ? '已取消本次批量，未生成结果。' : '已取消本次压缩。'
}

function download(): void {
  const out = result.value
  if (!out) return
  const name = singleFile.value
    ? outputFileName(singleFile.value.name, out.bytes.byteLength)
    : '妙压.jpg'
  const url = URL.createObjectURL(new Blob([out.bytes], { type: 'image/jpeg' }))
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  // 给浏览器接收下载请求的时间，随后释放临时地址。
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function downloadZip(): void {
  if (!zipUrl.value) return
  const link = document.createElement('a')
  link.href = zipUrl.value
  link.download = `妙压-${batchSummary.value.done}张.zip`
  link.click()
}

const summary = computed(() => {
  const out = result.value
  const input = singleFile.value
  if (!out || !input) return null
  const after = out.bytes.byteLength
  const before = input.size
  return {
    before: formatBytes(before),
    after: formatBytes(after),
    change: before > 0 ? Math.round(((after - before) / before) * 100) : 0,
    dimension:
      out.scaleStep > 0
        ? `${out.width}×${out.height}（缩小 ${out.scaleStep} 档）`
        : `${out.width}×${out.height}（保持原尺寸）`,
    quality: out.quality.toFixed(2),
    attempts: out.probes.length,
    seconds: (out.elapsedMs / 1000).toFixed(1),
    padding:
      out.padding.targetBytes === 0
        ? ''
        : out.padding.satisfied
          ? `已补 ${formatBytes(out.padding.added)} 注释段，凑到 ${formatBytes(out.padding.targetBytes)}`
          : '差额不足 5 字节，未能精确补到下限',
    dpi: out.dpi === null ? '' : `${out.dpi} DPI（只改元数据，不会让图片变清晰）`,
  }
})

/**
 * 参数一改，上一次的结果就不再对应当前设置，必须作废，否则用户会下载到与输入不符的文件。
 * 正在处理时改参数按"取消"处理，并说明原因，避免界面无声停下。
 */
watch([targetInput, minInput, dpiInput, allowShrink], () => {
  const wasBusy = busy.value
  if (!wasBusy && !result.value && !zipUrl.value) {
    message.value = ''
    return
  }
  invalidate()
  message.value = wasBusy ? '参数已修改，本次压缩已取消。' : ''
})

onBeforeUnmount(() => {
  activeId = 0
  worker?.terminate()
  worker = undefined
  if (sourceUrl.value) URL.revokeObjectURL(sourceUrl.value)
  if (resultUrl.value) URL.revokeObjectURL(resultUrl.value)
  if (zipUrl.value) URL.revokeObjectURL(zipUrl.value)
})
</script>

<template>
  <div class="targetkb">
    <section class="panel" aria-labelledby="pick-title">
      <h2 id="pick-title">1. 选择图片</h2>
      <p id="pick-help">
        图片只在浏览器里处理，不会上传。单张不超过 20 MB、不超过 4000 万像素；一次最多
        {{ MAX_BATCH_FILES }} 张、合计不超过 {{ MAX_BATCH_MB }} MB。输出统一为 JPEG，原图不会被修改。
      </p>
      <label class="drop" @dragover="onDragOver" @drop="onDrop">
        <input
          type="file"
          multiple
          accept="image/jpeg,image/png,image/webp,image/avif"
          aria-describedby="pick-help"
          @change="onSelect"
        />
        <ElIcon class="drop-icon"><Upload /></ElIcon>
        <span class="drop-main">点击选择图片，或把图片拖到这里</span>
        <span class="drop-sub">可多选；JPEG / PNG / WebP / AVIF</span>
      </label>

      <figure v-if="singleFile" class="source">
        <img :src="sourceUrl" :alt="`待压缩的图片：${singleFile.name}`" />
        <figcaption>
          <strong>{{ singleFile.name }}</strong>
          <span>{{ formatBytes(singleFile.size) }}</span>
          <span v-if="sourceSize">{{ sourceSize.width }}×{{ sourceSize.height }}</span>
          <span v-else>尺寸待解码后确认</span>
        </figcaption>
      </figure>
      <p v-else-if="picked.length > 1" class="picked-count">
        已选 {{ picked.length }} 张，合计 {{ formatBytes(batchSummary.inputBytes) }}。
      </p>

      <h2>2. 设置目标</h2>
      <label for="target-size">目标体积上限</label>
      <ElInput
        id="target-size"
        v-model="targetInput"
        placeholder="例如 200KB、1.5MB 或 307200"
        maxlength="16"
        aria-describedby="target-help"
      />
      <p id="target-help">
        支持 KB、MB 与纯字节；留空无法开始。目标体积指输出文件的字节数，不是画质档位。多张共用同一组设置。
      </p>

      <label for="min-size">体积下限（可选）</label>
      <ElInput
        id="min-size"
        v-model="minInput"
        placeholder="例如 30KB；留空表示只限制上限"
        maxlength="16"
        aria-describedby="min-help"
      />
      <p id="min-help">
        有些系统要求文件不能太小。低于下限时会插入一段无损的注释段把体积补到下限，不改画质、不重新编码。
      </p>

      <div class="switch-row">
        <ElSwitch id="allow-shrink" v-model="allowShrink" aria-describedby="shrink-help" />
        <label for="allow-shrink">允许缩小尺寸</label>
      </div>
      <p id="shrink-help">
        质量降到最低仍超限时，按 85% 逐档缩小最长边（最多 6 档）。关闭后只能靠降质量，压不下去会直接报失败。
      </p>

      <label for="dpi">DPI（可选）</label>
      <ElInput
        id="dpi"
        v-model="dpiInput"
        placeholder="例如 300；留空表示不改"
        maxlength="4"
        aria-describedby="dpi-help"
      />
      <p id="dpi-help">
        DPI 只是文件里的元数据，不会让图片变清晰；实际打印尺寸由像素数与 DPI 共同决定。
      </p>

      <div class="actions">
        <ElButton type="primary" :loading="busy" :disabled="!picked.length" @click="run">
          {{ busy ? '正在压缩' : '开始压缩' }}
        </ElButton>
        <ElButton v-if="busy" @click="cancel">取消</ElButton>
      </div>

      <ElAlert
        v-if="message"
        class="notice"
        :title="message"
        type="warning"
        :closable="false"
        show-icon
        role="alert"
      />
    </section>

    <section class="panel" aria-labelledby="result-title">
      <h2 id="result-title">3. 结果与下载</h2>

      <TargetKbBatchResult
        v-if="isBatch"
        :rows="rows"
        :summary="batchSummary"
        :packing="packing"
        :zip-ready="Boolean(zipUrl)"
        :zip-bytes="zipBytes"
        @download="downloadZip"
      />

      <template v-else-if="result && summary">
        <p role="status" class="headline">
          {{ summary.before }} → {{ summary.after }}
          <span class="change" :class="{ grew: summary.change > 0 }">
            {{ summary.change > 0 ? '+' : '' }}{{ summary.change }}%
          </span>
        </p>
        <dl class="facts">
          <div>
            <dt>输出尺寸</dt>
            <dd>{{ summary.dimension }}</dd>
          </div>
          <div>
            <dt>采用质量</dt>
            <dd>{{ summary.quality }}</dd>
          </div>
          <div>
            <dt>编码次数</dt>
            <dd>{{ summary.attempts }} 次 · 用时 {{ summary.seconds }} 秒</dd>
          </div>
          <div v-if="summary.padding">
            <dt>体积补齐</dt>
            <dd>{{ summary.padding }}</dd>
          </div>
          <div v-if="summary.dpi">
            <dt>DPI</dt>
            <dd>{{ summary.dpi }}</dd>
          </div>
        </dl>
        <figure class="preview">
          <img :src="resultUrl" alt="压缩后的图片预览" />
          <figcaption>压缩结果预览（{{ result.width }}×{{ result.height }}）</figcaption>
        </figure>
        <ElButton type="primary" class="download" @click="download">
          <ElIcon><Download /></ElIcon>下载 JPEG
        </ElButton>
      </template>

      <template v-else-if="busy">
        <p role="status" class="headline">
          正在寻找最大的可用质量<template v-if="progress">
            ：已试 {{ progress.attempts }} 次，当前质量 {{ progress.quality.toFixed(2) }}，{{
              formatBytes(progress.bytes)
            }}</template
          >。
        </p>
        <ElProgress :percentage="100" :indeterminate="true" :duration="1.4" :show-text="false" />
        <p class="hint">大图可能需要几秒；中途可以取消。</p>
      </template>

      <p v-else class="empty">
        <ElIcon class="empty-icon"><Picture /></ElIcon>
        选择图片并填写目标体积后，这里会显示压缩结果与下载入口。
      </p>
    </section>
  </div>
</template>

<style scoped>
.targetkb {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 24px;
  align-items: start;
}
.panel {
  min-width: 0;
  padding: 30px;
  border: 1px solid var(--ph-line);
  border-radius: 16px;
  background: white;
}
h2 {
  font-size: 20px;
  margin: 0 0 12px;
}
h2:not(:first-child) {
  margin-top: 30px;
}
p {
  color: var(--ph-muted);
  line-height: 1.7;
  font-size: 14px;
}
label:not(.drop) {
  display: block;
  margin: 14px 0 6px;
  font-size: 14px;
}
.drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  padding: 30px 18px;
  border: 1px dashed var(--ph-line);
  border-radius: 12px;
  background: var(--ph-soft);
  cursor: pointer;
  text-align: center;
}
.drop:hover {
  border-color: var(--ph-accent-strong);
}
.drop:focus-within {
  outline: 3px solid var(--ph-accent-strong);
  outline-offset: 4px;
}
.drop input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}
.drop-icon {
  font-size: 26px;
  color: var(--ph-muted);
}
.drop-main {
  font-size: 14px;
}
.drop-sub {
  font-size: 12px;
  color: var(--ph-muted);
}
.source {
  margin: 16px 0 0;
}
.source img {
  display: block;
  width: 100%;
  max-height: 220px;
  object-fit: contain;
  background: var(--ph-soft);
  border-radius: 10px;
}
.source figcaption {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--ph-muted);
  overflow-wrap: anywhere;
}
.source figcaption strong {
  color: var(--ph-ink);
  font-weight: 500;
}
.picked-count {
  margin: 14px 0 0;
  font-size: 13px;
}
.switch-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
}
.switch-row label {
  margin: 0;
}
/* Element Plus 把焦点放在 0 尺寸、透明度的隐藏 input 上，开关本身没有可见提示；
   这里把焦点样式转给可见的轨道，否则键盘用户看不到焦点落在开关上。 */
.switch-row :deep(.el-switch__input:focus-visible + .el-switch__core) {
  outline: 3px solid var(--ph-accent-strong);
  outline-offset: 4px;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 22px;
}
.actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.notice {
  margin-top: 18px;
}
.headline {
  font-size: 18px;
  color: var(--ph-ink);
  margin: 4px 0 16px;
}
.change {
  margin-left: 8px;
  font-size: 13px;
  color: #1f7a45;
}
.change.grew {
  color: var(--ph-accent-strong);
}
.facts {
  margin: 0;
  display: grid;
  gap: 10px;
}
.facts div {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 12px;
  font-size: 13px;
}
.facts dt {
  color: var(--ph-muted);
}
.facts dd {
  margin: 0;
  color: var(--ph-ink);
  overflow-wrap: anywhere;
}
.preview {
  margin: 18px 0 0;
}
.preview img {
  display: block;
  width: 100%;
  max-height: 260px;
  object-fit: contain;
  background: var(--ph-soft);
  border-radius: 10px;
}
.preview figcaption {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ph-muted);
}
.download {
  margin-top: 16px;
}
.hint {
  margin-top: 12px;
  font-size: 13px;
}
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 70px 24px;
  text-align: center;
  border: 1px dashed var(--ph-line);
  border-radius: 12px;
  background: var(--ph-soft);
}
.empty-icon {
  font-size: 26px;
  color: var(--ph-muted);
}
@media (max-width: 900px) {
  .targetkb {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 700px) {
  .panel {
    padding: 20px;
  }
  .facts div {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
</style>

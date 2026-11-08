<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { zip } from 'fflate'
import { ElAlert, ElButton, ElIcon, ElInput, ElProgress, ElSwitch } from 'element-plus'
import { Close, Download, Picture } from '@element-plus/icons-vue'
import FileDropZone from './FileDropZone.vue'
import ToolAdvancedOptions from './ToolAdvancedOptions.vue'
import TargetKbBatchResult from './TargetKbBatchResult.vue'
import {
  MAX_BATCH_BYTES,
  MAX_BATCH_FILES,
  mergePicked,
  mimeExtension,
  outputFileName,
  summarizeBatch,
  validateBatch,
  zipEntryName,
} from '../core/targetKbBatch'
import type { TargetKbBatchRow, TargetKbBatchStatus } from '../core/targetKbBatch'
import { describeCapabilityGap } from '../core/targetKbCapability'
import type { TargetKbCapability } from '../core/targetKbCapability'
import { createTargetRange, parseTargetSize } from '../core/targetKbCompress'
import { isCurrentReply } from '../core/targetKbProtocol'
import type {
  TargetKbEncodeResult,
  TargetKbProgress,
  TargetKbReply,
} from '../core/targetKbProtocol'
import { inspectSource, inspectSourceSize } from '../core/targetKbSource'
import type { TargetKbTarget } from '../core/targetKbTypes'
import { formatBytes } from '../utils/byteSize'

/** 文件头只读前 256 KiB：EXIF 缩略图可能很长，切太小会解析不到 SOF。 */
const HEADER_BYTES = 256 * 1024

/** 页面侧只做格式与范围提示，真正校验仍在内核与 Worker。 */
const DPI_RANGE = '1～1200'

/**
 * 探测本地编码能力。放在组件里而不是 core：铁律 7 不允许 core/workers 引用浏览器全局对象。
 * `convertToBlob` 必须单独判——Safari 16.4 之前有 `OffscreenCanvas` 构造器却没有这个方法。
 */
function detectCapability(): TargetKbCapability {
  const hasOffscreenCanvas = typeof OffscreenCanvas !== 'undefined'
  return {
    worker: typeof Worker !== 'undefined',
    createImageBitmap: typeof createImageBitmap === 'function',
    offscreenCanvas: hasOffscreenCanvas,
    convertToBlob:
      hasOffscreenCanvas && typeof OffscreenCanvas.prototype.convertToBlob === 'function',
  }
}

/** 能力由浏览器版本决定，页面生命周期内不会变，算一次即可。 */
const capabilityGap = describeCapabilityGap(detectCapability())

/**
 * 选中的一张图：文件、缩略图地址与状态放在同一个对象里。
 * 早先用"文件数组 + 状态数组"两个平行列表按索引对应，删掉中间一项就会整体错位；
 * 这里改成稳定 id，输出字节也以 id 为键，删除后不需要重排任何东西。
 */
interface PickedEntry {
  /** 稳定标识，只增不减，删除后不复用。 */
  id: number
  /** 用户选中的原始文件；字节从不改动，只读取。 */
  file: File
  /** 缩略图临时地址，移除或换图时必须撤销，否则 Blob 一直占着内存。 */
  url: string
  /** 单张时解析出的原始宽高；批量不解析文件头，保持 null。 */
  width: number | null
  height: number | null
  /** 当前处理状态。 */
  status: TargetKbBatchStatus
  /** 成功后的输出字节数。 */
  outputBytes?: number
  /** 成功后的实际输出类型；决定下载与 ZIP 内文件的后缀。 */
  outputMime?: string
  /** 是否免重新编码，DPI 元数据仍可能改写。 */
  passthrough?: boolean
  /** 失败原因，直接展示。 */
  message?: string
}

const entries = ref<PickedEntry[]>([])
let nextEntryId = 1
const targetInput = ref('')
const minInput = ref('')
const dpiInput = ref('')
const allowShrink = ref(true)
const advancedOpen = ref(false)
/** 折叠后仍提示生效的非默认设置，避免用户忘记隐藏参数。 */
const advancedSummary = computed(() => {
  const settings = [
    minInput.value.trim() ? `下限 ${minInput.value.trim()}` : '',
    !allowShrink.value ? '保留尺寸' : '',
    dpiInput.value.trim() ? `DPI ${dpiInput.value.trim()}` : '',
  ].filter(Boolean)
  return settings.length ? settings.join(' · ') : '体积下限 · 尺寸 · DPI'
})
const busy = ref(false)
const packing = ref(false)
const message = ref('')
const progress = ref<TargetKbProgress | null>(null)
const result = ref<TargetKbEncodeResult | null>(null)
const resultUrl = ref('')
const zipUrl = ref('')
const zipBytes = ref(0)

// 输出字节单独用普通 Map 存放：放进响应式数组会被代理包住，大数组的代理开销没必要。
// 键是 entry.id，不是数组下标——删掉一张后下标会变，id 不会。
const outputs = new Map<number, Uint8Array>()

// 单线程复用：取消只作废当前 id，不重建 Worker；卸载时再终止。
let worker: Worker | undefined
let activeId = 0
let nextId = 1
let disposed = false

/** 一个递增编号覆盖读文件、整批编码与打包，异步边界只认可仍有效的这次任务。 */
function isCurrentTask(id: number): boolean {
  return !disposed && isCurrentReply(id, activeId)
}
let pendingReply: ((reply: TargetKbReply) => void) | null = null

/** 多选即批量；单张仍走原来的详细结果视图。 */
const isBatch = computed(() => entries.value.length > 1)
/** 单张时才有大预览与详细结果；模板据此切换，不依赖非空断言。 */
const singleEntry = computed(() => (entries.value.length === 1 ? (entries.value[0] ?? null) : null))
/** 结果面板仍按"行"消费，这里把 entry 投影成只读行，组件无需知道 id。 */
const rows = computed<TargetKbBatchRow[]>(() =>
  entries.value.map((entry) => ({
    name: entry.file.name,
    size: entry.file.size,
    status: entry.status,
    outputBytes: entry.outputBytes,
    passthrough: entry.passthrough,
    message: entry.message,
  })),
)
const batchSummary = computed(() => summarizeBatch(rows.value))
/** 文件限额按二进制 MiB 表示，与实际校验字节数一致。 */
const MAX_BATCH_MIB = Math.round(MAX_BATCH_BYTES / 1024 / 1024)

/**
 * 只释放"输出侧"的临时地址。
 * 输入缩略图不能在这里撤销：作废旧结果时用户选的文件还在，缩略图必须继续可见。
 */
function clearOutputUrls(): void {
  if (resultUrl.value) URL.revokeObjectURL(resultUrl.value)
  if (zipUrl.value) URL.revokeObjectURL(zipUrl.value)
  resultUrl.value = ''
  zipUrl.value = ''
  zipBytes.value = 0
}

/** 清空选中项并释放所有缩略图地址。 */
function releaseEntries(): void {
  for (const entry of entries.value) URL.revokeObjectURL(entry.url)
  entries.value = []
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
  clearOutputUrls()
  for (const entry of entries.value) {
    entry.status = 'pending'
    entry.outputBytes = undefined
    entry.message = undefined
  }
  resolve?.({ id: 0, ok: false, reason: 'cancelled', message: '已取消' })
}

/**
 * 单张时补解析文件头，用于显示原始尺寸。
 * 解析失败或超限就整批退回：这张图后面也压不了，留着只会让用户白等。
 */
async function inspectSingle(entry: PickedEntry): Promise<void> {
  const stillSingle = () =>
    !disposed && entries.value.length === 1 && entries.value[0]?.id === entry.id
  const reject = (text: string) => {
    if (!stillSingle()) return
    invalidate()
    releaseEntries()
    message.value = text
  }
  if (!stillSingle()) return
  const sizeCheck = inspectSourceSize(entry.file.size)
  if (!sizeCheck.ok) {
    reject(sizeCheck.message)
    return
  }
  try {
    const head = new Uint8Array(await entry.file.slice(0, HEADER_BYTES).arrayBuffer())
    // 追加、移除或清空后，迟到检查不能清掉新列表；文件引用与稳定 id 一起限定来源。
    if (!stillSingle()) return
    const inspection = inspectSource(head)
    if (!inspection.ok) {
      reject(inspection.message)
      return
    }
    entry.width = inspection.width
    entry.height = inspection.height
  } catch {
    reject('读取图片信息失败，请重新选择图片。')
  }
}

/**
 * 收下选中的图片：去重、校验合并后的批量上限，然后**追加**到列表末尾。
 * 追加而不是替换——用户常常先拖一张试，再陆续把其余的补进来。
 */
async function acceptFiles(list: File[]): Promise<void> {
  if (disposed) return
  message.value = ''

  const { added, duplicates, notImages } = mergePicked(
    entries.value.map((entry) => entry.file),
    list,
  )
  const notes: string[] = []
  if (notImages > 0) notes.push(`已忽略 ${notImages} 个非图片文件`)
  if (duplicates > 0) notes.push(`已忽略 ${duplicates} 张重复图片`)

  if (added.length === 0) {
    message.value =
      notImages === list.length
        ? '请选择图片文件（JPEG、PNG、WebP 或 AVIF）。'
        : notes.length
          ? `${notes.join('，')}。`
          : '这些图片已经在列表里了。'
    return
  }

  // 先按"已有 + 新增"校验，超限就整批不加，避免列表被加到一半才失败。
  const merged = [
    ...entries.value.map((entry) => ({ name: entry.file.name, size: entry.file.size })),
    ...added.map((file) => ({ name: file.name, size: file.size })),
  ]
  try {
    validateBatch(merged)
  } catch (error: unknown) {
    message.value = error instanceof Error ? error.message : '选择失败，请重试'
    return
  }

  // 列表构成变了，之前的批量结果不再对应；这里在追加之前作废，被拒绝的选择不会清空已有列表。
  invalidate()

  for (const file of added) {
    entries.value.push({
      id: nextEntryId++,
      file,
      url: URL.createObjectURL(file),
      width: null,
      height: null,
      status: 'pending',
    })
  }

  // 多张时不逐张解析文件头：解析结果只用于单图预览，批量交给 Worker 逐张判断。
  if (entries.value.length === 1) await inspectSingle(entries.value[0]!)
  if (!disposed && notes.length) message.value = `${notes.join('，')}。`
}

/**
 * 从待选区移除一张。
 * 删除会改变批量结果的含义（ZIP 少一项、合计体积变了），所以一并作废旧结果，
 * 不让用户下载到与当前列表不符的文件。
 */
function removeEntry(id: number): void {
  if (busy.value) return
  const index = entries.value.findIndex((entry) => entry.id === id)
  if (index < 0) return
  const [removed] = entries.value.splice(index, 1)
  if (removed) URL.revokeObjectURL(removed.url)
  invalidate()
  message.value = removed ? `已移除 ${removed.file.name}` : ''
  // 删到只剩一张时补解析尺寸，否则大预览会一直显示"尺寸待解码后确认"。
  const only = entries.value[0]
  if (entries.value.length === 1 && only && only.width === null) void inspectSingle(only)
}

/** 清空待选区，回到未选图的状态。 */
function clearAll(): void {
  if (busy.value) return
  invalidate()
  releaseEntries()
  message.value = ''
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
    if (worker !== instance || disposed) return
    instance.terminate()
    worker = undefined
    invalidate()
    // 能力检测已挡住已知的旧浏览器，但 Worker 内报错也可能来自 OOM 等运行时原因，
    // 所以两种可能都提一句，别让用户对着"请重试"无限重试。
    message.value =
      '本地编码线程出错，请重试；若反复出现，请改用 Chrome、Edge 或 Safari 16.4 及以上版本。'
  }
  worker = instance
  return instance
}

/** 只按 id 判断归属：换图或改参数会换 id，旧任务的迟到消息自然被丢弃。 */
function handleReply(reply: TargetKbReply): void {
  if (!isCurrentTask(reply.id)) return
  if (reply.progress) {
    progress.value = reply.progress
    return
  }
  const resolve = pendingReply
  pendingReply = null
  progress.value = null
  resolve?.(reply)
}

/** 串行发送一张图并等待结果；批量与单图共用同一条路径。 */
function sendEncode(
  bytes: ArrayBuffer,
  target: TargetKbTarget,
  dpi: number | null,
  id: number,
): Promise<TargetKbReply> {
  if (!isCurrentTask(id))
    return Promise.resolve({ id, ok: false, reason: 'cancelled', message: '已取消' })
  return new Promise((resolve) => {
    pendingReply = resolve
    ensureWorker().postMessage(
      { id, kind: 'encode', bytes, target, allowShrink: allowShrink.value, dpi },
      [bytes],
    )
  })
}

async function runSingle(target: TargetKbTarget, dpi: number | null, id: number): Promise<void> {
  const entry = entries.value[0]
  if (!entry) return
  try {
    const bytes = await entry.file.arrayBuffer()
    if (!isCurrentTask(id)) return
    const reply = await sendEncode(bytes, target, dpi, id)
    if (!isCurrentTask(id) || reply.reason === 'cancelled') return
    if (reply.ok && reply.result) {
      result.value = reply.result
      resultUrl.value = URL.createObjectURL(
        new Blob([reply.result.bytes], { type: reply.result.mime }),
      )
    } else message.value = reply.message ?? '处理失败，请重试'
  } catch {
    if (isCurrentTask(id)) message.value = '读取图片失败，请重试'
  }
}

async function runBatch(target: TargetKbTarget, dpi: number | null, id: number): Promise<void> {
  const list = [...entries.value]
  try {
    for (const entry of list) {
      if (!isCurrentTask(id)) return
      entry.status = 'running'
      progress.value = null
      const bytes = await entry.file.arrayBuffer()
      if (!isCurrentTask(id)) return
      const reply = await sendEncode(bytes, target, dpi, id)
      if (!isCurrentTask(id) || reply.reason === 'cancelled') return
      if (reply.ok && reply.result) {
        entry.status = 'done'
        entry.outputBytes = reply.result.bytes.byteLength
        entry.outputMime = reply.result.mime
        entry.passthrough = reply.result.passthrough
        outputs.set(entry.id, new Uint8Array(reply.result.bytes))
      } else {
        entry.status = 'failed'
        entry.message = reply.message ?? '处理失败'
      }
    }
    await packZip(id)
  } catch {
    if (isCurrentTask(id)) message.value = '批量处理中断，请重试'
  }
}

/** 只打包成功项；有失败就单独列出，不把半成品混进 ZIP。 */
async function packZip(id: number): Promise<void> {
  if (!isCurrentTask(id)) return
  const done = entries.value.filter((entry) => entry.status === 'done')
  if (done.length === 0) {
    message.value = '所有图片都未能压缩，未生成 ZIP。'
    return
  }
  packing.value = true
  try {
    const archiveEntries: Record<string, Uint8Array> = {}
    // 序号按"成功项在当前列表中的顺序"重新数，删掉中间一张后解压顺序仍与界面一致。
    let order = 0
    for (const entry of done) {
      const bytes = outputs.get(entry.id)
      if (!bytes) continue
      // 后缀跟随实际输出类型：原样交付的 PNG/WebP 不能写成 .jpg。
      const ext = mimeExtension(entry.outputMime ?? 'image/jpeg')
      archiveEntries[zipEntryName(order, entry.file.name, bytes.byteLength, ext)] = bytes
      order += 1
    }
    const archive = await new Promise<Uint8Array>((resolve, reject) => {
      // JPEG 已经压缩过，deflate 几乎不再变小；用 store 换取更短的等待。
      zip(archiveEntries, { level: 0 }, (error, data) => (error ? reject(error) : resolve(data)))
    })
    if (!isCurrentTask(id)) return
    zipUrl.value = URL.createObjectURL(new Blob([archive.slice()], { type: 'application/zip' }))
    zipBytes.value = archive.byteLength
  } catch {
    if (isCurrentTask(id)) message.value = '打包 ZIP 失败，请重试'
  } finally {
    if (isCurrentTask(id)) packing.value = false
  }
}

async function run(): Promise<void> {
  if (disposed || !entries.value.length || busy.value) return
  message.value = ''

  let target: TargetKbTarget
  let dpi: number | null
  try {
    target = readTarget()
    dpi = readDpi()
  } catch (error: unknown) {
    message.value = error instanceof Error ? error.message : '参数填写有误'
    // 高级参数可能已折叠，出错时展开以便直接修正；不重置用户输入。
    if (minInput.value.trim() || dpiInput.value.trim()) advancedOpen.value = true
    return
  }

  invalidate()
  const id = nextId++
  activeId = id
  busy.value = true
  try {
    if (isBatch.value) await runBatch(target, dpi, id)
    else await runSingle(target, dpi, id)
  } finally {
    // 旧任务的 finally 也不能解除新任务的忙碌状态或清掉它的等待回调。
    if (isCurrentTask(id)) {
      activeId = 0
      pendingReply = null
      busy.value = false
      packing.value = false
    }
  }
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

/**
 * 放弃结果：释放输出字节与 ZIP 地址，但保留已选图片和参数。
 * 用户可能只是不想下载、想换个目标再压一次，不该被迫重新选图。
 */
function discardResult(): void {
  if (busy.value) return
  invalidate()
  message.value = '已放弃结果，可调整设置后重新压缩。'
}

function download(): void {
  const out = result.value
  if (!out) return
  // 后缀跟随实际输出类型，原样交付的 PNG/WebP 不能存成 .jpg。
  const ext = mimeExtension(out.mime)
  const name = singleEntry.value
    ? outputFileName(singleEntry.value.file.name, out.bytes.byteLength, ext)
    : `妙压.${ext}`
  const url = URL.createObjectURL(new Blob([out.bytes], { type: out.mime }))
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
  const input = singleEntry.value
  if (!out || !input) return null
  const after = out.bytes.byteLength
  const before = input.file.size
  return {
    before: formatBytes(before),
    after: formatBytes(after),
    change: before > 0 ? Math.round(((after - before) / before) * 100) : 0,
    passthrough: out.passthrough,
    dimension: out.passthrough
      ? `${out.width}×${out.height}（保持原尺寸，未重新编码）`
      : out.scaleStep > 0
        ? `${out.width}×${out.height}（缩小 ${out.scaleStep} 档）`
        : `${out.width}×${out.height}（保持原尺寸）`,
    // 原样交付没有"采用质量"这回事，留空让模板整行不渲染。
    quality: out.quality === null ? '' : out.quality.toFixed(2),
    attempts: out.probes.length,
    seconds: (out.elapsedMs / 1000).toFixed(1),
    format: out.mime.replace('image/', '').toUpperCase(),
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
  disposed = true
  invalidate()
  worker?.terminate()
  worker = undefined
  clearOutputUrls()
  releaseEntries()
})
</script>

<template>
  <div class="targetkb">
    <section class="panel" aria-labelledby="pick-title">
      <h2 id="pick-title">1. 选择图片</h2>
      <p id="pick-help">
        本机处理，不上传。最多 {{ MAX_BATCH_FILES }} 张、合计 {{ MAX_BATCH_MIB }} MiB；单张 20 MiB /
        4000 万像素。需要压缩或转换时输出 JPEG，透明区域变白。
      </p>
      <ElAlert
        v-if="capabilityGap"
        class="notice"
        :title="capabilityGap"
        description="图片不会被上传，但本地编码跑不起来；换成受支持的浏览器后即可正常使用。"
        type="error"
        :closable="false"
        show-icon
        role="alert"
      />

      <FileDropZone
        accept="image/jpeg,image/png,image/webp,image/avif"
        label="点击选择图片，或拖到这里"
        description="JPEG / PNG / WebP / AVIF · 支持多选和追加"
        help-id="pick-help"
        :disabled="busy"
        @select="acceptFiles"
        @reject="message = $event"
      />

      <figure v-if="singleEntry" class="source">
        <img :src="singleEntry.url" :alt="`待压缩的图片：${singleEntry.file.name}`" />
        <figcaption>
          <strong>{{ singleEntry.file.name }}</strong>
          <span>{{ formatBytes(singleEntry.file.size) }}</span>
          <span v-if="singleEntry.width">{{ singleEntry.width }}×{{ singleEntry.height }}</span>
          <span v-else>尺寸待解码后确认</span>
          <ElButton
            class="pick-remove"
            text
            :disabled="busy"
            aria-label="移除这张图片"
            @click="removeEntry(singleEntry.id)"
          >
            <ElIcon><Close /></ElIcon>移除
          </ElButton>
        </figcaption>
      </figure>

      <div v-else-if="entries.length > 1" class="picked">
        <div class="picked-head">
          <p class="picked-count">
            已选 {{ entries.length }} 张，合计 {{ formatBytes(batchSummary.inputBytes) }}。
          </p>
          <ElButton text :disabled="busy" @click="clearAll">清空</ElButton>
        </div>
        <ul class="pick-list">
          <li v-for="entry in entries" :key="entry.id" class="pick-row">
            <img class="pick-thumb" :src="entry.url" alt="" />
            <span class="pick-name" :title="entry.file.name">{{ entry.file.name }}</span>
            <span class="pick-size">{{ formatBytes(entry.file.size) }}</span>
            <ElButton
              text
              :disabled="busy"
              :aria-label="`移除 ${entry.file.name}`"
              @click="removeEntry(entry.id)"
            >
              <ElIcon><Close /></ElIcon>
            </ElButton>
          </li>
        </ul>
      </div>

      <h2>2. 设置目标</h2>
      <label for="target-size">目标体积上限</label>
      <ElInput
        id="target-size"
        v-model="targetInput"
        placeholder="例如 200 KiB 或 1.5 MiB"
        maxlength="16"
        aria-describedby="target-help"
      />
      <p id="target-help">
        支持 KiB、MiB 或字节，旧写法 KB、MB 仍可用。多张共用设置，默认按需缩小尺寸。
      </p>

      <ToolAdvancedOptions id="targetkb-advanced" v-model="advancedOpen" :summary="advancedSummary">
        <label for="min-size">体积下限（可选）</label>
        <ElInput
          id="min-size"
          v-model="minInput"
          placeholder="例如 30 KiB；留空表示只限制上限"
          maxlength="16"
          aria-describedby="min-help"
        />
        <p id="min-help">文件过小时用注释数据补到下限，不改变画质。</p>

        <div class="switch-row">
          <ElSwitch id="allow-shrink" v-model="allowShrink" aria-describedby="shrink-help" />
          <label for="allow-shrink">允许缩小尺寸</label>
        </div>
        <p id="shrink-help">降低画质仍超限时缩小尺寸；关闭后可能无法达到目标。</p>

        <label for="dpi">DPI（可选）</label>
        <ElInput
          id="dpi"
          v-model="dpiInput"
          placeholder="例如 300；留空表示不改"
          maxlength="4"
          aria-describedby="dpi-help"
        />
        <p id="dpi-help">仅修改打印密度标记，不增加清晰度。支持 {{ DPI_RANGE }}，留空不改。</p>
      </ToolAdvancedOptions>

      <div class="actions">
        <ElButton
          type="primary"
          :loading="busy"
          :disabled="!entries.length || Boolean(capabilityGap)"
          @click="run"
        >
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
        :running="busy"
        :packing="packing"
        :zip-ready="Boolean(zipUrl)"
        :zip-bytes="zipBytes"
        @download="downloadZip"
        @discard="discardResult"
      />

      <template v-else-if="result && summary">
        <p v-if="summary.passthrough" role="status" class="headline untouched">
          原图 {{ summary.before }} 已在目标体积内，<template v-if="summary.dpi"
            >未重新压缩像素；已按设置写入 DPI 信息。</template
          ><template v-else>无需压缩，一个字节都没改。</template>
        </p>
        <p v-else role="status" class="headline">
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
          <div v-if="summary.quality">
            <dt>采用质量</dt>
            <dd>{{ summary.quality }}</dd>
          </div>
          <div v-if="!summary.passthrough">
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
          <img :src="resultUrl" :alt="summary.passthrough ? '原图预览' : '压缩后的图片预览'" />
          <figcaption>
            {{ summary.passthrough ? '原图预览' : '压缩结果预览' }}（{{ result.width }}×{{
              result.height
            }}）
          </figcaption>
        </figure>
        <div class="result-actions">
          <ElButton type="primary" @click="download">
            <ElIcon><Download /></ElIcon>下载 {{ summary.format }}
          </ElButton>
          <ElButton text @click="discardResult">放弃结果</ElButton>
        </div>
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
  margin-top: 32px;
}
p {
  color: var(--ph-muted);
  line-height: 1.7;
  font-size: 14px;
}
label:not(.file-drop) {
  display: block;
  margin: 14px 0 6px;
  font-size: 14px;
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
.pick-remove {
  margin-left: auto;
  font-size: 12px;
  color: var(--ph-muted);
}
.picked {
  margin-top: 14px;
}
.picked-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.picked-count {
  margin: 0;
  font-size: 13px;
}
.pick-list {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  display: grid;
  /* 默认 auto 轨道会按长文件名的最小内容撑宽，挤出右侧移除入口。 */
  grid-template-columns: minmax(0, 1fr);
  gap: 6px;
}
.pick-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border: 1px solid var(--ph-line);
  border-radius: 8px;
  background: var(--ph-soft);
  font-size: 12px;
  color: var(--ph-muted);
}
.pick-thumb {
  width: 36px;
  height: 36px;
  flex: none;
  object-fit: cover;
  border-radius: 6px;
  background: white;
}
.pick-name {
  flex: 1 1 auto;
  min-width: 0;
  color: var(--ph-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pick-size {
  flex: none;
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
/* 原样返回不是"压缩结果"，语气要和压缩成功区分开。 */
.headline.untouched {
  color: var(--ph-muted);
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
.result-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}
.result-actions :deep(.el-button + .el-button) {
  margin-left: 0;
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

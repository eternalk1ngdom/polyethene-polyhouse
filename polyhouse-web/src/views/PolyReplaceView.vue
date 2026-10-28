<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ElAlert, ElButton, ElInput } from 'element-plus'
import type { ReplaceRule } from '../core/acReplacer'
import type { VirtualFileEntry } from '../core/types'
import { validateBatch } from '../core/polyReplaceBatch'
import type {
  BatchDownload,
  InputEncoding,
  ReplaceMode,
  WorkerReply,
} from '../core/polyReplaceProtocol'

const selectedFiles = ref<File[]>([])
const filePicker = ref<HTMLInputElement>()
const fileList = ref<HTMLUListElement>()
const selectedMode = ref<ReplaceMode>('files')
const inputEncoding = ref<InputEncoding>('utf-8')
const directorySupported = 'webkitdirectory' in document.createElement('input')
const rules = ref<ReplaceRule[]>([{ search: '', replace: '' }])
const result = ref<BatchDownload | null>(null)
const previewIndex = ref(0)
const message = ref('')
const busy = ref(false)
let worker: Worker | undefined
let revision = 0
const activePreview = computed(() => result.value?.files[previewIndex.value])
const totalCount = computed(
  () => result.value?.files.reduce((total, file) => total + file.count, 0) ?? 0,
)

/** 文件或规则变化时停止旧任务，保留选中文件以供重新处理。 */
function invalidate(): void {
  revision += 1
  worker?.terminate()
  worker = undefined
  busy.value = false
  result.value = null
  previewIndex.value = 0
  message.value = ''
}
watch(rules, invalidate, { deep: true, flush: 'sync' })
watch(inputEncoding, invalidate, { flush: 'sync' })
onBeforeUnmount(invalidate)

/** 选择阶段只保存文件引用并检查大小，不与编辑规则争抢异步读取结果。 */
function selectFile(event: Event, mode: ReplaceMode): void {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return
  invalidate()
  selectedFiles.value = []
  try {
    validateBatch(
      files.map((file) => ({
        relativePath: mode === 'directory' ? file.webkitRelativePath : file.name,
        size: file.size,
      })),
      mode,
    )
    selectedMode.value = mode
    selectedFiles.value = files
  } catch (error: unknown) {
    message.value = error instanceof Error ? error.message : '选择失败，请重试'
  }
}

/** 移除仅影响本次选择；先终止旧任务并清空结果，避免下载包仍含用户已排除的文件。 */
async function removeFile(index: number): Promise<void> {
  if (index < 0 || index >= selectedFiles.value.length) return
  const wasBusy = busy.value
  invalidate()
  selectedFiles.value.splice(index, 1)
  if (wasBusy) message.value = '已取消处理并移除文件，请重新开始替换。'
  // 删除按钮会随清单移除，主动把键盘焦点交给相邻项，删空后回到文件选择入口。
  await nextTick()
  const buttons = fileList.value?.querySelectorAll<HTMLButtonElement>('.remove-file')
  const nextButton = buttons?.[Math.min(index, selectedFiles.value.length - 1)]
  if (nextButton) nextButton.focus()
  else filePicker.value?.focus()
}

/** 限制规则数量，控制任务工作量。 */
function addRule(): void {
  if (rules.value.length < 20) rules.value.push({ search: '', replace: '' })
}
/** 删除规则同步使旧结果失效。 */
function removeRule(index: number): void {
  if (rules.value.length > 1) rules.value.splice(index, 1)
}
/** 在任务开始时固定输入快照，逐个读取文件以限制并发内存。 */
async function runReplace(): Promise<void> {
  invalidate()
  if (!selectedFiles.value.length) {
    message.value = '请先选择文本文件'
    return
  }
  if (rules.value.some((rule) => !rule.search)) {
    message.value = '请填写每条规则的查找内容'
    return
  }
  if (new Set(rules.value.map((rule) => rule.search)).size !== rules.value.length) {
    message.value = '查找内容不能重复'
    return
  }
  const current = revision
  const inputs = [...selectedFiles.value]
  const mode = selectedMode.value
  const encoding = inputEncoding.value
  const ruleSnapshot = rules.value.map(({ search, replace }) => ({ search, replace }))
  busy.value = true
  try {
    const files: VirtualFileEntry[] = []
    for (const file of inputs) {
      const content = await file.arrayBuffer()
      if (current !== revision) return
      files.push({
        relativePath: mode === 'directory' ? file.webkitRelativePath : file.name,
        content,
      })
    }
    const active = new Worker(new URL('../workers/polyReplace.worker.ts', import.meta.url), {
      type: 'module',
    })
    worker = active
    active.onmessage = (event: MessageEvent<WorkerReply>) => {
      if (current !== revision) return
      busy.value = false
      active.terminate()
      worker = undefined
      if (event.data.ok && event.data.result) result.value = event.data.result
      else message.value = event.data.message || '处理失败，请重试'
    }
    active.onerror = () => {
      if (current !== revision) return
      invalidate()
      message.value = '处理失败，请重试'
    }
    // 转移读取所得缓冲区，不复制 Vue 代理或 File 对象到后台线程。
    active.postMessage(
      { files, rules: ruleSnapshot, mode, inputEncoding: encoding },
      files.map((file) => file.content),
    )
  } catch {
    if (current !== revision) return
    invalidate()
    message.value = '读取或启动本地处理失败，请重试'
  }
}
/** 下载 Worker 生成的完整文件，随后释放临时地址。 */
function download(): void {
  if (!result.value) return
  const url = URL.createObjectURL(new Blob([result.value.bytes], { type: result.value.mime }))
  const link = document.createElement('a')
  link.download = result.value.name
  link.href = url
  link.click()
  // 给浏览器接收下载请求的时间，随后释放内存中的临时地址。
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<template>
  <div class="replace-page">
    <RouterLink class="back" to="/">← 返回首页</RouterLink>
    <header>
      <p class="eyebrow">POLYREPLACE / 文本替换</p>
      <h1>妙替<span class="workspace-label">本地文本工作台</span></h1>
      <p>让重复的文字修改，简单一点。</p>
    </header>
    <div class="layout">
      <section class="panel" aria-labelledby="input-title">
        <h2 id="input-title">1. 选择文本文件</h2>
        <p>
          文件仅在浏览器中处理，不会上传。最多 20 个文件，每个不超过 2 MiB，总计不超过 5
          MiB。文件夹保留目录结构并打包为 ZIP，空文件夹不会包含在结果中；
          任一文件无法处理则整批停止，请移除二进制文件或检查输入编码。
        </p>
        <p>
          Python 等源码按全文替换，注释、字符串和代码中匹配的内容都会修改，不支持仅替换注释。
          文件夹不会自动跳过 .git、.venv 等目录，请先准备需要处理的小型文本目录。
        </p>
        <label class="file-button"
          >选择文件<input
            ref="filePicker"
            type="file"
            multiple
            accept=".txt,.md,.json,.csv,.html,.css,.js,.ts,.vue,.java,.py,.xml,.yml,.yaml,.log"
            @change="selectFile($event, 'files')"
        /></label>
        <label v-if="directorySupported" class="file-button directory-button"
          >选择文件夹<input
            type="file"
            webkitdirectory
            multiple
            @change="selectFile($event, 'directory')"
        /></label>
        <p v-else>当前浏览器不支持选择文件夹，可改为选择文件。</p>
        <span class="file-name">{{
          selectedFiles.length ? `已选 ${selectedFiles.length} 个文件` : '尚未选择文件'
        }}</span>
        <ul v-if="selectedFiles.length" ref="fileList" class="file-list">
          <li v-for="(file, index) in selectedFiles" :key="index">
            <span class="selected-path"
              >{{ index + 1 }}.
              {{ selectedMode === 'directory' ? file.webkitRelativePath : file.name }}</span
            >
            <ElButton
              text
              class="remove-file"
              :aria-label="`移除 ${selectedMode === 'directory' ? file.webkitRelativePath : file.name}`"
              @click="removeFile(index)"
              >移除</ElButton
            >
          </li>
        </ul>
        <label for="input-encoding">文件原本的编码</label>
        <select id="input-encoding" v-model="inputEncoding" aria-describedby="encoding-help">
          <option value="utf-8">UTF-8（默认）</option>
          <option value="gb18030">GBK / GB18030</option>
        </select>
        <p id="encoding-help">
          请选择所有文件共同使用的编码；无法自动判断，请检查预览。导出统一为 UTF-8，GBK
          文件不会保留原编码；Python、HTML、XML 等文件中的编码声明不会自动修改。
        </p>
        <h2>2. 设置替换规则</h2>
        <p>按原文一次处理；同一位置多条规则匹配时，优先较长的内容。替换内容留空表示删除。</p>
        <div v-for="(rule, index) in rules" :key="index" class="rule">
          <label :for="`search-${index}`">查找 {{ index + 1 }}</label>
          <ElInput
            :id="`search-${index}`"
            v-model="rule.search"
            placeholder="要查找的内容"
            maxlength="200"
          />
          <label :for="`replace-${index}`">替换为 {{ index + 1 }}</label>
          <ElInput
            :id="`replace-${index}`"
            v-model="rule.replace"
            placeholder="替换后的内容，可留空"
            maxlength="200"
          />
          <ElButton v-if="rules.length > 1" text @click="removeRule(index)">删除规则</ElButton>
        </div>
        <div class="actions">
          <ElButton :disabled="rules.length >= 20 || busy" @click="addRule">添加规则</ElButton
          ><ElButton type="primary" :loading="busy" @click="runReplace">开始替换</ElButton
          ><ElButton v-if="busy" @click="invalidate">取消处理</ElButton>
        </div>
        <ElAlert
          v-if="message"
          :title="message"
          type="warning"
          :closable="false"
          show-icon
          class="notice"
          role="alert"
        />
      </section>
      <section class="panel" aria-labelledby="result-title">
        <h2 id="result-title">3. 预览与下载</h2>
        <template v-if="result">
          <p role="status">完成 {{ result.files.length }} 个文件，共替换 {{ totalCount }} 处。</p>
          <p>结果文件编码：UTF-8。下载前请检查预览是否正确。</p>
          <label for="preview-file">选择预览文件</label>
          <select id="preview-file" v-model="previewIndex">
            <option v-for="(file, index) in result.files" :key="index" :value="index">
              {{ file.name }} · {{ file.count }} 处
            </option>
          </select>
          <p v-if="activePreview?.truncated">预览仅显示前 10000 个字符，下载包含完整结果。</p>
          <label for="result-preview">替换结果预览</label>
          <textarea id="result-preview" :value="activePreview?.preview ?? ''" readonly rows="14" />
          <ElButton type="primary" class="download" @click="download">{{
            result.mime === 'application/zip' ? '下载 ZIP 结果包' : '下载结果文件'
          }}</ElButton>
        </template>
        <p v-else class="empty">选择文件并添加规则后，在这里查看结果。</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.replace-page {
  width: min(1320px, calc(100% - 96px));
  margin: 28px auto 0;
}
.back {
  color: var(--ph-muted);
  font-size: 14px;
}
header {
  margin: 24px 0 30px;
  padding: 30px 34px;
  background: var(--ph-cream);
  border-radius: 20px;
}
.eyebrow {
  color: var(--ph-muted);
  font-size: 12px;
  letter-spacing: 1.5px;
}
h1 {
  font-size: 42px;
  margin: 10px 0;
}
.workspace-label {
  display: inline-block;
  vertical-align: middle;
  font-size: 12px;
  font-weight: 500;
  color: var(--ph-muted);
  margin-left: 18px;
  padding: 7px 12px;
  border: 1px solid var(--ph-line);
  border-radius: 99px;
}
h2 {
  font-size: 20px;
  margin: 0 0 12px;
}
p {
  color: var(--ph-muted);
  line-height: 1.7;
  font-size: 14px;
}
.layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
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
.panel h2:not(:first-child) {
  margin-top: 32px;
}
.file-button {
  display: inline-block;
  padding: 10px 18px;
  border-radius: 99px;
  border: 1px solid var(--ph-line);
  cursor: pointer;
}
.file-button:focus-within {
  outline: 3px solid var(--ph-brand);
  outline-offset: 3px;
}
.directory-button {
  margin-left: 8px;
}
.file-button input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}
.file-name {
  display: inline-block;
  margin-left: 12px;
  color: var(--ph-muted);
  overflow-wrap: anywhere;
  font-size: 14px;
}
.rule {
  margin: 18px 0;
  padding: 16px;
  background: var(--ph-soft);
  border: 1px solid var(--ph-line);
  border-radius: 10px;
}
/* Element Plus 已在圆角外框显示聚焦边线，内部 input 不再叠加全局 tabindex 方框。 */
.rule :deep(.el-input__inner:focus-visible) {
  outline: none;
}
label:not(.file-button) {
  display: block;
  margin: 10px 0 6px;
  font-size: 14px;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 20px;
}
.actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.notice {
  margin-top: 18px;
}
.file-list {
  max-height: 140px;
  overflow: auto;
  padding: 0;
  list-style: none;
  overflow-wrap: anywhere;
  color: var(--ph-muted);
  font-size: 14px;
}
.file-list li {
  display: flex;
  align-items: center;
  gap: 12px;
}
.selected-path {
  min-width: 0;
  flex: 1;
}
.remove-file {
  min-width: 44px;
  min-height: 44px;
  flex-shrink: 0;
}
select {
  width: 100%;
  padding: 10px;
  border: 1px solid var(--ph-line);
  border-radius: 8px;
  background: white;
  color: var(--ph-ink);
}
textarea {
  width: 100%;
  resize: vertical;
  border: 1px solid var(--ph-line);
  border-radius: 8px;
  padding: 12px;
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
}
.download {
  margin-top: 16px;
}
.empty {
  padding: 70px 24px;
  text-align: center;
  border: 1px dashed var(--ph-line);
  border-radius: 12px;
  background: var(--ph-soft);
}
@media (max-width: 700px) {
  .replace-page {
    width: calc(100% - 40px);
    margin-top: 32px;
  }
  .layout {
    grid-template-columns: 1fr;
  }
  .panel {
    padding: 20px;
  }
  header {
    padding: 24px 20px;
  }
}
</style>

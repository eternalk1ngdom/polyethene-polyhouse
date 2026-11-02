<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, onBeforeRouteLeave } from 'vue-router'
import { ElAlert, ElButton, ElCheckbox, ElInput, ElOption, ElSelect } from 'element-plus'
import RuleLibraryPanel from '../components/RuleLibraryPanel.vue'
import ReplaceDraftPanel from '../components/ReplaceDraftPanel.vue'
import ReplaceResultPreview from '../components/ReplaceResultPreview.vue'
import type { ReplaceRule } from '../core/acReplacer'
import type { VirtualFileEntry } from '../core/types'
import { validateBatch } from '../core/polyReplaceBatch'
import type {
  BatchDownload,
  InputEncoding,
  ReplaceMode,
  WorkerReply,
} from '../core/polyReplaceProtocol'

const MAX_CANDIDATE_FILES = 500
const candidateFiles = ref<File[]>([])
// 候选按浏览器顺序保留；只记录跳过的编号，选回不会丢失文件引用或改变 ZIP 顺序。
const skippedFiles = ref(new Set<number>())
const selectedFiles = computed(() =>
  candidateFiles.value.filter((_, index) => !skippedFiles.value.has(index)),
)
const filePicker = ref<HTMLInputElement>()
const directoryPicker = ref<HTMLInputElement>()
const fileList = ref<HTMLUListElement>()
const selectedMode = ref<ReplaceMode>('files')
const selectionIssue = computed(() => {
  if (!candidateFiles.value.length) return ''
  if (!selectedFiles.value.length) return '请至少勾选 1 个参与处理的文件'
  try {
    validateBatch(
      selectedFiles.value.map((file) => ({
        relativePath: selectedMode.value === 'directory' ? file.webkitRelativePath : file.name,
        size: file.size,
      })),
      selectedMode.value,
    )
    return ''
  } catch (error: unknown) {
    return error instanceof Error ? error.message : '文件选择不符合处理要求'
  }
})
const inputEncoding = ref<InputEncoding>('utf-8')
const directorySupported = 'webkitdirectory' in document.createElement('input')
const rules = ref<ReplaceRule[]>([{ search: '', replace: '' }])
const draftPanel = ref<InstanceType<typeof ReplaceDraftPanel>>()
onBeforeRouteLeave(async () => (await draftPanel.value?.prepareLeave()) ?? true)
const result = ref<BatchDownload | null>(null)
const previewIndex = ref(0)
const previewPanel = ref<InstanceType<typeof ReplaceResultPreview>>()
const previewHitCounts = computed(() =>
  rules.value.map(
    (_, index) =>
      activePreview.value?.previewHits.filter((hit) => hit.ruleIndex === index).length ?? 0,
  ),
)
const message = ref('')
const busy = ref(false)
let worker: Worker | undefined
let revision = 0
const activePreview = computed(() => result.value?.files[previewIndex.value])
const totalCount = computed(
  () => result.value?.files.reduce((total, file) => total + file.count, 0) ?? 0,
)
// 各文件共用同一规则快照；沿输入顺序求和，切换预览只改变当前文件列。
const totalRuleCounts = computed(() =>
  rules.value.map(
    (_, index) =>
      result.value?.files.reduce((total, file) => total + file.ruleCounts[index]!, 0) ?? 0,
  ),
)

/** ElSelect 未将描述属性透传到内部输入，显式保留原编码说明的读屏关联。 */
function vEncodingHelp(element: HTMLElement): void {
  element.querySelector('[role="combobox"]')?.setAttribute('aria-describedby', 'encoding-help')
}

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

/** 先限制候选数量，再保存引用；正文只在开始处理时读取，超限/取消保留原清单。 */
function selectFile(event: Event, mode: ReplaceMode): void {
  const input = event.target as HTMLInputElement
  const incoming = input.files
  if (!incoming?.length) return
  if (incoming.length > MAX_CANDIDATE_FILES) {
    input.value = ''
    message.value = '候选文件最多 500 个，本次未载入；请缩小选择范围。原文件清单保持不变。'
    return
  }
  const files = Array.from(incoming)
  input.value = ''
  invalidate()
  selectedMode.value = mode
  skippedFiles.value = new Set()
  candidateFiles.value = files
}

/** Checkbox 的通用值还可为字符串/数字，本页只接受布尔参与状态。 */
function setFileParticipation(index: number, checked: unknown): void {
  if (typeof checked !== 'boolean' || index < 0 || index >= candidateFiles.value.length) return
  if (skippedFiles.value.has(index) === !checked) return
  const wasBusy = busy.value
  invalidate()
  if (checked) skippedFiles.value.delete(index)
  else skippedFiles.value.add(index)
  if (wasBusy) message.value = '参与文件已改变，已取消旧任务；请重新开始替换。'
}

/** 全部操作作用于整个候选清单，不默默排除目录、扩展名或超限文件。 */
async function setAllParticipation(checked: boolean): Promise<void> {
  if (
    !candidateFiles.value.length ||
    selectedFiles.value.length === (checked ? candidateFiles.value.length : 0)
  )
    return
  const activatedButton = document.activeElement?.closest<HTMLButtonElement>(
    '.file-selection-actions button',
  )
  const wasBusy = busy.value
  invalidate()
  skippedFiles.value = checked ? new Set() : new Set(candidateFiles.value.map((_, index) => index))
  if (wasBusy) message.value = '参与文件已改变，已取消旧任务；请重新开始替换。'
  // 批量动作使自身按钮禁用时，把焦点交给第一项，避免键盘位置落回 body。
  await nextTick()
  if (activatedButton?.disabled)
    fileList.value?.querySelector<HTMLInputElement>('input[type="checkbox"]')?.focus()
}

/** 显示单文件大小帮助用户定位超限项，不需要读取正文。 */
function fileSizeLabel(size: number): string {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KiB`
  const label = `${(size / (1024 * 1024)).toFixed(2)} MiB`
  // 超限一个字节也要明确标出，不能因显示舍入成 2.00 MiB 而让人误以为合规。
  return size > 2 * 1024 * 1024 ? `${label}（超过 2 MiB）` : label
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
  if (!candidateFiles.value.length) {
    message.value = '请先选择文本文件'
    return
  }
  if (selectionIssue.value) {
    message.value = selectionIssue.value
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
          文件仅在浏览器中处理，不会上传。最多载入 500 个候选；勾选参与处理的文件最多 20
          个，每个不超过 2 MiB，总计不超过 5 MiB。文件夹保留目录结构并打包为
          ZIP，空文件夹不会包含在结果中；任一参与文件无法处理则整批停止，可取消该文件的勾选或检查输入编码。
        </p>
        <p>
          Python 等源码按全文替换，注释、字符串和代码中匹配的内容都会修改，不支持仅替换注释。
          不自动跳过 .git、.venv
          等目录，所有候选默认参与，请手动取消不需要的文件。浏览器枚举大目录可能耗时，请选择小型文本目录。
        </p>
        <ElButton class="file-button" @click="filePicker?.click()">选择文件</ElButton>
        <input
          ref="filePicker"
          type="file"
          hidden
          multiple
          accept=".txt,.md,.json,.csv,.html,.css,.js,.ts,.vue,.java,.py,.xml,.yml,.yaml,.log"
          @change="selectFile($event, 'files')"
        />
        <ElButton
          v-if="directorySupported"
          class="file-button directory-button"
          @click="directoryPicker?.click()"
          >选择文件夹</ElButton
        >
        <input
          v-if="directorySupported"
          ref="directoryPicker"
          type="file"
          hidden
          webkitdirectory
          multiple
          @change="selectFile($event, 'directory')"
        />
        <p v-else>当前浏览器不支持选择文件夹，可改为选择文件。</p>
        <p v-if="!candidateFiles.length" class="file-name">尚未选择文件</p>
        <section v-else class="file-selection" aria-labelledby="file-selection-title">
          <h3 id="file-selection-title">参与处理的文件</h3>
          <p id="file-selection-summary" role="status">
            共 {{ candidateFiles.length }} 个，参与 {{ selectedFiles.length }} 个，跳过
            {{ candidateFiles.length - selectedFiles.length }} 个
          </p>
          <div class="file-selection-actions">
            <ElButton
              :disabled="selectedFiles.length === candidateFiles.length"
              @click="setAllParticipation(true)"
              >全部参与</ElButton
            >
            <ElButton :disabled="selectedFiles.length === 0" @click="setAllParticipation(false)"
              >全部跳过</ElButton
            >
          </div>
          <p id="file-selection-help">
            勾选表示参与；取消后仍可选回，不删除原文件。只读取和导出参与文件。
          </p>
          <ul ref="fileList" class="file-list" aria-label="候选文件清单">
            <li v-for="(file, index) in candidateFiles" :key="index">
              <ElCheckbox
                class="file-checkbox"
                :model-value="!skippedFiles.has(index)"
                :aria-label="`参与 ${selectedMode === 'directory' ? file.webkitRelativePath : file.name}`"
                aria-describedby="file-selection-help"
                @update:model-value="setFileParticipation(index, $event)"
              >
                <span class="selected-path"
                  >{{ index + 1 }}.
                  {{ selectedMode === 'directory' ? file.webkitRelativePath : file.name }}</span
                >
                <span class="file-size">{{ fileSizeLabel(file.size) }}</span>
              </ElCheckbox>
            </li>
          </ul>
          <p v-if="selectionIssue" class="selection-issue" role="status">
            {{ selectionIssue }}，请调整勾选；清单会保留。
          </p>
        </section>
        <label for="input-encoding">文件原本的编码</label>
        <ElSelect
          id="input-encoding"
          v-encoding-help
          v-model="inputEncoding"
          class="business-select"
          aria-label="文件原本的编码"
          fit-input-width
        >
          <ElOption label="UTF-8（默认）" value="utf-8" />
          <ElOption label="GBK / GB18030" value="gb18030" />
        </ElSelect>
        <p id="encoding-help">
          请选择所有文件共同使用的编码；无法自动判断，请检查预览。导出统一为 UTF-8，GBK
          文件不会保留原编码；Python、HTML、XML 等文件中的编码声明不会自动修改。
        </p>
        <h2>2. 设置替换规则</h2>
        <p>按原文一次处理；同一位置多条规则匹配时，优先较长的内容。替换内容留空表示删除。</p>
        <RuleLibraryPanel :rules="rules" @load="rules = $event" />
        <ReplaceDraftPanel ref="draftPanel" :rules="rules" @load="rules = $event" />
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
          ><ElButton
            type="primary"
            :loading="busy"
            :disabled="Boolean(selectionIssue)"
            @click="runReplace"
            >开始替换</ElButton
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
          <ElSelect
            id="preview-file"
            v-model="previewIndex"
            class="business-select"
            aria-label="选择预览文件"
            fit-input-width
          >
            <ElOption
              v-for="(file, index) in result.files"
              :key="index"
              :label="`${file.name} · ${file.count} 处`"
              :value="index"
            />
          </ElSelect>
          <section class="rule-counts" aria-labelledby="rule-counts-title">
            <h3 id="rule-counts-title">逐条替换统计</h3>
            <p class="rule-counts-help">
              统计覆盖全文，按实际执行计次（含删除、原样替换）；0 表示未命中。
            </p>
            <div
              class="rule-counts-scroll"
              tabindex="0"
              role="region"
              aria-labelledby="rule-counts-title"
            >
              <table class="rule-counts-table">
                <thead>
                  <tr>
                    <th scope="col">规则</th>
                    <th scope="col">当前预览文件（处）</th>
                    <th scope="col">整批合计（处）</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(rule, index) in rules" :key="index">
                    <th scope="row">
                      <span>规则 {{ index + 1 }}</span>
                      <span class="rule-counts-text">查找：{{ rule.search }}</span>
                      <span class="rule-counts-text"
                        >替换为：{{ rule.replace === '' ? '（删除匹配内容）' : rule.replace }}</span
                      >
                    </th>
                    <td>
                      <span>{{ activePreview?.ruleCounts[index] ?? 0 }}</span>
                      <ElButton
                        v-if="previewHitCounts[index]"
                        class="locate-rule"
                        :aria-label="`定位规则 ${index + 1}`"
                        aria-controls="result-preview"
                        @click="previewPanel?.locateRule(index)"
                        >定位</ElButton
                      >
                      <span v-else-if="activePreview?.ruleCounts[index]" class="unavailable-hit"
                        >预览外或超过标记上限，无法定位</span
                      >
                    </td>
                    <td>{{ totalRuleCounts[index] ?? 0 }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
          <ReplaceResultPreview
            v-if="activePreview"
            :key="previewIndex"
            ref="previewPanel"
            :file="activePreview"
          />
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
.panel h2:not(:first-child) {
  margin-top: 32px;
}
.directory-button {
  margin-left: 8px;
}
.file-name {
  display: inline-block;
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
label:not(.file-button):not(.file-checkbox) {
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
.file-selection {
  margin-top: 16px;
}
.file-selection h3 {
  margin: 0;
  font-size: 16px;
}
.file-selection p {
  margin: 8px 0;
  font-size: 12px;
}
.file-selection-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.file-selection-actions :deep(.el-button) {
  margin-left: 0;
  min-height: 44px;
}
.file-list {
  max-height: 140px;
  overflow: auto;
  padding: 0;
  margin: 8px 0;
  list-style: none;
  overflow-wrap: anywhere;
  color: var(--ph-muted);
  font-size: 14px;
}
.file-checkbox {
  display: flex;
  width: 100%;
  height: auto;
  min-height: 44px;
  margin-right: 0;
  padding: 4px;
  align-items: center;
}
.file-checkbox :deep(.el-checkbox__label) {
  min-width: 0;
  white-space: normal;
  overflow-wrap: anywhere;
  font-size: 14px;
}
.file-checkbox :deep(.el-checkbox__input) {
  flex-shrink: 0;
}
.selected-path {
  overflow-wrap: anywhere;
}
.file-size {
  display: block;
  font-size: 12px;
  color: var(--ph-muted);
}
.selection-issue {
  color: var(--ph-ink);
}
.business-select {
  width: 100%;
}
.business-select :deep(.el-select__wrapper) {
  min-height: 44px;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 16px;
}
.download {
  margin-top: 16px;
}
.locate-rule {
  min-width: 44px;
  display: block;
  margin: 6px 0 0;
  max-width: 100%;
  min-height: 44px;
  padding: 4px;
  font-size: 12px;
}
.unavailable-hit {
  display: block;
  margin-top: 6px;
  color: var(--ph-muted);
  white-space: normal;
}
.rule-counts {
  margin-top: 16px;
}
.rule-counts h3 {
  margin: 0;
  font-size: 16px;
}
.rule-counts-help {
  margin: 8px 0;
  font-size: 12px;
}
.rule-counts-scroll {
  max-height: 320px;
  overflow: auto;
  border: 1px solid var(--ph-line);
  border-radius: 8px;
}
.rule-counts-table {
  width: 100%;
  table-layout: fixed;
  border-collapse: collapse;
  font-size: 12px;
}
.rule-counts-table th,
.rule-counts-table td {
  padding: 8px 4px;
  border-bottom: 1px solid var(--ph-line);
  vertical-align: top;
}
.rule-counts-table th {
  font-weight: 500;
  text-align: left;
  overflow-wrap: anywhere;
}
.rule-counts-table thead {
  background: var(--ph-soft);
}
.rule-counts-table th:first-child {
  width: 45%;
}
.rule-counts-table td {
  text-align: center;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.rule-counts-table tbody tr:last-child > * {
  border-bottom: 0;
}
.rule-counts-text {
  display: block;
  margin-top: 4px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.empty {
  padding: 70px 24px;
  text-align: center;
  border: 1px dashed var(--ph-line);
  border-radius: 12px;
  background: var(--ph-soft);
}
/* 规则编辑器和词库操作需要完整阅读宽度；与图片工作台同在窄桌面下转单栏。 */
@media (max-width: 900px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 700px) {
  .replace-page {
    width: calc(100% - 40px);
    margin-top: 32px;
  }
  .panel {
    padding: 20px;
  }
  header {
    padding: 24px 20px;
  }
  h1 {
    font-size: 34px;
  }
}
</style>

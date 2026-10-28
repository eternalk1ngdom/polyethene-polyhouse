<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ElAlert, ElButton, ElInput } from 'element-plus'
import type { ReplaceResult, ReplaceRule } from '../core/acReplacer'

/** 单次 Worker 返回只允许成功结果或可展示的失败提示。 */
interface WorkerReply {
  /** 标识本次处理是否成功。 */
  ok: boolean
  /** 成功时提供替换结果。 */
  result?: ReplaceResult
  /** 失败时提供不含文件内容的提示。 */
  message?: string
}

const fileName = ref('')
const original = ref('')
const hasFile = ref(false)
const rules = ref<ReplaceRule[]>([{ search: '', replace: '' }])
const result = ref<ReplaceResult | null>(null)
const message = ref('')
const busy = ref(false)
let worker: Worker | undefined
let revision = 0
const preview = computed(() => result.value?.text.slice(0, 10000) ?? '')

// 任意输入变化都使旧任务及旧结果失效，避免下载到先前文件的内容。
function invalidate(): void {
  revision += 1
  worker?.terminate()
  worker = undefined
  busy.value = false
  result.value = null
  message.value = ''
}
watch(rules, invalidate, { deep: true })
onBeforeUnmount(invalidate)

async function selectFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  invalidate()
  const current = revision
  fileName.value = ''
  original.value = ''
  hasFile.value = false
  if (file.size > 2 * 1024 * 1024) { message.value = '文件不能超过 2 MB'; return }
  try {
    const bytes = await file.arrayBuffer()
    if (current !== revision) return
    // NUL 字节常见于二进制文件；明确拒绝而不是把图片误当文本保存。
    if (new Uint8Array(bytes).includes(0)) throw new Error('文件包含二进制内容，请选择 UTF-8 文本')
    // 保留 UTF-8 BOM；非法字节直接报错，避免静默替换成乱码。
    const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes)
    if (current !== revision) return
    original.value = text
    fileName.value = file.name
    hasFile.value = true
  } catch (error: unknown) {
    if (current !== revision) return
    message.value = error instanceof TypeError ? '文件不是有效的 UTF-8 文本，请转换编码后重试' : error instanceof Error ? error.message : '读取失败，请重试'
  }
}

function addRule(): void {
  if (rules.value.length < 20) rules.value.push({ search: '', replace: '' })
}
function removeRule(index: number): void {
  if (rules.value.length > 1) rules.value.splice(index, 1)
}
function runReplace(): void {
  invalidate()
  if (!hasFile.value) { message.value = '请先选择文本文件'; return }
  if (rules.value.some((rule) => !rule.search)) { message.value = '请填写每条规则的查找内容'; return }
  if (new Set(rules.value.map((rule) => rule.search)).size !== rules.value.length) { message.value = '查找内容不能重复'; return }
  try {
    const current = revision
    const active = new Worker(new URL('../workers/polyReplace.worker.ts', import.meta.url), { type: 'module' })
    worker = active
    busy.value = true
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
      busy.value = false
      active.terminate()
      worker = undefined
      message.value = '处理失败，请重试'
    }
    // 发送普通对象快照，避免 Vue 响应式代理不能跨线程克隆。
    active.postMessage({ text: original.value, rules: rules.value.map(({ search, replace }) => ({ search, replace })) })
  } catch {
    worker?.terminate()
    worker = undefined
    busy.value = false
    message.value = '无法启动本地处理，请刷新后重试'
  }
}
function download(): void {
  if (!result.value) return
  const url = URL.createObjectURL(new Blob([result.value.text], { type: 'text/plain;charset=utf-8' }))
  const link = document.createElement('a')
  const dot = fileName.value.lastIndexOf('.')
  link.download = dot > 0 ? `${fileName.value.slice(0, dot)}-妙替${fileName.value.slice(dot)}` : `${fileName.value}-妙替.txt`
  link.href = url
  link.click()
  // 给浏览器接收下载请求的时间，随后释放内存中的临时地址。
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<template>
  <div class="replace-page">
    <RouterLink class="back" to="/">← 返回首页</RouterLink>
    <header><p class="eyebrow">POLYREPLACE / 文本替换</p><h1>妙替</h1><p>让重复的文字修改，简单一点。</p></header>
    <div class="layout">
      <section class="panel" aria-labelledby="input-title">
        <h2 id="input-title">1. 选择文本文件</h2>
        <p>文件仅在浏览器中处理，不会上传。支持 UTF-8 文本，大小不超过 2 MB。</p>
        <label class="file-button">选择文件<input type="file" accept=".txt,.md,.json,.csv,.html,.css,.js,.ts,.vue,.java,.xml,.yml,.yaml,.log" @change="selectFile" /></label>
        <span class="file-name">{{ fileName || '尚未选择文件' }}</span>
        <h2>2. 设置替换规则</h2>
        <p>按原文一次处理；同一位置多条规则匹配时，优先较长的内容。替换内容留空表示删除。</p>
        <div v-for="(rule, index) in rules" :key="index" class="rule">
          <label :for="`search-${index}`">查找 {{ index + 1 }}</label>
          <ElInput :id="`search-${index}`" v-model="rule.search" placeholder="要查找的内容" maxlength="200" />
          <label :for="`replace-${index}`">替换为 {{ index + 1 }}</label>
          <ElInput :id="`replace-${index}`" v-model="rule.replace" placeholder="替换后的内容，可留空" maxlength="200" />
          <ElButton v-if="rules.length > 1" text @click="removeRule(index)">删除规则</ElButton>
        </div>
        <div class="actions"><ElButton :disabled="rules.length >= 20 || busy" @click="addRule">添加规则</ElButton><ElButton type="primary" :loading="busy" @click="runReplace">开始替换</ElButton></div>
        <ElAlert v-if="message" :title="message" type="warning" :closable="false" show-icon class="notice" />
      </section>
      <section class="panel" aria-labelledby="result-title">
        <h2 id="result-title">3. 预览与下载</h2>
        <template v-if="result">
          <p role="status">完成，共替换 {{ result.count }} 处<span v-if="result.text.length > 10000">；预览仅显示前 10000 个字符，下载包含完整结果。</span></p>
          <label for="result-preview">替换结果预览</label>
          <textarea id="result-preview" :value="preview" readonly rows="14" />
          <ElButton type="primary" class="download" @click="download">下载结果文件</ElButton>
        </template>
        <p v-else class="empty">选择文件并添加规则后，在这里查看结果。</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.replace-page { width: min(1200px, calc(100% - 96px)); margin: 48px auto 0; }
.back { color: var(--ph-muted); font-size: 14px; }
header { margin: 38px 0 30px; }
.eyebrow { color: var(--ph-muted); font-size: 12px; letter-spacing: 1.5px; }
h1 { font-size: 40px; margin: 10px 0; }
h2 { font-size: 20px; margin: 0 0 12px; }
p { color: var(--ph-muted); line-height: 1.7; font-size: 14px; }
.layout { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; align-items: start; }
.panel { min-width: 0; padding: 30px; border: 1px solid var(--ph-line); border-radius: 16px; background: white; }
.panel h2:not(:first-child) { margin-top: 32px; }
.file-button { display: inline-block; padding: 10px 18px; border-radius: 8px; border: 1px solid var(--ph-line); cursor: pointer; }
.file-button:focus-within { outline: 3px solid var(--ph-brand); outline-offset: 3px; }
.file-button input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.file-name { display: inline-block; margin-left: 12px; color: var(--ph-muted); overflow-wrap: anywhere; font-size: 14px; }
.rule { margin: 18px 0; padding: 16px; background: var(--ph-soft); border-radius: 10px; }
label:not(.file-button) { display: block; margin: 10px 0 6px; font-size: 14px; }
.actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 20px; }
.actions :deep(.el-button + .el-button) { margin-left: 0; }
.notice { margin-top: 18px; }
textarea { width: 100%; resize: vertical; border: 1px solid var(--ph-line); border-radius: 8px; padding: 12px; font: inherit; line-height: 1.6; }
.download { margin-top: 16px; }
.empty { padding: 24px 0; }
@media (max-width: 700px) { .replace-page { width: calc(100% - 40px); margin-top: 32px; } .layout { grid-template-columns: 1fr; } .panel { padding: 20px; } }
</style>

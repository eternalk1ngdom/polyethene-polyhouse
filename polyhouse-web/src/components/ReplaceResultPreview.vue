<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ElButton } from 'element-plus'
import type { FileSummary } from '../core/polyReplaceProtocol'

interface Props {
  file: FileSummary
}
interface PreviewPart {
  text: string
  hitIndex: number
  deleted: boolean
  ruleIndex: number
}
const props = defineProps<Props>()
const preview = ref<HTMLElement>()
const activeRule = ref(-1)
const position = ref(0)
const available = computed(() =>
  props.file.previewHits.flatMap((hit, index) =>
    hit.ruleIndex === activeRule.value ? [index] : [],
  ),
)
const activeHit = computed(() => available.value[position.value] ?? -1)
const parts = computed(() => {
  const fragments: PreviewPart[] = []
  let cursor = 0
  for (const [index, hit] of props.file.previewHits.entries()) {
    if (hit.start > cursor)
      fragments.push({
        text: props.file.preview.slice(cursor, hit.start),
        hitIndex: -1,
        deleted: false,
        ruleIndex: -1,
      })
    fragments.push({
      text: props.file.preview.slice(hit.start, hit.end),
      hitIndex: index,
      deleted: hit.start === hit.end,
      ruleIndex: hit.ruleIndex,
    })
    cursor = hit.end
  }
  fragments.push({
    text: props.file.preview.slice(cursor),
    hitIndex: -1,
    deleted: false,
    ruleIndex: -1,
  })
  return fragments
})

/** 焦点落在唯一预览容器，避免把最多 500 个标记加入 Tab 顺序；按 Tab 可进入导航。 */
async function reveal(): Promise<void> {
  await nextTick()
  const container = preview.value
  const target = container?.querySelector<HTMLElement>(`[data-hit-index="${activeHit.value}"]`)
  if (!container || !target) return
  container.focus({ preventScroll: true })
  target.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' })
}
/** 外部统计表选择规则时总是从该规则第一处可见执行开始，不切换文件或修改内容。 */
async function locateRule(index: number): Promise<void> {
  if (!props.file.previewHits.some((hit) => hit.ruleIndex === index)) return
  activeRule.value = index
  position.value = 0
  await reveal()
}
/** 到首尾时停止，序号只覆盖可定位的标记，不伪装成全文执行序号。 */
async function move(delta: number): Promise<void> {
  const next = position.value + delta
  if (next < 0 || next >= available.value.length) return
  position.value = next
  await reveal()
}
/** 高亮容器保留原只读文本框的全选范围，避免 Ctrl/Cmd+A 意外选择整页。 */
function selectPreview(): void {
  if (!preview.value) return
  const selection = window.getSelection()
  const range = document.createRange()
  range.selectNodeContents(preview.value)
  selection?.removeAllRanges()
  selection?.addRange(range)
}
/** 复制仅取选中的正文，去掉富文本样式；删除提示由伪元素绘制，不进入文字。 */
function copyPreview(event: ClipboardEvent): void {
  const selection = window.getSelection()
  if (!selection || !event.clipboardData) return
  event.clipboardData.setData('text/plain', selection.toString())
  event.preventDefault()
}
defineExpose({ locateRule })
</script>

<template>
  <div class="result-preview-section">
    <h3 id="result-preview-title">替换结果预览</h3>
    <p id="preview-help" class="preview-help">
      浅色背景表示执行过替换；“已删除”仅为位置提示，不属于结果正文。 每个文件仅标记前 10000
      字符内的前 500 处，预览外或超出标记上限的执行不能定位；统计与下载覆盖全文。
    </p>
    <p v-if="file.truncated" class="preview-help">预览已截断，下载包含完整结果。</p>
    <div
      id="result-preview"
      ref="preview"
      class="result-preview"
      tabindex="0"
      role="region"
      aria-labelledby="result-preview-title"
      aria-describedby="preview-help"
      @keydown.ctrl.a.prevent="selectPreview"
      @keydown.meta.a.prevent="selectPreview"
      @copy="copyPreview"
    >
      <template v-for="(part, index) in parts" :key="index"
        ><span v-if="part.hitIndex === -1">{{ part.text }}</span
        ><span
          v-else-if="part.deleted"
          class="deleted-hit"
          :class="{ 'active-hit': part.hitIndex === activeHit }"
          :data-hit-index="part.hitIndex"
          role="img"
          :aria-label="`规则 ${part.ruleIndex + 1}：已删除`"
        ></span
        ><mark
          v-else
          :data-hit-index="part.hitIndex"
          :class="{ 'active-hit': part.hitIndex === activeHit }"
          :aria-label="`规则 ${part.ruleIndex + 1} 替换结果`"
          >{{ part.text }}</mark
        ></template
      >
    </div>
    <div v-if="activeRule >= 0" class="preview-navigation" aria-label="替换位置导航">
      <p role="status">
        规则 {{ activeRule + 1 }} · 可定位第 {{ position + 1 }} /
        {{ available.length }} 处（当前文件共 {{ file.ruleCounts[activeRule] }} 处）
      </p>
      <ElButton :disabled="position === 0" aria-controls="result-preview" @click="move(-1)"
        >上一处</ElButton
      >
      <ElButton
        :disabled="position + 1 >= available.length"
        aria-controls="result-preview"
        @click="move(1)"
        >下一处</ElButton
      >
    </div>
    <p v-else class="preview-help">点击统计表中的“定位”查看某条规则的位置。</p>
  </div>
</template>

<style scoped>
.result-preview-section {
  margin-top: 16px;
}
h3 {
  margin: 0;
  font-size: 16px;
}
.preview-help,
.preview-navigation p {
  font-size: 12px;
  margin: 8px 0;
  line-height: 1.6;
  color: var(--ph-muted);
}
.result-preview {
  width: 100%;
  height: 318px;
  resize: vertical;
  overflow: auto;
  border: 1px solid var(--ph-line);
  border-radius: 8px;
  padding: 12px;
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  tab-size: 4;
}
.result-preview:focus {
  border-color: var(--ph-ink);
  outline: 1px solid var(--ph-ink);
  outline-offset: -1px;
}
mark {
  background: #fff0ba;
  color: inherit;
}
.deleted-hit {
  display: inline;
  user-select: none;
  background: #f3e8ef;
  color: var(--ph-ink);
  font-family: inherit;
  font-size: 12px;
  border-radius: 2px;
}
/* 伪元素提示不会混入选择复制的正文；读屏通过 aria-label 获知删除语义。 */
.deleted-hit::before {
  content: '〔已删除〕';
}
.active-hit {
  background: #fbd680;
  box-shadow: inset 0 -2px 0 var(--ph-ink);
}
.preview-navigation {
  margin-top: 8px;
}
.preview-navigation :deep(.el-button) {
  min-height: 44px;
  margin: 0 8px 0 0;
}
</style>

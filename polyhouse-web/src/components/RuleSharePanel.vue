<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElDialog, ElInput } from 'element-plus'
import type { ReplaceRule } from '../core/acReplacer'
import type { StoredReplaceDraft } from '../utils/replaceDraft'
import { isEmptyReplaceDraft } from '../utils/replaceDraft'
import { useRuleShare } from '../composables/useRuleShare'

interface Props {
  /** 生成时复制，接收链接不会自动更改它。 */
  rules: readonly ReplaceRule[]
  /** 本机检查完成后才允许决定规则来源。 */
  ready: boolean
  /** 复用原面板的确认/保存动作隔离。 */
  busy: boolean
  /** 只呈现待恢复草稿，不将普通历史选择冒充待决来源。 */
  draft: StoredReplaceDraft | null
  /** 通过原父页面规则变更流程载入。 */
  loadRules(rules: readonly Readonly<ReplaceRule>[]): boolean
  /** 现有草稿面板负责核对来源并恢复。 */
  restoreDraft(id: string): boolean
  /** 两种来源都不载入时仍保留旧历史。 */
  keepCurrent(): boolean
}
interface Emits {
  /** 让旧草稿提示和本提示互斥，避免同时要求两次来源决定。 */
  pending: [value: boolean]
}
const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const route = useRoute()
const router = useRouter()
const share = useRuleShare({
  baseUrl: () => window.location.href,
  clearHash: async (expected) => {
    if (route.name !== 'poly-replace' || window.location.hash !== expected) return true
    await router.replace({ path: route.path, query: route.query, hash: '' })
    return window.location.hash !== expected
  },
  copy: async (text) => {
    if (!navigator.clipboard) throw new Error('剪贴板不可用')
    await navigator.clipboard.writeText(text)
  },
  canChoose: () => props.ready && !props.busy,
  loadRules: (rules) => props.loadRules(rules),
  restoreDraft: (id) => props.restoreDraft(id),
  keepCurrent: () => props.keepCurrent(),
})
const {
  incoming,
  notice,
  processing,
  pending,
  cleanupHash,
  generated,
  generatedCount,
  generationIssue,
  copyNotice,
  copying,
} = share
const dialogOpen = ref(false)
const closing = ref(false)
const sharePanel = ref<HTMLElement>()
const unavailable = computed(() => props.busy || processing.value || closing.value)
const hasEditing = computed(() => !isEmptyReplaceDraft(props.rules))
watch(
  () => route.fullPath,
  () => {
    // 使用原始片段校验，不让路由自动解码将非规范输入变成另一条有效规则链接。
    void share.receive(window.location.hash)
  },
  { immediate: true },
)
watch(pending, (value) => emit('pending', value), { immediate: true, flush: 'sync' })
onBeforeUnmount(() => share.dispose())

function generate(): void {
  if (!unavailable.value && share.generate(props.rules)) dialogOpen.value = true
}
function closeDialog(): void {
  closing.value = true
  share.closeGenerated()
}
/** 来源按钮会随候选消失，完成后把键盘焦点交回稳定入口，避免落到页面根部。 */
async function chooseSource(action: 'link' | 'draft' | 'keep', id?: string): Promise<void> {
  if (!(await share.choose(action, id)) || incoming.value) return
  await nextTick()
  sharePanel.value
    ?.querySelector<HTMLButtonElement>('.share-generate')
    ?.focus({ preventScroll: true })
}
async function retryCleanup(): Promise<void> {
  await share.retryCleanup()
  if (!cleanupHash.value && !incoming.value)
    sharePanel.value
      ?.querySelector<HTMLButtonElement>('.share-generate')
      ?.focus({ preventScroll: true })
}
</script>

<template>
  <section ref="sharePanel" class="share-panel" aria-labelledby="rule-share-title">
    <div class="share-toolbar">
      <h3 id="rule-share-title">规则链接</h3>
      <ElButton class="share-generate" size="small" :disabled="unavailable" @click="generate"
        >分享当前规则</ElButton
      >
    </div>
    <p class="share-help">
      只分享当前规则，不含文件或整个词库。链接包含规则原文，对方需能访问此网站。
    </p>
    <p v-if="generationIssue" role="status" aria-live="polite" class="share-description">
      {{ generationIssue }}
    </p>
    <div v-if="incoming" class="incoming-share">
      <p class="share-description">
        链接包含 {{ incoming.data.rules.length }} 条规则，请确认后载入。
      </p>
      <ol class="share-rules" tabindex="0" aria-label="分享链接中的规则预览">
        <li v-for="(rule, index) in incoming.data.rules" :key="index">
          <span>查找 {{ index + 1 }}</span>
          <pre>{{ rule.search }}</pre>
          <span>替换为 {{ index + 1 }}{{ rule.replace === '' ? '（删除匹配内容）' : '' }}</span>
          <pre v-if="rule.replace !== ''">{{ rule.replace }}</pre>
        </li>
      </ol>
      <p v-if="!ready" class="share-help">正在核对本机草稿，请稍候…</p>
      <p v-else-if="draft" class="share-description">
        同时检测到本机待恢复草稿：{{ new Date(draft.draft.updatedAt).toLocaleString() }}，
        {{ draft.draft.rules.length }} 条规则。请选择来源；使用链接或暂不载入都会保留旧历史。
      </p>
      <p v-if="hasEditing" class="share-description">
        载入链接或恢复草稿将替换当前编辑；所选文件保留，旧预览和下载将失效，不会自动执行。
      </p>
      <div class="share-actions">
        <ElButton type="primary" :disabled="unavailable || !ready" @click="chooseSource('link')">
          {{ draft ? '使用链接规则' : '载入规则' }}
        </ElButton>
        <ElButton
          v-if="draft"
          :disabled="unavailable || !ready"
          @click="chooseSource('draft', draft.id)"
          >恢复草稿</ElButton
        >
        <ElButton :disabled="unavailable || !ready" @click="chooseSource('keep')"
          >暂不载入</ElButton
        >
      </div>
    </div>
    <p v-if="notice" role="status" aria-live="polite" class="share-description">{{ notice }}</p>
    <ElButton v-if="cleanupHash" size="small" :disabled="unavailable" @click="retryCleanup"
      >重试清理链接</ElButton
    >
    <ElDialog
      v-model="dialogOpen"
      title="分享当前规则"
      width="min(560px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      @close="closeDialog"
      @closed="closing = false"
    >
      <p class="share-description">
        包含
        {{ generatedCount }}
        条规则，不包含文件或整个词库。链接只是编码，没有加密；含敏感内容时请勿分享。
      </p>
      <label for="generated-rule-link">完整分享链接</label>
      <ElInput
        id="generated-rule-link"
        :model-value="generated"
        type="textarea"
        readonly
        :rows="5"
      />
      <p class="share-help">
        可以复制给能访问此网站的人；本机 localhost 地址不会因此变成公开网站。
      </p>
      <p v-if="copyNotice" role="status" aria-live="polite" class="share-description">
        {{ copyNotice }}
      </p>
      <template #footer
        ><div class="share-actions share-footer">
          <ElButton @click="dialogOpen = false">关闭</ElButton>
          <ElButton
            type="primary"
            :loading="copying"
            :disabled="!generated || closing"
            @click="share.copy"
            >复制链接</ElButton
          >
        </div></template
      >
    </ElDialog>
  </section>
</template>

<style scoped>
.share-panel {
  margin: 12px 0 16px;
  padding: 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
  min-width: 0;
}
.share-toolbar,
.share-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.share-toolbar {
  justify-content: space-between;
}
.share-toolbar h3 {
  margin: 0;
  font-size: 14px;
}
.share-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.share-help,
.share-description {
  margin: 8px 0;
  line-height: 1.65;
  overflow-wrap: anywhere;
}
.share-help {
  color: var(--ph-muted);
  font-size: 12px;
}
.share-description {
  font-size: 13px;
}
.share-rules {
  max-height: 280px;
  overflow: auto;
  padding: 8px 8px 8px 30px;
  border: 1px solid var(--ph-line);
  border-radius: 8px;
  font-size: 13px;
}
.share-rules li + li {
  margin-top: 12px;
}
.share-rules pre {
  margin: 4px 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  background: var(--ph-soft);
  padding: 4px;
  border-radius: 4px;
}
.share-footer {
  justify-content: flex-end;
}
</style>

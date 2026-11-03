<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { ElButton, ElCheckbox, ElDialog, ElOption, ElSelect } from 'element-plus'
import RuleSharePanel from './RuleSharePanel.vue'
import type { ReplaceRule } from '../core/acReplacer'
import { useReplaceDraft } from '../composables/useReplaceDraft'
import { REPLACE_DRAFT_STORAGE_KEY } from '../stores/replaceDraftStorage'
import { isEmptyReplaceDraft, type StoredReplaceDraft } from '../utils/replaceDraft'

interface Props {
  /** 编辑副本与草稿隔离，恢复通过父页面原失效流程处理。 */
  rules: readonly ReplaceRule[]
}
interface Emits {
  /** 只更换规则，不执行替换或更改所选文件。 */
  load: [rules: ReplaceRule[]]
  /** 高级区收起时仍向工具报告生效设置和需要用户决定的来源/异常。 */
  disclosure: [enabled: boolean, needsAttention: boolean]
}
const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const manager = useReplaceDraft(props.rules)
const {
  ready,
  enabled,
  paused,
  dirty,
  saving,
  history,
  selectedId,
  selected,
  message,
  cleanupFailed,
  locationWarning,
} = manager
const expanded = ref(false)
const pending = ref(false)
const dialogOpen = ref(false)
const closing = ref(false)
const checkboxRevision = ref(0)
const action = ref<'restore' | 'delete' | 'clear' | 'disable' | 'leave'>('clear')
const source = shallowRef<StoredReplaceDraft>()
let decideLeave: ((leave: boolean) => void) | undefined
const blocked = computed(() => !ready.value || pending.value || dialogOpen.value || closing.value)
const sharePending = ref(false)
const shareAttention = ref(false)
const draftBlocked = computed(() => blocked.value || sharePending.value)
watch(
  [
    enabled,
    paused,
    shareAttention,
    cleanupFailed,
    locationWarning,
    () => manager.state.value.status,
  ],
  () =>
    emit(
      'disclosure',
      enabled.value,
      paused.value ||
        shareAttention.value ||
        cleanupFailed.value ||
        Boolean(locationWarning.value) ||
        !['ready', 'saved', 'disabled', 'cancelled'].includes(manager.state.value.status),
    ),
  { immediate: true, flush: 'sync' },
)
const title = computed(
  () =>
    ({
      restore: '恢复历史草稿',
      delete: '删除历史草稿',
      clear: '清空本页规则和草稿',
      disable: '关闭并清除本机草稿',
      leave: '草稿尚未保存，仍要离开？',
    })[action.value],
)
const description = computed(
  () =>
    ({
      restore: '恢复会替换当前非空编辑；文件不变，已有预览和下载将失效，不会自动运行。',
      delete: `删除所选的 ${source.value?.draft.rules.length ?? 0} 条历史规则。当前编辑保留，正在其他页面使用的草稿不会被删除。`,
      clear: '当前规则将回到空行，本页草稿及已退出页面的待恢复来源会清空，其他历史仍保留。',
      disable: `清除这个浏览器来源的全部 ${manager.state.value.book?.drafts.length ?? 0} 份草稿，并停止其他页面后续自动写入。当前编辑与正式词库保留。`,
      leave: '本次编辑尚未确认保存到本机。可以返回重试，或明确离开并接受本次未保存编辑丢失。',
    })[action.value],
)

/** 同步监听捕获每次输入，让等待锁的旧保存及时失效。 */
watch(
  () => props.rules,
  (rules) => manager.edit(rules),
  { deep: true, flush: 'sync' },
)
watch(paused, (value) => {
  if (value) expanded.value = true
})

/** 弹窗关闭动画完成前不接受下一动作，避免旧回调关闭新确认。 */
function ask(kind: typeof action.value, item?: StoredReplaceDraft): void {
  if (blocked.value || (sharePending.value && kind !== 'leave')) return
  source.value = item
  action.value = kind
  dialogOpen.value = true
}
function closed(): void {
  closing.value = false
  source.value = undefined
  decideLeave?.(false)
  decideLeave = undefined
}
/** 恢复先取回文本，再尝试保存新槽位；失败不会吞掉恢复的文本。 */
async function restore(item: StoredReplaceDraft): Promise<void> {
  emit('load', manager.restore(item))
  await manager.flush()
}
async function requestRestore(): Promise<void> {
  if (!selected.value || draftBlocked.value) return
  if (
    !isEmptyReplaceDraft(props.rules) &&
    JSON.stringify(props.rules) !== JSON.stringify(selected.value.draft.rules)
  ) {
    ask('restore', selected.value)
    return
  }
  pending.value = true
  try {
    await restore(selected.value)
  } finally {
    pending.value = false
  }
}
/** 用户取消关闭不会修改开关；关闭失败仍提供专门的清理重试入口。 */
async function toggle(value: string | number | boolean): Promise<void> {
  if (draftBlocked.value) return
  // 原生 checkbox 已先切换 checked；重建控件恢复受控状态，取消确认也不能显示为已关闭。
  checkboxRevision.value++
  if (!value) {
    ask('disable')
    return
  }
  pending.value = true
  try {
    await manager.enable()
  } finally {
    pending.value = false
  }
}
async function confirm(): Promise<void> {
  if (pending.value || closing.value || !dialogOpen.value) return
  pending.value = true
  try {
    if (action.value === 'restore' && source.value) await restore(source.value)
    else if (action.value === 'delete' && source.value) await manager.removeHistory(source.value.id)
    else if (action.value === 'clear') {
      const saved = manager.clearCurrent()
      // 清空立即同步父页面；异步保存完成后不能再覆盖用户期间的新输入。
      emit('load', [{ search: '', replace: '' }])
      await saved
    } else if (action.value === 'disable') await manager.disable()
    else if (action.value === 'leave') {
      decideLeave?.(true)
      decideLeave = undefined
    }
    dialogOpen.value = false
  } finally {
    pending.value = false
  }
}
/** 路由先等补存，失败时给用户保留在当前页修复的机会。 */
async function prepareLeave(): Promise<boolean> {
  if (blocked.value) return false
  pending.value = true
  let saved: boolean
  try {
    saved = await manager.flush()
  } finally {
    pending.value = false
  }
  if (saved) return true
  return new Promise<boolean>((resolve) => {
    ask('leave')
    decideLeave = resolve
  })
}
defineExpose({ prepareLeave })

/** 来源选择在同一处协调，链接载入走新槽位，不消费原来的待恢复历史。 */
function loadSharedRules(rules: readonly Readonly<ReplaceRule>[]): boolean {
  if (blocked.value) return false
  manager.continueCurrent()
  emit(
    'load',
    rules.map(({ search, replace }) => ({ search, replace })),
  )
  return true
}
/** 来源可能已被另一页清理，确认时重新核对当前待恢复 ID。 */
function restoreSharedDraft(id: string): boolean {
  if (blocked.value || !paused.value || selected.value?.id !== id) return false
  emit('load', manager.restore(selected.value))
  void manager.flush()
  return true
}
/** 暂不载入也结束自动恢复的等待，但历史仍能从原入口手动查看。 */
function keepSharedEditing(): boolean {
  if (blocked.value) return false
  if (paused.value) manager.continueCurrent()
  return true
}

function storageChanged(event: StorageEvent): void {
  if (event.key === REPLACE_DRAFT_STORAGE_KEY || event.key === null) manager.refresh()
}
function visibilityChanged(): void {
  if (document.visibilityState === 'hidden') void manager.flush()
  else manager.refresh()
}
function pageLeaving(): void {
  void manager.flush()
}
onMounted(() => {
  void manager.initialize()
  window.addEventListener('storage', storageChanged)
  document.addEventListener('visibilitychange', visibilityChanged)
  window.addEventListener('pagehide', pageLeaving)
})
onBeforeUnmount(() => {
  window.removeEventListener('storage', storageChanged)
  document.removeEventListener('visibilitychange', visibilityChanged)
  window.removeEventListener('pagehide', pageLeaving)
  decideLeave?.(false)
  decideLeave = undefined
  void manager.dispose()
})
/** 只显示时间与条数，不在历史选项中泄露规则文本。 */
function label(item: StoredReplaceDraft): string {
  return `${new Date(item.draft.updatedAt).toLocaleString()} · ${item.draft.rules.length} 条规则`
}
</script>

<template>
  <RuleSharePanel
    :rules="rules"
    :ready="ready"
    :busy="blocked"
    :draft="paused && selected ? selected : null"
    :load-rules="loadSharedRules"
    :restore-draft="restoreSharedDraft"
    :keep-current="keepSharedEditing"
    @pending="sharePending = $event"
    @attention="shareAttention = $event"
  />
  <section class="draft-panel" aria-label="本机规则草稿">
    <div class="draft-toolbar">
      <ElCheckbox
        :key="checkboxRevision"
        :model-value="enabled"
        :disabled="draftBlocked"
        @change="toggle"
        >自动保存规则草稿</ElCheckbox
      >
      <ElButton
        size="small"
        :disabled="draftBlocked"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
        >{{ expanded ? '收起历史' : `历史草稿（${history.length}）` }}</ElButton
      >
    </div>
    <p class="draft-help">
      首次默认关闭；开启后记住选择，仅在本机明文保存规则，不随账号、不保存文件。关页后可恢复，清除浏览器数据会丢失。
    </p>
    <p class="draft-status" role="status" aria-live="polite">
      {{ saving ? '正在保存草稿，请稍候…' : message }}
    </p>
    <p v-if="locationWarning" class="draft-warning">{{ locationWarning }}</p>
    <p v-if="paused && !sharePending" class="draft-warning">
      检测到上次草稿，请选择恢复或保留当前编辑继续；决定前暂停自动保存。
    </p>
    <div v-if="expanded && !sharePending" class="draft-history">
      <label for="replace-draft-history">选择历史草稿（最多 10 份）</label>
      <ElSelect
        id="replace-draft-history"
        v-model="selectedId"
        aria-label="选择历史草稿"
        :placeholder="history.length ? '请选择历史草稿' : '暂无历史草稿'"
        :disabled="draftBlocked || !history.length"
        fit-input-width
      >
        <ElOption v-for="item in history" :key="item.id" :value="item.id" :label="label(item)" />
      </ElSelect>
      <div class="draft-actions">
        <ElButton size="small" :disabled="draftBlocked || !selected" @click="requestRestore"
          >恢复所选草稿</ElButton
        >
        <ElButton
          size="small"
          :disabled="draftBlocked || !selected"
          @click="ask('delete', selected)"
          >删除所选历史</ElButton
        >
        <ElButton
          v-if="paused"
          size="small"
          :disabled="draftBlocked"
          @click="manager.continueCurrent"
          >保留当前编辑继续</ElButton
        >
      </div>
      <p class="draft-help">
        活动页面的草稿可复制恢复，关闭来源页面后才能删除。满额时须手动清理，不自动淘汰旧稿。
      </p>
    </div>
    <div class="draft-actions">
      <ElButton
        v-if="enabled && dirty && !paused"
        size="small"
        :disabled="draftBlocked || saving"
        @click="manager.flush"
        >立即保存 / 重试</ElButton
      >
      <ElButton v-if="cleanupFailed" size="small" :disabled="draftBlocked" @click="ask('disable')"
        >重试关闭清理</ElButton
      >
      <ElButton size="small" :disabled="draftBlocked" @click="ask('clear')"
        >清空本页规则和草稿</ElButton
      >
    </div>
    <p class="draft-help">
      正常离页会尝试补存；浏览器崩溃或强制关闭时，最后一次输入可能尚未保存。草稿与正式词库分别管理。
    </p>
    <ElDialog
      v-model="dialogOpen"
      :title="title"
      width="min(480px, calc(100vw - 32px))"
      class="draft-dialog"
      :close-on-click-modal="false"
      :close-on-press-escape="!pending"
      :show-close="!pending"
      @close="closing = true"
      @closed="closed"
    >
      <p class="draft-confirm">{{ description }}</p>
      <template #footer>
        <div class="draft-actions draft-footer">
          <ElButton :disabled="pending" @click="dialogOpen = false">{{
            action === 'leave' ? '返回编辑' : '取消'
          }}</ElButton>
          <ElButton type="primary" :loading="pending" :disabled="closing" @click="confirm">{{
            action === 'leave' ? '仍然离开' : '确认'
          }}</ElButton>
        </div>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.draft-panel {
  margin: 12px 0 16px;
  padding: 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
  min-width: 0;
}
.draft-toolbar,
.draft-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.draft-toolbar {
  justify-content: space-between;
}
.draft-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.draft-help,
.draft-status,
.draft-warning,
.draft-confirm {
  margin: 8px 0;
  line-height: 1.65;
  overflow-wrap: anywhere;
}
.draft-help {
  color: var(--ph-muted);
  font-size: 12px;
}
.draft-status {
  font-size: 13px;
}
.draft-warning {
  color: var(--el-text-color-primary);
  font-size: 13px;
}
.draft-history {
  display: grid;
  gap: 8px;
  margin: 12px 0;
  min-width: 0;
}
.draft-history label {
  font-size: 13px;
}
.draft-history :deep(.el-select) {
  width: 100%;
  min-width: 0;
}
.draft-footer {
  justify-content: flex-end;
}
.draft-panel :deep(.el-checkbox) {
  max-width: 100%;
  height: auto;
  min-height: 32px;
}
.draft-panel :deep(.el-checkbox__label) {
  white-space: normal;
}
</style>

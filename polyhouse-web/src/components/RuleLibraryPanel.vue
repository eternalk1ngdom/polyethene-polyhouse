<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import {
  ElButton,
  ElCheckbox,
  ElDialog,
  ElInput,
  ElOption,
  ElSelect,
  type InputInstance,
} from 'element-plus'
import type { ReplaceRule } from '../core/acReplacer'
import { RULE_LIBRARY_MAX_BYTES, type RuleSet } from '../utils/ruleLibrary'
import { useRuleLibraryManager, type RuleLibraryImport } from '../composables/useRuleLibraryManager'

/** 编辑规则由父页面持有；词库只在用户明确载入时发出替换副本。 */
interface Props {
  rules: readonly ReplaceRule[]
}
/** 载入与执行替换分开，父页面沿用原失效逻辑。 */
interface Emits {
  load: [rules: ReplaceRule[]]
}
const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const manager = useRuleLibraryManager()
const { state, selectedId, pending, hasUnsaved, issue } = manager
const expanded = ref(false)
const host = ref<HTMLElement>()
const nameInput = ref<InputInstance>()
const importInput = ref<HTMLInputElement>()
const importReading = ref(false)
const importPreview = shallowRef<RuleLibraryImport>()
const importFilename = ref('')
// 关闭、重新选择和卸载后，旧文件读取结果都必须失效。
let importRevision = 0
let disposed = false
const selected = computed(() => state.value.data.sets.find((set) => set.id === selectedId.value))
const dialogOpen = ref(false)
// ElDialog 的旧关闭回调结束前不重开或再次提交，避免新弹窗被旧回调关掉。
const closing = ref(false)
const controlsDisabled = computed(() => pending.value || closing.value || importReading.value)
const mode = ref<'save' | 'rename' | 'load' | 'delete' | 'clear' | 'reload' | 'export' | 'import'>(
  'save',
)
const dialogTarget = shallowRef<RuleSet>()
const name = ref('')
const overwriteConfirmed = ref(false)
const feedback = ref('')
const collision = computed(() =>
  mode.value === 'save' ? manager.findByName(name.value) : undefined,
)
const editingName = computed(() => mode.value === 'save' || mode.value === 'rename')
const title = computed(
  () =>
    ({
      save: '保存当前规则',
      rename: '重命名词库',
      load: '载入词库',
      delete: '删除词库',
      clear: '清除本机词库',
      reload: '重新读取本机词库',
      export: '导出未保存候选',
      import: '导入并替换全部词库',
    })[mode.value],
)
const notice = computed(
  () => hasUnsaved.value || !['ready', 'unloaded'].includes(state.value.status),
)
const canRetry = computed(() => hasUnsaved.value && state.value.status !== 'conflict')
watch(name, () => {
  overwriteConfirmed.value = false
  issue.value = ''
})
watch(
  dialogOpen,
  (isOpen) => {
    if (!isOpen && mode.value === 'import') {
      importRevision++
      importReading.value = false
      importPreview.value = undefined
      manager.cancelImport()
    }
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  disposed = true
  importRevision++
  manager.cancelImport()
})

/** 每次打开固定操作目标，避免异步保存后选择变化导致误改另一组。 */
function open(action: typeof mode.value): void {
  if (controlsDisabled.value) return
  mode.value = action
  dialogTarget.value = selected.value
  name.value = action === 'rename' ? (selected.value?.name ?? '') : ''
  overwriteConfirmed.value = false
  issue.value = ''
  dialogOpen.value = true
}

/** 只有当前规则非空且不同才要求确认；相同内容载入仍发出副本以使旧结果失效。 */
function loadSelected(): void {
  const set = selected.value
  if (!set) return
  const meaningful = props.rules.some((rule) => rule.search !== '' || rule.replace !== '')
  if (meaningful && JSON.stringify(props.rules) !== JSON.stringify(set.rules)) open('load')
  else applyRules(set)
}

function applyRules(set: RuleSet): void {
  emit(
    'load',
    set.rules.map(({ search, replace }) => ({ search, replace })),
  )
  feedback.value = `已载入“${set.name}”的 ${set.rules.length} 条规则，请检查后开始替换。`
}

/** 候选未保存时不能直接丢弃；重读不会触碰父页面规则。 */
function reload(): void {
  if (hasUnsaved.value) open('reload')
  else {
    manager.reload()
    feedback.value = ''
  }
}

/** 候选必须先确认；正常导出由管理层重读，读取失败不下载初始化空库。 */
function exportLibrary(): void {
  if (controlsDisabled.value || dialogOpen.value) return
  feedback.value = ''
  if (hasUnsaved.value) open('export')
  else downloadLibrary()
}

/** 浏览器仅接收下载请求，不能据此保证用户保存到磁盘；临时地址延后释放。 */
function downloadLibrary(allowUnsaved = false): boolean {
  const backup = manager.prepareExport(allowUnsaved)
  if (!backup) return false
  let url: string | undefined
  try {
    url = URL.createObjectURL(new Blob([backup.json], { type: 'application/json;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = backup.filename
    link.click()
    feedback.value = `已发起 ${backup.setCount} 组词库${backup.unsaved ? '未保存候选' : ''}的 JSON 下载，请确认文件已保存。`
    return true
  } catch {
    issue.value = '无法发起词库下载，现有内容保留，请重试或检查浏览器下载设置'
    return false
  } finally {
    if (url) {
      const downloadUrl = url
      setTimeout(() => URL.revokeObjectURL(downloadUrl), 1000)
    }
  }
}

/** 文件入口仅选择本机备份；同一文件可以再次选择，不上传或改动其他文件。 */
function chooseImport(): void {
  if (controlsDisabled.value || hasUnsaved.value || dialogOpen.value) return
  if (importInput.value) {
    importInput.value.value = ''
    importInput.value.click()
  }
}

async function onImportFileChange(event: Event): Promise<void> {
  const input = event.target
  if (!(input instanceof HTMLInputElement)) return
  const file = input.files?.[0]
  input.value = ''
  if (file) await readImportFile(file)
}

/** 读取前限额、读取后校验；读期间允许取消，不让迟到结果打开弹窗或准备写入。 */
async function readImportFile(file: File): Promise<void> {
  if (disposed || controlsDisabled.value || hasUnsaved.value || dialogOpen.value) return
  feedback.value = ''
  issue.value = ''
  if (file.size > RULE_LIBRARY_MAX_BYTES) {
    issue.value = '备份文件超过 256 KiB，未读取或导入'
    return
  }
  importPreview.value = undefined
  importFilename.value = file.name
  manager.cancelImport()
  open('import')
  const revision = ++importRevision
  importReading.value = true
  const current = () =>
    !disposed && revision === importRevision && dialogOpen.value && mode.value === 'import'
  try {
    const bytes = new Uint8Array(await file.arrayBuffer())
    if (current()) importPreview.value = manager.prepareImport(bytes)
  } catch {
    if (current()) issue.value = '无法读取备份文件，现有词库未改变，请重新选择文件'
  } finally {
    if (revision === importRevision) importReading.value = false
  }
}

/** 管理修改须持久化成功才结束；导出只等待下载请求成功，不解除候选状态。 */
async function confirm(): Promise<void> {
  if (pending.value || closing.value || importReading.value || !dialogOpen.value) return
  feedback.value = ''
  let success = false
  if (mode.value === 'save') {
    if (collision.value && !overwriteConfirmed.value) {
      issue.value = '请勾选确认覆盖，或使用另一个名称'
      return
    }
    success = await manager.saveRules(name.value, props.rules, collision.value?.id)
  } else if (mode.value === 'rename' && dialogTarget.value) {
    success = await manager.rename(dialogTarget.value.id, name.value)
  } else if (mode.value === 'load' && dialogTarget.value) {
    applyRules(dialogTarget.value)
    success = true
  } else if (mode.value === 'delete' && dialogTarget.value)
    success = await manager.remove(dialogTarget.value.id)
  else if (mode.value === 'clear') success = await manager.clear()
  else if (mode.value === 'reload') success = manager.reload(true)
  else if (mode.value === 'export') success = downloadLibrary(true)
  else if (mode.value === 'import' && importPreview.value) {
    const count = importPreview.value.data.sets.length
    success = await manager.importLibrary(importPreview.value)
    if (success) feedback.value = `已从备份恢复 ${count} 组词库。当前编辑保持不变，词库需主动载入。`
  }
  if (success) {
    dialogOpen.value = false
    expanded.value = true
  }
}

async function copy(): Promise<void> {
  if (selected.value) {
    feedback.value = ''
    await manager.copy(selected.value.id)
  }
}

async function retry(): Promise<void> {
  if (await manager.retry()) dialogOpen.value = false
}

/** 输入在弹窗挂载完成后聚焦；删除/清空后恢复到仍可操作的入口。 */
function opened(): void {
  if (editingName.value && !hasUnsaved.value) nameInput.value?.focus()
}
async function closed(): Promise<void> {
  closing.value = false
  if (mode.value === 'delete' || mode.value === 'clear' || mode.value === 'import') {
    await nextTick()
    const save = host.value?.querySelector<HTMLButtonElement>('[data-library-save]:not(:disabled)')
    const fallback = host.value?.querySelector<HTMLButtonElement>(
      '[aria-controls="local-library-controls"]',
    )
    const importButton =
      mode.value === 'import'
        ? host.value?.querySelector<HTMLButtonElement>('[data-library-import]:not(:disabled)')
        : undefined
    ;(importButton ?? save ?? fallback)?.focus()
  }
}
</script>

<template>
  <section ref="host" class="library" aria-labelledby="local-library-title">
    <input
      ref="importInput"
      type="file"
      accept=".json,application/json"
      hidden
      @change="onImportFileChange"
    />
    <div class="library-heading">
      <h3 id="local-library-title">本机词库</h3>
      <ElButton data-library-save :disabled="controlsDisabled || hasUnsaved" @click="open('save')"
        >保存当前规则</ElButton
      >
      <ElButton
        text
        :disabled="controlsDisabled"
        :aria-expanded="expanded"
        aria-controls="local-library-controls"
        @click="expanded = !expanded"
        >{{ expanded ? '收起管理' : `管理词库（${state.data.sets.length}）` }}</ElButton
      >
    </div>
    <p class="library-help">仅保存在当前浏览器，不随账号；清除浏览器数据会丢失。词库需主动保存。</p>
    <div v-if="expanded" id="local-library-controls" class="library-controls">
      <template v-if="state.data.sets.length">
        <label for="local-rule-set">选择本机词库</label>
        <ElSelect
          id="local-rule-set"
          v-model="selectedId"
          fit-input-width
          :disabled="controlsDisabled"
          placeholder="选择词库"
        >
          <ElOption
            v-for="set in state.data.sets"
            :key="set.id"
            :label="set.name"
            :value="set.id"
          />
        </ElSelect>
        <p v-if="selected" class="library-help">
          {{ selected.rules.length }} 条规则{{ state.persisted ? '' : ' · 当前内容尚未保存到本机' }}
        </p>
        <div class="library-actions">
          <ElButton :disabled="controlsDisabled || !selected" @click="loadSelected">载入</ElButton>
          <ElButton :disabled="controlsDisabled || hasUnsaved || !selected" @click="open('rename')"
            >重命名</ElButton
          >
          <ElButton :disabled="controlsDisabled || hasUnsaved || !selected" @click="copy"
            >复制</ElButton
          >
          <ElButton :disabled="controlsDisabled || hasUnsaved || !selected" @click="open('delete')"
            >删除词库</ElButton
          >
        </div>
      </template>
      <p v-else class="library-help">还没有词库，可将当前规则保存为第一组。</p>
      <div class="library-actions">
        <ElButton :disabled="controlsDisabled" @click="exportLibrary">{{
          hasUnsaved ? '导出未保存候选' : '导出全部词库'
        }}</ElButton>
        <ElButton
          data-library-import
          :disabled="controlsDisabled || hasUnsaved"
          @click="chooseImport"
          >导入并替换全部</ElButton
        >
        <ElButton text :disabled="controlsDisabled" @click="reload">重新读取本机词库</ElButton>
        <ElButton
          v-if="state.data.sets.length"
          text
          :disabled="controlsDisabled || hasUnsaved"
          @click="open('clear')"
          >清除本机词库</ElButton
        >
      </div>
      <p class="library-help">
        导出全部词库为本机明文
        JSON，不含当前未存入词库的编辑、草稿或文件。导入将替换全部词库，建议先导出当前版本；备份文件最大
        256 KiB。
      </p>
    </div>
    <p v-if="feedback" class="library-help" role="status">{{ feedback }}</p>
    <div v-if="notice" class="library-notice" :role="state.persisted ? 'status' : 'alert'">
      <p>{{ state.message }}</p>
      <p v-if="hasUnsaved">
        未保存的词库改动仅保留在本页，刷新或离开会丢失。重试保存的是上一次候选内容。
      </p>
      <ElButton v-if="canRetry && !dialogOpen" :loading="pending" @click="retry">重试保存</ElButton>
      <ElButton v-if="hasUnsaved && !dialogOpen" :disabled="controlsDisabled" @click="reload"
        >重新读取并核对</ElButton
      >
    </div>
    <p v-if="issue && !dialogOpen" class="library-error" role="alert">{{ issue }}</p>

    <ElDialog
      v-model="dialogOpen"
      :title="title"
      width="min(480px, calc(100% - 32px))"
      :close-on-click-modal="false"
      :close-on-press-escape="!pending"
      :show-close="!pending"
      @close="closing = true"
      @opened="opened"
      @closed="closed"
    >
      <div class="library-dialog">
        <template v-if="editingName">
          <label for="local-library-name">词库名称</label>
          <ElInput
            id="local-library-name"
            ref="nameInput"
            v-model="name"
            maxlength="32"
            :disabled="pending || hasUnsaved"
            :aria-invalid="!!issue"
            aria-describedby="local-library-name-help"
            @keyup.enter="confirm"
          />
          <p id="local-library-name-help">
            1～32 个字符；保存的查找内容不能为空，替换为空表示删除。
          </p>
          <ElCheckbox
            v-if="collision && dialogOpen && !hasUnsaved"
            v-model="overwriteConfirmed"
            :disabled="pending"
            >覆盖“{{ collision.name }}”的 {{ collision.rules.length }} 条规则</ElCheckbox
          >
        </template>
        <p v-else-if="mode === 'load'">
          将用“{{ dialogTarget?.name }}”的
          {{ dialogTarget?.rules.length }}
          条规则替换当前编辑内容，旧预览和下载将清空。尚未保存的规则将被舍弃。
        </p>
        <p v-else-if="mode === 'delete'">
          确定删除“{{ dialogTarget?.name }}”及其
          {{ dialogTarget?.rules.length }} 条规则？当前编辑的规则保留。
        </p>
        <p v-else-if="mode === 'clear'">
          确定清除本机全部 {{ state.data.sets.length }} 组词库？此操作不能撤销，当前编辑的规则保留。
        </p>
        <p v-else-if="mode === 'export'">
          将导出本页全部 {{ state.data.sets.length }}
          组词库的未保存候选，文件名带“未保存候选”标记；本机已保存的版本可能不同。导出不会完成保存或解决冲突，刷新或离开前仍请保管备份。
        </p>
        <template v-else-if="mode === 'import'">
          <p>备份文件：{{ importFilename }}</p>
          <p v-if="importReading" role="status">正在读取和校验备份，可取消本次导入。</p>
          <template v-if="importPreview">
            <p>
              将用备份的 {{ importPreview.data.sets.length }} 组词库替换本机全部
              {{ importPreview.current.sets.length }}
              组词库，不保留现有词库。建议先取消并导出当前版本。
            </p>
            <p v-if="!importPreview.data.sets.length" class="library-error" role="alert">
              此备份为空，确认后将清空本机全部词库。
            </p>
            <ul v-else class="library-import-list" tabindex="0" aria-label="备份词库及规则条数">
              <li v-for="set in importPreview.data.sets" :key="set.id">
                {{ set.name }} · {{ set.rules.length }} 条规则
              </li>
            </ul>
            <p>仅替换本机词库，当前编辑、草稿、文件与预览保持不变；导入后需主动载入词库。</p>
          </template>
        </template>
        <p v-else>
          重新读取会舍弃本页尚未保存的词库改动，读取其他页面已保存的版本。当前编辑的规则保留。
        </p>
        <p v-if="issue" class="library-error" role="alert">{{ issue }}</p>
        <p v-if="hasUnsaved" class="library-error" role="alert">
          {{ state.message }} 刷新或离开本页会丢失尚未保存的内容。
        </p>
      </div>
      <template #footer>
        <div class="library-dialog-actions">
          <ElButton :disabled="pending" @click="dialogOpen = false">{{
            hasUnsaved && mode !== 'export' ? '保留并关闭' : '取消'
          }}</ElButton>
          <ElButton
            v-if="hasUnsaved && canRetry && mode !== 'reload' && mode !== 'export'"
            type="primary"
            :loading="pending"
            @click="retry"
            >重试保存</ElButton
          >
          <ElButton
            v-if="!hasUnsaved || mode === 'reload' || mode === 'export'"
            type="primary"
            :loading="pending || importReading"
            :disabled="mode === 'import' && (!importPreview || importReading)"
            @click="confirm"
            >{{
              mode === 'import'
                ? '确认替换全部'
                : mode === 'export'
                  ? '确认导出候选'
                  : mode === 'save' && collision
                    ? '确认覆盖'
                    : mode === 'save' || mode === 'rename'
                      ? '保存'
                      : '确认'
            }}</ElButton
          >
        </div>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.library {
  margin: 18px 0;
  padding: 16px;
  border: 1px solid var(--ph-line);
  border-radius: 10px;
}
.library-heading,
.library-actions,
.library-dialog-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.library-import-list {
  max-height: 180px;
  overflow-y: auto;
  overflow-wrap: anywhere;
  border: 1px solid var(--ph-line);
  border-radius: 8px;
  padding: 8px 8px 8px 28px;
  color: var(--ph-ink);
  font-size: 13px;
  line-height: 1.7;
}
.library-heading h3 {
  margin: 0;
  font-size: 15px;
  flex-basis: 100%;
}
.library-heading :deep(.el-button + .el-button),
.library-actions :deep(.el-button + .el-button),
.library-dialog-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.library-help,
.library-notice p,
.library-dialog p {
  font-size: 13px;
  line-height: 1.7;
  color: var(--ph-muted);
  margin: 10px 0;
  overflow-wrap: anywhere;
}
.library-controls label,
.library-dialog label:not(.el-checkbox) {
  display: block;
  margin: 12px 0 8px;
  font-size: 14px;
}
.library-controls :deep(.el-select) {
  width: 100%;
  min-width: 0;
}
.library-actions {
  margin-top: 12px;
}
.library-notice {
  margin-top: 12px;
  border-top: 1px solid var(--ph-line);
}
.library-error {
  color: #a33b21;
  font-size: 13px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.library-dialog :deep(.el-checkbox) {
  height: auto;
  white-space: normal;
  align-items: start;
  margin-top: 8px;
}
.library-dialog :deep(.el-checkbox__label) {
  white-space: normal;
  overflow-wrap: anywhere;
}
.library-dialog-actions {
  justify-content: flex-end;
}
</style>

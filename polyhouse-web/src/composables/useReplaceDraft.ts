import { computed, ref, shallowRef } from 'vue'
import type { ReplaceRule } from '../core/acReplacer'
import {
  isEmptyReplaceDraft,
  validateReplaceDraft,
  type StoredReplaceDraft,
} from '../utils/replaceDraft'
import {
  createBrowserReplaceDraftRepository,
  REPLACE_DRAFT_POINTER_KEY,
  type ReplaceDraftRepository,
  type ReplaceDraftSnapshot,
} from '../stores/replaceDraftStorage'

/** 副作用集中注入；计时器测试不依赖机器快慢，也不碰真实本机草稿。 */
export interface ReplaceDraftServices {
  /** 每次页面实例独立的持久化边界。 */
  repository: ReplaceDraftRepository
  /** 只存非敏感页面 ID，刷新时优先提示本页上次草稿。 */
  readPointer(): string | null
  /** 清空后也记录空槽位，避免自动再次提示其他历史。 */
  point(id: string): void
  /** 时间仅用于历史提示和排序。 */
  now(): number
  /** 返回取消函数，关闭/清空必须作废尚未执行的防抖回调。 */
  schedule(action: () => void): () => void
}

/** 管理防抖、恢复决策和迟到保存，不接触文件或替换算法。 */
export function useReplaceDraft(
  initial: readonly ReplaceRule[],
  services: ReplaceDraftServices = {
    repository: createBrowserReplaceDraftRepository(),
    readPointer: () => sessionStorage.getItem(REPLACE_DRAFT_POINTER_KEY),
    point: (id) => sessionStorage.setItem(REPLACE_DRAFT_POINTER_KEY, id),
    now: () => Date.now(),
    schedule: (action) => {
      const timer = setTimeout(action, 400)
      return () => clearTimeout(timer)
    },
  },
) {
  const state = shallowRef<ReplaceDraftSnapshot>({
    book: null,
    status: 'ready',
    message: '正在读取草稿设置',
    writable: false,
  })
  const ready = ref(false)
  const enabled = ref(false)
  const paused = ref(false)
  const dirty = ref(false)
  const saving = ref(false)
  const selectedId = ref('')
  const message = ref('正在读取草稿设置')
  const cleanupFailed = ref(false)
  const locationWarning = ref('')
  let current = initial.map(({ search, replace }) => ({ search, replace }))
  let currentKey = JSON.stringify(current)
  let generation = ''
  let revision = 0
  let retireId: string | undefined
  let disposed = false
  let protectUnsaved = false
  let stopTimer: (() => void) | undefined
  let initialization: Promise<void> | undefined
  let operations = 0
  let inFlight: { revision: number; promise: Promise<boolean> } | undefined
  const history = computed(() =>
    [...(state.value.book?.drafts ?? [])]
      .filter((item) => item.id !== services.repository.id)
      .sort((a, b) => b.draft.updatedAt - a.draft.updatedAt),
  )
  const selected = computed(() => history.value.find((item) => item.id === selectedId.value))

  function cancelTimer(): void {
    stopTimer?.()
    stopTimer = undefined
  }
  function point(): void {
    try {
      services.point(services.repository.id)
    } catch {
      locationWarning.value = '本页定位信息未保存，下次可能再次提示历史，请手动选择。'
    }
  }
  function accept(result: ReplaceDraftSnapshot): void {
    state.value = result
    message.value = result.message
  }
  function queue(): void {
    cancelTimer()
    if (ready.value && enabled.value && !paused.value && dirty.value && !disposed)
      stopTimer = services.schedule(() => {
        stopTimer = undefined
        void flush()
      })
  }

  /** 初始化期间输入也先留在内存；读取到旧稿时暂停自动写入，等待明确选择。 */
  function initialize(): Promise<void> {
    if (initialization) return initialization
    initialization = (async () => {
      const result = await services.repository.open()
      if (disposed) return
      accept(result)
      ready.value = true
      enabled.value = result.status === 'ready' && !!result.book?.enabled && result.writable
      protectUnsaved = enabled.value
      generation = result.book?.generation ?? ''
      let pointer: string | null = null
      try {
        pointer = services.readPointer()
      } catch {
        locationWarning.value = '本页定位信息不可用，可从本机历史手动选择草稿'
      }
      selectedId.value =
        pointer === null
          ? (history.value[0]?.id ?? '')
          : history.value.some((item) => item.id === pointer)
            ? pointer
            : ''
      paused.value = !!selectedId.value
      if (enabled.value) queue()
      else if (result.status === 'ready') message.value = '自动草稿未开启，编辑内容不会自动留在本机'
    })()
    return initialization
  }

  /** 每次变更提高版本；锁等待期间的旧保存不能在清空、关闭或新输入后落盘。 */
  function edit(rules: readonly ReplaceRule[]): void {
    const key = JSON.stringify(rules)
    if (key === currentKey) return
    current = rules.map(({ search, replace }) => ({ search, replace }))
    currentKey = key
    revision++
    dirty.value = true
    cancelTimer()
    if (!enabled.value) return
    if (isEmptyReplaceDraft(current)) {
      retireId = selected.value?.id ?? retireId
      selectedId.value = ''
      paused.value = false
      point()
      void flush()
    } else {
      message.value = paused.value
        ? '当前编辑尚未保存，请先选择恢复或保留当前编辑继续'
        : '有编辑尚未保存，正在等待自动保存'
      queue()
    }
  }

  /** 隐藏/导航/按钮共用同一次补存；同版本复用 Promise，避免重复写入。 */
  function flush(): Promise<boolean> {
    cancelTimer()
    if (!ready.value || !enabled.value || paused.value || !dirty.value || disposed)
      return Promise.resolve(!dirty.value || (!enabled.value && !protectUnsaved))
    if (inFlight?.revision === revision) return inFlight.promise
    let draft
    try {
      draft = isEmptyReplaceDraft(current)
        ? null
        : validateReplaceDraft({ version: 1, rules: current, updatedAt: services.now() })
    } catch {
      message.value = '当前编辑超过草稿容量或结构限制，尚未自动保存'
      return Promise.resolve(false)
    }
    const ticket = revision
    const source = retireId
    operations++
    saving.value = true
    const promise = (async () => {
      try {
        const result = await services.repository.save(
          draft,
          generation,
          () => !disposed && revision === ticket && enabled.value,
          source,
        )
        if (disposed || revision !== ticket) return false
        accept(result)
        if (result.status === 'saved') {
          dirty.value = false
          retireId = undefined
          point()
          message.value = draft
            ? '草稿已保存到本机，关页后仍可从历史选择恢复'
            : '本页草稿已清空，其他页面的历史保留'
          return true
        }
        if (result.status === 'stale') enabled.value = false
        return false
      } finally {
        operations--
        saving.value = operations > 0
        if (inFlight?.revision === ticket) inFlight = undefined
      }
    })()
    inFlight = { revision: ticket, promise }
    return promise
  }

  /** 开启只在用户主动操作或已记住的设置下进行；旧稿未决时不自动覆盖。 */
  async function enable(): Promise<boolean> {
    await initialize()
    const result = await services.repository.enable()
    accept(result)
    if (result.status !== 'saved') return false
    revision++
    enabled.value = true
    protectUnsaved = true
    cleanupFailed.value = false
    generation = result.book!.generation
    dirty.value = !isEmptyReplaceDraft(current)
    if (selected.value) paused.value = true
    if (!paused.value && dirty.value) return flush()
    message.value = paused.value
      ? '已开启，请先处理检测到的旧草稿'
      : '已开启本机自动草稿，编辑后自动保存'
    return true
  }

  /** 先停本页计时器再清理；清理失败保留明确状态，不声称其他标签页已停用。 */
  async function disable(): Promise<boolean> {
    revision++
    cancelTimer()
    enabled.value = false
    protectUnsaved = false
    const result = await services.repository.disable()
    accept(result)
    cleanupFailed.value = result.status !== 'disabled'
    if (cleanupFailed.value) {
      message.value = '本页已暂停，但本机草稿尚未确认清除；其他页面可能仍启用，请重试关闭清理'
      return false
    }
    dirty.value = false
    paused.value = false
    selectedId.value = ''
    retireId = undefined
    point()
    return true
  }

  /** 恢复副本先进入编辑区；异步保存失败也不影响用户取回文本。 */
  function restore(source: StoredReplaceDraft): ReplaceRule[] {
    revision++
    cancelTimer()
    current = source.draft.rules.map(({ search, replace }) => ({ search, replace }))
    currentKey = JSON.stringify(current)
    paused.value = false
    retireId = source.id
    selectedId.value = ''
    dirty.value = enabled.value
    point()
    message.value = enabled.value
      ? '已恢复草稿，正在为本页保存'
      : '已恢复到编辑区；本页自动草稿未开启，请主动保存词库'
    return current.map((rule) => ({ ...rule }))
  }

  /** 不恢复时明确保留当前编辑，历史不删除；新编辑写入自己的新槽位。 */
  function continueCurrent(): void {
    revision++
    cancelTimer()
    paused.value = false
    retireId = undefined
    selectedId.value = ''
    point()
    dirty.value = enabled.value && !isEmptyReplaceDraft(current)
    message.value = '已保留当前编辑，历史草稿仍可手动查看'
    queue()
  }

  /** 清空本页同时作废旧请求；其他活动来源仅复制过，不会被顺带删除。 */
  async function clearCurrent(): Promise<boolean> {
    revision++
    cancelTimer()
    retireId = selected.value?.id ?? retireId
    selectedId.value = ''
    paused.value = false
    current = [{ search: '', replace: '' }]
    currentKey = JSON.stringify(current)
    dirty.value = enabled.value
    point()
    if (!enabled.value) {
      message.value = '当前规则已清空，自动草稿未开启'
      return true
    }
    return flush()
  }

  /** 清理必须核对来源是否仍活动；成功后不自动跳到另一份历史。 */
  async function removeHistory(id: string): Promise<boolean> {
    const result = await services.repository.removeHistory(id)
    accept(result)
    if (result.status !== 'saved' && result.status !== 'disabled') return false
    if (selectedId.value === id) {
      const wasPaused = paused.value
      selectedId.value = ''
      paused.value = false
      point()
      if (wasPaused) dirty.value = enabled.value && !isEmptyReplaceDraft(current)
      queue()
    }
    message.value = '所选历史草稿已删除，当前编辑保留'
    return true
  }

  /** 其他页面关闭或重开设置时停止本页旧请求；启用不会无提示打开既有关闭页面。 */
  function refresh(): void {
    const result = services.repository.read()
    accept(result)
    if (
      enabled.value &&
      (result.status !== 'ready' || !result.book?.enabled || result.book.generation !== generation)
    ) {
      revision++
      cancelTimer()
      enabled.value = false
      message.value = '本机草稿设置已改变或无法读取，本页自动保存已暂停，编辑内容保留'
    }
    if (result.status === 'ready' && selectedId.value && !selected.value) {
      // 另一页面已清理所选历史，不能继续显示失效的恢复提示或缓存选项名称。
      selectedId.value = ''
      paused.value = false
      point()
      queue()
      message.value = '所选历史草稿已被其他页面清理，当前编辑保留'
    }
  }

  /** 站内离开先等补存；真正关页仍只能尽力补存，不能保证进程异常退出不丢末次输入。 */
  async function dispose(): Promise<void> {
    cancelTimer()
    await flush()
    disposed = true
    revision++
    services.repository.close()
  }

  return {
    state,
    ready,
    enabled,
    paused,
    dirty,
    saving,
    selectedId,
    history,
    selected,
    message,
    cleanupFailed,
    locationWarning,
    initialize,
    edit,
    flush,
    enable,
    disable,
    restore,
    continueCurrent,
    clearCurrent,
    removeHistory,
    refresh,
    dispose,
  }
}

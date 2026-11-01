import { ref, shallowRef } from 'vue'
import type { ReplaceRule } from '../core/acReplacer'
import {
  normalizeRuleSetName,
  parseRuleLibraryBytes,
  serializeRuleLibrary,
  validateRuleLibrary,
  type RuleLibrary,
  type RuleSet,
} from '../utils/ruleLibrary'
import {
  createBrowserRuleLibraryStorage,
  type RuleLibraryStorage,
} from '../stores/ruleLibraryStorage'

/** 注入本机副作用，测试不连接真实词库，页面也不依赖账号状态。 */
export interface RuleLibraryManagerServices {
  /** 每个面板独立持有读取快照，陈旧写入由 R1 锁内校验拦截。 */
  repository: RuleLibraryStorage
  /** 标识只用于定位本机词库，不承载权限或秘密。 */
  createId(): string
  /** UTC 毫秒时间由校验层兜底，时钟回退不回写更早的更新时间。 */
  now(): number
}

/** 下载的是一个固定整库快照，未保存候选与已读取的本机版本须明确区分。 */
export interface RuleLibraryExport {
  /** 使用本机日期和候选标记，不在文件名中泄露用户词库名称。 */
  readonly filename: string
  /** 复用 v1 紧凑格式，避免缩进使临界容量备份无法重新解析。 */
  readonly json: string
  /** 用于说明备份范围，不代表浏览器已将文件保存到磁盘。 */
  readonly setCount: number
  /** 导出不能把内存候选标记为已持久化。 */
  readonly unsaved: boolean
}

/** 确认页绑定本次备份和读取时的本机快照，不能确认后再静默换一份内容。 */
export interface RuleLibraryImport {
  /** 经过既有 v1 校验的完整备份，保留 ID、顺序、规则原文及时间。 */
  readonly data: RuleLibrary
  /** 用引用核对本页状态；其他页面的写入继续由 R1 锁内比较拦截。 */
  readonly current: RuleLibrary
}

/** 将词库管理与文件/替换任务分离，保存失败仍保留 R1 的内存候选。 */
export function useRuleLibraryManager(
  services: RuleLibraryManagerServices = {
    repository: createBrowserRuleLibraryStorage(),
    createId: () => crypto.randomUUID(),
    now: () => Date.now(),
  },
) {
  const state = shallowRef(services.repository.load())
  const selectedId = ref(state.value.data.sets[0]?.id ?? '')
  const pending = ref(false)
  const hasUnsaved = ref(false)
  const issue = ref('')
  let preparedImport: RuleLibraryImport | undefined

  /** 同名覆盖必须由 UI 明确取得目标 ID，不能只凭同名自动替换。 */
  function findByName(name: string): RuleSet | undefined {
    return state.value.data.sets.find(
      (set) => normalizeRuleSetName(set.name) === normalizeRuleSetName(name),
    )
  }

  function target(id: string): RuleSet {
    const set = state.value.data.sets.find((set) => set.id === id)
    if (!set) throw new Error('所选词库已不存在，请重新选择或读取')
    return set
  }

  function updatedTime(set?: RuleSet): number {
    return Math.max(services.now(), set?.updatedAt ?? 0)
  }

  function syncSelection(preferredId = selectedId.value): void {
    selectedId.value = state.value.data.sets.some((set) => set.id === preferredId)
      ? preferredId
      : (state.value.data.sets[0]?.id ?? '')
  }

  /** 当前候选未处理前不叠加新的改动，避免冲突/配额失败变成隐式数据合并。 */
  async function change(make: () => RuleLibrary, preferredId?: () => string): Promise<boolean> {
    if (pending.value) return false
    issue.value = ''
    if (hasUnsaved.value) {
      issue.value = '请先重试保存，或确认舍弃未保存的词库改动后重新读取'
      return false
    }
    let candidate: RuleLibrary
    try {
      candidate = validateRuleLibrary(make())
    } catch (error: unknown) {
      issue.value = error instanceof Error ? error.message : '无法准备词库改动，请检查输入'
      return false
    }
    pending.value = true
    try {
      state.value = await services.repository.save(candidate)
      hasUnsaved.value = !state.value.persisted
      syncSelection(preferredId?.())
      return state.value.persisted
    } finally {
      pending.value = false
    }
  }

  /** 只有名称与显式覆盖 ID 同时匹配才更新原组，其他情况按新增或拒绝处理。 */
  function saveRules(
    name: string,
    rules: readonly ReplaceRule[],
    overwriteId?: string,
  ): Promise<boolean> {
    let savedId = ''
    return change(
      () => {
        const existing = findByName(name)
        if (
          (existing && existing.id !== overwriteId) ||
          (overwriteId && existing?.id !== overwriteId)
        )
          throw new Error('名称已存在或覆盖目标已改变，请重新确认')
        const now = updatedTime(existing)
        savedId = existing?.id ?? services.createId()
        const saved: RuleSet = {
          id: savedId,
          name,
          rules,
          createdAt: existing?.createdAt ?? now,
          updatedAt: now,
        }
        return {
          version: 1,
          sets: existing
            ? state.value.data.sets.map((set) => (set.id === existing.id ? saved : set))
            : [...state.value.data.sets, saved],
        }
      },
      () => savedId,
    )
  }

  /** 重命名只更改显示名称，重名由共享校验拒绝，不改规则或稳定 ID。 */
  function rename(id: string, name: string): Promise<boolean> {
    return change(
      () => {
        const existing = target(id)
        return {
          version: 1,
          sets: state.value.data.sets.map((set) =>
            set.id === id ? { ...set, name, updatedAt: updatedTime(existing) } : set,
          ),
        }
      },
      () => id,
    )
  }

  /** 副本后缀和序号保持唯一；按完整码点截短名称，避免切断 emoji。 */
  function copy(id: string): Promise<boolean> {
    let copiedId = ''
    return change(
      () => {
        const existing = target(id)
        let name = ''
        for (let index = 1; index <= 31; index++) {
          const suffix = index === 1 ? ' 副本' : ` 副本 ${index}`
          let base = ''
          for (const character of existing.name) {
            if (base.length + character.length + suffix.length > 32) break
            base += character
          }
          name = `${base}${suffix}`
          if (!findByName(name)) break
        }
        copiedId = services.createId()
        const now = updatedTime()
        return {
          version: 1,
          sets: [
            ...state.value.data.sets,
            { ...existing, id: copiedId, name, createdAt: now, updatedAt: now },
          ],
        }
      },
      () => copiedId,
    )
  }

  /** 删除的确认由 UI 承担；这里仍核对目标存在，避免失效选择被报告为成功。 */
  function remove(id: string): Promise<boolean> {
    return change(() => {
      target(id)
      return { version: 1, sets: state.value.data.sets.filter((set) => set.id !== id) }
    })
  }

  /** 整库清空仍走 R1 的快照和互斥锁，不直接 removeItem 绕过并发保护。 */
  function clear(): Promise<boolean> {
    return change(() => ({ version: 1, sets: [] }))
  }

  /** 重试上次保留的完整候选，不把编辑区的新规则混入旧保存请求。 */
  async function retry(): Promise<boolean> {
    if (pending.value || !hasUnsaved.value) return false
    issue.value = ''
    pending.value = true
    try {
      state.value = await services.repository.save(state.value.data)
      hasUnsaved.value = !state.value.persisted
      return state.value.persisted
    } finally {
      pending.value = false
    }
  }

  /** 显式舍弃只影响词库候选；本模块从不修改编辑中的替换规则。 */
  function reload(discardUnsaved = false): boolean {
    if (pending.value) return false
    issue.value = ''
    state.value = services.repository.load(discardUnsaved)
    if (state.value.status !== 'ready') return false
    hasUnsaved.value = false
    syncSelection()
    return true
  }

  /** 正常导出先重读；只有明确确认才备份候选，绝不为导出舍弃或写入数据。 */
  function prepareExport(allowUnsaved = false): RuleLibraryExport | undefined {
    if (pending.value) return undefined
    issue.value = ''
    const unsaved = hasUnsaved.value
    if (unsaved !== allowUnsaved) {
      issue.value = unsaved ? '请明确确认导出尚未保存的词库候选' : '候选状态已改变，请重新选择导出'
      return undefined
    }
    if (!unsaved && !reload()) {
      issue.value = '无法确认本机词库，未生成备份；请先处理读取错误'
      return undefined
    }
    try {
      const json = serializeRuleLibrary(state.value.data)
      const date = new Date(services.now())
      if (!Number.isFinite(date.getTime())) throw new Error('无法确定备份日期，请检查本机时间')
      const stamp = `${date.getFullYear()}${String(date.getMonth() + 1).padStart(2, '0')}${String(date.getDate()).padStart(2, '0')}`
      return Object.freeze({
        filename: `妙替词库-${stamp}${unsaved ? '-未保存候选' : ''}.json`,
        json,
        setCount: state.value.data.sets.length,
        unsaved,
      })
    } catch {
      issue.value = '词库或备份日期不符合要求，未生成备份；现有内容保留'
      return undefined
    }
  }

  /** 先验证备份再重读本机，不将坏文件或读失败转化为待确认的空库。 */
  function prepareImport(bytes: Uint8Array): RuleLibraryImport | undefined {
    if (pending.value) return undefined
    preparedImport = undefined
    issue.value = ''
    if (hasUnsaved.value) {
      issue.value = '请先处理或导出未保存候选，再导入备份'
      return undefined
    }
    try {
      const data = parseRuleLibraryBytes(bytes)
      if (!reload()) {
        issue.value = '无法确认本机词库，未准备导入；请先处理读取错误'
        return undefined
      }
      preparedImport = Object.freeze({ data, current: state.value.data })
      return preparedImport
    } catch (error: unknown) {
      issue.value = error instanceof Error ? error.message : '无法读取词库备份，请检查文件'
      return undefined
    }
  }

  /** 取消或离页立即撤销确认资格，迟到读取与旧确认不能再次写入。 */
  function cancelImport(): void {
    preparedImport = undefined
  }

  /** 仅接受本次展示的整库替换；确认后不重新载入或合并，失败仍走候选保护。 */
  function importLibrary(preview: RuleLibraryImport): Promise<boolean> {
    const prepared = preparedImport
    preparedImport = undefined
    return change(
      () => {
        if (preview !== prepared || state.value.data !== preview.current)
          throw new Error('导入确认已失效，请重新选择备份并核对替换范围')
        return preview.data
      },
      () => preview.data.sets[0]?.id ?? '',
    )
  }

  return {
    state,
    selectedId,
    pending,
    hasUnsaved,
    issue,
    findByName,
    saveRules,
    rename,
    copy,
    remove,
    clear,
    retry,
    reload,
    prepareExport,
    prepareImport,
    cancelImport,
    importLibrary,
  }
}

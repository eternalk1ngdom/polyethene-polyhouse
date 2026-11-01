import {
  parseRuleLibrary,
  serializeRuleLibrary,
  validateRuleLibrary,
  type RuleLibrary,
} from '../utils/ruleLibrary'

export const RULE_LIBRARY_STORAGE_KEY = 'polyhouse:replace:library:v1'

/** 最小存储接口，测试仅注入内存替身，不接触真实浏览器词库或账号缓存。 */
export interface RuleLibraryStorageAdapter {
  /** 返回原始值供冲突比较，null 表示尚无词库。 */
  getItem(key: string): string | null
  /** 一次写入整库；失败时由调用层明确报告未保存。 */
  setItem(key: string, value: string): void
}

/** 副作用集中在存储层；锁覆盖同源各标签页的检查和写入整个临界区。 */
export interface RuleLibraryEnvironment {
  /** 延迟获取，捕获禁用存储时连属性读取都会抛错的情况。 */
  storage(): RuleLibraryStorageAdapter
  /** 未提供时只允许内存编辑，不以非原子的读后写伪装并发保护。 */
  withExclusiveLock?: (action: () => void) => Promise<void>
}

/** 状态让后续界面区分持久化成功、会话内保留和必须先处理的冲突。 */
export type RuleLibraryStatus =
  | 'unloaded'
  | 'ready'
  | 'saved'
  | 'invalid'
  | 'invalid-storage'
  | 'read-error'
  | 'write-error'
  | 'memory-only'
  | 'conflict'
  | 'unsaved'
  | 'busy'

/** 快照不可改写；persisted 只表示最近一次核对，不代表实时监听其他标签页。 */
export interface RuleLibrarySnapshot {
  /** 包括保存失败时仍保留的有效内存数据，重新读取前可供界面取回。 */
  readonly data: RuleLibrary
  /** 当前内存数据是否已在最近一次读取/写入中确认持久化。 */
  readonly persisted: boolean
  /** 单个实例同时只允许一个存储操作，避免旧回调覆盖新状态。 */
  readonly busy: boolean
  /** 机器可判定的结果，不能只依赖提示文案判断保存成功。 */
  readonly status: RuleLibraryStatus
  /** 不包含规则原文、底层异常或浏览器私有信息的用户提示。 */
  readonly message: string
}

/** R1 提供完整的本地存储边界，R2 再按界面需要接入响应式状态。 */
export interface RuleLibraryStorage {
  /** 读取只读快照，不隐式访问存储或发起网络请求。 */
  snapshot(): RuleLibrarySnapshot
  /** 有未保存内容时拒绝重载；调用方确认舍弃后才可传 true。 */
  load(discardUnsaved?: boolean): RuleLibrarySnapshot
  /** 保存调用时的数据副本；失败不丢内存内容，也不绕过损坏数据或并发冲突。 */
  save(value: unknown): Promise<RuleLibrarySnapshot>
}

/** 注入的存储和互斥锁必须属于同一来源；只有本模块写入遵循同名锁协议。 */
export function createRuleLibraryStorage(environment: RuleLibraryEnvironment): RuleLibraryStorage {
  let data = validateRuleLibrary({ version: 1, sets: [] })
  let baseline: string | null | undefined
  let persisted = false
  let dirty = false
  let busy = false
  let status: RuleLibraryStatus = 'unloaded'
  let message = '尚未读取本机词库'

  function snapshot(): RuleLibrarySnapshot {
    return Object.freeze({ data, persisted, busy, status, message })
  }

  function report(nextStatus: RuleLibraryStatus, nextMessage: string): RuleLibrarySnapshot {
    status = nextStatus
    message = nextMessage
    return snapshot()
  }

  function load(discardUnsaved = false): RuleLibrarySnapshot {
    if (busy)
      return Object.freeze({ ...snapshot(), status: 'busy', message: '正在保存，请稍后再读取' })
    if (dirty && !discardUnsaved)
      return report('unsaved', '当前有未保存内容，请先保留或确认舍弃后再重新读取')
    let raw: string | null
    try {
      raw = environment.storage().getItem(RULE_LIBRARY_STORAGE_KEY)
    } catch {
      baseline = undefined
      persisted = false
      return report('read-error', '无法读取本机词库，现有内存内容仍保留，尚未确认已保存')
    }
    let loaded: RuleLibrary
    try {
      loaded = raw === null ? validateRuleLibrary({ version: 1, sets: [] }) : parseRuleLibrary(raw)
    } catch {
      baseline = undefined
      persisted = false
      return report('invalid-storage', '本机词库损坏或版本不支持，已保留原始存储且停止写入')
    }
    data = loaded
    baseline = raw
    dirty = false
    persisted = true
    return report('ready', '已读取本机词库')
  }

  async function save(value: unknown): Promise<RuleLibrarySnapshot> {
    if (busy)
      return Object.freeze({ ...snapshot(), status: 'busy', message: '正在保存，请勿重复操作' })
    let serialized: string
    let candidate: RuleLibrary
    try {
      serialized = serializeRuleLibrary(value)
      candidate = parseRuleLibrary(serialized)
    } catch {
      return report('invalid', '词库结构、规则或容量不符合要求，现有内存与存储内容未改变')
    }
    data = candidate
    dirty = true
    persisted = false
    if (baseline === undefined)
      return report('memory-only', '尚未成功读取本机词库，修改仅保留在当前会话中，未写入存储')
    if (!environment.withExclusiveLock)
      return report('memory-only', '当前环境不支持安全的多标签页保存，修改仅在当前会话中保留')
    busy = true
    let failure: RuleLibraryStatus = 'memory-only'
    let failureMessage = '无法取得保存锁，修改仅保留在当前会话中，未写入存储'
    try {
      await environment.withExclusiveLock(() => {
        failure = 'read-error'
        failureMessage = '保存前无法核对本机词库，修改仍在内存中，未写入存储'
        const storage = environment.storage()
        // 比较和 setItem 必须同在锁内；单独比较旧值无法阻止两个标签页同时覆盖。
        if (storage.getItem(RULE_LIBRARY_STORAGE_KEY) !== baseline) {
          report('conflict', '本机词库已被其他页面修改，当前修改仍保留，请先核对两份内容')
          return
        }
        failure = 'write-error'
        failureMessage = '写入本机词库失败，修改仍在内存中，尚未保存；可检查浏览器存储后重试'
        storage.setItem(RULE_LIBRARY_STORAGE_KEY, serialized)
        baseline = serialized
        dirty = false
        persisted = true
        report('saved', '已保存到本机词库')
      })
    } catch {
      report(failure, failureMessage)
    } finally {
      busy = false
    }
    return snapshot()
  }

  return { snapshot, load, save }
}

/** 显式创建才连接浏览器；导入模块本身不读存储，也不影响现有替换页面。 */
export function createBrowserRuleLibraryStorage(): RuleLibraryStorage {
  return createRuleLibraryStorage({
    storage: () => localStorage,
    withExclusiveLock: async (action) => {
      // Web Locks 仅在浏览器支持且上下文允许时可用；拒绝时由上层保留内存内容。
      if (typeof navigator === 'undefined' || !navigator.locks)
        throw new Error('Browser storage lock unavailable')
      await navigator.locks.request(RULE_LIBRARY_STORAGE_KEY, { mode: 'exclusive' }, action)
    },
  })
}

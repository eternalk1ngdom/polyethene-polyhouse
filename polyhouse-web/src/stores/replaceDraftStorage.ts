import {
  parseReplaceDraftBook,
  validateReplaceDraftBook,
  validateReplaceDraft,
  type ReplaceDraft,
  type ReplaceDraftBook,
} from '../utils/replaceDraft'
import type { RuleLibraryStorageAdapter } from './ruleLibraryStorage'

export const REPLACE_DRAFT_STORAGE_KEY = 'polyhouse:replace:draft:v1'
export const REPLACE_DRAFT_POINTER_KEY = 'polyhouse:replace:draft:page:v1'
const ownerLock = (id: string): string => `${REPLACE_DRAFT_STORAGE_KEY}:owner:${id}`

/** 底层结果明确区分持久化、取消、活动页面保护和环境故障。 */
export type ReplaceDraftStatus =
  | 'ready'
  | 'saved'
  | 'disabled'
  | 'cancelled'
  | 'stale'
  | 'active'
  | 'read-error'
  | 'invalid-storage'
  | 'write-error'
  | 'lock-unavailable'
  | 'invalid'

/** 失败时仍可展示最近成功读取的历史，但不能据此报告本次保存成功。 */
export interface ReplaceDraftSnapshot {
  /** 最近核验的本机数据，首次读取失败时为 null。 */
  readonly book: ReplaceDraftBook | null
  /** 当前操作的确定结果。 */
  readonly status: ReplaceDraftStatus
  /** 不包含规则或底层异常的反馈。 */
  readonly message: string
  /** 已取得当前页面身份锁才可写入，关闭实例后撤销。 */
  readonly writable: boolean
}

/** 只在存储层连接浏览器；锁在测试中可以被可控的并发替身替换。 */
export interface ReplaceDraftEnvironment {
  /** 全部草稿与设置只写本模块的一个键。 */
  storage(): RuleLibraryStorageAdapter
  /** 本页面唯一 ID 以及开关代次，均不承载权限。 */
  createId(): string
  /** 页面存活期间持有身份锁，历史清理不能删掉其他活动页面的槽位。 */
  claim(id: string): Promise<() => void>
  /** 所有草稿库变换共用排他锁，锁内重新读取后合并。 */
  exclusive(action: () => ReplaceDraftSnapshot): Promise<ReplaceDraftSnapshot>
  /** 仅在来源页面已释放身份锁时清理，忙时直接返回 null，不等待关闭其他页面。 */
  ifInactive(
    id: string,
    action: () => Promise<ReplaceDraftSnapshot>,
  ): Promise<ReplaceDraftSnapshot | null>
}

/** 页面控制器使用的完整持久化接口；不引用 Vue、文件或账号状态。 */
export interface ReplaceDraftRepository {
  /** 每次页面实例独立，刷新/复制标签页不会继续写旧页面 ID。 */
  readonly id: string
  /** 延迟取得页面锁，读取设置与历史。 */
  open(): Promise<ReplaceDraftSnapshot>
  /** 读取快照，不自动修复或覆盖坏数据。 */
  read(): ReplaceDraftSnapshot
  /** 主动开启；已开启时保留代次，不打断其他正常页面。 */
  enable(): Promise<ReplaceDraftSnapshot>
  /** 显式关闭/清理可以丢弃损坏的草稿库，其余操作不得绕过校验。 */
  disable(): Promise<ReplaceDraftSnapshot>
  /** 只更新本页槽位；代次与有效性都在实际写入前检查。 */
  save(
    draft: ReplaceDraft | null,
    generation: string,
    current: () => boolean,
    retireId?: string,
  ): Promise<ReplaceDraftSnapshot>
  /** 活动的其他页面不可清理，删除当前页应由保存 null 配合清空编辑处理。 */
  removeHistory(id: string): Promise<ReplaceDraftSnapshot>
  /** 释放页面身份；关闭后迟到保存不能继续写入。 */
  close(): void
}

/** 合并自己的槽位，不用整库旧快照覆盖另一标签页；开关代次阻止旧请求复活。 */
export function createReplaceDraftRepository(
  environment: ReplaceDraftEnvironment,
): ReplaceDraftRepository {
  const id = environment.createId()
  let release: (() => void) | undefined
  let closed = false
  let book: ReplaceDraftBook | null = null
  const empty = (): ReplaceDraftBook =>
    validateReplaceDraftBook({ version: 1, enabled: false, generation: '', drafts: [] })
  function result(status: ReplaceDraftStatus, message: string): ReplaceDraftSnapshot {
    return Object.freeze({ book, status, message, writable: !!release && !closed })
  }
  function read(): ReplaceDraftSnapshot {
    let raw: string | null
    try {
      raw = environment.storage().getItem(REPLACE_DRAFT_STORAGE_KEY)
    } catch {
      return result('read-error', '无法读取本机草稿，当前编辑内容仍保留')
    }
    try {
      book = raw === null ? empty() : parseReplaceDraftBook(raw)
    } catch {
      return result('invalid-storage', '本机草稿损坏或版本不支持，已保留原始数据；可明确关闭并清理')
    }
    return result('ready', '已读取本机草稿设置')
  }
  async function open(): Promise<ReplaceDraftSnapshot> {
    if (closed) return result('cancelled', '草稿页面已离开')
    if (!release) {
      try {
        const stop = await environment.claim(id)
        if (closed) {
          stop()
          return result('cancelled', '草稿页面已离开')
        }
        release = stop
      } catch {
        read()
        return result('lock-unavailable', '当前环境不支持安全的自动草稿，编辑和本机词库仍可使用')
      }
    }
    return read()
  }
  async function update(
    change: (currentBook: ReplaceDraftBook) => ReplaceDraftBook | ReplaceDraftSnapshot,
    allowReset = false,
  ): Promise<ReplaceDraftSnapshot> {
    if (!release || closed) return result('lock-unavailable', '未取得本页草稿锁，未写入本机')
    const failure: { status: ReplaceDraftStatus } = { status: 'lock-unavailable' }
    try {
      return await environment.exclusive(() => {
        if (closed) return result('cancelled', '草稿页面已离开，未写入')
        failure.status = 'read-error'
        let currentBook: ReplaceDraftBook
        if (allowReset) currentBook = empty()
        else {
          const raw = environment.storage().getItem(REPLACE_DRAFT_STORAGE_KEY)
          failure.status = 'invalid-storage'
          currentBook = raw === null ? empty() : parseReplaceDraftBook(raw)
        }
        const next = change(currentBook)
        if ('status' in next) return next
        failure.status = 'invalid'
        const candidate = validateReplaceDraftBook(next)
        failure.status = 'write-error'
        environment.storage().setItem(REPLACE_DRAFT_STORAGE_KEY, JSON.stringify(candidate))
        book = candidate
        return result(
          candidate.enabled ? 'saved' : 'disabled',
          candidate.enabled ? '草稿已保存到本机' : '已关闭自动草稿并清除本机历史',
        )
      })
    } catch {
      const message =
        failure.status === 'invalid'
          ? '本机最多保留 10 份草稿，请先清理历史；当前编辑未丢失'
          : failure.status === 'invalid-storage'
            ? '本机草稿损坏或版本不支持，已停止写入且保留原始数据'
            : failure.status === 'write-error'
              ? '本机草稿写入或清理失败，尚未确认保存；当前编辑仍保留'
              : failure.status === 'read-error'
                ? '无法核对本机草稿，尚未保存；当前编辑仍保留'
                : '无法取得草稿保存锁，尚未保存；当前编辑仍保留'
      return result(failure.status, message)
    }
  }
  function enable(): Promise<ReplaceDraftSnapshot> {
    return update((currentBook) => ({
      ...currentBook,
      enabled: true,
      generation: currentBook.enabled ? currentBook.generation : environment.createId(),
    }))
  }
  function disable(): Promise<ReplaceDraftSnapshot> {
    return update(
      () => ({ version: 1, enabled: false, generation: environment.createId(), drafts: [] }),
      true,
    )
  }
  function save(
    draft: ReplaceDraft | null,
    generation: string,
    current: () => boolean,
    retireId?: string,
  ): Promise<ReplaceDraftSnapshot> {
    let snapshot: ReplaceDraft | null
    try {
      snapshot = draft === null ? null : validateReplaceDraft(draft)
    } catch {
      return Promise.resolve(result('invalid', '草稿超限或结构不符合要求，当前编辑仍保留'))
    }
    const write = (retire?: string): Promise<ReplaceDraftSnapshot> =>
      update((currentBook) => {
        if (!current()) return result('cancelled', '旧草稿保存已作废')
        if (!currentBook.enabled || currentBook.generation !== generation) {
          book = currentBook
          return result('stale', '自动草稿设置已改变，已停止旧请求，请重新开启后保存')
        }
        return {
          ...currentBook,
          drafts: [
            ...currentBook.drafts.filter((item) => item.id !== id && item.id !== retire),
            ...(snapshot ? [{ id, draft: snapshot }] : []),
          ],
        }
      })
    if (!retireId || retireId === id) return write()
    // 来源仍有活动页面时只复制恢复，不删除其槽位；关闭来源后才可原子转移。
    return environment
      .ifInactive(retireId, () => write(retireId))
      .then((value) => value ?? write())
      .catch(() => result('lock-unavailable', '无法核对来源草稿，未清理或写入；当前编辑仍保留'))
  }
  function removeHistory(targetId: string): Promise<ReplaceDraftSnapshot> {
    if (targetId === id)
      return Promise.resolve(result('active', '请使用“清空本页规则和草稿”处理当前页面'))
    return environment
      .ifInactive(targetId, () =>
        update((currentBook) => ({
          ...currentBook,
          drafts: currentBook.drafts.filter((item) => item.id !== targetId),
        })),
      )
      .then(
        (value) => value ?? result('active', '该草稿仍在其他页面使用，未删除；请关闭原页面后重试'),
      )
      .catch(() => result('lock-unavailable', '无法核对历史草稿是否仍在使用，未删除'))
  }
  return {
    id,
    open,
    read,
    enable,
    disable,
    save,
    removeHistory,
    close: () => {
      closed = true
      release?.()
      release = undefined
    },
  }
}

/** Web Locks 的页面锁与写入锁各司其职；无锁环境不降级为不安全的共享写入。 */
export function createBrowserReplaceDraftRepository(): ReplaceDraftRepository {
  const locks = (): LockManager => {
    if (typeof navigator === 'undefined' || !navigator.locks)
      throw new Error('Draft locks unavailable')
    return navigator.locks
  }
  return createReplaceDraftRepository({
    storage: () => localStorage,
    createId: () =>
      Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) =>
        byte.toString(16).padStart(2, '0'),
      ).join(''),
    claim: (id) =>
      new Promise<() => void>((resolve, reject) => {
        // 将“取得锁”和“释放锁”分开；页面结束自动释放，历史清理只探测空闲锁。
        void locks()
          .request(ownerLock(id), { ifAvailable: true }, (lock) => {
            if (!lock) {
              reject(new Error('Draft owner already active'))
              return
            }
            return new Promise<void>((release) => resolve(release))
          })
          .catch(reject)
      }),
    exclusive: (action) =>
      locks().request(REPLACE_DRAFT_STORAGE_KEY, { mode: 'exclusive' }, action),
    ifInactive: (id, action) =>
      locks().request(ownerLock(id), { ifAvailable: true }, (lock) => (lock ? action() : null)),
  })
}

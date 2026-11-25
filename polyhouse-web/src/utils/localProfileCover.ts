/** 同一事务保存两种图，刷新后也能恢复本机开关。 */
export interface LocalProfileCover {
  desktop: Blob
  mobile: Blob
  enabled: boolean
}

const storeName = 'covers'
export const localCoverChannel = 'polyhouse-local-cover'

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('polyhouse-profile-cover', 1)
    let blocked = false
    request.onupgradeneeded = () => request.result.createObjectStore(storeName)
    request.onblocked = () => {
      blocked = true
      reject(new Error('请关闭此站点的其他标签页，再重新打开卡面设置。'))
    }
    request.onerror = () => reject(request.error)
    request.onsuccess = () => {
      if (blocked) {
        request.result.close()
        return
      }
      request.result.onversionchange = () => request.result.close()
      resolve(request.result)
    }
  })
}

function validCover(value: unknown): value is LocalProfileCover {
  if (!value || typeof value !== 'object') return false
  if (!('desktop' in value && 'mobile' in value && 'enabled' in value)) return false
  return (
    typeof value.enabled === 'boolean' &&
    [value.desktop, value.mobile].every(
      (image) => image instanceof Blob && image.type === 'image/webp' && image.size > 0,
    )
  )
}

async function transact<T>(
  mode: IDBTransactionMode,
  operation: (store: IDBObjectStore, result: (value: T) => void) => void,
): Promise<T> {
  const db = await openDatabase()
  try {
    return await new Promise((resolve, reject) => {
      let value: T
      const tx = db.transaction(storeName, mode)
      tx.oncomplete = () => resolve(value)
      tx.onabort = () => reject(tx.error ?? new Error('本地存储操作已中断。'))
      operation(tx.objectStore(storeName), (next) => {
        value = next
      })
    })
  } finally {
    db.close()
  }
}

/** 使用已验证账号的 ID 作为键，账号之间各有一套图片。 */
export function readLocalCover(id: string): Promise<LocalProfileCover | null> {
  return transact('readonly', (store, result) => {
    const request = store.get(id)
    request.onsuccess = () => {
      const value: unknown = request.result
      result(validCover(value) ? value : null)
    }
  })
}

/** 事务提交后才通知其他页面，通知只包含变更信号。 */
async function writeCover(operation: (store: IDBObjectStore) => void): Promise<void> {
  await transact<void>('readwrite', (store) => operation(store))
  if (typeof BroadcastChannel !== 'undefined') {
    let channel: BroadcastChannel | undefined
    try {
      channel = new BroadcastChannel(localCoverChannel)
      channel.postMessage('changed')
    } catch {
      // 存储事务已提交；跨标签通知仅作即时同步，其他标签页聚焦时仍会重新读取。
    } finally {
      channel?.close()
    }
  }
}

/** 保留完整成图，失败时 IndexedDB 会保留上一次已提交的版本。 */
export function saveLocalCover(id: string, cover: LocalProfileCover): Promise<void> {
  return writeCover((store) => store.put(cover, id))
}

/** 在同一写事务里读取最新版本，其他标签页删除或替换时也不会还原已删除的图片。 */
export function enableLocalCover(id: string, enabled: boolean): Promise<void> {
  return writeCover((store) => {
    const request = store.get(id)
    request.onsuccess = () => {
      const value: unknown = request.result
      if (validCover(value)) store.put({ ...value, enabled }, id)
    }
  })
}

/** 删除仅限当前账号的本机记录。 */
export function deleteLocalCover(id: string): Promise<void> {
  return writeCover((store) => store.delete(id))
}

import { ApiError, apiRequest, isRecord } from './index'

export const coverNames = { window: '窗边初晴', astrolabe: '黄铜星盘', koi: '末班信号' } as const
export type CoverKey = keyof typeof coverNames
const reasons = ['default', 'purchased', 'role', 'level', 'coins', 'levelRequired']

export interface CoverItem {
  key: CoverKey
  reason: string
  price: number
  missingExperience: number
}
export interface CoverCatalog {
  userId: string
  coins: string
  equipped: CoverKey
  quoteVersion: string
  covers: CoverItem[]
}

function isKey(value: unknown): value is CoverKey {
  return typeof value === 'string' && Object.hasOwn(coverNames, value)
}

function decode(value: unknown): CoverCatalog {
  const fail = (): never => {
    throw new ApiError('卡面资料暂时无法确认', 502)
  }
  if (
    !isRecord(value) ||
    typeof value.userId !== 'string' ||
    !isKey(value.equipped) ||
    typeof value.coins !== 'string' ||
    !/^\d{1,19}$/.test(value.coins) ||
    typeof value.quoteVersion !== 'string' ||
    !Array.isArray(value.covers)
  )
    return fail()
  const covers = value.covers.map((item: unknown) => {
    if (
      !isRecord(item) ||
      !isKey(item.key) ||
      typeof item.reason !== 'string' ||
      !reasons.includes(item.reason) ||
      typeof item.price !== 'number' ||
      !Number.isSafeInteger(item.price) ||
      item.price < 0 ||
      typeof item.missingExperience !== 'number' ||
      !Number.isSafeInteger(item.missingExperience) ||
      item.missingExperience < 0
    )
      return fail()
    return {
      key: item.key,
      reason: item.reason,
      price: item.price,
      missingExperience: item.missingExperience,
    }
  })
  const keys = new Set(covers.map((item) => item.key))
  // 仅校验非空、key 唯一且当前佩戴项在目录中，避免未来新增官方卡面时因固定数量校验触发 502。
  if (covers.length < 1 || keys.size !== covers.length || !keys.has(value.equipped)) return fail()
  return {
    userId: value.userId,
    coins: value.coins,
    equipped: value.equipped,
    quoteVersion: value.quoteVersion,
    covers,
  }
}

export function readCovers(token: string) {
  return apiRequest({ method: 'GET', url: '/profile/covers' }, decode, token)
}

export function changeCover(token: string, key: CoverKey, version?: string) {
  return apiRequest(
    {
      method: 'POST',
      url: '/profile/covers/' + (version === undefined ? 'equip' : 'purchase'),
      data: version === undefined ? { coverKey: key } : { coverKey: key, quoteVersion: version },
    },
    decode,
    token,
  )
}

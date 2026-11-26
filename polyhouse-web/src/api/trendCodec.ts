import { ApiError, isRecord } from './index'

/** 日期参数是北京期号。UTC 只用于验证日历日期，避免本机时区改变判断。 */
export function isTrendDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const day = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(day.getTime()) && day.toISOString().slice(0, 10) === value
}

/** 重试与会话恢复只接受服务端约定的小写 UUID。 */
export function isTrendId(value: unknown): value is string {
  return typeof value === 'string' && /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(value)
}

/** 统一抛出 502，避免把畸形或截断的上游结构泄露到视图层。 */
export function invalid(): never {
  throw new ApiError('星潮返回的数据不完整，请稍后重试', 502)
}

export function object(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : invalid()
}

export function text(value: unknown): string {
  return typeof value === 'string' ? value : invalid()
}

export function optional<T>(value: unknown, decode: (input: unknown) => T): T | null {
  return value === null || value === undefined ? null : decode(value)
}

export function whole(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : invalid()
}

export function signedInt(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) ? value : invalid()
}

export function ratio(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1
    ? value
    : invalid()
}

export function signedRatio(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= -1 && value <= 1
    ? value
    : invalid()
}

export function nonNegativeNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : invalid()
}

/** 校验仓库名与 GitHub 链接一一对应，阻止非白名单外链混入公开卡片。 */
export function repoIdentity(row: Record<string, unknown>): { fullName: string; repoUrl: string } {
  const fullName = text(row.fullName)
  // 原始描述和解读只渲染为文字，仓库链接也须与允许的 GitHub 路径一致。
  if (
    !/^[\w.-]+\/[\w.-]+$/.test(fullName) ||
    fullName.split('/').some((part) => part === '.' || part === '..')
  )
    invalid()
  const repoUrl = `https://github.com/${fullName}`
  if (row.repoUrl !== repoUrl) invalid()
  return { fullName, repoUrl }
}

export function truth(value: unknown): boolean {
  return typeof value === 'boolean' ? value : invalid()
}

export function date(value: unknown): string {
  return isTrendDate(value) ? value : invalid()
}

export function instant(value: unknown): string {
  return typeof value === 'string' && value.endsWith('Z') && Number.isFinite(Date.parse(value))
    ? value
    : invalid()
}

export function id(value: unknown): string {
  return isTrendId(value) ? value : invalid()
}

export function choice<T extends string>(value: unknown, values: readonly T[]): T {
  return values.find((item) => item === value) ?? invalid()
}

export function list<T>(value: unknown, max: number, decode: (input: unknown) => T): T[] {
  return Array.isArray(value) && value.length <= max ? value.map(decode) : invalid()
}

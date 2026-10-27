import { ApiError, apiRequest, isRecord } from './index'

/** 成长展示全部来自服务端快照，前端不复制升级门槛或把缓存作为到账证明。 */
export interface UserGrowthSummary {
  /** 绑定当前用户，防止旧响应显示到新账号。 */
  userId: string
  /** 累计经验保持十进制字符串。 */
  experience: string
  /** 硬币余额保持十进制字符串。 */
  coins: string
  /** 现行规则计算的等级。 */
  level: number
  /** 现行规则的等级上限。 */
  maxLevel: number
  /** 满级时不再展示虚构的下一级。 */
  maxLevelReached: boolean
  /** 当前等级累计门槛。 */
  levelStartExperience: string
  /** 下一级累计门槛，满级为 null。 */
  nextLevelExperience: string | null
  /** 距下一级所需经验，满级为 0。 */
  experienceToNextLevel: string
  /** 服务端给出的等级内百分比。 */
  progressPercent: number
  /** 服务端自然日，不按浏览器日期推算领取情况。 */
  rewardDate: string
  /** 奖励日所属时区。 */
  rewardTimeZone: string
  /** 当日真实奖励流水是否存在。 */
  dailyLoginRewardClaimed: boolean
  /** 已到账时为当日实际经验，尚未到账时为当前规则预期值。 */
  dailyLoginExperience: number
  /** 已到账时为当日实际硬币，尚未到账时为当前规则预期值。 */
  dailyLoginCoins: number
  /** 服务端捕获时间，用于计算相对刷新间隔。 */
  observedAt: string
  /** 服务端下一奖励日零点。 */
  nextRewardAt: string
  /** 当前门槛及未到账预期奖励的规则版本。 */
  ruleVersion: string
}

/** 校验金额类型和字段之间的关系；只能拒绝坏响应，不能据此发奖或修改规则。 */
export function decodeGrowthSummary(value: unknown): UserGrowthSummary {
  const decimal = (entry: unknown): entry is string =>
    typeof entry === 'string' && entry.length <= 19 && /^(0|[1-9]\d*)$/.test(entry)
  const integer = (entry: unknown): entry is number =>
    typeof entry === 'number' && Number.isSafeInteger(entry) && entry >= 0
  const invalid = (): never => {
    throw new ApiError('成长数据暂时无法确认，请稍后重试', 502)
  }
  if (
    !isRecord(value) ||
    !decimal(value.userId) ||
    value.userId === '0' ||
    !decimal(value.experience) ||
    !decimal(value.coins) ||
    !decimal(value.levelStartExperience) ||
    !decimal(value.experienceToNextLevel) ||
    !(value.nextLevelExperience === null || decimal(value.nextLevelExperience)) ||
    !integer(value.level) ||
    value.level < 1 ||
    !integer(value.maxLevel) ||
    value.level > value.maxLevel ||
    typeof value.maxLevelReached !== 'boolean' ||
    value.maxLevelReached !== (value.level === value.maxLevel) ||
    !integer(value.progressPercent) ||
    value.progressPercent > 100 ||
    typeof value.rewardDate !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}$/.test(value.rewardDate) ||
    typeof value.rewardTimeZone !== 'string' ||
    typeof value.dailyLoginRewardClaimed !== 'boolean' ||
    !integer(value.dailyLoginExperience) ||
    !integer(value.dailyLoginCoins) ||
    typeof value.observedAt !== 'string' ||
    typeof value.nextRewardAt !== 'string' ||
    typeof value.ruleVersion !== 'string' ||
    !value.ruleVersion ||
    value.ruleVersion.length > 64
  )
    return invalid()
  const experience = BigInt(value.experience)
  const start = BigInt(value.levelStartExperience)
  const remaining = BigInt(value.experienceToNextLevel)
  if (experience < start) return invalid()
  if (value.maxLevelReached) {
    if (value.nextLevelExperience !== null || remaining !== 0n || value.progressPercent !== 100)
      return invalid()
  } else {
    if (value.nextLevelExperience === null) return invalid()
    const next = BigInt(value.nextLevelExperience)
    if (
      next <= experience ||
      remaining !== next - experience ||
      BigInt(value.progressPercent) !== ((experience - start) * 100n) / (next - start)
    )
      return invalid()
  }
  const duration = Date.parse(value.nextRewardAt) - Date.parse(value.observedAt)
  if (!Number.isFinite(duration) || duration <= 0 || duration > 172800000) return invalid()
  try {
    new Intl.DateTimeFormat('zh-CN', { timeZone: value.rewardTimeZone })
  } catch {
    return invalid()
  }
  return {
    userId: value.userId,
    experience: value.experience,
    coins: value.coins,
    level: value.level,
    maxLevel: value.maxLevel,
    maxLevelReached: value.maxLevelReached,
    levelStartExperience: value.levelStartExperience,
    nextLevelExperience: value.nextLevelExperience,
    experienceToNextLevel: value.experienceToNextLevel,
    progressPercent: value.progressPercent,
    rewardDate: value.rewardDate,
    rewardTimeZone: value.rewardTimeZone,
    dailyLoginRewardClaimed: value.dailyLoginRewardClaimed,
    dailyLoginExperience: value.dailyLoginExperience,
    dailyLoginCoins: value.dailyLoginCoins,
    observedAt: value.observedAt,
    nextRewardAt: value.nextRewardAt,
    ruleVersion: value.ruleVersion,
  }
}

/** 只读取当前 Bearer 对应用户的成长状态，不接受用户编号或领取数值。 */
export function getGrowthSummary(token: string): Promise<UserGrowthSummary> {
  return apiRequest({ method: 'GET', url: '/auth/growth' }, decodeGrowthSummary, token)
}

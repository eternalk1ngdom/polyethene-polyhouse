import type { UserProfile } from '../api/auth'

/** 旧资料允许空昵称，所有账号区域使用一致的回退名称。 */
export function userDisplayName(profile: UserProfile): string {
  return profile.nickname?.trim() ? profile.nickname : profile.username
}

/** 只解释服务端角色，不通过成长等级推导权限。 */
export function userRoleLabel(role: UserProfile['role']): string {
  const labels: Record<UserProfile['role'], string> = {
    USER: '普通居民',
    ADMIN: '管理员',
    INSIDER: '特邀熟人',
    OWNER: '聚妙屋屋主',
  }
  return labels[role]
}

/** 昵称可能包含 emoji，按 Unicode 码点取首字，避免切出半个代理对。 */
export function avatarInitial(name: string): string {
  return Array.from(name.trim())[0]?.toLocaleUpperCase('zh-CN') ?? '屋'
}

/** 在字符串上分组展示 bigint，避免先转 number 丢失经验或余额精度。 */
export function formatAccountNumber(value: string): string {
  return value.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

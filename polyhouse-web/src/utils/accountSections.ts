/** 页面分类只负责导航，不代表后台权限或资料修改动作。 */
export type AccountSection = 'growth' | 'profile' | 'tools' | 'security'

/** 有限导航项同时约束 URL、文案和当前项，避免维护多份顺序。 */
interface AccountSectionItem {
  /** URL 白名单值。 */
  key: AccountSection
  /** 沿用用户已经确认的四个分类。 */
  label: string
}

export const accountSections: readonly AccountSectionItem[] = [
  { key: 'growth', label: '我的成长' },
  { key: 'profile', label: '账号资料' },
  { key: 'tools', label: '常用工具' },
  { key: 'security', label: '账号管理' },
]

const legacySections: Readonly<Record<string, AccountSection>> = {
  '#my-growth': 'growth',
  '#profile-details-title': 'profile',
  '#my-tools-title': 'tools',
  '#account-management-title': 'security',
}

/** 旧链接优先定位其原分类；数组、未知值不会成为组件名或后台参数。 */
export function resolveAccountSection(value: unknown, hash = ''): AccountSection {
  const legacy = Object.hasOwn(legacySections, hash) ? legacySections[hash] : undefined
  return legacy ?? accountSections.find((item) => item.key === value)?.key ?? 'growth'
}

/** 仅规范旧锚点和非法参数；默认入口不制造额外历史项。 */
export function needsAccountSectionRedirect(value: unknown, hash: string): boolean {
  return (
    Object.hasOwn(legacySections, hash) ||
    (value !== undefined && !accountSections.some((item) => item.key === value))
  )
}

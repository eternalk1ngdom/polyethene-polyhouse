/** 提前给出账号提示，但最终是否合法仍由后端 DTO 决定。 */
export function usernameError(value: string): string | null {
  // 使用与后端相同的 ASCII 白名单，拒绝空格及不可见字符，不偷偷修改输入。
  return /^[A-Za-z0-9_]{4,32}$/.test(value) ? null : '账号须为 4～32 位字母、数字或下划线'
}

/** BCrypt 限制按 UTF-8 字节计算；中文、emoji 不能只按字符个数判断。 */
export function passwordError(value: string): string | null {
  if (!value.trim() || value.length < 8 || value.length > 72) return '密码须为 8～72 个字符'
  return new TextEncoder().encode(value).length <= 72 ? null : '密码的 UTF-8 长度不能超过 72 字节'
}

/** 昵称只检查长度和非空白，不替用户裁剪或改写展示名称。 */
export function nicknameError(value: string): string | null {
  return value.trim() && value.length <= 32 ? null : '请填写 1～32 个字符的昵称'
}

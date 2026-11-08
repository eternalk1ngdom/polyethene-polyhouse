/**
 * 体积格式化：统一按 1024 进制显示，与目标体积输入框的换算保持一致。
 * 纯函数、无状态，单图面板与批量列表共用，避免两处各写一份后对不上。
 */
export function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(2)} MiB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${bytes} B`
}

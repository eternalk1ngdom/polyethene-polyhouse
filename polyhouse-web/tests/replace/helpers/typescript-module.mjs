import { readFile } from 'node:fs/promises'
import ts from 'typescript'

/**
 * 妙替测试共用编译设置，直接执行真实 TS 模块；调用方传绝对 URL，避免路径随帮助文件位置改变。
 * 替换规则保持调用方显式提供，只改模块地址，不自动模拟逻辑或跨测试缓存可变状态。
 */
export async function typeScriptModuleUrl(sourceUrl, replacements = {}) {
  const source = await readFile(sourceUrl, 'utf8')
  let code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(replacements)) code = code.replaceAll(from, to)
  return `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
}

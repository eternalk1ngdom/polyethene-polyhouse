/**
 * 前端测试静默输出包装脚本：
 * - 全部通过时仅打印一行精简汇总，避免数百行用例名淹没终端与 AI 上下文；
 * - 任一失败时完整展开原始输出并保留非零退出码，不隐藏任何排错细节。
 */
import { spawnSync } from 'node:child_process'
import process from 'node:process'
import { pathToFileURL } from 'node:url'

const DEFAULT_GLOB = 'tests/**/*.test.mjs'
const SUMMARY_LINE_PATTERN = /^\s*(?:ℹ|#)\s+(tests|pass|fail|skipped|duration_ms)\s+(\d+(?:\.\d+)?)\s*$/i

/**
 * 从 node:test 默认 spec 输出尾部的官方统计行提取计数与耗时。
 * 按行自底向上扫描且仅匹配带官方前缀（ℹ / #）的整行，避免被测试正文自行打印的 "pass 999" 等日志干扰。
 */
export function parseTestSummary(output) {
  const summary = {
    tests: null,
    pass: null,
    fail: null,
    skipped: null,
    durationMs: null,
  }
  if (!output) {
    return summary
  }
  const keyMap = {
    tests: 'tests',
    pass: 'pass',
    fail: 'fail',
    skipped: 'skipped',
    duration_ms: 'durationMs',
  }
  const lines = output.split(/\r?\n/)
  for (let i = lines.length - 1; i >= 0; i -= 1) {
    const match = lines[i].match(SUMMARY_LINE_PATTERN)
    if (!match) {
      continue
    }
    const targetKey = keyMap[match[1].toLowerCase()]
    if (targetKey && summary[targetKey] === null) {
      summary[targetKey] = match[2]
    }
    if (
      summary.tests !== null &&
      summary.pass !== null &&
      summary.fail !== null &&
      summary.skipped !== null &&
      summary.durationMs !== null
    ) {
      break
    }
  }
  return summary
}

function runCli() {
  const userTargets = process.argv.slice(2)
  const targets = userTargets.length > 0 ? userTargets : [DEFAULT_GLOB]

  const startedAt = Date.now()
  const result = spawnSync(process.execPath, ['--test', '--test-reporter=spec', ...targets], {
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
  })

  if (result.error) {
    console.error(`[FAIL] 前端测试启动失败：${result.error.message}`)
    process.exit(1)
  }

  const stdout = result.stdout ?? ''
  const stderr = result.stderr ?? ''
  const combined = `${stdout}${stderr}`
  const exitCode = result.status ?? 1
  const elapsedSec = ((Date.now() - startedAt) / 1000).toFixed(2)

  if (exitCode === 0) {
    const stats = parseTestSummary(combined)
    const durationText = stats.durationMs
      ? `${(Number(stats.durationMs) / 1000).toFixed(2)}s`
      : `${elapsedSec}s`
    const passText = stats.pass ?? stats.tests ?? '?'
    const failText = stats.fail ?? '0'
    const skipText = stats.skipped ?? '0'
    console.log(`[PASS] 前端测试：${passText} 通过 / ${failText} 失败 / ${skipText} 跳过 (${durationText})`)
    process.exit(0)
  }

  console.error(`[FAIL] 前端测试失败（退出码 ${exitCode}，耗时 ${elapsedSec}s），展开完整输出：`)
  if (stdout) {
    process.stdout.write(stdout)
  }
  if (stderr) {
    process.stderr.write(stderr)
  }
  process.exit(exitCode)
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  runCli()
}

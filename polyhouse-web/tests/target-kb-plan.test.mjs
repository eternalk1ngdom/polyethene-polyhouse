import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

// 与现有测试一致：内存编译并执行真实模块，只替换模块地址，不替换被测逻辑。
function toModuleUrl(source, replacements = {}) {
  let output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [specifier, url] of Object.entries(replacements)) {
    const quoted = JSON.stringify(url)
    output = output.replaceAll(`"${specifier}"`, quoted).replaceAll(`'${specifier}'`, quoted)
  }
  return `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`
}

async function loadCompress() {
  const types = await readFile(new URL('../src/core/targetKbTypes.ts', import.meta.url), 'utf8')
  const source = await readFile(new URL('../src/core/targetKbCompress.ts', import.meta.url), 'utf8')
  return import(toModuleUrl(source, { './targetKbTypes': toModuleUrl(types) }))
}

const { startSearch, advanceSearch } = await loadCompress()

/** 驱动搜索直到结束；编码器由用例提供，用来替代真实 Canvas 编码。 */
function run(input, encode) {
  let step = startSearch(input)
  const probes = []
  while (step.kind === 'probe') {
    probes.push(step.probe)
    if (probes.length > 200) throw new Error('搜索未收敛')
    step = advanceSearch(step.state, encode(step.probe))
  }
  return { outcome: step, probes }
}

test('原图已满足上限时一次编码即结束，不牺牲画质', () => {
  const { outcome, probes } = run(
    { target: { minBytes: 0, maxBytes: 204800 }, longEdge: 2000 },
    () => 150000,
  )
  assert.equal(probes.length, 1)
  assert.equal(probes[0].quality, 1)
  assert.equal(outcome.kind, 'done')
  assert.equal(outcome.result.quality, 1)
  assert.equal(outcome.result.bytes, 150000)
  assert.equal(outcome.result.needsPadding, false)
})

test('二分收敛到满足上限的最大质量', () => {
  const encode = (probe) => Math.round(200000 * probe.quality)
  const { outcome, probes } = run(
    { target: { minBytes: 0, maxBytes: 100000 }, longEdge: 2000 },
    encode,
  )
  assert.equal(outcome.kind, 'done')
  assert.ok(outcome.result.bytes <= 100000)
  assert.ok(
    outcome.result.quality > 0.45 && outcome.result.quality < 0.55,
    `质量应接近 0.5，实际 ${outcome.result.quality}`,
  )
  assert.ok(probes.length <= 13, `编码次数应受 12 次上限约束，实际 ${probes.length}`)
  assert.equal(outcome.result.maxEdge, 2000)
  assert.equal(outcome.result.scaleStep, 0)
})

test('非单调编码器也只返回实测满足上限的参数', () => {
  const encode = (probe) => Math.round(120000 * probe.quality + (probe.quality > 0.8 ? 60000 : 0))
  const { outcome } = run({ target: { minBytes: 0, maxBytes: 100000 }, longEdge: 3000 }, encode)
  assert.equal(outcome.kind, 'done')
  assert.ok(outcome.result.bytes <= 100000)
  assert.equal(
    outcome.result.bytes,
    encode({ quality: outcome.result.quality, maxEdge: outcome.result.maxEdge }),
  )
})

test('下限高于实际可编码体积时用补齐而不是继续压质量', () => {
  const { outcome } = run(
    { target: { minBytes: 150000, maxBytes: 204800 }, longEdge: 2000 },
    () => 100000,
  )
  assert.equal(outcome.kind, 'done')
  assert.equal(outcome.result.needsPadding, true)
  assert.equal(outcome.result.padToBytes, 150000)
  assert.equal(outcome.result.quality, 1)
})

test('结果落在区间内时不需要补齐', () => {
  const { outcome } = run(
    { target: { minBytes: 100000, maxBytes: 204800 }, longEdge: 2000 },
    () => 160000,
  )
  assert.equal(outcome.kind, 'done')
  assert.equal(outcome.result.needsPadding, false)
  assert.equal(outcome.result.padToBytes, 0)
})

test('无法缩小尺寸且始终超限时如实失败', () => {
  const { outcome, probes } = run(
    { target: { minBytes: 0, maxBytes: 1024 }, longEdge: null },
    () => 5000000,
  )
  assert.equal(outcome.kind, 'failed')
  assert.equal(outcome.reason, 'exceeds-target')
  assert.ok(probes.every((probe) => probe.maxEdge === null))
})

test('可以缩尺寸时逐档降级，用尽档位后失败', () => {
  const { outcome, probes } = run(
    { target: { minBytes: 0, maxBytes: 1024 }, longEdge: 4000 },
    () => 5000000,
  )
  assert.equal(outcome.kind, 'failed')
  assert.equal(outcome.reason, 'exceeds-target')
  const edges = [...new Set(probes.map((probe) => probe.maxEdge))]
  assert.equal(edges.length, 7, '原尺寸加 6 档降级')
  // 逐档按 0.85 连乘取整；第三档受浮点表示影响为 2456 而不是 2456.5 的四舍五入结果。
  assert.deepEqual(edges, [4000, 3400, 2890, 2456, 2088, 1775, 1509])
})

test('降档后能满足上限时返回缩小过的结果', () => {
  // 质量压到最低仍然超限，只能靠缩小尺寸，才能覆盖降档分支。
  const encode = (probe) => {
    const edge = probe.maxEdge ?? 4000
    return Math.round(edge * edge * 0.05 * (0.2 + 0.8 * probe.quality))
  }
  const { outcome } = run({ target: { minBytes: 0, maxBytes: 200000 }, longEdge: 4000 }, encode)
  assert.equal(outcome.kind, 'done')
  assert.ok(outcome.result.scaleStep > 0, '应发生降档')
  assert.ok(outcome.result.bytes <= 200000)
  assert.ok(outcome.result.maxEdge < 4000)
})

test('拒绝无效的搜索输入与编码结果', () => {
  assert.throws(
    () => startSearch({ target: { minBytes: 0, maxBytes: 0 }, longEdge: 100 }),
    /正整数/,
  )
  assert.throws(
    () => startSearch({ target: { minBytes: 300, maxBytes: 200 }, longEdge: 100 }),
    /下限不能大于上限/,
  )
  assert.throws(
    () => startSearch({ target: { minBytes: 0, maxBytes: 204800 }, longEdge: 0 }),
    /最长边/,
  )
  const step = startSearch({ target: { minBytes: 0, maxBytes: 204800 }, longEdge: 100 })
  assert.throws(() => advanceSearch(step.state, 0), /字节数无效/)
  assert.throws(() => advanceSearch(step.state, Number.NaN), /字节数无效/)
})

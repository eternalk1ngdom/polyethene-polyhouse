import assert from 'node:assert/strict'
import test from 'node:test'
import { typeScriptModuleUrl } from '../helpers/test-runtime.mjs'

const typesUrl = await typeScriptModuleUrl(
  new URL('../../src/core/targetKbTypes.ts', import.meta.url),
)
const { startSearch, advanceSearch, planDelivery } = await import(
  await typeScriptModuleUrl(new URL('../../src/core/targetKbCompress.ts', import.meta.url), {
    "'./targetKbTypes'": `'${typesUrl}'`,
    '"./targetKbTypes"': `"${typesUrl}"`,
  })
)

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

test('略微超限时走二分不额外探底，收敛到满足上限的最大质量', () => {
  const encode = (probe) => Math.round(200000 * probe.quality)
  const { outcome, probes } = run(
    { target: { minBytes: 0, maxBytes: 100000 }, longEdge: 2000 },
    encode,
  )
  assert.equal(probes[0].quality, 1)
  assert.ok(probes[1].quality > 0.4, `第二次应走二分而非探底，实际 ${probes[1].quality}`)
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

test('远超上限时直接探底并按 0.85 逐档降级，用尽 7 档后失败', () => {
  const { outcome, probes } = run(
    { target: { minBytes: 0, maxBytes: 1000 }, longEdge: 4000 },
    () => 5000000,
  )
  assert.equal(outcome.kind, 'failed')
  assert.equal(outcome.reason, 'exceeds-target')
  assert.equal(probes[0].quality, 1)
  assert.equal(probes[1].quality, 0.1, '第二次应直接探底而不是二分到 0.55')
  const edges = [...new Set(probes.map((probe) => probe.maxEdge))]
  assert.deepEqual(edges, [4000, 3400, 2890, 2456, 2088, 1775, 1509])
  assert.equal(probes.length, 14, `每档两次编码，实际 ${probes.length} 次`)
})

test('探底成功后立刻夹住区间，最终质量贴着上限', () => {
  const encode = (probe) => Math.round(200000 * probe.quality)
  const { outcome, probes } = run(
    { target: { minBytes: 0, maxBytes: 25000 }, longEdge: 2000 },
    encode,
  )
  assert.equal(probes[0].quality, 1)
  assert.equal(probes[1].quality, 0.1)
  assert.equal(outcome.kind, 'done')
  assert.equal(outcome.result.scaleStep, 0, '质量区间里有解就不该降档')
  assert.ok(outcome.result.bytes <= 25000)
  assert.ok(
    outcome.result.quality > 0.11 && outcome.result.quality <= 0.125,
    `质量应贴着 0.125，实际 ${outcome.result.quality}`,
  )
})

test('补齐判定：低于下限时启用补齐，落在区间内时不补齐', async (t) => {
  for (const [label, minBytes, actualBytes, expectPad, padToBytes] of [
    ['低于下限时保留最高质量并补齐到下限', 150000, 100000, true, 150000],
    ['落在目标区间内时不补齐', 100000, 160000, false, 0],
  ]) {
    await t.test(label, () => {
      const { outcome } = run(
        { target: { minBytes, maxBytes: 204800 }, longEdge: 2000 },
        () => actualBytes,
      )
      assert.equal(outcome.kind, 'done')
      assert.equal(outcome.result.needsPadding, expectPad)
      assert.equal(outcome.result.padToBytes, padToBytes)
      assert.equal(outcome.result.quality, 1)
    })
  }
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

test('降档后能满足上限时返回缩小过的结果', () => {
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

test('planDelivery 按区间与 DPI 决定原样交付或编码，拒绝非法字节数', async (t) => {
  const cases = [
    ['区间内且不改 DPI 原样交付', { sourceBytes: 70176, isJpeg: false, target: { minBytes: 0, maxBytes: 150 * 1024 }, dpi: null }, 'passthrough'],
    ['超出上限必须编码', { sourceBytes: 200 * 1024, isJpeg: true, target: { minBytes: 60 * 1024, maxBytes: 150 * 1024 }, dpi: null }, 'encode'],
    ['低于下限必须编码以补 COM 段', { sourceBytes: 20 * 1024, isJpeg: true, target: { minBytes: 60 * 1024, maxBytes: 150 * 1024 }, dpi: null }, 'encode'],
    ['写 DPI 时 JPEG 可原样交付', { sourceBytes: 70176, isJpeg: true, target: { minBytes: 0, maxBytes: 150 * 1024 }, dpi: 300 }, 'passthrough'],
    ['写 DPI 时非 JPEG 必须编码转 JPEG', { sourceBytes: 70176, isJpeg: false, target: { minBytes: 0, maxBytes: 150 * 1024 }, dpi: 300 }, 'encode'],
  ]
  for (const [label, input, expected] of cases) {
    await t.test(label, () => assert.equal(planDelivery(input), expected))
  }
  await t.test('拒绝 0 或 NaN 字节数', () => {
    const target = { minBytes: 0, maxBytes: 150 * 1024 }
    assert.throws(() => planDelivery({ sourceBytes: 0, isJpeg: true, target, dpi: null }), /字节数无效/)
    assert.throws(() => planDelivery({ sourceBytes: Number.NaN, isJpeg: true, target, dpi: null }), /字节数无效/)
  })
})

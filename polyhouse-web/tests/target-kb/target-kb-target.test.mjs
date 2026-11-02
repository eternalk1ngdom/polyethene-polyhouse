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
  const types = await readFile(new URL('../../src/core/targetKbTypes.ts', import.meta.url), 'utf8')
  const source = await readFile(new URL('../../src/core/targetKbCompress.ts', import.meta.url), 'utf8')
  return import(toModuleUrl(source, { './targetKbTypes': toModuleUrl(types) }))
}

const { parseTargetSize, createTargetRange, judgeSize } = await loadCompress()

test('解析带单位的目标体积并换算为字节', () => {
  assert.deepEqual(parseTargetSize('200KB'), { minBytes: 0, maxBytes: 204800 })
  assert.deepEqual(parseTargetSize('1.5MB'), { minBytes: 0, maxBytes: 1572864 })
  assert.deepEqual(parseTargetSize('307200'), { minBytes: 0, maxBytes: 307200 })
  assert.deepEqual(parseTargetSize('  200 kb '), { minBytes: 0, maxBytes: 204800 })
})

test('拒绝非法、为零与超过 20MB 的目标体积', () => {
  for (const input of ['', '   ', 'abc', '-1', '200GB', '0', '0.4B', '21MB', '1e3'])
    assert.throws(() => parseTargetSize(input), Error, `应拒绝：${input}`)
})

test('上下限组合校验区间', () => {
  assert.deepEqual(createTargetRange(0, 204800), { minBytes: 0, maxBytes: 204800 })
  assert.deepEqual(createTargetRange(102400, 204800), { minBytes: 102400, maxBytes: 204800 })
  assert.throws(() => createTargetRange(204800, 102400), /下限不能大于上限/)
  assert.throws(() => createTargetRange(-1, 1024), /非负整数/)
  assert.throws(() => createTargetRange(0, 0), /正整数/)
  assert.throws(() => createTargetRange(0, 1.5), /正整数/)
  assert.throws(() => createTargetRange(0, 21 * 1024 * 1024), /20MB/)
})

test('体积判定区分过大、过小与命中区间', () => {
  const upperOnly = { minBytes: 0, maxBytes: 204800 }
  assert.equal(judgeSize(204800, upperOnly), 'within')
  assert.equal(judgeSize(204801, upperOnly), 'too-large')
  assert.equal(judgeSize(1, upperOnly), 'within')

  const ranged = { minBytes: 102400, maxBytes: 204800 }
  assert.equal(judgeSize(102399, ranged), 'too-small')
  assert.equal(judgeSize(102400, ranged), 'within')
  assert.equal(judgeSize(204800, ranged), 'within')
})

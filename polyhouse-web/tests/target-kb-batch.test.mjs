import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

// 与现有测试一致：内存编译并执行真实模块，只替换模块地址，不替换被测逻辑。
function toModuleUrl(source) {
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  return `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`
}

const source = await readFile(new URL('../src/core/targetKbBatch.ts', import.meta.url), 'utf8')
const {
  MAX_BATCH_FILES,
  MAX_BATCH_BYTES,
  validateBatch,
  summarizeBatch,
  sanitizeName,
  outputFileName,
  zipEntryName,
} = await import(toModuleUrl(source))

/** 构造一批等大的条目，用来验证数量与总体积两条上限。 */
function entries(count, size) {
  return Array.from({ length: count }, (_, index) => ({ name: `p${index}.jpg`, size }))
}

test('批量上限：数量与总体积都要挡住', () => {
  assert.equal(MAX_BATCH_FILES, 20)
  assert.equal(MAX_BATCH_BYTES, 60 * 1024 * 1024)
  assert.doesNotThrow(() => validateBatch(entries(20, 1024)))
  assert.throws(() => validateBatch(entries(21, 1024)), /一次最多 20 张/)
  assert.doesNotThrow(() => validateBatch([{ name: 'a.jpg', size: MAX_BATCH_BYTES }]))
  assert.throws(
    () => validateBatch([{ name: 'a.jpg', size: MAX_BATCH_BYTES }, { name: 'b.jpg', size: 1 }]),
    /超过 60.0MB 上限/,
  )
})

test('空选择、空文件与非数字体积都拒绝', () => {
  assert.throws(() => validateBatch([]), /至少选择一张/)
  assert.throws(() => validateBatch([{ name: 'a.jpg', size: 0 }]), /空文件/)
  assert.throws(() => validateBatch([{ name: 'a.jpg', size: Number.NaN }]), /空文件/)
})

test('汇总区分成功、失败与未处理，未处理不计入输出体积', () => {
  const rows = [
    { name: 'a', size: 1000, status: 'done', outputBytes: 400 },
    { name: 'b', size: 2000, status: 'failed', message: '太大' },
    { name: 'c', size: 3000, status: 'running' },
    { name: 'd', size: 4000, status: 'pending' },
  ]
  assert.deepEqual(summarizeBatch(rows), {
    total: 4,
    done: 1,
    failed: 1,
    pending: 2,
    inputBytes: 10000,
    // 体积对比只能用成功项的输入，否则失败文件的体积会被算成"压缩前"。
    doneInputBytes: 1000,
    outputBytes: 400,
  })
})

test('文件名清理：路径分隔符与非法字符不能进 ZIP', () => {
  assert.equal(sanitizeName('a/b\\c:d*e?f"g<h>i|j.jpg'), 'a_b_c_d_e_f_g_h_i_j.jpg')
  assert.equal(sanitizeName('   .hidden.jpg  '), 'hidden.jpg')
  assert.equal(sanitizeName('   '), 'image')
})

test('下载名与 ZIP 内名称：扩展名统一为 jpg，体积取整到 KB', () => {
  assert.equal(outputFileName('photo.PNG', 122745), 'photo-120KB.jpg')
  assert.equal(outputFileName('无扩展名', 2048), '无扩展名-2KB.jpg')
  assert.equal(zipEntryName(0, 'photo.jpg', 122745), '01-photo-120KB.jpg')
  assert.equal(zipEntryName(11, 'a/b.jpg', 500), '12-a_b-1KB.jpg')
})

test('同名文件靠序号区分，不会在 ZIP 里互相覆盖', () => {
  const first = zipEntryName(0, 'same.jpg', 1024)
  const second = zipEntryName(1, 'same.jpg', 1024)
  assert.notEqual(first, second)
  assert.equal(first, '01-same-1KB.jpg')
  assert.equal(second, '02-same-1KB.jpg')
})

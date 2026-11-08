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

const source = await readFile(new URL('../../src/core/targetKbBatch.ts', import.meta.url), 'utf8')
const {
  MAX_BATCH_FILES,
  MAX_BATCH_BYTES,
  validateBatch,
  summarizeBatch,
  sanitizeName,
  mergePicked,
  outputFileName,
  zipEntryName,
  mimeExtension,
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
    /超过 60 MiB 上限/,
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

test('下载名与 ZIP 内名称：默认 jpg，体积取整到 KB', () => {
  assert.equal(outputFileName('photo.PNG', 122745), 'photo-120KB.jpg')
  assert.equal(outputFileName('无扩展名', 2048), '无扩展名-2KB.jpg')
  assert.equal(zipEntryName(0, 'photo.jpg', 122745), '01-photo-120KB.jpg')
  assert.equal(zipEntryName(11, 'a/b.jpg', 500), '12-a_b-1KB.jpg')
})

test('原样交付时后缀跟随实际输出类型', () => {
  // 原样返回的 PNG/WebP 内容没变，后缀写成 .jpg 会让用户拿到名不副实的文件。
  assert.equal(outputFileName('photo.png', 70176, 'png'), 'photo-69KB.png')
  assert.equal(outputFileName('photo.webp', 70176, 'webp'), 'photo-69KB.webp')
  assert.equal(zipEntryName(0, 'photo.webp', 70176, 'webp'), '01-photo-69KB.webp')
  assert.equal(mimeExtension('image/jpeg'), 'jpg')
  assert.equal(mimeExtension('image/png'), 'png')
  assert.equal(mimeExtension('image/webp'), 'webp')
  assert.equal(mimeExtension('image/avif'), 'avif')
  // 认不出的类型不假装成 JPEG。
  assert.equal(mimeExtension('application/octet-stream'), 'bin')
})

test('同名文件靠序号区分，不会在 ZIP 里互相覆盖', () => {
  const first = zipEntryName(0, 'same.jpg', 1024)
  const second = zipEntryName(1, 'same.jpg', 1024)
  assert.notEqual(first, second)
  assert.equal(first, '01-same-1KB.jpg')
  assert.equal(second, '02-same-1KB.jpg')
})

/** 构造一个满足 mergePicked 入参形状的"文件"。 */
function pick(name, size, lastModified = 1000, type = 'image/jpeg') {
  return { name, size, lastModified, type }
}

test('追加拖入：新图加在已有列表后面，已有项原样保留', () => {
  const existing = [pick('a.jpg', 100)]
  const { added, duplicates, notImages } = mergePicked(existing, [pick('b.jpg', 200)])
  assert.deepEqual(
    added.map((file) => file.name),
    ['b.jpg'],
  )
  assert.equal(duplicates, 0)
  assert.equal(notImages, 0)
})

test('重复判定用名字 + 体积 + 修改时间，只看名字会误杀同名不同图', () => {
  const existing = [pick('a.jpg', 100, 1000)]
  // 完全相同的文件：应判为重复，不再追加第二份。
  assert.equal(mergePicked(existing, [pick('a.jpg', 100, 1000)]).duplicates, 1)
  // 同名但体积不同（不同照片撞名）：不算重复。
  assert.equal(mergePicked(existing, [pick('a.jpg', 200, 1000)]).added.length, 1)
  // 同名同体积但修改时间不同：也不算重复。
  assert.equal(mergePicked(existing, [pick('a.jpg', 100, 2000)]).added.length, 1)
})

test('同一批里重复的文件只保留一份', () => {
  const { added, duplicates } = mergePicked([], [pick('a.jpg', 100), pick('a.jpg', 100)])
  assert.equal(added.length, 1)
  assert.equal(duplicates, 1)
})

test('非图片单独计数，不与重复混为一谈', () => {
  const { added, duplicates, notImages } = mergePicked([], [
    pick('a.jpg', 100),
    pick('note.txt', 10, 1000, 'text/plain'),
    pick('b.png', 300, 1000, 'image/png'),
  ])
  assert.deepEqual(
    added.map((file) => file.name),
    ['a.jpg', 'b.png'],
  )
  assert.equal(notImages, 1)
  assert.equal(duplicates, 0)
})

test('合并后再校验上限：追加会让总数超限时能挡住', () => {
  const existing = Array.from({ length: 20 }, (_, index) => pick(`p${index}.jpg`, 1024))
  const { added } = mergePicked(existing, [pick('extra.jpg', 1024)])
  assert.equal(added.length, 1)
  // 校验的是"已有 + 新增"，所以第 21 张必须被 validateBatch 拒绝。
  assert.throws(
    () => validateBatch([...existing, ...added]),
    /一次最多 20 张/,
  )
})

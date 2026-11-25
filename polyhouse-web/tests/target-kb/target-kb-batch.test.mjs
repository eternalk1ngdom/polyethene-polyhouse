import assert from 'node:assert/strict'
import test from 'node:test'
import { typeScriptModuleUrl } from '../helpers/test-runtime.mjs'

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
} = await import(
  await typeScriptModuleUrl(new URL('../../src/core/targetKbBatch.ts', import.meta.url))
)

/** 构造一批等大的条目，用来验证数量与总体积两条上限。 */
function entries(count, size) {
  return Array.from({ length: count }, (_, index) => ({ name: `p${index}.jpg`, size }))
}

/** 构造一个满足 mergePicked 入参形状的"文件"。 */
function pick(name, size, lastModified = 1000, type = 'image/jpeg') {
  return { name, size, lastModified, type }
}

test('validateBatch 守卫数量上限（20 张）、总体积上限（60 MiB）与空文件拒绝', async (t) => {
  await t.test('正好 20 张或正好 60 MiB 放行', () => {
    assert.equal(MAX_BATCH_FILES, 20)
    assert.equal(MAX_BATCH_BYTES, 60 * 1024 * 1024)
    assert.doesNotThrow(() => validateBatch(entries(20, 1024)))
    assert.doesNotThrow(() => validateBatch([{ name: 'a.jpg', size: MAX_BATCH_BYTES }]))
  })

  for (const [label, list, pattern] of [
    ['超过 20 张', entries(21, 1024), /一次最多 20 张/],
    ['总体积超 60 MiB', [{ name: 'a.jpg', size: MAX_BATCH_BYTES }, { name: 'b.jpg', size: 1 }], /超过 60 MiB 上限/],
    ['空选择', [], /至少选择一张/],
    ['0 字节空文件', [{ name: 'a.jpg', size: 0 }], /空文件/],
    ['NaN 非数字体积', [{ name: 'a.jpg', size: Number.NaN }], /空文件/],
  ]) {
    await t.test(`拒绝${label}`, () => {
      assert.throws(() => validateBatch(list), pattern)
    })
  }
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
    doneInputBytes: 1000,
    outputBytes: 400,
  })
})

test('文件名清理、默认/原样后缀与 ZIP 序号防覆盖', async (t) => {
  await t.test('sanitizeName 过滤路径分隔符、非法字符与前导点', () => {
    assert.equal(sanitizeName('a/b\\c:d*e?f"g<h>i|j.jpg'), 'a_b_c_d_e_f_g_h_i_j.jpg')
    assert.equal(sanitizeName('   .hidden.jpg  '), 'hidden.jpg')
    assert.equal(sanitizeName('   '), 'image')
  })

  await t.test('默认 jpg 命名与 ZIP 序号区分同名文件', () => {
    assert.equal(outputFileName('photo.PNG', 122745), 'photo-120KB.jpg')
    assert.equal(outputFileName('无扩展名', 2048), '无扩展名-2KB.jpg')
    assert.equal(zipEntryName(0, 'photo.jpg', 122745), '01-photo-120KB.jpg')
    assert.equal(zipEntryName(11, 'a/b.jpg', 500), '12-a_b-1KB.jpg')
    const first = zipEntryName(0, 'same.jpg', 1024)
    const second = zipEntryName(1, 'same.jpg', 1024)
    assert.notEqual(first, second)
    assert.equal(first, '01-same-1KB.jpg')
    assert.equal(second, '02-same-1KB.jpg')
  })

  await t.test('原样交付后缀与 MIME 扩展名映射', () => {
    assert.equal(outputFileName('photo.png', 70176, 'png'), 'photo-69KB.png')
    assert.equal(outputFileName('photo.webp', 70176, 'webp'), 'photo-69KB.webp')
    assert.equal(zipEntryName(0, 'photo.webp', 70176, 'webp'), '01-photo-69KB.webp')
    assert.equal(mimeExtension('image/jpeg'), 'jpg')
    assert.equal(mimeExtension('image/png'), 'png')
    assert.equal(mimeExtension('image/webp'), 'webp')
    assert.equal(mimeExtension('image/avif'), 'avif')
    assert.equal(mimeExtension('application/octet-stream'), 'bin')
  })
})

test('mergePicked 追加、去重（名+体积+时间）与非图片过滤', async (t) => {
  await t.test('追加拖入保留已有列表并追加新图', () => {
    const { added, duplicates, notImages } = mergePicked([pick('a.jpg', 100)], [pick('b.jpg', 200)])
    assert.deepEqual(
      added.map((file) => file.name),
      ['b.jpg'],
    )
    assert.equal(duplicates, 0)
    assert.equal(notImages, 0)
  })

  await t.test('重复判定用名字+体积+修改时间，同批重复也只保留一份', () => {
    const existing = [pick('a.jpg', 100, 1000)]
    assert.equal(mergePicked(existing, [pick('a.jpg', 100, 1000)]).duplicates, 1)
    assert.equal(mergePicked(existing, [pick('a.jpg', 200, 1000)]).added.length, 1)
    assert.equal(mergePicked(existing, [pick('a.jpg', 100, 2000)]).added.length, 1)
    const sameBatch = mergePicked([], [pick('a.jpg', 100), pick('a.jpg', 100)])
    assert.equal(sameBatch.added.length, 1)
    assert.equal(sameBatch.duplicates, 1)
  })

  await t.test('非图片单独计数，不与重复混为一谈', () => {
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
})

test('合并后再校验上限：追加会让总数超限时能挡住', () => {
  const existing = Array.from({ length: 20 }, (_, index) => pick(`p${index}.jpg`, 1024))
  const { added } = mergePicked(existing, [pick('extra.jpg', 1024)])
  assert.equal(added.length, 1)
  assert.throws(() => validateBatch([...existing, ...added]), /一次最多 20 张/)
})

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

const source = await readFile(new URL('../../src/core/jpegComInjector.ts', import.meta.url), 'utf8')
const { readSegments, padWithComSegments, rewriteJfifDpi, readJfifDpi, prepareDelivery, MAX_COM_PAYLOAD } =
  await import(toModuleUrl(source))

/** 构造结构合法的最小 JPEG：段头、SOS 与 EOI 都可被解析，但不保证能解码出像素。 */
function buildJpeg({
  app0 = true,
  extraSegments = [],
  entropy = [0x11, 0x22, 0xff, 0x00, 0x33],
} = {}) {
  const bytes = [0xff, 0xd8]
  if (app0)
    bytes.push(
      0xff,
      0xe0,
      0x00,
      0x10,
      0x4a,
      0x46,
      0x49,
      0x46,
      0x00,
      0x01,
      0x01,
      0x01,
      0x00,
      0x48,
      0x00,
      0x48,
      0x00,
      0x00,
    )
  for (const segment of extraSegments) bytes.push(...segment)
  // DQT：长度字段 0x0043 = 67，含 65 字节量化表。
  bytes.push(0xff, 0xdb, 0x00, 0x43, ...new Array(65).fill(0x10))
  // SOF0：8 位精度、16×16、单分量。
  bytes.push(0xff, 0xc0, 0x00, 0x0b, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01, 0x01, 0x11, 0x00)
  // SOS 之后是熵编码数据，解析必须在此停止。
  bytes.push(0xff, 0xda, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3f, 0x00)
  bytes.push(...entropy)
  bytes.push(0xff, 0xd9)
  return Uint8Array.from(bytes)
}

/** 构造一个带指定文本载荷的 COM 段。 */
function comSegment(text) {
  const payload = [...text].map((character) => character.charCodeAt(0))
  const length = payload.length + 2
  return [0xff, 0xfe, (length >> 8) & 0xff, length & 0xff, ...payload]
}

test('解析 SOS 之前的段并在 SOS 处停止', () => {
  const segments = readSegments(buildJpeg())
  assert.deepEqual(
    segments.map((segment) => [segment.marker, segment.offset, segment.length]),
    [
      [0xe0, 2, 18],
      [0xdb, 20, 69],
      [0xc0, 89, 13],
    ],
  )
})

test('拒绝非 JPEG 与截断的数据', () => {
  assert.throws(() => readSegments(Uint8Array.from([0x89, 0x50, 0x4e, 0x47])), /有效的 JPEG/)
  assert.throws(() => readSegments(Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0xff])), /越界/)
})

test('COM 段补齐到目标体积且不改变尾部 EOI', () => {
  const base = buildJpeg()
  const result = padWithComSegments(base, base.length + 100)
  assert.equal(result.added, 100)
  assert.equal(result.satisfied, true)
  assert.equal(result.bytes.length, base.length + 100)
  assert.deepEqual([...result.bytes.subarray(-2)], [0xff, 0xd9])
  assert.doesNotThrow(() => readSegments(result.bytes))
})

test('大差额拆成多个 COM 段，单段载荷不超过上限', () => {
  const base = buildJpeg()
  const result = padWithComSegments(base, base.length + 100000)
  assert.equal(result.added, 100000)
  assert.equal(result.satisfied, true)
  const comSegments = readSegments(result.bytes).filter((segment) => segment.marker === 0xfe)
  assert.equal(comSegments.length, 2)
  for (const segment of comSegments) assert.ok(segment.length - 4 <= MAX_COM_PAYLOAD)
})

test('最小可补齐差额为 5 字节，4 字节时原样返回', () => {
  const base = buildJpeg()
  const padded = padWithComSegments(base, base.length + 5)
  assert.equal(padded.added, 5)
  assert.equal(padded.satisfied, true)
  assert.equal(padded.bytes.length, base.length + 5)

  const tooSmall = padWithComSegments(base, base.length + 4)
  assert.equal(tooSmall.added, 0)
  assert.equal(tooSmall.satisfied, false)
  assert.equal(tooSmall.bytes, base)
})

test('已达到或超过目标体积时不写入任何字节', () => {
  const base = buildJpeg()
  const result = padWithComSegments(base, base.length)
  assert.equal(result.added, 0)
  assert.equal(result.satisfied, true)
  assert.equal(result.bytes, base)
  assert.throws(() => padWithComSegments(base, 0), /正整数/)
})

test('补齐保留已有的 COM 段，并插在元数据之后', () => {
  const base = buildJpeg({ extraSegments: [comSegment('HELLO')] })
  const result = padWithComSegments(base, base.length + 100)
  const segments = readSegments(result.bytes)
  const original = segments.find((segment) => segment.marker === 0xfe)
  assert.ok(original)
  assert.equal(original.offset, 20)
  assert.equal(
    String.fromCharCode(...result.bytes.subarray(original.offset + 4, original.offset + 9)),
    'HELLO',
  )
  assert.equal(segments.filter((segment) => segment.marker === 0xfe).length, 2)
})

test('已有 JFIF 时原地改写 DPI，长度不变且不修改入参', () => {
  const base = buildJpeg()
  assert.equal(readJfifDpi(base), 72)
  const rewritten = rewriteJfifDpi(base, 300)
  assert.equal(rewritten.length, base.length)
  assert.equal(readJfifDpi(rewritten), 300)
  assert.equal(readJfifDpi(base), 72, '入参必须保持不变')
})

test('缺少 JFIF 时插入 APP0，并紧跟在 SOI 之后', () => {
  const base = buildJpeg({ app0: false })
  assert.equal(readJfifDpi(base), null)
  const rewritten = rewriteJfifDpi(base, 350)
  assert.equal(rewritten.length, base.length + 18)
  assert.equal(readJfifDpi(rewritten), 350)
  const first = readSegments(rewritten)[0]
  assert.equal(first.marker, 0xe0)
  assert.equal(first.offset, 2)
  assert.equal(readJfifDpi(base), null, '入参必须保持不变')
})

test('拒绝非法 DPI', () => {
  const base = buildJpeg()
  for (const dpi of [0, -1, 1201, 1.5, Number.NaN])
    assert.throws(() => rewriteJfifDpi(base, dpi), /DPI/)
})

test('交付前必须先写 DPI 再量体积：不带 APP0 的编码器会多 18 字节', () => {
  const target = 1000
  // 编码器返回的字节刚好等于上限——这正是最危险的情形。
  const encoded = new Uint8Array(target)
  encoded.set(buildJpeg({ app0: false }))

  // 编码器自带 APP0 时改写不改变长度，先判后写与先写后判等价。
  const withApp0 = new Uint8Array(target)
  withApp0.set(buildJpeg())
  assert.equal(prepareDelivery(withApp0, 300).length, target)

  // 不带 APP0 时：按编码长度判定会放行，但交付物实际超限 18 字节。
  assert.ok(encoded.length <= target, '旧的判定口径会放行')
  const delivered = prepareDelivery(encoded, 300)
  assert.equal(delivered.length, target + 18)
  assert.ok(delivered.length > target, '交付长度才是判定依据，必须判为超限')

  // 不写 DPI 时原样返回，不引入额外字节。
  assert.equal(prepareDelivery(encoded, null).length, target)
})

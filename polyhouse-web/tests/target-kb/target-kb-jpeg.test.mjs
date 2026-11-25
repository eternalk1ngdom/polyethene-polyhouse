import assert from 'node:assert/strict'
import test from 'node:test'
import { typeScriptModuleUrl } from '../helpers/test-runtime.mjs'

const { readSegments, padWithComSegments, rewriteJfifDpi, readJfifDpi, prepareDelivery, MAX_COM_PAYLOAD } =
  await import(
    await typeScriptModuleUrl(new URL('../../src/core/jpegComInjector.ts', import.meta.url))
  )

/** 构造结构合法的最小 JPEG：段头、SOS 与 EOI 都可被解析，但不保证能解码出像素。 */
function buildJpeg({
  app0 = true,
  extraSegments = [],
  entropy = [0x11, 0x22, 0xff, 0x00, 0x33],
} = {}) {
  const bytes = [0xff, 0xd8]
  if (app0)
    bytes.push(
      0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00,
      0x01, 0x01, 0x01, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00,
    )
  for (const segment of extraSegments) bytes.push(...segment)
  bytes.push(0xff, 0xdb, 0x00, 0x43, ...Array.from({ length: 65 }, () => 0x10))
  bytes.push(0xff, 0xc0, 0x00, 0x0b, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01, 0x01, 0x11, 0x00)
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

test('解析 SOS 之前的段并在 SOS 处停止，拒绝非 JPEG 与截断数据', () => {
  const segments = readSegments(buildJpeg())
  assert.deepEqual(
    segments.map((segment) => [segment.marker, segment.offset, segment.length]),
    [
      [0xe0, 2, 18],
      [0xdb, 20, 69],
      [0xc0, 89, 13],
    ],
  )
  assert.throws(() => readSegments(Uint8Array.from([0x89, 0x50, 0x4e, 0x47])), /有效的 JPEG/)
  assert.throws(() => readSegments(Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0xff])), /越界/)
})

test('padWithComSegments 覆盖普通补齐、大差额拆段、4/5 字节边界与零写入/非法目标', async (t) => {
  await t.test('普通补齐到目标体积且不破坏尾部 EOI', () => {
    const base = buildJpeg()
    const result = padWithComSegments(base, base.length + 100)
    assert.equal(result.added, 100)
    assert.equal(result.satisfied, true)
    assert.equal(result.bytes.length, base.length + 100)
    assert.deepEqual([...result.bytes.subarray(-2)], [0xff, 0xd9])
    assert.doesNotThrow(() => readSegments(result.bytes))
  })

  await t.test('大差额拆成多个 COM 段且每段载荷不超上限', () => {
    const base = buildJpeg()
    const result = padWithComSegments(base, base.length + 100000)
    assert.equal(result.added, 100000)
    assert.equal(result.satisfied, true)
    const comSegments = readSegments(result.bytes).filter((segment) => segment.marker === 0xfe)
    assert.equal(comSegments.length, 2)
    for (const segment of comSegments) assert.ok(segment.length - 4 <= MAX_COM_PAYLOAD)
  })

  await t.test('最小可补齐差额为 5 字节，4 字节时原样返回', () => {
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

  await t.test('已达到目标体积不写入任何字节，目标为 0 时拒绝', () => {
    const base = buildJpeg()
    const result = padWithComSegments(base, base.length)
    assert.equal(result.added, 0)
    assert.equal(result.satisfied, true)
    assert.equal(result.bytes, base)
    assert.throws(() => padWithComSegments(base, 0), /正整数/)
  })
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

test('rewriteJfifDpi 支持原地改写、缺 APP0 插入与非法 DPI 拒绝', async (t) => {
  await t.test('已有 JFIF 时原地改写 DPI，长度不变且不修改入参', () => {
    const base = buildJpeg()
    assert.equal(readJfifDpi(base), 72)
    const rewritten = rewriteJfifDpi(base, 300)
    assert.equal(rewritten.length, base.length)
    assert.equal(readJfifDpi(rewritten), 300)
    assert.equal(readJfifDpi(base), 72, '入参必须保持不变')
  })

  await t.test('缺少 JFIF 时插入 APP0 并紧跟 SOI 之后', () => {
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

  await t.test('拒绝非法 DPI', () => {
    const base = buildJpeg()
    for (const dpi of [0, -1, 1201, 1.5, Number.NaN]) {
      assert.throws(() => rewriteJfifDpi(base, dpi), /DPI/)
    }
  })
})

test('交付前必须先写 DPI 再量体积：不带 APP0 的编码器会多 18 字节', () => {
  const target = 1000
  const encoded = new Uint8Array(target)
  encoded.set(buildJpeg({ app0: false }))

  const withApp0 = new Uint8Array(target)
  withApp0.set(buildJpeg())
  assert.equal(prepareDelivery(withApp0, 300).length, target)

  assert.ok(encoded.length <= target)
  const delivered = prepareDelivery(encoded, 300)
  assert.equal(delivered.length, target + 18)
  assert.ok(delivered.length > target)
  assert.equal(prepareDelivery(encoded, null).length, target)
})

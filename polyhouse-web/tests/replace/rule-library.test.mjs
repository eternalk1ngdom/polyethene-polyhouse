import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

// 与现有测试一致：内存编译并执行真实模块，只替换模块地址，不替换被测逻辑。
async function moduleUrl(path, replacements = {}) {
  const source = await readFile(new URL(path, import.meta.url), 'utf8')
  let output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [dependency, url] of Object.entries(replacements))
    output = output.replaceAll(`from '${dependency}'`, `from '${url}'`)
  return `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`
}

const libraryUrl = await moduleUrl('../../src/utils/ruleLibrary.ts')
const storageUrl = await moduleUrl('../../src/stores/ruleLibraryStorage.ts', {
  '../utils/ruleLibrary': libraryUrl,
})
const {
  validateRuleLibrary,
  parseRuleLibrary,
  parseRuleLibraryBytes,
  serializeRuleLibrary,
  RULE_LIBRARY_MAX_BYTES,
} = await import(libraryUrl)
const { createRuleLibraryStorage, createBrowserRuleLibraryStorage, RULE_LIBRARY_STORAGE_KEY } =
  await import(storageUrl)
const { createTextReplacer } = await import(await moduleUrl('../../src/core/acReplacer.ts'))

test('备份文件严格按 UTF-8 解码，保留规则原文并支持 UTF-8 BOM', () => {
  const value = library(group())
  value.sets[0].rules = [
    { search: ' 小明😀\n', replace: '' },
    { search: 'constructor', replace: '__proto__ ' },
  ]
  const json = serializeRuleLibrary(value)
  for (const raw of [json, `\ufeff${json}`])
    assert.deepEqual(parseRuleLibraryBytes(new TextEncoder().encode(raw)), value)
  for (const bytes of [
    new Uint8Array([0xff]),
    new Uint8Array([0xe4, 0xb8]),
    new Uint8Array([0xc0, 0xaf]),
  ])
    assert.throws(() => parseRuleLibraryBytes(bytes), /UTF-8/u)
})

test('备份文件字节限额在解码前检查，恰达 256 KiB 可解析，超一字节拒绝', () => {
  const compact = serializeRuleLibrary(library(group()))
  const raw = compact.padEnd(
    compact.length + RULE_LIBRARY_MAX_BYTES - Buffer.byteLength(compact, 'utf8'),
    ' ',
  )
  assert.equal(new TextEncoder().encode(raw).byteLength, RULE_LIBRARY_MAX_BYTES)
  assert.deepEqual(parseRuleLibraryBytes(new TextEncoder().encode(raw)), library(group()))
  assert.throws(() => parseRuleLibraryBytes(new TextEncoder().encode(`${raw} `)), /256 KiB/u)
  assert.throws(
    () => parseRuleLibraryBytes(new Uint8Array(RULE_LIBRARY_MAX_BYTES + 1).fill(0xff)),
    /256 KiB/u,
  )
})

test('备份字节入口复用固定格式和容量保护，不接受脚本或非法结构', () => {
  const invalid = [
    '',
    '{',
    'alert(1)',
    '{"version":2,"sets":[]}',
    '{"version":1,"sets":[],"__proto__":{}}',
  ]
  for (const raw of invalid)
    assert.throws(() => parseRuleLibraryBytes(new TextEncoder().encode(raw)))
  assert.deepEqual(parseRuleLibraryBytes(new TextEncoder().encode('{"version":1,"sets":[]}')), {
    version: 1,
    sets: [],
  })
})

function group(id = 'one', name = '常用规则') {
  return {
    id,
    name,
    rules: [{ search: '小明', replace: '小红' }],
    createdAt: 1793462400000,
    updatedAt: 1793462400001,
  }
}

function library(...sets) {
  return { version: 1, sets: sets.length ? sets : [group()] }
}

function fixture(initial = null) {
  let raw = initial
  let reads = 0
  let writes = 0
  let failRead = false
  let failWrite = false
  const storage = {
    getItem(key) {
      assert.equal(key, RULE_LIBRARY_STORAGE_KEY)
      reads++
      if (failRead) throw new Error('private-read-error')
      return raw
    },
    setItem(key, value) {
      assert.equal(key, RULE_LIBRARY_STORAGE_KEY)
      if (failWrite) throw new Error('private-quota-error')
      writes++
      raw = value
    },
  }
  let tail = Promise.resolve()
  let active = 0
  let maxActive = 0
  // 所有客户端共享这把锁，真实冲突测试使用两个独立的存储实例。
  const withExclusiveLock = (action) => {
    const run = tail.then(() => {
      active++
      maxActive = Math.max(maxActive, active)
      try {
        return action()
      } finally {
        active--
      }
    })
    tail = run.catch(() => undefined)
    return run
  }
  const environment = { storage: () => storage, withExclusiveLock }
  return {
    storage,
    environment,
    client: () => createRuleLibraryStorage(environment),
    raw: () => raw,
    reads: () => reads,
    writes: () => writes,
    maxActive: () => maxActive,
    externalWrite: (value) => {
      raw = value
    },
    failRead: (value) => {
      failRead = value
    },
    failWrite: (value) => {
      failWrite = value
    },
  }
}

test('空库合法，中文/emoji/换行/删除规则往返一致且兼容原替换内核', () => {
  assert.deepEqual(parseRuleLibrary(serializeRuleLibrary({ version: 1, sets: [] })), {
    version: 1,
    sets: [],
  })
  const value = library(group())
  value.sets[0].rules = [
    { search: '小明', replace: '' },
    { search: '😀', replace: '🌸\n' },
    { search: 'constructor', replace: '__proto__' },
  ]
  const result = parseRuleLibrary(serializeRuleLibrary(value))
  assert.deepEqual(result, value)
  assert.deepEqual(createTextReplacer(result.sets[0].rules)('小明😀constructor'), {
    text: '🌸\n__proto__',
    count: 3,
    ruleCounts: [1, 1, 1],
    previewHits: [
      { start: 0, end: 0, ruleIndex: 0 },
      { start: 0, end: 3, ruleIndex: 1 },
      { start: 3, end: 12, ruleIndex: 2 },
    ],
  })
})

test('校验返回深冻结副本，调用方之后编辑不改变保存候选', () => {
  const source = library()
  const result = validateRuleLibrary(source)
  source.sets[0].rules[0].replace = '变更'
  assert.equal(result.sets[0].rules[0].replace, '小红')
  for (const value of [
    result,
    result.sets,
    result.sets[0],
    result.sets[0].rules,
    result.sets[0].rules[0],
  ])
    assert.equal(Object.isFrozen(value), true)
  assert.throws(() => {
    result.sets[0].name = '改写'
  }, TypeError)
})

test('格式版本、缺失/额外字段和特殊对象一律拒绝', () => {
  for (const value of [
    null,
    [],
    {},
    { version: 2, sets: [] },
    { version: 1, sets: null },
    { version: 1, sets: [], extra: true },
    new Date(),
    Object.create({ version: 1, sets: [] }),
  ])
    assert.throws(() => validateRuleLibrary(value))
  const value = library()
  delete value.sets[0].createdAt
  assert.throws(() => validateRuleLibrary(value))
  let invoked = false
  const getter = { version: 1 }
  Object.defineProperty(getter, 'sets', {
    get: () => {
      invoked = true
      return []
    },
  })
  assert.throws(() => validateRuleLibrary(getter))
  assert.equal(invoked, false)
})

test('危险对象键在每层被拒绝，但危险单词可作为普通规则文本', () => {
  for (const key of ['__proto__', 'prototype', 'constructor']) {
    for (const target of ['library', 'set', 'rule']) {
      const value = library()
      const destination =
        target === 'library' ? value : target === 'set' ? value.sets[0] : value.sets[0].rules[0]
      Object.defineProperty(destination, key, { enumerable: true, value: { polluted: true } })
      assert.throws(() => parseRuleLibrary(JSON.stringify(value)))
    }
  }
  assert.equal(Object.prototype.polluted, undefined)
})

test('组数 30 和规则 20 的边界通过，超限或空组规则拒绝', () => {
  const value = library(
    ...Array.from({ length: 30 }, (_, index) => group(`id-${index}`, `组${index}`)),
  )
  value.sets[0].rules = Array.from({ length: 20 }, (_, index) => ({
    search: `${index}`,
    replace: '',
  }))
  assert.equal(validateRuleLibrary(value).sets.length, 30)
  value.sets.push(group('extra', '额外'))
  assert.throws(() => validateRuleLibrary(value))
  const rules = library()
  rules.sets[0].rules = []
  assert.throws(() => validateRuleLibrary(rules))
  rules.sets[0].rules = Array.from({ length: 21 }, (_, index) => ({
    search: `${index}`,
    replace: '',
  }))
  assert.throws(() => validateRuleLibrary(rules))
})

test('名称去首尾空白，按大小写/NFC 判重，ID 单独校验唯一性', () => {
  assert.equal(validateRuleLibrary(library(group('id', '  Alpha  '))).sets[0].name, 'Alpha')
  for (const names of [
    ['Alpha', ' ALPHA '],
    ['é', 'e\u0301'],
  ])
    assert.throws(() => validateRuleLibrary(library(group('a', names[0]), group('b', names[1]))))
  for (const name of ['', '   ', '字'.repeat(33), '😀'.repeat(17)])
    assert.throws(() => validateRuleLibrary(library(group('id', name))))
  assert.equal(validateRuleLibrary(library(group('id', '😀'.repeat(16)))).sets.length, 1)
  assert.throws(() => validateRuleLibrary(library(group('same', '一'), group('same', '二'))))
  for (const id of ['', '__proto__', '../escape', 'a'.repeat(65), 'Key', 'ſet', 'id\n'])
    assert.throws(() => validateRuleLibrary(library(group(id))))
  assert.equal(validateRuleLibrary(library(group('a'.repeat(64)))).sets.length, 1)
})

test('规则保留字面空白、精确判重并执行 200 代码单元限制', () => {
  const value = library()
  value.sets[0].rules = [
    { search: ' x ', replace: '' },
    { search: 'x', replace: ' '.repeat(200) },
  ]
  assert.deepEqual(validateRuleLibrary(value).sets[0].rules, value.sets[0].rules)
  for (const rule of [
    { search: '', replace: '' },
    { search: 'x'.repeat(201), replace: '' },
    { search: 'x', replace: '😀'.repeat(101) },
    { search: 1, replace: '' },
    { search: 'x', replace: null },
    { search: '\ud800', replace: '' },
    { search: 'x', replace: '\udfff' },
  ]) {
    value.sets[0].rules = [rule]
    assert.throws(() => validateRuleLibrary(value))
  }
  value.sets[0].rules = [{ search: 'x'.repeat(200), replace: 'y'.repeat(200) }]
  assert.equal(validateRuleLibrary(value).sets[0].rules[0].search.length, 200)
  value.sets[0].rules.push({ ...value.sets[0].rules[0] })
  assert.throws(() => validateRuleLibrary(value))
})

test('时间必须为有效毫秒整数，更新时间不能倒退', () => {
  for (const time of [-1, NaN, Infinity, 0.5, '2026-11-01', 8.64e15 + 1]) {
    const value = library()
    value.sets[0].createdAt = time
    assert.throws(() => validateRuleLibrary(value))
  }
  const value = library()
  value.sets[0].updatedAt = value.sets[0].createdAt - 1
  assert.throws(() => validateRuleLibrary(value))
})

test('解析前拒绝损坏或超大 JSON，256 KiB 按实际 UTF-8 字节检查', () => {
  for (const raw of ['', '{', 'null', '[]', 'undefined']) assert.throws(() => parseRuleLibrary(raw))
  const raw = serializeRuleLibrary(library())
  const paddedLength =
    raw.length + RULE_LIBRARY_MAX_BYTES - new TextEncoder().encode(raw).byteLength
  assert.equal(parseRuleLibrary(raw.padEnd(paddedLength, ' ')).sets.length, 1)
  assert.throws(() => parseRuleLibrary(raw.padEnd(paddedLength + 1, ' ')))
  const value = library(
    ...Array.from({ length: 30 }, (_, index) => group(`id-${index}`, `组${index}`)),
  )
  for (const set of value.sets)
    set.rules = Array.from({ length: 20 }, (_, index) => ({
      search: `${index}`.padEnd(200, '字'),
      replace: '文'.repeat(200),
    }))
  const chinese = JSON.stringify(value)
  assert.ok(chinese.length < RULE_LIBRARY_MAX_BYTES)
  assert.ok(new TextEncoder().encode(chinese).byteLength > RULE_LIBRARY_MAX_BYTES)
  assert.throws(() => parseRuleLibrary(chinese))
  assert.throws(() => serializeRuleLibrary(value))
})

test('创建实例不访问浏览器，显式 load 后保存并跨实例读取', async () => {
  const f = fixture()
  const client = f.client()
  assert.equal(f.reads(), 0)
  assert.equal(client.snapshot().status, 'unloaded')
  assert.equal(client.load().status, 'ready')
  const result = await client.save(library())
  assert.equal(result.status, 'saved')
  assert.equal(result.persisted, true)
  assert.equal(result.busy, false)
  assert.equal(f.writes(), 1)
  assert.deepEqual(f.client().load().data, validateRuleLibrary(library()))
  assert.equal(Object.isFrozen(result), true)
})

test('未读取、读取权限失败或存储 getter 抛错时不覆盖旧数据', async () => {
  const original = serializeRuleLibrary(library())
  const f = fixture(original)
  const client = f.client()
  assert.equal((await client.save(library(group('two', '新组')))).status, 'memory-only')
  assert.equal(f.raw(), original)
  f.failRead(true)
  assert.equal(client.load(true).status, 'read-error')
  assert.equal((await client.save(library())).persisted, false)
  assert.equal(f.writes(), 0)
  const denied = createRuleLibraryStorage({
    storage: () => {
      throw new Error('private-getter-error')
    },
  })
  assert.equal(denied.load().status, 'read-error')
  const result = await denied.save(library())
  assert.equal(result.status, 'memory-only')
  assert.equal(result.message.includes('private-'), false)
})

test('损坏/未知版本存储原样保留；无静默重置或自动修复写入', async () => {
  for (const original of [
    '{broken',
    '{"version":2,"sets":[]}',
    '{"version":1,"sets":[],"constructor":{}}',
  ]) {
    const f = fixture(original)
    const client = f.client()
    assert.equal(client.load().status, 'invalid-storage')
    const result = await client.save(library())
    assert.equal(result.status, 'memory-only')
    assert.equal(result.persisted, false)
    assert.equal(result.data.sets.length, 1)
    assert.equal(f.raw(), original)
    assert.equal(f.writes(), 0)
  }
})

test('非法保存不改变已确认的内存/存储，错误不回显外部异常', async () => {
  const f = fixture(serializeRuleLibrary(library()))
  const client = f.client()
  const before = client.load()
  const hostile = new Proxy(
    {},
    {
      getPrototypeOf: () => {
        throw new Error('private-input-error')
      },
    },
  )
  for (const value of [null, { version: 1, sets: [group(), group()] }, hostile]) {
    const result = await client.save(value)
    assert.equal(result.status, 'invalid')
    assert.equal(result.persisted, true)
    assert.equal(result.data, before.data)
    assert.equal(result.message.includes('private-'), false)
  }
  assert.equal(f.writes(), 0)
})

test('配额失败保留内存候选与旧磁盘内容，解除故障可重试', async () => {
  const original = serializeRuleLibrary(library())
  const f = fixture(original)
  const client = f.client()
  client.load()
  f.failWrite(true)
  const candidate = library(group('two', '新组'))
  const result = await client.save(candidate)
  assert.equal(result.status, 'write-error')
  assert.equal(result.persisted, false)
  assert.deepEqual(result.data, validateRuleLibrary(candidate))
  assert.equal(f.raw(), original)
  assert.equal(result.message.includes('private-'), false)
  f.failWrite(false)
  assert.equal((await client.save(result.data)).status, 'saved')
  assert.equal(f.client().load().data.sets[0].id, 'two')
})

test('锁缺失或取得锁失败时仅保留内存，不冒充已保存', async () => {
  for (const lock of [
    undefined,
    async () => {
      throw new Error('private-lock-error')
    },
  ]) {
    const f = fixture()
    const client = createRuleLibraryStorage({ storage: () => f.storage, withExclusiveLock: lock })
    client.load()
    const result = await client.save(library())
    assert.equal(result.status, 'memory-only')
    assert.equal(result.persisted, false)
    assert.equal(result.data.sets.length, 1)
    assert.equal(f.writes(), 0)
    assert.equal(result.message.includes('private-'), false)
  }
})

test('保存前重新读取失败会停止写入并保留候选，故障解除可重试', async () => {
  const f = fixture()
  const client = f.client()
  client.load()
  f.failRead(true)
  assert.equal((await client.save(library())).status, 'read-error')
  assert.equal(f.writes(), 0)
  f.failRead(false)
  assert.equal((await client.save(client.snapshot().data)).status, 'saved')
})

test('两个实例同时保存：共享锁内复核，只有一个写入，另一份候选保留', async () => {
  const f = fixture()
  const first = f.client()
  const second = f.client()
  first.load()
  second.load()
  const [a, b] = await Promise.all([
    first.save(library(group('a', '第一份'))),
    second.save(library(group('b', '第二份'))),
  ])
  assert.equal(a.status, 'saved')
  assert.equal(b.status, 'conflict')
  assert.equal(b.persisted, false)
  assert.equal(b.data.sets[0].id, 'b')
  assert.equal(f.client().load().data.sets[0].id, 'a')
  assert.equal(f.writes(), 1)
  assert.equal(f.maxActive(), 1)
  assert.equal((await second.save(b.data)).status, 'conflict')
  assert.equal(f.writes(), 1)
})

test('外部删除或改成损坏数据同样判冲突，不因原库变空而静默覆盖', async () => {
  for (const next of [null, '{broken']) {
    const f = fixture(serializeRuleLibrary(library()))
    const client = f.client()
    client.load()
    f.externalWrite(next)
    assert.equal((await client.save(library(group('b', '第二份')))).status, 'conflict')
    assert.equal(f.raw(), next)
    assert.equal(f.writes(), 0)
  }
})

test('等待锁期间固定候选，重复保存或重载不替换进行中的操作', async () => {
  const f = fixture()
  let release
  const gate = new Promise((resolve) => {
    release = resolve
  })
  const client = createRuleLibraryStorage({
    storage: () => f.storage,
    withExclusiveLock: async (action) => {
      await gate
      action()
    },
  })
  client.load()
  const candidate = library()
  const saving = client.save(candidate)
  candidate.sets[0].rules[0].replace = '后改'
  assert.equal(client.snapshot().busy, true)
  assert.equal((await client.save(library(group('new', '新的')))).status, 'busy')
  const blocked = client.load(true)
  assert.equal(blocked.status, 'busy')
  assert.equal(Object.isFrozen(blocked), true)
  release()
  assert.equal((await saving).status, 'saved')
  assert.equal(f.client().load().data.sets[0].rules[0].replace, '小红')
})

test('重新读取须显式舍弃未保存候选，读取失败时仍不丢候选', async () => {
  const f = fixture()
  const client = f.client()
  client.load()
  f.failWrite(true)
  const candidate = (await client.save(library())).data
  assert.equal(client.load().status, 'unsaved')
  assert.equal(client.snapshot().data, candidate)
  f.failRead(true)
  assert.equal(client.load(true).status, 'read-error')
  assert.equal(client.snapshot().data, candidate)
  f.failRead(false)
  assert.equal(client.load(true).status, 'ready')
  assert.equal(client.snapshot().data.sets.length, 0)
  assert.equal(candidate.sets.length, 1)
})

test('清空以受锁保护的空库保存，旧页面不能恢复已清空的词库', async () => {
  const f = fixture(serializeRuleLibrary(library()))
  const current = f.client()
  const old = f.client()
  current.load()
  old.load()
  assert.equal((await current.save({ version: 1, sets: [] })).status, 'saved')
  assert.equal((await old.save(library())).status, 'conflict')
  assert.equal(f.client().load().data.sets.length, 0)
})

test('浏览器包装延迟读取存储，并使用固定同名 exclusive 锁', async () => {
  const storageDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  const navigatorDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  const f = fixture()
  const calls = []
  try {
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, get: () => f.storage })
    Object.defineProperty(globalThis, 'navigator', {
      configurable: true,
      value: {
        locks: {
          request: async (name, options, action) => {
            calls.push({ name, options })
            return action()
          },
        },
      },
    })
    const client = createBrowserRuleLibraryStorage()
    assert.equal(f.reads(), 0)
    client.load()
    assert.equal((await client.save(library())).status, 'saved')
    assert.deepEqual(calls, [{ name: RULE_LIBRARY_STORAGE_KEY, options: { mode: 'exclusive' } }])
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {} })
    assert.equal((await client.save(library(group('next', '下一组')))).status, 'memory-only')
    assert.equal(f.writes(), 1)
  } finally {
    if (storageDescriptor) Object.defineProperty(globalThis, 'localStorage', storageDescriptor)
    else delete globalThis.localStorage
    if (navigatorDescriptor) Object.defineProperty(globalThis, 'navigator', navigatorDescriptor)
    else delete globalThis.navigator
  }
})

import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, nextTick, ref } from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'

const url = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
function compile(source, replacements = {}) {
  let output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(replacements))
    output = output.replaceAll(`from '${from}'`, `from '${to}'`)
  return url(output)
}
const pureUrl = compile(
  await readFile(new URL('../../src/utils/ruleLibrary.ts', import.meta.url), 'utf8'),
)
const storageUrl = compile(
  await readFile(new URL('../../src/stores/ruleLibraryStorage.ts', import.meta.url), 'utf8'),
  { '../utils/ruleLibrary': pureUrl },
)
const managerUrl = compile(
  await readFile(
    new URL('../../src/composables/useRuleLibraryManager.ts', import.meta.url),
    'utf8',
  ),
  {
    vue: import.meta.resolve('vue'),
    '../utils/ruleLibrary': pureUrl,
    '../stores/ruleLibraryStorage': storageUrl,
  },
)
const { createRuleLibraryStorage } = await import(storageUrl)
const { useRuleLibraryManager } = await import(managerUrl)
const { parseRuleLibrary, RULE_LIBRARY_MAX_BYTES } = await import(pureUrl)
const rules = (replace = '小红') => [{ search: '小明', replace }]
const importBytes = (data = { version: 1, sets: [] }) =>
  new TextEncoder().encode(JSON.stringify(data))
const importData = () => ({
  version: 1,
  sets: [
    {
      id: 'restored-id',
      name: '备份😀',
      rules: [
        { search: ' 小明😀\n', replace: '' },
        { search: 'constructor', replace: '__proto__ ' },
      ],
      createdAt: 20,
      updatedAt: 30,
    },
  ],
})
const importFile = (data = importData()) =>
  new File([JSON.stringify(data)], '备份😀.json', { type: 'application/json' })

function fixture() {
  let raw = null
  let failWrite = false
  let failRead = false
  let id = 0
  let now = 100
  let writes = 0
  let tail = Promise.resolve()
  const environment = {
    storage: () => ({
      getItem: () => {
        if (failRead) throw new Error('fixture read failure')
        return raw
      },
      setItem: (_key, value) => {
        if (failWrite) throw new Error('fixture quota')
        raw = value
        writes++
      },
    }),
    withExclusiveLock: (action) => {
      const run = tail.then(action)
      tail = run.catch(() => undefined)
      return run
    },
  }
  const services = () => ({
    repository: createRuleLibraryStorage(environment),
    createId: () => `test-${++id}`,
    now: () => now,
  })
  return {
    services,
    manager: () => useRuleLibraryManager(services()),
    raw: () => raw,
    failWrite: (value) => {
      failWrite = value
    },
    failRead: (value) => {
      failRead = value
    },
    clock: (value) => {
      now = value
    },
    writes: () => writes,
    rawValue: (value) => {
      raw = value
    },
  }
}

test('保存/读取独立词库，校验失败不改原有数据', async () => {
  const f = fixture()
  const manager = f.manager()
  assert.equal(await manager.saveRules('第一组', [{ search: '', replace: '' }]), false)
  assert.equal(f.writes(), 0)
  assert.equal(await manager.saveRules('第一组', rules()), true)
  assert.equal(manager.state.value.persisted, true)
  assert.equal(manager.selectedId.value, parseRuleLibrary(f.raw()).sets[0].id)
  assert.equal(f.manager().state.value.data.sets[0].name, '第一组')
  const before = f.raw()
  assert.equal(await manager.saveRules(' ', rules()), false)
  assert.equal(f.raw(), before)
})

test('同名覆盖须明确匹配目标，保留原 ID/创建时间并防止更新时间倒退', async () => {
  const f = fixture()
  const manager = f.manager()
  await manager.saveRules('Alpha', rules())
  const original = manager.state.value.data.sets[0]
  assert.equal(await manager.saveRules(' ALPHA ', rules('别的')), false)
  assert.equal(await manager.saveRules('ALPHA', rules('别的'), 'wrong-id'), false)
  assert.equal(await manager.saveRules('不同名', rules('别的'), original.id), false)
  f.clock(10)
  assert.equal(await manager.saveRules('ALPHA', rules('别的'), original.id), true)
  const saved = manager.state.value.data.sets[0]
  assert.equal(saved.id, original.id)
  assert.equal(saved.createdAt, original.createdAt)
  assert.equal(saved.updatedAt, original.updatedAt)
  assert.equal(saved.rules[0].replace, '别的')
})

test('重命名保留规则，重名或失效目标拒绝', async () => {
  const f = fixture()
  const manager = f.manager()
  await manager.saveRules('一', rules())
  const one = manager.selectedId.value
  await manager.saveRules('二', rules('第二组'))
  assert.equal(await manager.rename(one, ' 二 '), false)
  assert.equal(await manager.rename('missing', '失效'), false)
  assert.equal(await manager.rename(one, '新名'), true)
  assert.equal(manager.state.value.data.sets.find((set) => set.id === one).rules[0].replace, '小红')
  assert.equal(manager.selectedId.value, one)
})

test('复制生成不同 ID 与唯一副本名，32 位 emoji 名不会被切断', async () => {
  const f = fixture()
  const manager = f.manager()
  await manager.saveRules('😀'.repeat(16), rules())
  const originalId = manager.selectedId.value
  assert.equal(await manager.copy(originalId), true)
  assert.equal(await manager.copy(originalId), true)
  const sets = manager.state.value.data.sets
  assert.equal(new Set(sets.map((set) => set.id)).size, 3)
  assert.equal(new Set(sets.map((set) => set.name)).size, 3)
  assert.match(sets[1].name, /副本$/u)
  assert.match(sets[2].name, /副本 2$/u)
  assert.ok(sets.every((set) => set.name.length <= 32))
  assert.deepEqual(parseRuleLibrary(f.raw()).sets[2].rules, sets[0].rules)
})

test('删除最后一组和整库清除可持久化，失效 ID 不冒充删除成功', async () => {
  const f = fixture()
  const manager = f.manager()
  await manager.saveRules('一', rules())
  const id = manager.selectedId.value
  assert.equal(await manager.remove('missing'), false)
  assert.equal(await manager.remove(id), true)
  assert.equal(manager.selectedId.value, '')
  assert.equal(parseRuleLibrary(f.raw()).sets.length, 0)
  await manager.saveRules('二', rules())
  await manager.copy(manager.selectedId.value)
  assert.equal(await manager.clear(), true)
  assert.equal(f.manager().state.value.data.sets.length, 0)
})

test('写入失败保留候选，阻止叠加修改，重试成功才解除未保存状态', async () => {
  const f = fixture()
  const manager = f.manager()
  f.failWrite(true)
  assert.equal(await manager.saveRules('未保存', rules()), false)
  const candidate = manager.state.value.data
  assert.equal(manager.hasUnsaved.value, true)
  assert.equal(await manager.rename(manager.selectedId.value, '不应叠加'), false)
  assert.equal(manager.state.value.data, candidate)
  assert.equal(manager.reload(), false)
  assert.equal(manager.state.value.data, candidate)
  f.failWrite(false)
  assert.equal(await manager.retry(), true)
  assert.equal(manager.hasUnsaved.value, false)
  assert.equal(parseRuleLibrary(f.raw()).sets[0].name, '未保存')
})

test('两个管理实例陈旧保存被拒绝，候选保留，确认重读后方可继续', async () => {
  const f = fixture()
  const first = f.manager()
  const second = f.manager()
  assert.equal(await first.saveRules('先保存', rules()), true)
  assert.equal(await second.saveRules('迟到保存', rules('另一个')), false)
  assert.equal(second.state.value.status, 'conflict')
  assert.equal(second.state.value.data.sets[0].name, '迟到保存')
  assert.equal(await second.saveRules('继续修改', rules()), false)
  f.failRead(true)
  assert.equal(second.reload(true), false)
  assert.equal(second.hasUnsaved.value, true)
  f.failRead(false)
  assert.equal(second.reload(true), true)
  assert.equal(second.state.value.data.sets[0].name, '先保存')
  assert.equal(await second.saveRules('新追加', rules()), true)
  assert.equal(parseRuleLibrary(f.raw()).sets.length, 2)
})

test('导出重读其他页面最新整库，保留顺序、元数据和 Unicode，且不写入', async () => {
  const f = fixture()
  const first = f.manager()
  const stale = f.manager()
  const special = [
    { search: ' 小明😀\n', replace: '' },
    { search: 'constructor', replace: '__proto__\n ' },
  ]
  await first.saveRules('中文😀', special)
  await first.saveRules('第二组', rules())
  const original = f.raw()
  const writes = f.writes()
  f.clock(new Date(2026, 10, 2, 12).getTime())
  const backup = stale.prepareExport()
  assert.equal(backup.filename, '妙替词库-20261102.json')
  assert.equal(backup.json, original)
  assert.deepEqual(parseRuleLibrary(backup.json), first.state.value.data)
  assert.equal(backup.setCount, 2)
  assert.equal(backup.unsaved, false)
  assert.equal(stale.selectedId.value, first.state.value.data.sets[0].id)
  assert.equal(f.raw(), original)
  assert.equal(f.writes(), writes)
})

test('有效空库可导出，坏存储、未知版本和读取失败不生成空备份且不覆盖原文', () => {
  const f = fixture()
  const manager = f.manager()
  assert.deepEqual(parseRuleLibrary(manager.prepareExport().json), { version: 1, sets: [] })
  for (const raw of ['{', '{"version":2,"sets":[]}']) {
    f.rawValue(raw)
    assert.equal(manager.prepareExport(), undefined)
    assert.equal(f.raw(), raw)
    assert.equal(manager.state.value.status, 'invalid-storage')
  }
  f.failRead(true)
  assert.equal(manager.prepareExport(), undefined)
  assert.equal(f.writes(), 0)
  assert.equal(f.manager().prepareExport(), undefined)
})

test('原始 UTF-8 恰达 256 KiB 仍可导出紧凑可解析备份，超一字节拒绝且保留原文', async () => {
  const f = fixture()
  const manager = f.manager()
  await manager.saveRules('中文😀', rules())
  const compact = f.raw()
  const padded = compact.padEnd(
    compact.length + RULE_LIBRARY_MAX_BYTES - Buffer.byteLength(compact, 'utf8'),
    ' ',
  )
  assert.equal(Buffer.byteLength(padded, 'utf8'), RULE_LIBRARY_MAX_BYTES)
  f.rawValue(padded)
  const backup = manager.prepareExport()
  assert.equal(backup.json, compact)
  assert.deepEqual(parseRuleLibrary(backup.json), parseRuleLibrary(padded))
  assert.equal(f.raw(), padded)
  f.rawValue(`${padded} `)
  assert.equal(manager.prepareExport(), undefined)
  assert.equal(f.raw(), `${padded} `)
})

test('保存失败候选仅在明确确认时可导出，不重读、不消除未保存状态，可随后重试', async () => {
  const f = fixture()
  const manager = f.manager()
  await manager.saveRules('已保存', rules())
  const original = f.raw()
  f.failWrite(true)
  await manager.saveRules('未保存', rules('候选'))
  const snapshot = manager.state.value
  f.failRead(true)
  assert.equal(manager.prepareExport(), undefined)
  const backup = manager.prepareExport(true)
  assert.match(backup.filename, /-未保存候选\.json$/u)
  assert.equal(backup.unsaved, true)
  assert.deepEqual(parseRuleLibrary(backup.json), snapshot.data)
  assert.equal(manager.state.value, snapshot)
  assert.equal(manager.hasUnsaved.value, true)
  assert.equal(f.raw(), original)
  f.failRead(false)
  f.failWrite(false)
  assert.equal(await manager.retry(), true)
  assert.equal(manager.prepareExport(true), undefined)
})

test('冲突候选可独立备份，不能覆盖另一页面的已保存版本', async () => {
  const f = fixture()
  const first = f.manager()
  const stale = f.manager()
  await first.saveRules('其他页面', rules())
  await stale.saveRules('本页候选', rules('不同'))
  const raw = f.raw()
  const backup = stale.prepareExport(true)
  assert.equal(parseRuleLibrary(backup.json).sets[0].name, '本页候选')
  assert.equal(stale.state.value.status, 'conflict')
  assert.equal(stale.hasUnsaved.value, true)
  assert.equal(f.raw(), raw)
  stale.pending.value = true
  assert.equal(stale.prepareExport(true), undefined)
})

test('整库导入替换 30 组现有内容，保留备份 ID/时间/顺序/原文，往返逐字一致', async () => {
  const f = fixture()
  const manager = f.manager()
  for (let index = 0; index < 30; index++) await manager.saveRules(`原词库${index}`, rules())
  const original = f.raw()
  const writes = f.writes()
  const data = importData()
  data.sets.push({ ...data.sets[0], id: 'another-id', name: '第二组', rules: rules('第二组') })
  const preview = manager.prepareImport(importBytes(data))
  assert.equal(preview.current.sets.length, 30)
  assert.equal(f.raw(), original)
  assert.equal(f.writes(), writes)
  data.sets[0].rules[0].replace = '读取后外部修改'
  assert.equal(preview.data.sets[0].rules[0].replace, '')
  assert.equal(await manager.importLibrary(preview), true)
  assert.equal(f.writes(), writes + 1)
  assert.deepEqual(parseRuleLibrary(f.raw()), preview.data)
  assert.equal(manager.prepareExport().json, f.raw())
  assert.equal(manager.selectedId.value, 'restored-id')
  assert.equal(await manager.importLibrary(preview), false)
  assert.equal(f.writes(), writes + 1)
})

test('有效空备份只在确认时一次清空，初始空存储也能恢复备份', async () => {
  const f = fixture()
  const manager = f.manager()
  assert.equal(await manager.importLibrary(manager.prepareImport(importBytes(importData()))), true)
  const before = f.raw()
  const preview = manager.prepareImport(importBytes())
  assert.equal(f.raw(), before)
  assert.equal(await manager.importLibrary(preview), true)
  assert.deepEqual(parseRuleLibrary(f.raw()), { version: 1, sets: [] })
  assert.equal(manager.selectedId.value, '')
})

test('非法备份整份拒绝，不改内存内容或持久化，不将内部重复自动合并/改名', async () => {
  const f = fixture()
  const manager = f.manager()
  await manager.saveRules('现有', rules())
  const original = f.raw()
  const candidate = manager.state.value.data
  const repeatedId = importData()
  repeatedId.sets.push({ ...repeatedId.sets[0], name: '另一个' })
  const repeatedName = importData()
  repeatedName.sets.push({ ...repeatedName.sets[0], id: 'other-id' })
  const repeatedSearch = importData()
  repeatedSearch.sets[0].rules.push({ ...repeatedSearch.sets[0].rules[0] })
  const tooMany = {
    version: 1,
    sets: Array.from({ length: 31 }, (_, index) => ({
      ...importData().sets[0],
      id: `id${index}`,
      name: `组${index}`,
    })),
  }
  for (const bytes of [
    new Uint8Array([0xff]),
    importBytes({ version: 2, sets: [] }),
    importBytes(repeatedId),
    importBytes(repeatedName),
    importBytes(repeatedSearch),
    importBytes(tooMany),
  ]) {
    assert.equal(manager.prepareImport(bytes), undefined)
    assert.equal(manager.state.value.data, candidate)
    assert.equal(f.raw(), original)
  }
  assert.equal(f.writes(), 1)
})

test('未保存候选、取消和本页状态变化均不能沿用旧导入确认', async () => {
  const f = fixture()
  const manager = f.manager()
  const cancelled = manager.prepareImport(importBytes(importData()))
  manager.cancelImport()
  assert.equal(await manager.importLibrary(cancelled), false)
  const stale = manager.prepareImport(importBytes(importData()))
  manager.reload()
  assert.equal(await manager.importLibrary(stale), false)
  manager.pending.value = true
  assert.equal(manager.prepareImport(importBytes(importData())), undefined)
  manager.pending.value = false
  f.failWrite(true)
  await manager.saveRules('未保存候选', rules())
  const candidate = manager.state.value.data
  assert.equal(manager.prepareImport(importBytes(importData())), undefined)
  assert.equal(manager.state.value.data, candidate)
  assert.equal(manager.hasUnsaved.value, true)
  assert.equal(f.raw(), null)
})

test('导入确认后其他页已保存则冲突，不静默重读覆盖，导入候选可备份', async () => {
  const f = fixture()
  const manager = f.manager()
  const preview = manager.prepareImport(importBytes(importData()))
  const other = f.manager()
  await other.saveRules('其他页的新数据', rules())
  const original = f.raw()
  assert.equal(await manager.importLibrary(preview), false)
  assert.equal(manager.state.value.status, 'conflict')
  assert.equal(manager.hasUnsaved.value, true)
  assert.equal(f.raw(), original)
  assert.deepEqual(parseRuleLibrary(manager.prepareExport(true).json), preview.data)
  assert.equal(await manager.retry(), false)
  assert.equal(f.raw(), original)
})

test('导入写入故障保留完整候选，重试成功后恢复；无锁环境不冒充已保存', async () => {
  const f = fixture()
  const manager = f.manager()
  const preview = manager.prepareImport(importBytes(importData()))
  f.failWrite(true)
  assert.equal(await manager.importLibrary(preview), false)
  assert.equal(manager.hasUnsaved.value, true)
  assert.deepEqual(manager.state.value.data, preview.data)
  assert.equal(f.raw(), null)
  f.failWrite(false)
  assert.equal(await manager.retry(), true)
  assert.deepEqual(parseRuleLibrary(f.raw()), preview.data)
  let writes = 0
  const noLock = useRuleLibraryManager({
    repository: createRuleLibraryStorage({
      storage: () => ({ getItem: () => null, setItem: () => writes++ }),
    }),
    createId: () => 'unused',
    now: () => 100,
  })
  assert.equal(await noLock.importLibrary(noLock.prepareImport(importBytes(importData()))), false)
  assert.equal(noLock.state.value.status, 'memory-only')
  assert.equal(noLock.hasUnsaved.value, true)
  assert.equal(writes, 0)
})

test('本机损坏/未知版本和读取失败均不能准备导入，不绕过坏存储保护', () => {
  for (const raw of ['{', '{"version":2,"sets":[]}']) {
    const f = fixture()
    f.rawValue(raw)
    const manager = f.manager()
    assert.equal(manager.prepareImport(importBytes(importData())), undefined)
    assert.equal(f.raw(), raw)
    assert.equal(f.writes(), 0)
  }
  const f = fixture()
  f.failRead(true)
  assert.equal(f.manager().prepareImport(importBytes(importData())), undefined)
  assert.equal(f.writes(), 0)
})

// 真实 SFC setup 的行为测试；DOM、鼠标和焦点另由真实浏览器验收。
const managerFixtureUrl = url(
  'export let current; export const setCurrent = (value) => { current = value }; export const useRuleLibraryManager = () => current',
)
const hook = await import(managerFixtureUrl)
const controls = url(
  'export const ElButton={}, ElCheckbox={}, ElDialog={}, ElInput={}, ElOption={}, ElSelect={}',
)
const { descriptor } = parse(
  await readFile(new URL('../../src/components/RuleLibraryPanel.vue', import.meta.url), 'utf8'),
)
const { default: component } = await import(
  compile(compileScript(descriptor, { id: 'rule-library-panel-test' }).content, {
    vue: import.meta.resolve('vue'),
    'element-plus': controls,
    '../utils/ruleLibrary': pureUrl,
    '../composables/useRuleLibraryManager': managerFixtureUrl,
  })
)
const renderer = createRenderer({
  createElement: () => ({}),
  createText: () => ({}),
  createComment: () => ({}),
  insert() {},
  remove() {},
  setText() {},
  setElementText() {},
  patchProp() {},
  parentNode: () => null,
  nextSibling: () => null,
})
function mount(manager, initialRules = rules('编辑中')) {
  hook.setCurrent(manager)
  const current = ref(initialRules)
  const emitted = []
  let page
  const child = {
    ...component,
    setup(props, context) {
      page = component.setup(props, context)
      return () => null
    },
  }
  const app = renderer.createApp({
    setup: () => () => h(child, { rules: current.value, onLoad: (value) => emitted.push(value) }),
  })
  app.mount({})
  return { page, current, emitted, unmount: () => app.unmount() }
}

test('读取备份只预览，取消不写；再次选择并重复确认只写一次，不载入编辑规则', async (t) => {
  const f = fixture()
  const manager = f.manager()
  await manager.saveRules('旧词库', rules())
  const original = f.raw()
  const view = mount(manager)
  t.after(view.unmount)
  await view.page.readImportFile(importFile())
  assert.equal(view.page.mode.value, 'import')
  assert.equal(view.page.dialogOpen.value, true)
  assert.equal(view.page.importPreview.value.current.sets.length, 1)
  assert.equal(f.raw(), original)
  view.page.dialogOpen.value = false
  await view.page.confirm()
  assert.equal(f.raw(), original)
  await view.page.readImportFile(importFile())
  const first = view.page.confirm()
  await view.page.confirm()
  await first
  assert.equal(f.writes(), 2)
  assert.deepEqual(parseRuleLibrary(f.raw()), importData())
  assert.equal(view.emitted.length, 0)
  assert.deepEqual(view.current.value, rules('编辑中'))
  assert.match(view.page.feedback.value, /已从备份恢复 1 组/u)
})

test('文件过大在读取前拒绝，不打开确认或调用 arrayBuffer', async (t) => {
  const f = fixture()
  const view = mount(f.manager())
  t.after(view.unmount)
  let reads = 0
  await view.page.readImportFile({
    name: 'large.json',
    size: RULE_LIBRARY_MAX_BYTES + 1,
    arrayBuffer: async () => {
      reads++
      return new ArrayBuffer(0)
    },
  })
  assert.equal(reads, 0)
  assert.equal(view.page.dialogOpen.value, false)
  assert.match(view.page.issue.value, /256 KiB/u)
  assert.equal(f.writes(), 0)
})

test('文件读取异常和非法 JSON 不生成确认候选，确认无写入', async (t) => {
  const f = fixture()
  const manager = f.manager()
  const view = mount(manager)
  t.after(view.unmount)
  await view.page.readImportFile({
    name: 'failed.json',
    size: 2,
    arrayBuffer: async () => {
      throw new Error('fixture read failure')
    },
  })
  assert.match(manager.issue.value, /无法读取/u)
  assert.equal(view.page.importPreview.value, undefined)
  await view.page.confirm()
  assert.equal(f.writes(), 0)
  view.page.dialogOpen.value = false
  await view.page.readImportFile(new File(['{'], 'bad.json'))
  assert.equal(view.page.importPreview.value, undefined)
  await view.page.confirm()
  assert.equal(f.writes(), 0)
  assert.equal(view.page.importReading.value, false)
})

test('读取期间取消后可选择新文件，旧读取迟到不能替换新预览或写入', async (t) => {
  const f = fixture()
  const view = mount(f.manager())
  t.after(view.unmount)
  let resolve
  const reading = view.page.readImportFile({
    name: 'old.json',
    size: 1,
    arrayBuffer: () =>
      new Promise((done) => {
        resolve = done
      }),
  })
  assert.equal(view.page.importReading.value, true)
  await view.page.confirm()
  assert.equal(f.writes(), 0)
  view.page.dialogOpen.value = false
  assert.equal(view.page.importReading.value, false)
  await view.page.readImportFile(importFile())
  const preview = view.page.importPreview.value
  resolve(importBytes().buffer)
  await reading
  assert.equal(view.page.importPreview.value, preview)
  assert.equal(view.page.importFilename.value, '备份😀.json')
  assert.equal(f.writes(), 0)
})

test('卸载使未完成的文件读取失效，不准备导入或写存储', async () => {
  const f = fixture()
  const view = mount(f.manager())
  let resolve
  const reading = view.page.readImportFile({
    name: 'pending.json',
    size: 1,
    arrayBuffer: () =>
      new Promise((done) => {
        resolve = done
      }),
  })
  view.unmount()
  resolve(importBytes(importData()).buffer)
  await reading
  assert.equal(view.page.importPreview.value, undefined)
  assert.equal(f.writes(), 0)
})

test('导入保存失败保持确认弹窗与候选，重试成功才关闭', async (t) => {
  const f = fixture()
  const manager = f.manager()
  const view = mount(manager)
  t.after(view.unmount)
  await view.page.readImportFile(importFile())
  f.failWrite(true)
  await view.page.confirm()
  assert.equal(view.page.dialogOpen.value, true)
  assert.equal(manager.hasUnsaved.value, true)
  assert.equal(view.page.feedback.value, '')
  assert.deepEqual(manager.state.value.data, importData())
  f.failWrite(false)
  await view.page.retry()
  assert.equal(view.page.dialogOpen.value, false)
  assert.equal(manager.state.value.persisted, true)
})

// 只替换浏览器下载边界，组件 setup 与管理/校验层仍执行真实实现。
function downloadFixture(t, fail = false) {
  const blobs = []
  const links = []
  const revoked = []
  t.mock.timers.enable({ apis: ['setTimeout'] })
  t.mock.method(URL, 'createObjectURL', (blob) => {
    blobs.push(blob)
    return 'blob:rule-library-test'
  })
  t.mock.method(URL, 'revokeObjectURL', (url) => revoked.push(url))
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'document')
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: {
      createElement: () => {
        const link = {
          href: '',
          download: '',
          click() {
            if (fail) throw new Error('fixture download denied')
            links.push({ href: this.href, download: this.download })
          },
        }
        return link
      },
    },
  })
  t.after(() => {
    if (previous) Object.defineProperty(globalThis, 'document', previous)
    else delete globalThis.document
  })
  return {
    blobs,
    links,
    revoked,
    fail: (value) => {
      fail = value
    },
  }
}

test('正常下载完整 JSON，不载入规则或写存储，提示仅已发起下载并释放临时地址', async (t) => {
  const f = fixture()
  const manager = f.manager()
  await manager.saveRules('备份', rules())
  const view = mount(manager)
  t.after(view.unmount)
  const download = downloadFixture(t)
  const raw = f.raw()
  const writes = f.writes()
  view.page.exportLibrary()
  assert.equal(download.links.length, 1)
  assert.equal(download.blobs[0].type, 'application/json;charset=utf-8')
  assert.equal(await download.blobs[0].text(), raw)
  assert.equal(view.emitted.length, 0)
  assert.deepEqual(view.current.value, rules('编辑中'))
  assert.equal(f.writes(), writes)
  assert.match(view.page.feedback.value, /已发起 1 组/u)
  assert.match(view.page.feedback.value, /请确认文件已保存/u)
  assert.equal(download.revoked.length, 0)
  t.mock.timers.tick(1000)
  assert.deepEqual(download.revoked, ['blob:rule-library-test'])
})

test('候选导出先确认，取消不下载，确认后仍保持未保存状态及父页面编辑', async (t) => {
  const f = fixture()
  const manager = f.manager()
  f.failWrite(true)
  await manager.saveRules('候选', rules())
  const view = mount(manager)
  t.after(view.unmount)
  const download = downloadFixture(t)
  view.page.exportLibrary()
  assert.equal(view.page.mode.value, 'export')
  assert.equal(view.page.dialogOpen.value, true)
  assert.equal(download.links.length, 0)
  view.page.dialogOpen.value = false
  await view.page.confirm()
  assert.equal(download.links.length, 0)
  view.page.exportLibrary()
  await view.page.confirm()
  assert.equal(download.links.length, 1)
  assert.match(download.links[0].download, /-未保存候选\.json$/u)
  assert.equal(view.page.dialogOpen.value, false)
  assert.equal(manager.hasUnsaved.value, true)
  assert.equal(manager.state.value.persisted, false)
  assert.equal(f.raw(), null)
  assert.equal(view.emitted.length, 0)
  t.mock.timers.tick(1000)
})

test('下载边界失败保留候选和确认弹窗，可重试，且释放已创建的临时地址', async (t) => {
  const f = fixture()
  const manager = f.manager()
  f.failWrite(true)
  await manager.saveRules('候选', rules())
  const view = mount(manager)
  t.after(view.unmount)
  const download = downloadFixture(t, true)
  view.page.exportLibrary()
  await view.page.confirm()
  assert.equal(view.page.dialogOpen.value, true)
  assert.equal(download.links.length, 0)
  assert.equal(view.page.feedback.value, '')
  assert.match(manager.issue.value, /无法发起词库下载/u)
  assert.equal(manager.hasUnsaved.value, true)
  assert.equal(f.raw(), null)
  t.mock.timers.tick(1000)
  assert.deepEqual(download.revoked, ['blob:rule-library-test'])
  download.fail(false)
  await view.page.confirm()
  assert.equal(download.links.length, 1)
  assert.equal(view.page.dialogOpen.value, false)
  assert.equal(manager.hasUnsaved.value, true)
  assert.equal(f.raw(), null)
  t.mock.timers.tick(1000)
  assert.equal(download.revoked.length, 2)
})

test('载入不同的非空规则先确认，取消不发事件，确认发可编辑副本', async (t) => {
  const f = fixture()
  const manager = f.manager()
  await manager.saveRules('本机', rules())
  const view = mount(manager)
  t.after(view.unmount)
  view.page.loadSelected()
  assert.equal(view.page.dialogOpen.value, true)
  assert.equal(view.emitted.length, 0)
  view.page.dialogOpen.value = false
  assert.equal(view.emitted.length, 0)
  view.page.loadSelected()
  await view.page.confirm()
  assert.deepEqual(view.emitted[0], rules())
  view.emitted[0][0].replace = '编辑'
  assert.equal(manager.state.value.data.sets[0].rules[0].replace, '小红')
})

test('空规则载入无需覆盖确认，同名保存未勾选不写入', async (t) => {
  const f = fixture()
  const manager = f.manager()
  await manager.saveRules('本机', rules())
  const view = mount(manager, [{ search: '', replace: '' }])
  t.after(view.unmount)
  view.page.loadSelected()
  assert.equal(view.emitted.length, 1)
  view.page.open('save')
  view.page.name.value = '本机'
  await nextTick()
  const before = f.writes()
  await view.page.confirm()
  assert.equal(f.writes(), before)
  assert.match(manager.issue.value, /勾选确认覆盖/u)
  view.page.overwriteConfirmed.value = true
  // 空查找仍由底层校验拒绝，覆盖确认不能绕过规则合法性。
  await view.page.confirm()
  assert.equal(f.writes(), before)
  view.current.value = rules('更新')
  await nextTick()
  await view.page.confirm()
  assert.equal(manager.state.value.data.sets[0].rules[0].replace, '更新')
})

test('保存失败时表单保留，关闭不宣称成功，重试持久化后才能结束', async (t) => {
  const f = fixture()
  const manager = f.manager()
  const view = mount(manager)
  t.after(view.unmount)
  view.page.open('save')
  view.page.name.value = '失败保留'
  await nextTick()
  f.failWrite(true)
  await view.page.confirm()
  assert.equal(view.page.dialogOpen.value, true)
  assert.equal(manager.hasUnsaved.value, true)
  assert.equal(view.emitted.length, 0)
  assert.equal(view.page.canRetry.value, true)
  f.failWrite(false)
  await view.page.retry()
  assert.equal(view.page.dialogOpen.value, false)
  assert.equal(manager.state.value.persisted, true)
})

test('关闭动画结束前拒绝打开新操作，防止旧关闭回调吞掉新弹窗', async (t) => {
  const f = fixture()
  const view = mount(f.manager())
  t.after(view.unmount)
  view.page.open('save')
  view.page.name.value = '不应重复保存'
  await nextTick()
  view.page.dialogOpen.value = false
  view.page.closing.value = true
  await view.page.confirm()
  assert.equal(f.writes(), 0)
  view.page.open('reload')
  assert.equal(view.page.dialogOpen.value, false)
  assert.equal(view.page.mode.value, 'save')
  await view.page.closed()
  view.page.open('reload')
  assert.equal(view.page.dialogOpen.value, true)
  assert.equal(view.page.mode.value, 'reload')
})

test('30 组满额后新增/复制被拒绝，保存表单保留并提示，不破坏已有词库', async (t) => {
  const f = fixture()
  const manager = f.manager()
  for (let index = 0; index < 30; index++)
    assert.equal(await manager.saveRules(`词库 ${index}`, rules()), true)
  const before = f.raw()
  const view = mount(manager)
  t.after(view.unmount)
  view.page.open('save')
  view.page.name.value = '第 31 组'
  await nextTick()
  await view.page.confirm()
  assert.equal(view.page.dialogOpen.value, true)
  assert.match(manager.issue.value, /最多保存 30 组/u)
  assert.equal(await manager.copy(manager.selectedId.value), false)
  assert.equal(f.raw(), before)
  assert.equal(manager.hasUnsaved.value, false)
})

import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'
import { createRenderer, h, nextTick, reactive } from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'

const url = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
async function compile(path, imports = {}) {
  let code = ts.transpileModule(await readFile(new URL(path, import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(imports))
    code = code.replaceAll(`from '${from}'`, `from '${to}'`)
  return url(code)
}
const pureUrl = await compile('../../src/utils/replaceDraft.ts')
const storageUrl = await compile('../../src/stores/replaceDraftStorage.ts', {
  '../utils/replaceDraft': pureUrl,
})
const controllerUrl = await compile('../../src/composables/useReplaceDraft.ts', {
  vue: import.meta.resolve('vue'),
  '../utils/replaceDraft': pureUrl,
  '../stores/replaceDraftStorage': storageUrl,
})
const { validateReplaceDraftBook, parseReplaceDraftBook, REPLACE_DRAFT_BOOK_MAX_BYTES } =
  await import(pureUrl)
const { createReplaceDraftRepository } = await import(storageUrl)
const { useReplaceDraft } = await import(controllerUrl)
const rules = (text = '半成品') => [
  { search: '', replace: text },
  { search: '重复', replace: '' },
  { search: '重复', replace: '😀' },
]
const draft = (text = '半成品') => ({ version: 1, rules: rules(text), updatedAt: 100 })
const blank = [{ search: '', replace: '' }]

// 同一共享介质与串行锁，故障/排队时点可控；不访问真实用户的浏览器数据。
function fixture() {
  let raw = null,
    sequence = 0,
    writes = 0,
    queue = Promise.resolve()
  const active = new Set(),
    faults = {},
    schedules = new Set()
  const environment = {
    createId: () => `id${++sequence}`,
    storage: () => ({
      getItem: () => {
        if (faults.read) throw Error('fixture')
        return raw
      },
      setItem: (_key, value) => {
        if (faults.write) throw Error('fixture')
        raw = value
        writes++
      },
    }),
    claim: async (id) => {
      if (faults.claim || active.has(id)) throw Error('fixture')
      active.add(id)
      return () => active.delete(id)
    },
    exclusive: (action) => {
      const operation = queue.then(() => {
        if (faults.lock) throw Error('fixture')
        return action()
      })
      // 只恢复替身队列的可用性；原操作的拒绝仍传给真实存储层分类处理。
      queue = operation.catch(() => undefined)
      return operation
    },
    ifInactive: async (id, action) => {
      if (faults.lock) throw Error('fixture')
      if (active.has(id)) return null
      active.add(id)
      try {
        return await action()
      } finally {
        active.delete(id)
      }
    },
  }
  const repository = () => createReplaceDraftRepository(environment)
  function controller(pointer = null, initial = blank) {
    const repo = repository()
    const location = { value: pointer }
    const manager = useReplaceDraft(initial, {
      repository: repo,
      readPointer: () => location.value,
      point: (id) => {
        location.value = id
      },
      now: () => 101,
      schedule: (action) => {
        schedules.add(action)
        return () => schedules.delete(action)
      },
    })
    return { manager, repo, location }
  }
  return {
    repository,
    controller,
    faults,
    active,
    schedules,
    get raw() {
      return raw
    },
    set raw(value) {
      raw = value
    },
    get writes() {
      return writes
    },
    hold() {
      let release
      queue = new Promise((resolve) => {
        release = resolve
      })
      return release
    },
  }
}
async function enabled(f) {
  const repo = f.repository()
  await repo.open()
  const result = await repo.enable()
  return { repo, generation: result.book.generation }
}

test('有界草稿库校验固定字段、唯一 ASCII ID、深冻结和未知版本', () => {
  const value = {
    version: 1,
    enabled: true,
    generation: 'g1',
    drafts: [{ id: 'page_1', draft: draft() }],
  }
  const copy = validateReplaceDraftBook(value)
  value.drafts[0].draft.rules[0].replace = '变更'
  assert.equal(copy.drafts[0].draft.rules[0].replace, '半成品')
  assert.ok(Object.isFrozen(copy.drafts))
  for (const patch of [
    { version: 2 },
    { unknown: 1 },
    { generation: '' },
    { generation: 'K' },
    { drafts: [copy.drafts[0], copy.drafts[0]] },
    { drafts: [{ id: '中文', draft: draft() }] },
  ])
    assert.throws(() => validateReplaceDraftBook({ ...copy, ...patch }))
  assert.throws(() => parseReplaceDraftBook('x'.repeat(REPLACE_DRAFT_BOOK_MAX_BYTES + 1)))
  assert.throws(() => parseReplaceDraftBook('{'))
})

test('默认关闭只读不写；半成品保存并按自己的页面槽位合并', async () => {
  const f = fixture(),
    a = f.repository(),
    b = f.repository()
  assert.equal((await a.open()).book.enabled, false)
  assert.equal(f.writes, 0)
  await b.open()
  const generation = (await a.enable()).book.generation
  const [one, two] = await Promise.all([
    a.save(draft('A'), generation, () => true),
    b.save(draft('B'), generation, () => true),
  ])
  assert.equal(one.status, 'saved')
  assert.equal(two.status, 'saved')
  const book = JSON.parse(f.raw)
  assert.deepEqual(
    book.drafts.map((item) => item.draft.rules[0].replace),
    ['A', 'B'],
  )
})

test('清空自己的槽位保留其他页，活动历史禁止删除，退出后可删', async () => {
  const f = fixture(),
    { repo: a, generation } = await enabled(f),
    b = f.repository()
  await b.open()
  await a.save(draft('A'), generation, () => true)
  await b.save(draft('B'), generation, () => true)
  assert.equal((await a.removeHistory(b.id)).status, 'active')
  await a.save(null, generation, () => true)
  assert.deepEqual(
    JSON.parse(f.raw).drafts.map((item) => item.id),
    [b.id],
  )
  b.close()
  assert.equal((await a.removeHistory(b.id)).status, 'saved')
  assert.equal(JSON.parse(f.raw).drafts.length, 0)
})

test('恢复活动来源只复制；来源退出后原子转移，写失败保留旧来源', async () => {
  const f = fixture(),
    { repo: a, generation } = await enabled(f),
    b = f.repository()
  await b.open()
  await a.save(draft('A'), generation, () => true)
  await b.save(draft('copy'), generation, () => true, a.id)
  assert.equal(JSON.parse(f.raw).drafts.length, 2)
  a.close()
  f.faults.write = true
  assert.equal((await b.save(draft('retry'), generation, () => true, a.id)).status, 'write-error')
  assert.equal(JSON.parse(f.raw).drafts.length, 2)
  f.faults.write = false
  await b.save(draft('retry'), generation, () => true, a.id)
  assert.deepEqual(
    JSON.parse(f.raw).drafts.map((item) => item.id),
    [b.id],
  )
})

test('满10份拒绝第11份、不淘汰；退出来源转移无需额外槽位', async () => {
  const f = fixture(),
    { repo, generation } = await enabled(f)
  f.raw = JSON.stringify({
    version: 1,
    enabled: true,
    generation,
    drafts: Array.from({ length: 10 }, (_, i) => ({ id: `old${i}`, draft: draft(String(i)) })),
  })
  const before = f.raw
  assert.equal((await repo.save(draft(), generation, () => true)).status, 'invalid')
  assert.equal(f.raw, before)
  assert.equal((await repo.save(draft(), generation, () => true, 'old0')).status, 'saved')
  assert.equal(JSON.parse(f.raw).drafts.length, 10)
})

test('等待锁的旧输入可取消；关闭重开后的旧代次不能复活', async () => {
  const f = fixture(),
    { repo, generation } = await enabled(f)
  const release = f.hold()
  let current = true
  const pending = repo.save(draft('old'), generation, () => current)
  current = false
  release()
  assert.equal((await pending).status, 'cancelled')
  await repo.disable()
  const newer = await repo.enable()
  assert.notEqual(newer.book.generation, generation)
  assert.equal((await repo.save(draft('old'), generation, () => true)).status, 'stale')
  assert.equal(JSON.parse(f.raw).drafts.length, 0)
})

test('损坏数据保留且拒绝自动写；只有明确关闭允许重置', async () => {
  const f = fixture(),
    repo = f.repository()
  f.raw = '{bad'
  assert.equal((await repo.open()).status, 'invalid-storage')
  assert.equal((await repo.enable()).status, 'invalid-storage')
  assert.equal(f.raw, '{bad')
  assert.equal((await repo.disable()).status, 'disabled')
  assert.deepEqual(JSON.parse(f.raw).drafts, [])
})

test('读、写、写锁和页面锁故障均不冒充成功，重试可恢复', async () => {
  const f = fixture(),
    { repo, generation } = await enabled(f),
    before = f.raw
  for (const [fault, status] of [
    ['read', 'read-error'],
    ['write', 'write-error'],
    ['lock', 'lock-unavailable'],
  ]) {
    f.faults[fault] = true
    assert.equal((await repo.save(draft(), generation, () => true)).status, status)
    assert.equal(f.raw, before)
    f.faults[fault] = false
  }
  assert.equal((await repo.save(draft(), generation, () => true)).status, 'saved')
  f.faults.claim = true
  assert.equal((await f.repository().open()).status, 'lock-unavailable')
  repo.close()
  assert.equal(f.active.has(repo.id), false)
  assert.equal((await repo.save(draft(), generation, () => true)).status, 'lock-unavailable')
})

test('控制器默认关闭不排计时器，主动开启记住选择并保存最新半成品', async () => {
  const f = fixture(),
    { manager } = f.controller()
  await manager.initialize()
  manager.edit(rules())
  assert.equal(manager.enabled.value, false)
  assert.equal(f.schedules.size, 0)
  assert.equal(f.writes, 0)
  await manager.enable()
  assert.equal(manager.dirty.value, false)
  manager.edit(rules('末次'))
  assert.equal(f.schedules.size, 1)
  await manager.flush()
  assert.equal(f.schedules.size, 0)
  assert.equal(JSON.parse(f.raw).drafts[0].draft.rules[0].replace, '末次')
})

test('刷新提示旧稿，未决定不自动写；恢复后旧来源转移且父页面回声不重复保存', async () => {
  const f = fixture(),
    old = f.controller()
  await old.manager.initialize()
  old.manager.edit(rules())
  await old.manager.enable()
  await old.manager.dispose()
  const next = f.controller(old.location.value)
  await next.manager.initialize()
  assert.equal(next.manager.enabled.value, true)
  assert.equal(next.manager.paused.value, true)
  next.manager.edit(rules('当前新输入'))
  assert.equal(f.schedules.size, 0)
  const count = f.writes
  assert.equal(await next.manager.flush(), false)
  assert.equal(f.writes, count)
  const restored = next.manager.restore(next.manager.selected.value)
  next.manager.edit(restored)
  await next.manager.flush()
  assert.equal(JSON.parse(f.raw).drafts.length, 1)
  assert.equal(JSON.parse(f.raw).drafts[0].id, next.repo.id)
  assert.equal(next.manager.dirty.value, false)
})

test('待恢复旧稿未决时，临时输入再删空不能删除旧历史或解除来源选择', async () => {
  const f = fixture()
  const old = f.controller()
  await old.manager.initialize()
  await old.manager.enable()
  old.manager.edit(rules('必须保留的旧稿'))
  await old.manager.flush()
  await old.manager.dispose()
  const before = f.raw
  const writes = f.writes
  const next = f.controller(old.location.value)
  await next.manager.initialize()
  const sourceId = next.manager.selectedId.value
  next.manager.edit(rules('临时输入'))
  next.manager.edit(blank)
  assert.equal(await next.manager.flush(), true)
  assert.equal(next.manager.dirty.value, false)
  assert.equal(f.raw, before)
  assert.equal(f.writes, writes)
  assert.equal(next.manager.paused.value, true)
  assert.equal(next.manager.selectedId.value, sourceId)
  assert.deepEqual(next.manager.selected.value.draft.rules, rules('必须保留的旧稿'))
  await next.manager.dispose()
})

test('仅在历史下拉框选中记录，删空编辑或确认清空本页都保留未恢复历史', async () => {
  for (const action of ['edit', 'clear']) {
    const f = fixture(),
      old = f.controller()
    await old.manager.initialize()
    old.manager.edit(rules('未恢复的历史'))
    await old.manager.enable()
    await old.manager.dispose()
    const next = f.controller()
    await next.manager.initialize()
    next.manager.edit(rules('本页新编辑'))
    next.manager.continueCurrent()
    await next.manager.flush()
    next.manager.selectedId.value = old.repo.id
    if (action === 'edit') {
      next.manager.edit(blank)
      await next.manager.flush()
    } else await next.manager.clearCurrent()
    const saved = JSON.parse(f.raw).drafts
    assert.deepEqual(
      saved.map((item) => item.id),
      [old.repo.id],
      action,
    )
    assert.deepEqual(saved[0].draft.rules, rules('未恢复的历史'), action)
    await next.manager.dispose()
  }
})

test('明确确认清空待恢复来源仍可清理该来源，保留其他历史', async () => {
  const f = fixture(),
    old = f.controller()
  await old.manager.initialize()
  old.manager.edit(rules('待清空的来源'))
  await old.manager.enable()
  await old.manager.dispose()
  const other = f.repository()
  const opened = await other.open()
  await other.save(draft('其他历史'), opened.book.generation, () => true)
  other.close()
  const next = f.controller(old.location.value)
  await next.manager.initialize()
  assert.equal(next.manager.paused.value, true)
  await next.manager.clearCurrent()
  assert.deepEqual(
    JSON.parse(f.raw).drafts.map((item) => item.id),
    [other.id],
  )
  await next.manager.dispose()
})

test('历史恢复失败仍取回文本并保留来源，手动重试成功', async () => {
  const f = fixture(),
    old = f.controller()
  await old.manager.initialize()
  old.manager.edit(rules('原稿'))
  await old.manager.enable()
  await old.manager.dispose()
  const next = f.controller()
  await next.manager.initialize()
  const recovered = next.manager.restore(next.manager.selected.value)
  f.faults.write = true
  assert.equal(await next.manager.flush(), false)
  assert.equal(recovered[0].replace, '原稿')
  assert.equal(next.manager.dirty.value, true)
  assert.equal(JSON.parse(f.raw).drafts[0].id, old.repo.id)
  f.faults.write = false
  assert.equal(await next.manager.flush(), true)
})

test('保留当前编辑继续不删旧稿；本页清空后刷新不会自动再次选其他历史', async () => {
  const f = fixture(),
    old = f.controller()
  await old.manager.initialize()
  old.manager.edit(rules('旧历史'))
  await old.manager.enable()
  await old.manager.dispose()
  const next = f.controller()
  await next.manager.initialize()
  next.manager.edit(rules('当前'))
  next.manager.continueCurrent()
  await next.manager.flush()
  assert.equal(JSON.parse(f.raw).drafts.length, 2)
  await next.manager.clearCurrent()
  await next.manager.dispose()
  const reload = f.controller(next.location.value)
  await reload.manager.initialize()
  assert.equal(reload.manager.history.value.length, 1)
  assert.equal(reload.manager.paused.value, false)
  assert.equal(reload.manager.selected.value, undefined)
})

test('清空使等待写入和计时器作废，不能复活末次输入', async () => {
  const f = fixture(),
    { manager } = f.controller()
  await manager.initialize()
  await manager.enable()
  manager.edit(rules('旧输入'))
  const release = f.hold(),
    old = manager.flush(),
    cleared = manager.clearCurrent()
  release()
  assert.equal(await old, false)
  assert.equal(await cleared, true)
  assert.equal(f.schedules.size, 0)
  assert.equal(JSON.parse(f.raw).drafts.length, 0)
})

test('关闭清理失败只暂停本页且可重试；成功后其他页迟到写入被拒', async () => {
  const f = fixture(),
    a = f.controller(),
    b = f.controller()
  await a.manager.initialize()
  await a.manager.enable()
  await b.manager.initialize()
  b.manager.edit(rules('B'))
  f.faults.write = true
  assert.equal(await a.manager.disable(), false)
  assert.equal(a.manager.cleanupFailed.value, true)
  assert.equal(a.manager.enabled.value, false)
  f.faults.write = false
  assert.equal(await a.manager.disable(), true)
  assert.equal(await b.manager.flush(), false)
  assert.equal(b.manager.enabled.value, false)
  assert.deepEqual(JSON.parse(f.raw).drafts, [])
})

test('外部设置变化暂停旧页面；dispose补存末次编辑并释放身份锁', async () => {
  const f = fixture(),
    a = f.controller(),
    b = f.controller()
  await a.manager.initialize()
  await a.manager.enable()
  await b.manager.initialize()
  b.manager.edit(rules('隐藏前末次'))
  await b.manager.dispose()
  assert.equal(f.active.has(b.repo.id), false)
  assert.equal(JSON.parse(f.raw).drafts[0].draft.rules[0].replace, '隐藏前末次')
  a.manager.edit(rules('待存'))
  await (await enabled(f)).repo.disable()
  a.manager.refresh()
  assert.equal(a.manager.enabled.value, false)
  assert.equal(f.schedules.size, 0)
  assert.equal(a.manager.dirty.value, true)
  assert.equal(await a.manager.flush(), false)
})

test('防抖回调实际保存最新输入；不支持页面锁时不写入', async () => {
  const f = fixture(),
    { manager } = f.controller()
  await manager.initialize()
  await manager.enable()
  manager.edit(rules('旧'))
  const cancelled = [...f.schedules][0]
  manager.edit(rules('新'))
  assert.equal(f.schedules.has(cancelled), false)
  ;[...f.schedules][0]()
  await manager.flush()
  assert.equal(JSON.parse(f.raw).drafts[0].draft.rules[0].replace, '新')
  f.faults.claim = true
  const unavailable = f.controller()
  await unavailable.manager.initialize()
  assert.equal(await unavailable.manager.enable(), false)
  assert.equal(unavailable.manager.enabled.value, false)
})

test('其他页清理待恢复历史后撤销失效选择，当前编辑保留', async () => {
  const f = fixture(),
    old = await enabled(f)
  await old.repo.save(draft(), old.generation, () => true)
  old.repo.close()
  const page = f.controller()
  await page.manager.initialize()
  page.manager.edit(rules('保留当前'))
  const other = await enabled(f)
  await other.repo.disable()
  page.manager.refresh()
  assert.equal(page.manager.selectedId.value, '')
  assert.equal(page.manager.paused.value, false)
  assert.equal(await page.manager.flush(), false)
  assert.deepEqual(JSON.parse(f.raw).drafts, [])
})

test('删除非待决历史不重复保存当前稿；页面定位故障保留可见提示', async () => {
  const f = fixture(),
    old = await enabled(f)
  await old.repo.save(draft('旧稿'), old.generation, () => true)
  old.repo.close()
  const page = f.controller()
  await page.manager.initialize()
  page.manager.edit(rules('当前'))
  page.manager.continueCurrent()
  await page.manager.flush()
  page.manager.selectedId.value = old.repo.id
  const count = f.writes
  await page.manager.removeHistory(old.repo.id)
  assert.equal(f.writes, count + 1)
  assert.equal(f.schedules.size, 0)
  assert.equal(page.manager.dirty.value, false)
  const repository = f.repository()
  const manager = useReplaceDraft(blank, {
    repository,
    readPointer: () => {
      throw Error('fixture')
    },
    point: () => {
      throw Error('fixture')
    },
    now: () => 100,
    schedule: () => () => {},
  })
  await manager.initialize()
  manager.continueCurrent()
  manager.edit(rules('可保存'))
  await manager.flush()
  assert.equal(manager.dirty.value, false)
  assert.match(manager.locationWarning.value, /定位信息未保存/)
})

// 执行真实 SFC setup；用受控浏览器事件替身验离页分支，真实鼠标/焦点另做浏览器验收。
const hostsUrl = url(`
export const window = { listeners:new Map(), addEventListener(name, fn){this.listeners.set(name,fn)}, removeEventListener(name){this.listeners.delete(name)} };
export const document = { visibilityState:'visible', listeners:new Map(), addEventListener(name, fn){this.listeners.set(name,fn)}, removeEventListener(name){this.listeners.delete(name)} };
export let useReplaceDraft;
export function inject(factory){useReplaceDraft=factory}
`)
const hosts = await import(hostsUrl)
const { descriptor } = parse(
  await readFile(new URL('../../src/components/ReplaceDraftPanel.vue', import.meta.url), 'utf8'),
)
const controlsUrl = url(
  'export const ElButton={}, ElCheckbox={}, ElDialog={}, ElOption={}, ElSelect={}',
)
let componentCode = ts.transpileModule(
  `import {window, document} from '${hostsUrl}';\n${compileScript(descriptor, { id: 'draft-ui-test' }).content}`,
  { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } },
).outputText
for (const [from, to] of Object.entries({
  vue: import.meta.resolve('vue'),
  'element-plus': controlsUrl,
  '../utils/replaceDraft': pureUrl,
  '../stores/replaceDraftStorage': storageUrl,
  '../composables/useReplaceDraft': hostsUrl,
  './RuleSharePanel.vue': url('export default {}'),
}))
  componentCode = componentCode.replaceAll(`from '${from}'`, `from '${to}'`)
const { default: component } = await import(url(componentCode))
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
async function mountPanel(f, initial = blank) {
  const item = f.controller(null, initial),
    props = reactive({ rules: initial.map((rule) => ({ ...rule })) })
  hosts.inject(() => item.manager)
  let page
  // 固定组件类型，父页面接收恢复事件时应更新 props，不能意外重挂载 setup。
  const mountedComponent = {
    ...component,
    setup(p, ctx) {
      page = component.setup(p, ctx)
      return () => null
    },
  }
  const app = renderer.createApp({
    setup: () => () =>
      h(mountedComponent, {
        rules: props.rules,
        onLoad(value) {
          props.rules = value
        },
      }),
  })
  app.mount({})
  await item.manager.initialize()
  await nextTick()
  return {
    ...item,
    props,
    get page() {
      return page
    },
    unmount: () => app.unmount(),
  }
}
test('关闭先确认，取消保留开启状态；重建开关修复原生 checked，迟到确认被拦截', async () => {
  const f = fixture(),
    panel = await mountPanel(f)
  await panel.page.toggle(true)
  const writes = f.writes
  await panel.page.toggle(false)
  assert.equal(panel.manager.enabled.value, true)
  assert.equal(f.writes, writes)
  assert.equal(panel.page.action.value, 'disable')
  assert.equal(panel.page.checkboxRevision.value, 2)
  panel.page.dialogOpen.value = false
  panel.page.closing.value = true
  await panel.page.confirm()
  assert.equal(f.writes, writes)
  panel.page.closed()
  assert.equal(panel.manager.enabled.value, true)
  panel.unmount()
})
test('真实面板恢复不同编辑先确认，取消不更改，确认后父页面接收半成品', async () => {
  const f = fixture(),
    old = await enabled(f)
  await old.repo.save(draft('已关闭来源'), old.generation, () => true)
  old.repo.close()
  const panel = await mountPanel(f, rules('当前编辑'))
  await panel.page.requestRestore()
  assert.equal(panel.page.action.value, 'restore')
  assert.equal(panel.props.rules[0].replace, '当前编辑')
  panel.page.dialogOpen.value = false
  panel.page.closed()
  await panel.page.requestRestore()
  await panel.page.confirm()
  await nextTick()
  assert.equal(panel.props.rules[0].replace, '已关闭来源')
  assert.equal(panel.manager.dirty.value, false)
  panel.unmount()
})
test('链接来源选择走真实面板：载入保留旧历史，待决定时原草稿动作被阻止', async () => {
  const f = fixture(),
    old = await enabled(f)
  await old.repo.save(draft('旧草稿不可丢'), old.generation, () => true)
  old.repo.close()
  const panel = await mountPanel(f, [{ search: '当前', replace: '编辑' }])
  panel.page.sharePending.value = true
  await panel.page.requestRestore()
  assert.equal(panel.page.dialogOpen.value, false)
  await panel.page.toggle(false)
  assert.equal(panel.manager.enabled.value, true)
  const before = f.raw
  f.faults.write = true
  assert.equal(panel.page.loadSharedRules([{ search: '链接', replace: '新规则' }]), true)
  await nextTick()
  assert.equal(await panel.manager.flush(), false)
  assert.equal(f.raw, before)
  assert.deepEqual(panel.props.rules, [{ search: '链接', replace: '新规则' }])
  f.faults.write = false
  await panel.manager.flush()
  const stored = JSON.parse(f.raw).drafts
  assert.ok(
    stored.some(
      (item) => item.id === old.repo.id && item.draft.rules[0].replace === '旧草稿不可丢',
    ),
  )
  assert.ok(
    stored.some((item) => item.id === panel.repo.id && item.draft.rules[0].search === '链接'),
  )
  assert.deepEqual(panel.props.rules, [{ search: '链接', replace: '新规则' }])
  panel.unmount()
})
test('统一来源选择的暂不载入保留编辑和旧历史；恢复只接受仍待决的草稿 ID', async () => {
  const f = fixture(),
    old = await enabled(f)
  await old.repo.save(draft('待恢复'), old.generation, () => true)
  old.repo.close()
  const panel = await mountPanel(f, [{ search: '当前', replace: '保留' }])
  assert.equal(panel.page.restoreSharedDraft('missing'), false)
  assert.deepEqual(panel.props.rules, [{ search: '当前', replace: '保留' }])
  assert.equal(panel.page.keepSharedEditing(), true)
  await panel.manager.flush()
  assert.ok(JSON.parse(f.raw).drafts.some((item) => item.id === old.repo.id))
  assert.equal(panel.manager.paused.value, false)
  assert.deepEqual(panel.props.rules, [{ search: '当前', replace: '保留' }])
  assert.equal(panel.page.restoreSharedDraft(old.repo.id), false)
  panel.unmount()
  const other = await mountPanel(f)
  // 新面板默认定位最新历史；本断言明确选择原来的待恢复来源。
  other.manager.selectedId.value = old.repo.id
  assert.equal(other.page.restoreSharedDraft(old.repo.id), true)
  await nextTick()
  assert.equal(other.props.rules[0].replace, '待恢复')
  other.unmount()
})
test('导航保存失败可返回或明确离开，关闭确认不会留下未决 Promise', async () => {
  const f = fixture(),
    panel = await mountPanel(f)
  await panel.manager.enable()
  panel.manager.edit(rules('尚未保存'))
  f.faults.write = true
  const leaving = panel.page.prepareLeave()
  await new Promise(setImmediate)
  assert.equal(panel.page.action.value, 'leave')
  assert.equal(panel.page.dialogOpen.value, true)
  panel.page.dialogOpen.value = false
  panel.page.closed()
  assert.equal(await leaving, false)
  const allowed = panel.page.prepareLeave()
  await new Promise(setImmediate)
  await panel.page.confirm()
  assert.equal(await allowed, true)
  panel.unmount()
})
test('真实面板隐藏和 pagehide 补存，卸载移除事件与页面锁', async () => {
  const f = fixture(),
    panel = await mountPanel(f)
  await panel.manager.enable()
  panel.manager.edit(rules('隐藏末次'))
  hosts.document.visibilityState = 'hidden'
  hosts.document.listeners.get('visibilitychange')()
  await panel.manager.flush()
  assert.equal(JSON.parse(f.raw).drafts[0].draft.rules[0].replace, '隐藏末次')
  panel.manager.edit(rules('离页末次'))
  hosts.window.listeners.get('pagehide')()
  await panel.manager.flush()
  assert.equal(JSON.parse(f.raw).drafts[0].draft.rules[0].replace, '离页末次')
  panel.unmount()
  await new Promise(setImmediate)
  assert.equal(hosts.window.listeners.size, 0)
  assert.equal(hosts.document.listeners.size, 0)
  assert.equal(f.active.has(panel.repo.id), false)
  hosts.document.visibilityState = 'visible'
})

import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, nextTick } from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'

const url = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const fixtures = url(`
export const zipCallbacks = [];
export function zip(entries, options, callback) { zipCallbacks.push({entries, options, callback}) }
export class Worker {
  static instances = [];
  constructor() { this.sent = []; this.stopped = false; Worker.instances.push(this) }
  postMessage(data) { this.sent.push(data) }
  terminate() { this.stopped = true }
}
export class OffscreenCanvas { convertToBlob() {} }
export function createImageBitmap() {}
export class URL extends globalThis.URL {
  static created = []; static revoked = [];
  static createObjectURL(value) { const name = 'blob:fixture-' + URL.created.length; URL.created.push({name, value}); return name }
  static revokeObjectURL(value) { URL.revoked.push(value) }
}
export const ElAlert = {}, ElButton = {}, ElIcon = {}, ElInput = {}, ElProgress = {}, ElSwitch = {}, Close = {}, Download = {}, Picture = {}, Upload = {};
export default {};
`)
const compiledModules = new Map()
async function compileModule(sourceUrl) {
  if (compiledModules.has(sourceUrl.href)) return compiledModules.get(sourceUrl.href)
  let code = ts.transpileModule(await readFile(sourceUrl, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const match of [...code.matchAll(/from ['"](\.[^'"]+)['"]/g)]) {
    code = code.replaceAll(
      match[0],
      `from '${await compileModule(new URL(match[1] + '.ts', sourceUrl))}'`,
    )
  }
  const result = url(code)
  compiledModules.set(sourceUrl.href, result)
  return result
}
const sourceUrl = new URL('../../src/components/TargetKbPanel.vue', import.meta.url)
const { descriptor } = parse(await readFile(sourceUrl, 'utf8'))
let source = compileScript(descriptor, { id: 'target-kb-lifecycle' }).content.replace(
  "new URL('../workers/targetkb.worker.ts', import.meta.url)",
  "new URL('file:///fixture-worker.mjs')",
)
source = `import {Worker,OffscreenCanvas,createImageBitmap,URL} from '${fixtures}';\n` + source
let code = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
for (const match of [...code.matchAll(/from ['"]([^'"]+)['"]/g)]) {
  const name = match[1]
  const target =
    name === fixtures
      ? fixtures
      : name === 'vue'
        ? import.meta.resolve('vue')
        : name.startsWith('../')
          ? await compileModule(new URL(name + '.ts', sourceUrl))
          : fixtures
  code = code.replaceAll(match[0], `from '${target}'`)
}
const { default: component } = await import(url(code))
const { Worker, URL: MockURL, zipCallbacks } = await import(fixtures)
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
async function mount() {
  Worker.instances.length = 0
  zipCallbacks.length = 0
  MockURL.created.length = 0
  MockURL.revoked.length = 0
  let page
  const app = renderer.createApp({
    setup: () => () =>
      h({
        ...component,
        setup(props, context) {
          page = component.setup(props, context)
          return () => null
        },
      }),
  })
  app.mount({})
  page.targetInput.value = '100KB'
  await nextTick()
  return { page, close: () => app.unmount() }
}
const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => {
    resolve = ok
    reject = fail
  })
  return { promise, resolve, reject }
}
const file = (name = 'a.png', read = async () => new ArrayBuffer(24), header = read) => ({
  name,
  size: 24,
  lastModified: 1,
  type: 'image/png',
  arrayBuffer: read,
  slice: () => ({ arrayBuffer: header }),
})
const entry = (id, input = file()) => ({
  id,
  file: input,
  url: 'blob:source-' + id,
  width: 1,
  height: 1,
  status: 'pending',
})
const output = (patch = {}) => ({
  bytes: new ArrayBuffer(16),
  width: 1,
  height: 1,
  quality: null,
  maxEdge: null,
  scaleStep: 0,
  passthrough: true,
  mime: 'image/png',
  probes: [],
  padding: { targetBytes: 0, added: 0, satisfied: true },
  dpi: null,
  elapsedMs: 1,
  ...patch,
})
const complete = (worker, patch = {}) => {
  const request = worker.sent.filter((x) => x.kind === 'encode').at(-1)
  worker.onmessage({ data: { id: request.id, ok: true, result: output(patch) } })
  return request.id
}
async function settle() {
  await nextTick()
  await nextTick()
}

// 等待点由 Promise 控制，不靠计时器碰巧点中读文件/打包阶段；编码和字节算法由专项另验。
test('读图时取消：迟到读取不能启动 Worker 或复活结果', async (t) => {
  const f = await mount()
  t.after(f.close)
  const read = deferred()
  f.page.entries.value = [
    entry(
      1,
      file('a.png', () => read.promise),
    ),
  ]
  const job = f.page.run()
  f.page.cancel()
  read.resolve(new ArrayBuffer(24))
  await job
  assert.equal(Worker.instances.length, 0)
  assert.equal(f.page.result.value, null)
  assert.equal(f.page.busy.value, false)
})

test('旧读取取消后立即重试：旧 finally 不解除新忙碌状态，旧错误不改新提示', async (t) => {
  const f = await mount()
  t.after(f.close)
  const read = deferred()
  f.page.entries.value = [
    entry(
      1,
      file('a.png', () => read.promise),
    ),
  ]
  const old = f.page.run()
  f.page.cancel()
  f.page.entries.value = [entry(2)]
  const current = f.page.run()
  await settle()
  read.reject(Error('old read'))
  await old
  assert.equal(f.page.busy.value, true)
  assert.equal(f.page.message.value, '')
  complete(Worker.instances[0])
  await current
  assert.ok(f.page.result.value)
})

test('批量读图取消：不发送迟到文件，也不继续读取后续文件', async (t) => {
  const f = await mount()
  t.after(f.close)
  const read = deferred()
  let secondReads = 0
  f.page.entries.value = [
    entry(
      1,
      file('a.png', () => read.promise),
    ),
    entry(
      2,
      file('b.png', async () => {
        secondReads++
        return new ArrayBuffer(24)
      }),
    ),
  ]
  const job = f.page.run()
  f.page.cancel()
  read.resolve(new ArrayBuffer(24))
  await job
  assert.equal(Worker.instances.length, 0)
  assert.equal(secondReads, 0)
  assert.equal(f.page.zipUrl.value, '')
})

test('取消编码后旧回复不能污染新任务，同一 Worker 新任务 id 递增', async (t) => {
  const f = await mount()
  t.after(f.close)
  f.page.entries.value = [entry(1)]
  const old = f.page.run()
  await settle()
  const worker = Worker.instances[0]
  const oldId = worker.sent[0].id
  f.page.cancel()
  const current = f.page.run()
  await settle()
  worker.onmessage({ data: { id: oldId, ok: true, result: output() } })
  await old
  assert.equal(f.page.busy.value, true)
  assert.equal(f.page.result.value, null)
  const newId = complete(worker)
  assert.ok(newId > oldId)
  await current
  assert.ok(f.page.result.value)
})

test('批量成功与失败串行汇总，只将成功字节送入 ZIP', async (t) => {
  const f = await mount()
  t.after(f.close)
  f.page.entries.value = [entry(1), entry(2, file('b.png'))]
  const job = f.page.run()
  await settle()
  const worker = Worker.instances[0]
  const id = complete(worker)
  await settle()
  assert.equal(worker.sent.filter((x) => x.kind === 'encode').length, 2)
  worker.onmessage({ data: { id, ok: false, reason: 'decode-failed', message: '坏图片' } })
  await settle()
  assert.equal(zipCallbacks.length, 1)
  assert.equal(Object.keys(zipCallbacks[0].entries).length, 1)
  zipCallbacks[0].callback(null, new Uint8Array([1, 2, 3]))
  await job
  assert.equal(f.page.batchSummary.value.done, 1)
  assert.equal(f.page.batchSummary.value.failed, 1)
  assert.ok(f.page.zipUrl.value)
  assert.equal(f.page.busy.value, false)
})

async function reachZip(f) {
  f.page.entries.value = [entry(1), entry(2, file('b.png'))]
  const job = f.page.run()
  await settle()
  const worker = Worker.instances[0]
  complete(worker)
  await settle()
  complete(worker)
  await settle()
  assert.equal(zipCallbacks.length, 1)
  assert.equal(f.page.packing.value, true)
  return { job, callback: zipCallbacks[0].callback }
}

test('ZIP 期间取消，迟到成功不复活下载；重新运行只发布新 ZIP', async (t) => {
  const f = await mount()
  t.after(f.close)
  const old = await reachZip(f)
  f.page.cancel()
  const current = f.page.run()
  await settle()
  old.callback(null, new Uint8Array([1]))
  await old.job
  assert.equal(f.page.zipUrl.value, '')
  assert.equal(f.page.busy.value, true)
  const worker = Worker.instances[0]
  complete(worker)
  await settle()
  complete(worker)
  await settle()
  zipCallbacks[1].callback(null, new Uint8Array([2, 3]))
  await current
  assert.ok(f.page.zipUrl.value)
  assert.equal(f.page.zipBytes.value, 2)
})

test('ZIP 期间修改参数或卸载，迟到成功/失败都不发布结果或覆盖提示', async () => {
  for (const mode of ['parameters', 'unmount']) {
    const f = await mount()
    const pending = await reachZip(f)
    if (mode === 'parameters') {
      f.page.targetInput.value = '90KB'
      await settle()
    } else f.close()
    const notice = f.page.message.value
    pending.callback(mode === 'parameters' ? Error('late zip') : null, new Uint8Array([1]))
    await pending.job
    assert.equal(f.page.zipUrl.value, '')
    assert.equal(f.page.message.value, notice)
    if (mode === 'parameters') f.close()
  }
})

test('Worker 出错后终止并重建，旧 Worker 错误不能取消新任务', async (t) => {
  const f = await mount()
  t.after(f.close)
  f.page.entries.value = [entry(1)]
  const first = f.page.run()
  await settle()
  const old = Worker.instances[0]
  old.onerror()
  await first
  assert.equal(old.stopped, true)
  assert.equal(f.page.busy.value, false)
  const second = f.page.run()
  await settle()
  assert.equal(Worker.instances.length, 2)
  old.onerror()
  assert.equal(f.page.busy.value, true)
  complete(Worker.instances[1])
  await second
  assert.ok(f.page.result.value)
})

test('单图头部检查迟到不能清空新追加或重选文件，读取错误有反馈', async (t) => {
  const f = await mount()
  t.after(f.close)
  const head = deferred()
  const pending = f.page.acceptFiles([
    file(
      'old.png',
      async () => new ArrayBuffer(24),
      () => head.promise,
    ),
  ])
  await f.page.acceptFiles([file('new.png')])
  head.reject(Error('late header'))
  await pending
  assert.equal(f.page.entries.value.length, 2)
  assert.equal(f.page.message.value, '')
  f.page.clearAll()
  await f.page.acceptFiles([
    file(
      'bad.png',
      async () => new ArrayBuffer(24),
      async () => {
        throw Error('header')
      },
    ),
  ])
  assert.equal(f.page.entries.value.length, 0)
  assert.match(f.page.message.value, /读取图片信息失败/)
})

test('卸载会结束等待回复，迟到读取不创建新线程', async () => {
  const f = await mount()
  const read = deferred()
  f.page.entries.value = [
    entry(
      1,
      file('a.png', () => read.promise),
    ),
  ]
  const pending = f.page.run()
  f.close()
  read.resolve(new ArrayBuffer(24))
  await pending
  assert.equal(Worker.instances.length, 0)
  assert.equal(f.page.result.value, null)
  const g = await mount()
  g.page.entries.value = [entry(1)]
  const active = g.page.run()
  await settle()
  const w = Worker.instances[0]
  g.close()
  await active
  assert.equal(w.stopped, true)
  assert.equal(g.page.result.value, null)
})

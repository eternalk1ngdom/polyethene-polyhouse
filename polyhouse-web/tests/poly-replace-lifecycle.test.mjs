import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, nextTick } from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'

const url = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
function compile(source, replacements = {}) {
  let code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(replacements)) {
    code = code.replaceAll(`from '${from}'`, `from '${to}'`)
  }
  return url(code)
}
const core = compile(await readFile(new URL('../src/core/acReplacer.ts', import.meta.url), 'utf8'))
const batch = compile(
  await readFile(new URL('../src/core/polyReplaceBatch.ts', import.meta.url), 'utf8'),
  { './acReplacer': core },
)
const controls = url('export const RouterLink = {}, ElAlert = {}, ElButton = {}, ElInput = {}')
// 控制读取和 Worker 回调的先后，不靠机器速度碰巧点击到取消；真实算法另由 Worker 基准验收。
const fixtures = url(`
export const document = { createElement: () => ({ webkitdirectory: true }) };
export class Worker {
  static instances = [];
  constructor() { Worker.instances.push(this); this.stopped = false }
  postMessage(data) { this.data = data }
  terminate() { this.stopped = true }
}
`)
const { descriptor } = parse(
  await readFile(new URL('../src/views/PolyReplaceView.vue', import.meta.url), 'utf8'),
)
const compiled = compileScript(descriptor, { id: 'replace-lifecycle-test' }).content
const { default: component } = await import(
  compile(
    `import { document, Worker } from '${fixtures}';\n${compiled}`.replace(
      "new URL('../workers/polyReplace.worker.ts', import.meta.url)",
      "new URL('file:///fixture-worker.mjs')",
    ),
    {
      vue: import.meta.resolve('vue'),
      'vue-router': controls,
      'element-plus': controls,
      '../core/polyReplaceBatch': batch,
    },
  )
)
const { Worker } = await import(fixtures)
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
function mount() {
  Worker.instances.length = 0
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
  page.rules.value = [{ search: 'A', replace: 'B' }]
  return { page, unmount: () => app.unmount() }
}
const file = (arrayBuffer = async () => new TextEncoder().encode('A').buffer) => ({
  name: 'fixture.txt',
  arrayBuffer,
})
const reply = (text) => ({
  data: {
    ok: true,
    result: {
      files: [{ name: 'fixture.txt', preview: text, count: 1 }],
      bytes: new ArrayBuffer(1),
    },
  },
})

test('取消读取后，迟到的文件内容不会创建 Worker；原选择可以立即重试', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  let finish
  fixture.page.selectedFiles.value = [
    file(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    ),
  ]
  const pending = fixture.page.runReplace()
  assert.equal(fixture.page.busy.value, true)
  fixture.page.invalidate()
  finish(new ArrayBuffer(1))
  await pending
  assert.equal(Worker.instances.length, 0)
  assert.equal(fixture.page.result.value, null)
  fixture.page.selectedFiles.value = [file()]
  await fixture.page.runReplace()
  assert.equal(Worker.instances.length, 1)
})

test('取消 Worker 后的旧成功和旧错误不能污染新任务或解除新任务忙碌状态', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  fixture.page.selectedFiles.value = [file()]
  await fixture.page.runReplace()
  const old = Worker.instances[0]
  fixture.page.invalidate()
  assert.equal(old.stopped, true)
  await fixture.page.runReplace()
  old.onmessage(reply('old'))
  old.onerror()
  assert.equal(fixture.page.result.value, null)
  assert.equal(fixture.page.message.value, '')
  assert.equal(fixture.page.busy.value, true)
  const current = Worker.instances[1]
  current.onmessage(reply('current'))
  assert.equal(fixture.page.activePreview.value.preview, 'current')
  assert.equal(current.stopped, true)
})

test('规则或编码变化作废快照，移除文件和离开页面均终止 Worker', async () => {
  const fixture = mount()
  try {
    fixture.page.selectedFiles.value = [file(), { ...file(), name: 'second.txt' }]
    await fixture.page.runReplace()
    const rulesWorker = Worker.instances[0]
    fixture.page.rules.value[0].replace = 'C'
    assert.equal(rulesWorker.stopped, true)
    assert.equal(rulesWorker.data.rules[0].replace, 'B')
    await fixture.page.runReplace()
    const encodingWorker = Worker.instances[1]
    fixture.page.inputEncoding.value = 'gb18030'
    assert.equal(encodingWorker.stopped, true)
    await fixture.page.runReplace()
    const removedWorker = Worker.instances[2]
    await fixture.page.removeFile(0)
    assert.equal(removedWorker.stopped, true)
    assert.equal(fixture.page.selectedFiles.value.length, 1)
    await fixture.page.runReplace()
    assert.equal(fixture.page.busy.value, true)
  } finally {
    fixture.unmount()
  }
  assert.equal(Worker.instances.at(-1).stopped, true)
})

test('读取失败和 Worker 失败后可以重试，不保留旧结果', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  fixture.page.selectedFiles.value = [
    file(async () => {
      throw new Error('fixture')
    }),
  ]
  await fixture.page.runReplace()
  assert.match(fixture.page.message.value, /读取或启动/)
  assert.equal(fixture.page.busy.value, false)
  fixture.page.selectedFiles.value = [file()]
  await fixture.page.runReplace()
  Worker.instances[0].onerror()
  assert.equal(fixture.page.result.value, null)
  assert.equal(fixture.page.busy.value, false)
  await fixture.page.runReplace()
  Worker.instances[1].onmessage(reply('B'))
  await nextTick()
  assert.equal(fixture.page.activePreview.value.preview, 'B')
})

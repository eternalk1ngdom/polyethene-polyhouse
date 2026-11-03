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
const core = compile(
  await readFile(new URL('../../src/core/acReplacer.ts', import.meta.url), 'utf8'),
)
const batch = compile(
  await readFile(new URL('../../src/core/polyReplaceBatch.ts', import.meta.url), 'utf8'),
  { './acReplacer': core },
)
const controls = url(
  'export const RouterLink = {}, ElAlert = {}, ElButton = {}, ElCheckbox = {}, ElInput = {}, ElOption = {}, ElSelect = {}; export function onBeforeRouteLeave() {}',
)
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
  await readFile(new URL('../../src/views/PolyReplaceView.vue', import.meta.url), 'utf8'),
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
      '../components/RuleLibraryPanel.vue': url('export default {}'),
      '../components/ReplaceDraftPanel.vue': url('export default {}'),
      '../components/ReplaceResultPreview.vue': url('export default {}'),
      '../components/FileDropZone.vue': url('export default {}'),
      '../components/ToolAdvancedOptions.vue': url('export default {}'),
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
  size: 1,
  webkitRelativePath: '',
  arrayBuffer,
})
const reply = (text) => ({
  data: {
    ok: true,
    result: {
      files: [
        {
          name: 'fixture.txt',
          preview: text,
          count: 1,
          ruleCounts: [1],
          previewHits: [],
          truncated: false,
        },
      ],
      bytes: new ArrayBuffer(1),
    },
  },
})

test('高级面板收起不停止替换或清空结果，待处理来源主动展开', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  const page = fixture.page
  page.selectFiles([file()], 'files')
  await page.runReplace()
  const active = Worker.instances[0]
  page.advancedOpen.value = true
  page.advancedOpen.value = false
  await nextTick()
  assert.equal(page.busy.value, true)
  assert.equal(active.stopped, false)
  active.onmessage(reply('B'))
  const completed = page.result.value
  page.advancedOpen.value = true
  page.detailsOpen.value = true
  page.advancedOpen.value = false
  page.detailsOpen.value = false
  await nextTick()
  assert.equal(page.result.value, completed)
  page.updateDraftDisclosure(true, true)
  assert.equal(page.advancedOpen.value, true)
  assert.match(page.advancedSummary.value, /自动草稿已开启.*提示/)
  page.advancedOpen.value = false
  page.updateDraftDisclosure(true, false)
  assert.equal(page.advancedOpen.value, false)
  assert.equal(page.result.value, completed)
})

test('取消读取后，迟到的文件内容不会创建 Worker；原选择可以立即重试', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  let finish
  fixture.page.candidateFiles.value = [
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
  fixture.page.candidateFiles.value = [file()]
  await fixture.page.runReplace()
  assert.equal(Worker.instances.length, 1)
})

test('取消 Worker 后的旧成功和旧错误不能污染新任务或解除新任务忙碌状态', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  fixture.page.candidateFiles.value = [file()]
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

test('规则或编码变化作废快照，跳过文件和离开页面均终止 Worker', async () => {
  const fixture = mount()
  try {
    fixture.page.candidateFiles.value = [file(), { ...file(), name: 'second.txt' }]
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
    fixture.page.setFileParticipation(0, false)
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
  fixture.page.candidateFiles.value = [
    file(async () => {
      throw new Error('fixture')
    }),
  ]
  await fixture.page.runReplace()
  assert.match(fixture.page.message.value, /读取或启动/)
  assert.equal(fixture.page.busy.value, false)
  fixture.page.candidateFiles.value = [file()]
  await fixture.page.runReplace()
  Worker.instances[0].onerror()
  assert.equal(fixture.page.result.value, null)
  assert.equal(fixture.page.busy.value, false)
  await fixture.page.runReplace()
  Worker.instances[1].onmessage(reply('B'))
  await nextTick()
  assert.equal(fixture.page.activePreview.value.preview, 'B')
})

test('真实页面按当前文件和整批分别统计，规则/编码变化及迟到结果不复活旧计数', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  fixture.page.rules.value = [
    { search: 'A', replace: 'B' },
    { search: 'B', replace: '' },
    { search: 'Z', replace: 'X' },
  ]
  fixture.page.candidateFiles.value = [file(), { ...file(), name: 'second.txt' }]
  await fixture.page.runReplace()
  const completed = () => ({
    data: {
      ok: true,
      result: {
        files: [
          {
            name: 'first.txt',
            preview: 'BB',
            count: 3,
            ruleCounts: [2, 1, 0],
            previewHits: [
              { start: 0, end: 1, ruleIndex: 0 },
              { start: 1, end: 1, ruleIndex: 1 },
              { start: 1, end: 2, ruleIndex: 0 },
            ],
            truncated: false,
          },
          {
            name: 'second.txt',
            preview: 'B',
            count: 3,
            ruleCounts: [1, 2, 0],
            previewHits: [
              { start: 0, end: 0, ruleIndex: 1 },
              { start: 0, end: 0, ruleIndex: 1 },
              { start: 0, end: 1, ruleIndex: 0 },
            ],
            truncated: false,
          },
        ],
        bytes: new ArrayBuffer(0),
        name: 'fixture.zip',
        mime: 'application/zip',
      },
    },
  })
  const old = Worker.instances[0]
  old.onmessage(completed())
  assert.deepEqual(fixture.page.activePreview.value.ruleCounts, [2, 1, 0])
  assert.deepEqual(fixture.page.totalRuleCounts.value, [3, 3, 0])
  assert.equal(fixture.page.totalCount.value, 6)
  assert.deepEqual(fixture.page.previewHitCounts.value, [2, 1, 0])
  fixture.page.previewIndex.value = 1
  assert.deepEqual(fixture.page.activePreview.value.ruleCounts, [1, 2, 0])
  assert.deepEqual(fixture.page.previewHitCounts.value, [1, 2, 0])
  assert.deepEqual(fixture.page.totalRuleCounts.value, [3, 3, 0])
  fixture.page.rules.value[0].replace = 'C'
  assert.equal(fixture.page.result.value, null)
  assert.deepEqual(fixture.page.totalRuleCounts.value, [0, 0, 0])
  assert.deepEqual(fixture.page.previewHitCounts.value, [0, 0, 0])
  old.onmessage(completed())
  assert.equal(fixture.page.result.value, null)
  assert.deepEqual(fixture.page.totalRuleCounts.value, [0, 0, 0])
  assert.deepEqual(fixture.page.previewHitCounts.value, [0, 0, 0])
  await fixture.page.runReplace()
  Worker.instances[1].onmessage(completed())
  fixture.page.inputEncoding.value = 'gb18030'
  assert.equal(fixture.page.result.value, null)
  assert.deepEqual(fixture.page.totalRuleCounts.value, [0, 0, 0])
  assert.deepEqual(fixture.page.previewHitCounts.value, [0, 0, 0])
  assert.equal(fixture.page.selectedFiles.value.length, 2)
})

function choose(page, files, mode = 'files') {
  const input = { files, value: 'fixture' }
  page.selectFile({ target: input }, mode)
  return input
}
const namedFile = (
  name,
  path = '',
  size = 1,
  read = async () => new TextEncoder().encode('A').buffer,
) => ({ name, webkitRelativePath: path, size, arrayBuffer: read })

test('候选默认全部参与，无目录/扩展名排除，取消可选回且不读取正文', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  let reads = 0
  const files = [
    'root/.git/config',
    'root/node_modules/a.js',
    'root/.venv/a.py',
    'root/中文😀.txt',
  ].map((path) =>
    namedFile(path.split('/').at(-1), path, 1, async () => {
      reads++
      return new ArrayBuffer(1)
    }),
  )
  assert.equal(choose(fixture.page, files, 'directory').value, '')
  assert.equal(fixture.page.candidateFiles.value.length, 4)
  assert.equal(fixture.page.selectedFiles.value.length, 4)
  assert.equal(fixture.page.selectionIssue.value, '')
  fixture.page.setFileParticipation(1, false)
  assert.deepEqual(
    fixture.page.selectedFiles.value.map((x) => x.webkitRelativePath),
    [files[0].webkitRelativePath, files[2].webkitRelativePath, files[3].webkitRelativePath],
  )
  fixture.page.setFileParticipation(1, true)
  assert.deepEqual(
    fixture.page.selectedFiles.value.map((x) => x.webkitRelativePath),
    files.map((x) => x.webkitRelativePath),
  )
  fixture.page.setAllParticipation(false)
  assert.match(fixture.page.selectionIssue.value, /至少/)
  assert.equal(fixture.page.candidateFiles.value.length, 4)
  await fixture.page.runReplace()
  assert.equal(reads, 0)
  assert.equal(Worker.instances.length, 0)
  fixture.page.setAllParticipation(true)
  assert.equal(fixture.page.selectedFiles.value.length, 4)
  assert.equal(reads, 0)
})

test('21 到 500 个候选可先筛选；501 整次拒绝且不截断、不覆盖旧清单/结果', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  const files = Array.from({ length: 500 }, (_, i) => namedFile(`file${i}.txt`))
  choose(fixture.page, files.slice(0, 21))
  assert.equal(fixture.page.candidateFiles.value.length, 21)
  assert.match(fixture.page.selectionIssue.value, /20/)
  await fixture.page.runReplace()
  assert.equal(Worker.instances.length, 0)
  fixture.page.setFileParticipation(20, false)
  assert.equal(fixture.page.selectionIssue.value, '')
  choose(fixture.page, files)
  assert.equal(fixture.page.candidateFiles.value.length, 500)
  assert.equal(fixture.page.selectedFiles.value.length, 500)
  fixture.page.setAllParticipation(false)
  fixture.page.setFileParticipation(499, true)
  assert.equal(fixture.page.selectionIssue.value, '')
  await fixture.page.runReplace()
  Worker.instances[0].onmessage(reply('B'))
  const previous = fixture.page.result.value
  // 超限时只看 length，不先把整个 FileList 复制到页面。
  const excess = {
    length: 501,
    get 0() {
      throw new Error('不应枚举超限候选')
    },
  }
  assert.equal(choose(fixture.page, excess, 'directory').value, '')
  assert.equal(fixture.page.candidateFiles.value.length, 500)
  assert.equal(fixture.page.selectedMode.value, 'files')
  assert.equal(fixture.page.selectedFiles.value[0].name, 'file499.txt')
  assert.equal(fixture.page.result.value, previous)
  assert.match(fixture.page.message.value, /500.*未载入/)
  choose(fixture.page, [], 'directory')
  assert.equal(fixture.page.result.value, previous)
})

test('参与集合保留大小与路径校验，跳过问题项即可修正，不把候选限制当处理扩容', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  choose(
    fixture.page,
    [
      namedFile('huge.txt', 'root/huge.txt', 2 * 1024 * 1024 + 1),
      namedFile('ok.txt', 'root/ok.txt'),
    ],
    'directory',
  )
  assert.match(fixture.page.selectionIssue.value, /2 MiB/)
  await fixture.page.runReplace()
  assert.equal(Worker.instances.length, 0)
  fixture.page.setFileParticipation(0, false)
  assert.equal(fixture.page.selectionIssue.value, '')
  choose(
    fixture.page,
    Array.from({ length: 3 }, (_, i) => namedFile(`${i}.txt`, '', 2 * 1024 * 1024)),
  )
  assert.match(fixture.page.selectionIssue.value, /5 MiB/)
  fixture.page.setFileParticipation(2, false)
  assert.equal(fixture.page.selectionIssue.value, '')
  choose(
    fixture.page,
    [namedFile('A.txt', 'root/A.txt'), namedFile('a.txt', 'root/a.txt')],
    'directory',
  )
  assert.match(fixture.page.selectionIssue.value, /冲突/)
  fixture.page.setFileParticipation(1, false)
  assert.equal(fixture.page.selectionIssue.value, '')
  choose(
    fixture.page,
    [namedFile('a.txt', 'root/../a.txt'), namedFile('ok.txt', 'root/ok.txt')],
    'directory',
  )
  assert.match(fixture.page.selectionIssue.value, /名称/)
  fixture.page.setFileParticipation(0, false)
  assert.equal(fixture.page.selectionIssue.value, '')
})

test('普通多选与目录共用勾选，只读取参与正文，保留浏览器顺序和原相对路径', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  const reads = []
  const files = Array.from({ length: 24 }, (_, i) =>
    namedFile(`${i}.txt`, `root/sub/${i}.txt`, 1, async () => {
      reads.push(i)
      return new TextEncoder().encode('A').buffer
    }),
  )
  choose(fixture.page, files, 'directory')
  fixture.page.setAllParticipation(false)
  fixture.page.setFileParticipation(23, true)
  fixture.page.setFileParticipation(0, true)
  await fixture.page.runReplace()
  assert.deepEqual(reads, [0, 23])
  assert.equal(Worker.instances[0].data.mode, 'directory')
  assert.deepEqual(
    Worker.instances[0].data.files.map((x) => x.relativePath),
    ['root/sub/0.txt', 'root/sub/23.txt'],
  )
  choose(fixture.page, files.slice(0, 2))
  assert.equal(Worker.instances[0].stopped, true)
  assert.equal(fixture.page.selectedFiles.value.length, 2)
  assert.equal(fixture.page.selectedMode.value, 'files')
  await fixture.page.runReplace()
  assert.deepEqual(
    Worker.instances[1].data.files.map((x) => x.relativePath),
    ['0.txt', '1.txt'],
  )
})

test('读取期间改变勾选使迟到读取失效，后台阶段改变勾选阻止旧结果复活', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  let finish,
    skippedReads = 0
  choose(fixture.page, [
    namedFile(
      'a.txt',
      '',
      1,
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    ),
    namedFile('b.txt', '', 1, async () => {
      skippedReads++
      return new ArrayBuffer(1)
    }),
  ])
  const pending = fixture.page.runReplace()
  fixture.page.setFileParticipation(1, false)
  finish(new ArrayBuffer(1))
  await pending
  assert.equal(skippedReads, 0)
  assert.equal(Worker.instances.length, 0)
  assert.equal(fixture.page.busy.value, false)
  assert.match(fixture.page.message.value, /取消旧任务/)
  choose(fixture.page, [namedFile('c.txt')])
  await fixture.page.runReplace()
  const old = Worker.instances[0]
  fixture.page.setAllParticipation(false)
  old.onmessage(reply('late'))
  old.onerror()
  assert.equal(old.stopped, true)
  assert.equal(fixture.page.result.value, null)
  assert.match(fixture.page.selectionIssue.value, /至少/)
  fixture.page.setAllParticipation(true)
  await fixture.page.runReplace()
  Worker.instances[1].onmessage(reply('new'))
  assert.equal(fixture.page.activePreview.value.preview, 'new')
  fixture.page.setFileParticipation(0, false)
  assert.equal(fixture.page.result.value, null)
  assert.deepEqual(fixture.page.previewHitCounts.value, [0])
})

test('无效索引/非布尔事件/重复状态不作废结果，候选上限拒绝不停止原任务', async (t) => {
  const fixture = mount()
  t.after(fixture.unmount)
  choose(fixture.page, [namedFile('a.txt')])
  await fixture.page.runReplace()
  const active = Worker.instances[0]
  choose(fixture.page, { length: 501 })
  assert.equal(active.stopped, false)
  assert.equal(fixture.page.busy.value, true)
  active.onmessage(reply('B'))
  const result = fixture.page.result.value
  for (const [index, value] of [
    [-1, false],
    [1, false],
    [0, 'false'],
    [0, true],
  ])
    fixture.page.setFileParticipation(index, value)
  fixture.page.setAllParticipation(true)
  assert.equal(fixture.page.result.value, result)
})

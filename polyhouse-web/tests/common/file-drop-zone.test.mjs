import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, nextTick, ref } from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'

const url = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const controls = url(
  'export const ElButton = {}, ElButtonGroup = {}, ElDropdown = {}, ElDropdownItem = {}, ElDropdownMenu = {}, ElIcon = {}, ArrowDown = {}, Upload = {};',
)
const { descriptor } = parse(
  await readFile(new URL('../../src/components/FileDropZone.vue', import.meta.url), 'utf8'),
)
const source = compileScript(descriptor, { id: 'file-drop-zone-test' }).content
const code = ts
  .transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  })
  .outputText.replaceAll("from 'vue'", `from '${import.meta.resolve('vue')}'`)
  .replaceAll("from 'element-plus'", `from '${controls}'`)
  .replaceAll("from '@element-plus/icons-vue'", `from '${controls}'`)
const { default: component } = await import(url(code))
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

test('点击与拖入交付完整文件引用，取消不改变状态，目录拒绝与禁用无法绕过', async (t) => {
  const selected = [],
    rejected = [],
    disabled = ref(false),
    directoryEnabled = ref(false)
  let directoryRequests = 0,
    fileRequests = 0
  let input
  const app = renderer.createApp({
    setup: () => () =>
      h(
        {
          ...component,
          setup(props, context) {
            input = component.setup(props, context)
            return () => null
          },
        },
        {
          accept: '.txt',
          label: '选择文件',
          description: '支持多选',
          helpId: 'help',
          disabled: disabled.value,
          directoryEnabled: directoryEnabled.value,
          onSelect: (files) => selected.push(files),
          onReject: (message) => rejected.push(message),
          onDirectory: () => directoryRequests++,
        },
      ),
  })
  app.mount({})
  t.after(() => app.unmount())
  input.picker.value = { click: () => fileRequests++ }
  input.openFiles()
  assert.equal(fileRequests, 1)
  input.chooseAlternative('directory')
  assert.equal(directoryRequests, 0)
  directoryEnabled.value = true
  await nextTick()
  for (const invalid of ['files', null, {}, 1]) input.chooseAlternative(invalid)
  assert.equal(directoryRequests, 0)
  input.chooseAlternative('directory')
  assert.equal(directoryRequests, 1)
  const first = { name: 'a.txt' },
    second = { name: 'b.txt' }
  const picker = { files: [first, second], value: 'a.txt' }
  input.onSelect({ target: picker })
  assert.equal(picker.value, '')
  assert.deepEqual(selected, [[first, second]])
  input.onSelect({ target: { files: [], value: '' } })
  assert.equal(selected.length, 1)
  let prevented = 0
  const drop = (items, files) => ({
    preventDefault: () => prevented++,
    dataTransfer: { items, files },
  })
  input.onDrop(drop([], [second, first]))
  assert.deepEqual(selected[1], [second, first])
  input.onDrop(drop([{ kind: 'file', webkitGetAsEntry: () => ({ isDirectory: true }) }], [first]))
  assert.equal(rejected.length, 1)
  assert.equal(selected.length, 2)
  disabled.value = true
  await nextTick()
  input.openFiles()
  input.chooseAlternative('directory')
  assert.equal(fileRequests, 1)
  assert.equal(directoryRequests, 1)
  input.onSelect({ target: picker })
  input.onDrop(drop([], [first]))
  assert.equal(selected.length, 2)
  assert.equal(prevented, 3)
})

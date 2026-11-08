import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h } from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'

const url = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const fixtures = url(`
import { ref } from '${import.meta.resolve('vue')}';
export const manager = {
  ready: ref(true), enabled: ref(false), paused: ref(false), dirty: ref(false), saving: ref(false),
  history: ref([]), selectedId: ref(''), selected: ref(null), message: ref(''), cleanupFailed: ref(false),
  locationWarning: ref(''), state: ref({ status: 'ready' }), initialize() {}, dispose() {},
};
export function useReplaceDraft() { return manager }
export const window = { addEventListener() {}, removeEventListener() {} };
export const document = window;
export const ElButton = {}, ElCheckbox = {}, ElDialog = {}, ElOption = {}, ElSelect = {};
export const REPLACE_DRAFT_STORAGE_KEY = 'fixture';
export function isEmptyReplaceDraft() { return true }
export default {};
`)
const { descriptor } = parse(
  await readFile(new URL('../../src/components/ReplaceDraftPanel.vue', import.meta.url), 'utf8'),
)
let code = ts.transpileModule(
  `import {window, document} from '${fixtures}';\n` +
    compileScript(descriptor, { id: 'draft-disclosure' }).content,
  { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } },
).outputText
for (const match of [...code.matchAll(/from ['"]([^'"]+)['"]/g)])
  code = code.replaceAll(
    match[0],
    `from '${match[1] === 'vue' ? import.meta.resolve('vue') : fixtures}'`,
  )
const { default: component } = await import(url(code))
const { manager } = await import(fixtures)
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

test('草稿保存成功不提示异常，待恢复/存储失败/分享提示穿透高级折叠', (t) => {
  const events = []
  let panel
  const app = renderer.createApp({
    setup: () => () =>
      h(
        {
          ...component,
          setup(props, context) {
            panel = component.setup(props, context)
            return () => null
          },
        },
        { rules: [], onDisclosure: (...args) => events.push(args) },
      ),
  })
  app.mount({})
  t.after(() => app.unmount())
  assert.deepEqual(events.at(-1), [false, false])
  manager.enabled.value = true
  for (const status of ['ready', 'saved', 'disabled', 'cancelled']) {
    manager.state.value = { status }
    assert.deepEqual(events.at(-1), [true, false])
  }
  for (const status of [
    'read-error',
    'write-error',
    'invalid-storage',
    'lock-unavailable',
    'stale',
    'active',
    'invalid',
  ]) {
    manager.state.value = { status }
    assert.deepEqual(events.at(-1), [true, true])
  }
  manager.state.value = { status: 'saved' }
  manager.paused.value = true
  assert.deepEqual(events.at(-1), [true, true])
  manager.paused.value = false
  panel.shareAttention.value = true
  assert.deepEqual(events.at(-1), [true, true])
  panel.shareAttention.value = false
  assert.deepEqual(events.at(-1), [true, false])
})

import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, reactive } from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'

const { descriptor } = parse(
  await readFile(new URL('../../src/components/ReplaceResultPreview.vue', import.meta.url), 'utf8'),
)
const script = compileScript(descriptor, { id: 'preview-test' }).content
const controls = `data:text/javascript;base64,${Buffer.from('export const ElButton = {}').toString('base64')}`
const compiled = ts
  .transpileModule(script, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  })
  .outputText.replaceAll("from 'vue'", `from '${import.meta.resolve('vue')}'`)
  .replaceAll("from 'element-plus'", `from '${controls}'`)
const { default: component } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
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
function mount(file) {
  let page
  const props = reactive({ file })
  const app = renderer.createApp({
    setup: () => () =>
      h({
        ...component,
        setup(_, context) {
          page = component.setup(props, context)
          return () => null
        },
      }),
  })
  app.mount({})
  return { page, props, unmount: () => app.unmount() }
}

test('真实预览组件按输出片段恢复原文，连续删除保持独立且不混入正文', (t) => {
  const text = '甲🌸\n<b>字面</b>尾'
  const fixture = mount({
    preview: text,
    ruleCounts: [1, 2],
    previewHits: [
      { start: 1, end: 3, ruleIndex: 0 },
      { start: 3, end: 3, ruleIndex: 1 },
      { start: 3, end: 3, ruleIndex: 1 },
    ],
  })
  t.after(fixture.unmount)
  assert.equal(fixture.page.parts.value.map((part) => part.text).join(''), text)
  assert.equal(fixture.page.parts.value.filter((part) => part.deleted).length, 2)
  assert.equal(
    fixture.page.parts.value.filter((part) => part.deleted).every((part) => part.text === ''),
    true,
  )
  assert.equal(fixture.page.parts.value.at(-1).text, '\n<b>字面</b>尾')
})

test('按原规则定位第一处并前后切换，边界不越界，不存在位置时不伪定位', async (t) => {
  const fixture = mount({
    preview: 'ABC',
    ruleCounts: [2, 5, 0],
    previewHits: [
      { start: 0, end: 1, ruleIndex: 0 },
      { start: 1, end: 1, ruleIndex: 1 },
      { start: 1, end: 2, ruleIndex: 0 },
    ],
  })
  t.after(fixture.unmount)
  const focused = [],
    scrolled = []
  fixture.page.preview.value = {
    focus: (options) => focused.push(options),
    querySelector: (selector) => ({
      scrollIntoView: (options) => scrolled.push({ selector, options }),
    }),
  }
  await fixture.page.locateRule(0)
  assert.equal(fixture.page.activeHit.value, 0)
  assert.deepEqual(fixture.page.available.value, [0, 2])
  await fixture.page.move(-1)
  assert.equal(scrolled.length, 1)
  await fixture.page.move(1)
  assert.equal(fixture.page.activeHit.value, 2)
  await fixture.page.move(1)
  assert.equal(scrolled.length, 2)
  await fixture.page.move(-1)
  assert.equal(fixture.page.position.value, 0)
  await fixture.page.locateRule(1)
  assert.equal(fixture.page.activeHit.value, 1)
  assert.equal(fixture.page.available.value.length, 1)
  await fixture.page.locateRule(2)
  assert.equal(fixture.page.activeRule.value, 1)
  assert.equal(focused.length, 4)
  assert.match(scrolled.at(-1).selector, /"1"/)
})

import { readFile } from 'node:fs/promises'
import ts from 'typescript'
import { createRenderer, h } from 'vue'
import { compileScript, compileTemplate, parse } from 'vue/compiler-sfc'

/** 将编译后的 ESM 代码编码为 data URL，便于 Node 直接动态导入。 */
export function dataUrl(code) {
  return `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
}

/**
 * 在内存中编译真实 TypeScript 源码为可导入的 data URL。
 * 调用方传入绝对 URL 与显式模块替换映射，不跨测试缓存可变状态。
 */
export async function typeScriptModuleUrl(sourceUrl, replacements = {}) {
  const source = await readFile(sourceUrl, 'utf8')
  let code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(replacements)) {
    code = code.replaceAll(from, to)
  }
  return dataUrl(code)
}

/**
 * 编译 Vue 单文件组件（SFC）为可直接挂载的组件对象（含 render 函数）。
 * 仅调用一次 compileScript 并复用其 bindings，避免重复编译。
 */
export async function compileSfcComponent(sourceUrl, id, replacements = {}) {
  const source = await readFile(sourceUrl, 'utf8')
  const filename = sourceUrl instanceof URL ? sourceUrl.pathname : String(sourceUrl)
  const { descriptor } = parse(source, { filename })
  const script = compileScript(descriptor, { id })
  const vueUrl = import.meta.resolve('vue')
  let scriptCode = ts
    .transpileModule(script.content, {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    })
    .outputText.replaceAll("from 'vue'", `from '${vueUrl}'`)
    .replaceAll('from "vue"', `from '${vueUrl}'`)
  for (const [from, to] of Object.entries(replacements)) {
    scriptCode = scriptCode.replaceAll(from, to)
  }

  const templateCode = compileTemplate({
    source: descriptor.template.content,
    filename,
    id,
    compilerOptions: { bindingMetadata: script.bindings },
  })
    .code.replaceAll('from "vue"', `from '${vueUrl}'`)
    .replaceAll("from 'vue'", `from '${vueUrl}'`)

  const { default: component } = await import(dataUrl(scriptCode))
  const { render } = await import(dataUrl(templateCode))
  return { ...component, render }
}

/** 创建轻量无头内存 DOM 渲染器，用于在 Node 环境下挂载真实 Vue 组件并查询渲染结果。 */
export function createHeadlessDom() {
  const makeNode = (type, tag = '') => ({
    type,
    tag,
    text: '',
    props: {},
    children: [],
    parent: null,
  })
  const renderer = createRenderer({
    patchProp(el, key, _prev, next) {
      el.props[key] = next
    },
    insert(child, parent, anchor) {
      if (!parent) return
      child.parent = parent
      const idx = anchor ? parent.children.indexOf(anchor) : -1
      if (idx >= 0) parent.children.splice(idx, 0, child)
      else parent.children.push(child)
    },
    remove(child) {
      if (!child.parent) return
      const idx = child.parent.children.indexOf(child)
      if (idx >= 0) child.parent.children.splice(idx, 1)
      child.parent = null
    },
    createElement: (tag) => makeNode('el', tag),
    createText: (text) => {
      const node = makeNode('text')
      node.text = text
      return node
    },
    createComment: (text) => {
      const node = makeNode('comment')
      node.text = text
      return node
    },
    setText(node, text) {
      node.text = text
    },
    setElementText(el, text) {
      el.children = [{ type: 'text', tag: '', text, props: {}, children: [], parent: el }]
    },
    parentNode: (node) => node.parent,
    nextSibling(node) {
      if (!node.parent) return null
      const idx = node.parent.children.indexOf(node)
      return idx >= 0 ? (node.parent.children[idx + 1] ?? null) : null
    },
  })
  return { root: makeNode('root', 'div'), renderer }
}

/** 递归收集无头 DOM 节点中的可见文本。 */
export function collectText(node) {
  if (node.type === 'text') return node.text
  if (node.type === 'comment') return ''
  return (node.children || []).map(collectText).join('')
}

/** 递归查找满足条件的所有无头 DOM 节点。 */
export function findAll(node, predicate, out = []) {
  if (predicate(node)) out.push(node)
  for (const child of node.children || []) findAll(child, predicate, out)
  return out
}

/**
 * 挂载组件并自动向 node:test 注册 t.after 卸载清理，避免断言失败时遗留活跃组件实例。
 */
export function mountHeadless(t, component, props = {}) {
  const { root, renderer } = createHeadlessDom()
  const app = renderer.createApp({
    render: () => h(component, props),
  })
  app.mount(root)
  let unmounted = false
  const unmount = () => {
    if (unmounted) return
    unmounted = true
    app.unmount()
  }
  t.after(unmount)
  return { root, unmount }
}

/**
 * 安全替换 globalThis 上的属性并在测试结束时按原属性描述符还原；
 * 若原属性不存在则使用 delete 彻底移除，避免跨用例污染。
 */
export function patchGlobal(t, key, value) {
  const hadOwn = Object.prototype.hasOwnProperty.call(globalThis, key)
  const previous = hadOwn ? Object.getOwnPropertyDescriptor(globalThis, key) : undefined
  Object.defineProperty(globalThis, key, {
    value,
    configurable: true,
    writable: true,
    enumerable: true,
  })
  t.after(() => {
    if (hadOwn && previous) {
      Object.defineProperty(globalThis, key, previous)
    } else {
      delete globalThis[key]
    }
  })
}

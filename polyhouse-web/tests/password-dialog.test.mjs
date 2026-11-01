import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, nextTick, reactive, ref } from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'

const codeUrl = (source) => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
function compile(source, replacements) {
  let output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
  for (const [from, to] of Object.entries(replacements)) {
    output = output.replaceAll(`from '${from}'`, `from '${to}'`).replaceAll(`from "${from}"`, `from '${to}'`)
  }
  return codeUrl(output)
}

const userUrl = codeUrl('let store; export const useUserStore = () => store; export const install = value => { store = value }')
const uiUrl = codeUrl('export const useAccountUiStore = () => ({ pending: null, actionError: "" })')
const controlsUrl = codeUrl('export const ElAlert = {}, ElButton = {}, ElDialog = {}, ElForm = {}, ElFormItem = {}, ElInput = {}')
const apiUrl = compile(await readFile(new URL('../src/api/index.ts', import.meta.url), 'utf8'), { axios: import.meta.resolve('axios') })
const validationUrl = compile(await readFile(new URL('../src/utils/authValidation.ts', import.meta.url), 'utf8'), {})
const { descriptor } = parse(await readFile(new URL('../src/components/PasswordChangeDialog.vue', import.meta.url), 'utf8'))
const { default: component } = await import(compile(compileScript(descriptor, { id: 'password-dialog-test' }).content, {
  vue: import.meta.resolve('vue'), 'element-plus': controlsUrl, '../api': apiUrl,
  '../stores/useUserStore': userUrl, '../stores/useAccountUiStore': uiUrl, '../utils/authValidation': validationUrl,
}))
const { install } = await import(userUrl)
const { ApiError } = await import(apiUrl)
const renderer = createRenderer({
  createElement: () => ({}), createText: () => ({}), createComment: () => ({}), insert() {}, remove() {},
  setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null,
})

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

// 实际运行组件的 setup、watch、校验等待与卸载逻辑，不用源码字符串断言替代行为验证。
function mountDialog(changePassword) {
  const store = reactive({ token: 'fixture', isLoggedIn: true, requiresPasswordChange: false, changePassword })
  install(store)
  const open = ref(true)
  let dialog
  const subject = { ...component, setup(props, context) { dialog = component.setup(props, context); return () => null } }
  const app = renderer.createApp({ setup: () => () => h(subject, {
    modelValue: open.value, 'onUpdate:modelValue': (value) => { open.value = value },
  }) })
  app.mount({})
  dialog.formRef.value = { validate: async () => true, clearValidate() {} }
  const fill = () => Object.assign(dialog.form, { currentPassword: 'fixture-old', newPassword: 'fixture-new', confirmation: 'fixture-new' })
  return { dialog, store, open, fill, unmount: () => app.unmount() }
}

test('成功关闭表单并清除全部密码，重复校验结束不会发送两次改密', async (t) => {
  let calls = 0
  const fixture = mountDialog(async (request) => {
    calls += 1
    assert.deepEqual(request, { currentPassword: 'fixture-old', newPassword: 'fixture-new' })
    return true
  })
  t.after(fixture.unmount)
  fixture.fill()
  await Promise.all([fixture.dialog.save(), fixture.dialog.save()])
  await nextTick()
  assert.equal(calls, 1)
  assert.equal(fixture.open.value, false)
  assert.equal(fixture.dialog.form.currentPassword, '')
  assert.equal(fixture.dialog.form.newPassword, '')
})

test('换号后的旧失败不能污染新表单，也不能解除新请求的忙碌状态', async (t) => {
  const old = deferred(), current = deferred()
  let calls = 0
  const fixture = mountDialog(() => ++calls === 1 ? old.promise : current.promise)
  t.after(fixture.unmount)
  fixture.fill()
  const previous = fixture.dialog.save()
  await nextTick()
  fixture.store.token = 'new-fixture'
  await nextTick()
  fixture.fill()
  const next = fixture.dialog.save()
  await nextTick()
  old.reject(new ApiError('old failure', 400))
  await previous
  assert.equal(fixture.dialog.error.value, '')
  assert.equal(fixture.dialog.saving.value, true)
  current.resolve(true)
  await next
})

test('校验期间关闭或卸载表单后不再发送请求，初始凭据不允许直接关闭', async (t) => {
  let calls = 0
  const fixture = mountDialog(async () => { calls += 1; return true })
  t.after(fixture.unmount)
  fixture.store.requiresPasswordChange = true
  fixture.dialog.close()
  assert.equal(fixture.open.value, true)
  const validation = deferred()
  fixture.dialog.formRef.value.validate = () => validation.promise
  fixture.fill()
  const pending = fixture.dialog.save()
  fixture.store.isLoggedIn = false
  await nextTick()
  validation.resolve(true)
  await pending
  assert.equal(calls, 0)
  assert.equal(fixture.dialog.form.confirmation, '')
})

test('网络异常明确提示结果待核对并清除密码，不自动重试', async (t) => {
  let calls = 0
  const fixture = mountDialog(async () => { calls += 1; throw new ApiError('network', 0) })
  t.after(fixture.unmount)
  fixture.fill()
  await fixture.dialog.save()
  assert.equal(calls, 1)
  assert.match(fixture.dialog.error.value, /结果尚未确认/)
  assert.equal(fixture.dialog.form.newPassword, '')
  assert.equal(fixture.dialog.saving.value, false)
})

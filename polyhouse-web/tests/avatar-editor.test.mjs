import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, nextTick, reactive, ref } from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'

const codeUrl = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
function compile(code, replacements = {}) {
  let output = ts.transpileModule(code, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(replacements)) {
    output = output.replaceAll(`from '${from}'`, `from '${to}'`)
    output = output.replaceAll(`from "${from}"`, `from '${to}'`)
  }
  return codeUrl(output)
}

const apiUrl = compile(await readFile(new URL('../src/api/index.ts', import.meta.url), 'utf8'), {
  axios: import.meta.resolve('axios'),
})
const authUrl = compile(await readFile(new URL('../src/api/auth.ts', import.meta.url), 'utf8'), { './index': apiUrl })
const cropUrl = compile(
  await readFile(new URL('../src/core/avatarCrop.ts', import.meta.url), 'utf8'),
)
const storeUrl = codeUrl(
  'let store; export const useUserStore = () => store; export const install = value => { store = value }',
)
const controlsUrl = codeUrl(
  'export const ElAlert = {}, ElButton = {}, ElDialog = {}, ElIcon = {}, Minus = {}, Plus = {}, RefreshLeft = {}, Upload = {}',
)
const { descriptor } = parse(
  await readFile(new URL('../src/components/AvatarUploadDialog.vue', import.meta.url), 'utf8'),
)
const { default: component } = await import(
  compile(compileScript(descriptor, { id: 'avatar-editor-test' }).content, {
    vue: import.meta.resolve('vue'),
    'element-plus': controlsUrl,
    '@element-plus/icons-vue': controlsUrl,
    '../api': apiUrl,
    '../api/auth': authUrl,
    '../core/avatarCrop': cropUrl,
    '../stores/useUserStore': storeUrl,
  })
)
const { install } = await import(storeUrl)
const { ApiError } = await import(apiUrl)

// 运行真实 SFC 的 setup、watch 和卸载钩子；只替换画布及网络，不需要新增 DOM/测试框架依赖。
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

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

function mountEditor(upload) {
  const store = reactive({ token: 'editor-fixture', uploadAvatar: upload })
  install(store)
  const open = ref(true)
  const updates = [],
    saved = []
  let editor
  const subject = {
    ...component,
    setup(props, context) {
      editor = component.setup(props, context)
      return () => null
    },
  }
  const app = renderer.createApp({
    setup: () => () =>
      h(subject, {
        modelValue: open.value,
        'onUpdate:modelValue': (value) => {
          updates.push(value)
          open.value = value
        },
        onSaved: (value) => saved.push(value),
      }),
  })
  app.mount({})
  const image = () => {
    const bitmap = {
      width: 256,
      height: 256,
      closed: false,
      close() {
        this.closed = true
      },
    }
    editor.image.value = bitmap
    editor.canvas.value = {
      toBlob: (callback) => callback(new Blob(['fixture'], { type: 'image/jpeg' })),
    }
    return bitmap
  }
  return { editor, store, open, updates, saved, image, unmount: () => app.unmount() }
}

test('当前上传明确拒绝后保留裁剪图，可以重试成功且只提示一次', async (t) => {
  let attempts = 0
  const fixture = mountEditor(async () => {
    if (++attempts === 1) throw new ApiError('图片不符合要求', 400)
    return { status: 'PENDING', remainingToday: 4 }
  })
  t.after(fixture.unmount)
  const image = fixture.image()
  await fixture.editor.save()
  assert.equal(fixture.editor.error.value, '图片不符合要求')
  assert.equal(fixture.editor.saving.value, false)
  assert.equal(fixture.editor.image.value, image)
  assert.equal(image.closed, false)
  assert.deepEqual(fixture.updates, [])
  await fixture.editor.save()
  assert.deepEqual(fixture.saved, [4])
  assert.deepEqual(fixture.updates, [false])
})

test('旧上传失败不能污染重开的编辑器，也不能解除新上传的保存状态', async (t) => {
  const old = deferred(),
    current = deferred()
  let attempts = 0
  const fixture = mountEditor(() => (++attempts === 1 ? old.promise : current.promise))
  t.after(fixture.unmount)
  fixture.image()
  const previousSave = fixture.editor.save()
  await nextTick()
  fixture.open.value = false
  await nextTick()
  fixture.open.value = true
  await nextTick()
  fixture.image()
  const currentSave = fixture.editor.save()
  await nextTick()
  assert.equal(attempts, 2)
  old.reject(new ApiError('旧请求失败', 503))
  await previousSave
  assert.equal(fixture.editor.error.value, '')
  assert.equal(fixture.editor.saving.value, true)
  current.resolve({ status: 'PENDING', remainingToday: 3 })
  await currentSave
  assert.deepEqual(fixture.saved, [3])
})

test('换号后的旧成功响应不能关闭新弹窗或显示旧账号保存成功', async (t) => {
  const pending = deferred()
  const fixture = mountEditor(() => pending.promise)
  t.after(fixture.unmount)
  fixture.image()
  const saving = fixture.editor.save()
  await nextTick()
  fixture.store.token = 'another-fixture'
  await nextTick()
  fixture.open.value = true
  await nextTick()
  fixture.image()
  fixture.updates.length = 0
  pending.resolve(null) // 会话层已经丢弃旧账号成功响应，弹窗也必须丢弃这次操作。
  await saving
  assert.deepEqual(fixture.updates, [])
  assert.deepEqual(fixture.saved, [])
  assert.equal(fixture.open.value, true)
  assert.equal(fixture.editor.saving.value, false)
})

test('旧画布编码回调在关闭重开后不发上传请求', async (t) => {
  let uploads = 0,
    finishEncoding
  const fixture = mountEditor(async () => {
    uploads++
    return null
  })
  t.after(fixture.unmount)
  fixture.image()
  fixture.editor.canvas.value = {
    toBlob: (callback) => {
      finishEncoding = callback
    },
  }
  const saving = fixture.editor.save()
  fixture.open.value = false
  await nextTick()
  fixture.open.value = true
  await nextTick()
  fixture.image()
  finishEncoding(new Blob(['old-fixture']))
  await saving
  assert.equal(uploads, 0)
  assert.deepEqual(fixture.updates, [])
})

test('本机编码失败不发请求，保留图片并提示重新选择', async (t) => {
  let uploads = 0
  const fixture = mountEditor(async () => {
    uploads++
    return null
  })
  t.after(fixture.unmount)
  fixture.image()
  fixture.editor.canvas.value = { toBlob: (callback) => callback(null) }
  await fixture.editor.save()
  assert.equal(uploads, 0)
  assert.match(fixture.editor.error.value, /编码失败.*重新选择/)
  assert.equal(fixture.editor.saving.value, false)
  assert.equal(fixture.open.value, true)
})

test('网络故障或服务异常提示结果未确认，保留图像和当前编辑以便核对', async () => {
  for (const status of [0, 502, 503]) {
    const fixture = mountEditor(async () => {
      throw new ApiError('无法取得有效响应', status)
    })
    try {
      const image = fixture.image()
      await fixture.editor.save()
      assert.match(fixture.editor.error.value, /结果暂不确定.*刷新申请状态/)
      assert.equal(fixture.editor.image.value, image)
      assert.equal(image.closed, false)
      assert.equal(fixture.open.value, true)
      assert.equal(fixture.editor.saving.value, false)
      assert.deepEqual(fixture.saved, [])
    } finally {
      fixture.unmount()
    }
  }
})

test('离开页面释放图片，迟到的上传失败不再产生界面提示', async () => {
  const pending = deferred()
  const fixture = mountEditor(() => pending.promise)
  const image = fixture.image()
  const saving = fixture.editor.save()
  await nextTick()
  fixture.unmount()
  pending.reject(new ApiError('迟到的服务异常', 503))
  await saving
  assert.equal(image.closed, true)
  assert.equal(fixture.editor.error.value, '')
  assert.equal(fixture.editor.saving.value, false)
  assert.deepEqual(fixture.updates, [])
  assert.deepEqual(fixture.saved, [])
})


test('未知上传结果的显式重试沿用同一申请编号，避免多计额度',async(t)=>{
 const ids=[];const f=mountEditor(async(_blob,id)=>{ids.push(id);if(ids.length===1)throw new ApiError('offline',0);return{status:'PENDING',remainingToday:4}})
 t.after(f.unmount);f.image();await f.editor.save();await f.editor.save();assert.equal(ids.length,2);assert.equal(ids[0],ids[1]);assert.match(ids[0],/^[0-9a-f-]{36}$/)
})

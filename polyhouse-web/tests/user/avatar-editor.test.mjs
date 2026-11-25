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

const apiUrl = compile(await readFile(new URL('../../src/api/index.ts', import.meta.url), 'utf8'), {
  axios: import.meta.resolve('axios'),
})
const authUrl = compile(await readFile(new URL('../../src/api/auth.ts', import.meta.url), 'utf8'), { './index': apiUrl })
const cropUrl = compile(
  await readFile(new URL('../../src/core/avatarCrop.ts', import.meta.url), 'utf8'),
)
const storeUrl = codeUrl(
  'let store; export const useUserStore = () => store; export const install = value => { store = value }',
)
const controlsUrl = codeUrl(
  'export const ElAlert = {}, ElButton = {}, ElDialog = {}, ElIcon = {}, Minus = {}, Plus = {}, RefreshLeft = {}, Upload = {}',
)
const { descriptor } = parse(
  await readFile(new URL('../../src/components/AvatarUploadDialog.vue', import.meta.url), 'utf8'),
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
      toBlob: (callback) => callback(new Blob(['fixture'], { type: 'image/webp' })),
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
    current = deferred(),
    oldStarted = deferred(),
    currentStarted = deferred()
  let attempts = 0
  const fixture = mountEditor(() => {
    if (++attempts === 1) {
      oldStarted.resolve()
      return old.promise
    }
    currentStarted.resolve()
    return current.promise
  })
  t.after(fixture.unmount)
  fixture.image()
  const previousSave = fixture.editor.save()
  await oldStarted.promise
  fixture.open.value = false
  await nextTick()
  fixture.open.value = true
  await nextTick()
  fixture.image()
  const currentSave = fixture.editor.save()
  await currentStarted.promise
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
  const started = deferred()
  const fixture = mountEditor(() => {
    started.resolve()
    return pending.promise
  })
  t.after(fixture.unmount)
  fixture.image()
  const saving = fixture.editor.save()
  await started.promise
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

test('网络故障或服务异常提示结果未确认，保留图像和当前编辑以便核对', async (t) => {
  for (const status of [0, 502, 503]) {
    await t.test(`异常状态码 ${status}`, async (sub) => {
      const fixture = mountEditor(async () => {
        throw new ApiError('无法取得有效响应', status)
      })
      sub.after(fixture.unmount)
      const image = fixture.image()
      await fixture.editor.save()
      assert.match(fixture.editor.error.value, /结果暂不确定.*刷新申请状态/)
      assert.equal(fixture.editor.image.value, image)
      assert.equal(image.closed, false)
      assert.equal(fixture.open.value, true)
      assert.equal(fixture.editor.saving.value, false)
      assert.deepEqual(fixture.saved, [])
    })
  }
})

test('离开页面释放图片，迟到的上传失败不再产生界面提示', async (t) => {
  const pending = deferred()
  const started = deferred()
  const fixture = mountEditor(() => {
    started.resolve()
    return pending.promise
  })
  t.after(fixture.unmount)
  const image = fixture.image()
  const saving = fixture.editor.save()
  await started.promise
  fixture.unmount()
  pending.reject(new ApiError('迟到的服务异常', 503))
  await saving
  assert.equal(image.closed, true)
  assert.equal(fixture.editor.error.value, '')
  assert.equal(fixture.editor.saving.value, false)
  assert.deepEqual(fixture.updates, [])
  assert.deepEqual(fixture.saved, [])
})

test('未知上传结果的显式重试沿用同一申请编号，避免多计额度', async (t) => {
  const ids = []
  const fixture = mountEditor(async (_blob, id) => {
    ids.push(id)
    if (ids.length === 1) throw new ApiError('offline', 0)
    return { status: 'PENDING', remainingToday: 4 }
  })
  t.after(fixture.unmount)
  fixture.image()
  await fixture.editor.save()
  await fixture.editor.save()
  assert.equal(ids.length, 2)
  assert.equal(ids[0], ids[1])
  assert.match(ids[0], /^[0-9a-f-]{36}$/)
})

test('头像以 0.8 起步，超出 20 KiB 降画质，同次重试复用原始 JSON 图片', async (t) => {
  const uploads = []
  const qualities = []
  const fixture = mountEditor(async (payload, requestId) => {
    uploads.push({ payload, requestId })
    if (uploads.length === 1) throw new ApiError('网络异常')
    return { status: 'PENDING', remainingToday: 4 }
  })
  t.after(fixture.unmount)
  fixture.image()
  fixture.editor.canvas.value = {
    toBlob: (callback, type, quality) => {
      assert.equal(type, 'image/webp')
      qualities.push(quality)
      const bytes = quality === 0.8 ? new Uint8Array(20 * 1024 + 1) : 'webp-fixture'
      callback(new Blob([bytes], { type }))
    },
  }
  await fixture.editor.save()
  await fixture.editor.save()
  assert.deepEqual(qualities, [0.8, 0.7])
  assert.equal(uploads.length, 2)
  assert.deepEqual(uploads[0], uploads[1])
  assert.equal(atob(uploads[0].payload), 'webp-fixture')
})

function buildPngHeader(width, height) {
  const bytes = new Uint8Array(24)
  bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a], 0)
  const view = new DataView(bytes.buffer)
  view.setUint32(8, 13)
  view.setUint32(12, 0x49484452)
  view.setUint32(16, width)
  view.setUint32(20, height)
  return bytes
}

function buildAnimatedWebpHeader() {
  // RIFF(4) + chunkSize(4) + WEBP(4) + VP8X(4) + size=10(4) + flags(bit1=动画)(1) + 保留与宽高(9) = 30 字节
  const bytes = new Uint8Array(30)
  const view = new DataView(bytes.buffer)
  view.setUint32(0, 0x52494646)
  view.setUint32(4, bytes.length - 8, true)
  view.setUint32(8, 0x57454250)
  view.setUint32(12, 0x56503858)
  view.setUint32(16, 10, true)
  view.setUint8(20, 0x02)
  return bytes
}

function patchGlobal(t, key, value) {
  const hadOwn = Object.prototype.hasOwnProperty.call(globalThis, key)
  const previous = globalThis[key]
  globalThis[key] = value
  t.after(() => {
    if (hadOwn) globalThis[key] = previous
    else delete globalThis[key]
  })
}

test('已有草稿时改选非法、超限、动图或解码失败文件会作废旧草稿，取消选择保留旧图，改选有效图后可正常提交且迟到解码不复活草稿', async (t) => {
  class FakeInput {
    constructor(files) {
      this.files = files
      this.value = 'picked'
    }
  }
  const validPng = buildPngHeader(256, 256)
  const oversizedPng = buildPngHeader(4097, 256)
  const animatedWebp = buildAnimatedWebpHeader()

  let decodeHandler = async () => ({
    width: 256,
    height: 256,
    closed: false,
    close() {
      this.closed = true
    },
  })
  patchGlobal(t, 'HTMLInputElement', FakeInput)
  patchGlobal(t, 'createImageBitmap', (...args) => decodeHandler(...args))

  const requestIds = []
  const fixture = mountEditor(async (_payload, requestId) => {
    requestIds.push(requestId)
    return { status: 'PENDING', remainingToday: 4 }
  })
  t.after(fixture.unmount)

  await t.test('取消系统文件选择器保留已有草稿', async () => {
    const kept = fixture.image()
    fixture.editor.fileName.value = 'old.png'
    await fixture.editor.choose({ target: new FakeInput([]) })
    assert.equal(fixture.editor.image.value, kept)
    assert.equal(kept.closed, false)
    assert.equal(fixture.editor.fileName.value, 'old.png')
  })

  const invalidCases = [
    {
      name: '不支持的格式（GIF）',
      file: { name: 'anim.gif', type: 'image/gif', size: 1024 },
      expectedError: /JPEG、PNG 或 WebP/,
    },
    {
      name: '文件体积超过 5 MiB 上限',
      file: { name: 'huge.png', type: 'image/png', size: 5 * 1024 * 1024 + 1 },
      expectedError: /5 MiB/,
    },
    {
      name: 'PNG 像素尺寸超过 4096×4096 差一边界',
      file: {
        name: 'wide.png',
        type: 'image/png',
        size: oversizedPng.byteLength,
        arrayBuffer: async () => oversizedPng.buffer.slice(0),
      },
      expectedError: /无法读取|4096/,
    },
    {
      name: '动画 WebP 在浏览器解码前被拒绝',
      file: {
        name: 'anim.webp',
        type: 'image/webp',
        size: animatedWebp.byteLength,
        arrayBuffer: async () => animatedWebp.buffer.slice(0),
      },
      expectedError: /无法读取|动图/,
    },
    {
      name: '文件头合法但浏览器解码失败',
      file: {
        name: 'broken.png',
        type: 'image/png',
        size: validPng.byteLength,
        arrayBuffer: async () => validPng.buffer.slice(0),
      },
      failDecode: true,
      expectedError: /无法读取/,
    },
  ]

  for (const item of invalidCases) {
    await t.test(`改选非法文件立即作废旧草稿：${item.name}`, async () => {
      const priorCount = requestIds.length
      const oldDraft = fixture.image()
      fixture.editor.fileName.value = 'previous.png'
      decodeHandler = item.failDecode
        ? async () => {
            throw new Error('decode failed')
          }
        : async () => ({
            width: 256,
            height: 256,
            closed: false,
            close() {
              this.closed = true
            },
          })

      await fixture.editor.choose({ target: new FakeInput([item.file]) })
      assert.equal(oldDraft.closed, true)
      assert.equal(fixture.editor.image.value, undefined)
      assert.equal(fixture.editor.fileName.value, '')
      assert.match(fixture.editor.error.value, item.expectedError)
      await fixture.editor.save()
      assert.equal(requestIds.length, priorCount)
    })
  }

  await t.test('慢解码在改选无效文件后迟到完成时自动释放且不复活草稿', async () => {
    const slowDecode = deferred()
    decodeHandler = () => slowDecode.promise
    const pendingChoose = fixture.editor.choose({
      target: new FakeInput([
        {
          name: 'slow.png',
          type: 'image/png',
          size: validPng.byteLength,
          arrayBuffer: async () => validPng.buffer.slice(0),
        },
      ]),
    })
    await Promise.resolve()
    // 慢解码挂起期间改选 GIF，应递增 selection 并作废前一次选择。
    await fixture.editor.choose({
      target: new FakeInput([{ name: 'anim.gif', type: 'image/gif', size: 128 }]),
    })
    const staleBitmap = {
      width: 256,
      height: 256,
      closed: false,
      close() {
        this.closed = true
      },
    }
    slowDecode.resolve(staleBitmap)
    await pendingChoose
    assert.equal(staleBitmap.closed, true)
    assert.equal(fixture.editor.image.value, undefined)
    assert.match(fixture.editor.error.value, /JPEG、PNG 或 WebP/)
  })

  await t.test('重新选择有效静态 PNG 后恢复可提交并分配新申请编号', async () => {
    decodeHandler = async () => ({
      width: 256,
      height: 256,
      closed: false,
      close() {
        this.closed = true
      },
    })
    fixture.editor.canvas.value = {
      getContext: () => ({ fillRect() {}, drawImage() {} }),
      toBlob: (callback) => callback(new Blob(['valid-webp'], { type: 'image/webp' })),
    }
    await fixture.editor.choose({
      target: new FakeInput([
        {
          name: 'valid.png',
          type: 'image/png',
          size: validPng.byteLength,
          arrayBuffer: async () => validPng.buffer.slice(0),
        },
      ]),
    })
    assert.ok(fixture.editor.image.value)
    assert.equal(fixture.editor.fileName.value, 'valid.png')
    assert.equal(fixture.editor.error.value, '')
    await fixture.editor.save()
    assert.equal(requestIds.length, 1)
    assert.match(requestIds[0], /^[0-9a-f-]{36}$/)
  })
})

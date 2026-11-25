import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, nextTick, reactive, ref } from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import { AxiosError } from 'axios'
import ts from 'typescript'
import { dataUrl, patchGlobal, typeScriptModuleUrl } from '../helpers/test-runtime.mjs'

async function compile(path, replacements = {}, source) {
  if (source !== undefined) {
    let code = ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    }).outputText
    for (const [a, b] of Object.entries(replacements)) {
      code = code.replaceAll(`from '${a}'`, `from '${b}'`).replaceAll(`from "${a}"`, `from '${b}'`)
    }
    return dataUrl(code)
  }
  const mapped = {}
  for (const [a, b] of Object.entries(replacements)) {
    mapped[`from '${a}'`] = `from '${b}'`
    mapped[`from "${a}"`] = `from '${b}'`
  }
  return typeScriptModuleUrl(new URL(path, import.meta.url), mapped)
}

const apiUrl = await compile('../../src/api/index.ts', { axios: import.meta.resolve('axios') })
const {
  ApiError,
  apiImage,
  apiClient,
  setUnauthorizedHandler,
  setPasswordChangeRequiredHandler,
} = await import(apiUrl)
const authUrl = await compile('../../src/api/auth.ts', { './index': apiUrl })
const actualUrl = await compile('../../src/api/profileModeration.ts', { './index': apiUrl })
const { decodeSubmission } = await import(actualUrl)
const storeUrl = dataUrl(
  'let store;export const useUserStore=()=>store;export const install=s=>{store=s}',
)
const mockUrl = dataUrl(
  'let a;export const install=v=>{a=v};export const getProfileStatus=(...x)=>a.mine(...x);export const submitNickname=(...x)=>a.submit(...x);export const getReviews=(...x)=>a.reviews(...x);export const getPublished=(...x)=>a.published(...x);export const decideReview=(...x)=>a.decide(...x);export const changePublished=(...x)=>a.change(...x);export const privateImage=(...x)=>a.image(...x)',
)
const { install: installStore } = await import(storeUrl)
const { install: installApi } = await import(mockUrl)
const replacements = {
  vue: import.meta.resolve('vue'),
  '../api': apiUrl,
  '../api/auth': authUrl,
  '../api/profileModeration': mockUrl,
  '../stores/useUserStore': storeUrl,
}
const { useProfileSubmissions } = await import(
  await compile('../../src/composables/useProfileSubmissions.ts', replacements)
)
const { useProfileReview } = await import(
  await compile('../../src/composables/useProfileReview.ts', replacements)
)
const { descriptor } = parse(
  await readFile(new URL('../../src/components/PrivateProfileImage.vue', import.meta.url), 'utf8'),
)
const { default: PrivateImage } = await import(
  await compile(
    '',
    {
      ...replacements,
      'element-plus': dataUrl('export const ElButton={}'),
      '../api/profileModeration': mockUrl,
    },
    compileScript(descriptor, { id: 'private-preview-test' }).content,
  )
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

const record = {
  id: '42',
  userId: '2',
  username: 'fixture',
  kind: 'NICKNAME',
  status: 'PENDING',
  version: '1',
  requestId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
  proposedValue: '测试申请',
  reason: null,
  createdAt: '2026-10-30T13:00:00',
  expiresAt: '2026-11-06T13:00:00',
  remainingToday: 1,
}
const status = { submissions: [record], avatarRemainingToday: 5, nicknameRemainingToday: 1 }
const page = { items: [record], total: '1', page: 1, pageSize: 20 }

function deferred() {
  let resolve
  let reject
  const promise = new Promise((a, b) => {
    resolve = a
    reject = b
  })
  return { promise, resolve, reject }
}

function fixture(t, kind = 'mine', api = {}, role = 'OWNER', initiallyActive = true) {
  const active = ref(initiallyActive)
  const section = ref('pending')
  const store = reactive({
    isLoggedIn: true,
    requiresPasswordChange: false,
    token: 'fixture',
    user: { id: '1', role, nickname: '旧公开昵称' },
  })
  installStore(store)
  installApi({
    mine: async () => status,
    submit: async (id, nickname) => ({ ...record, requestId: id, proposedValue: nickname }),
    reviews: async () => page,
    published: async () => ({ ...page, items: [] }),
    decide: async () => record,
    change: async () => ({}),
    image: async () => new Blob(['fixture']),
    ...api,
  })
  let c
  const props = reactive({ submissionId: '42' })
  const component =
    kind === 'image'
      ? {
          ...PrivateImage,
          setup(p, ctx) {
            c = PrivateImage.setup(p, ctx)
            return () => h('div')
          },
        }
      : {
          setup() {
            c =
              kind === 'mine'
                ? useProfileSubmissions(() => active.value)
                : useProfileReview(() => section.value)
            return () => h('div')
          },
        }
  const ready = []
  props.onReady = (v) => ready.push(v)
  const app = renderer.createApp({ setup: () => () => h(component, props) })
  app.mount({})
  let unmounted = false
  const unmount = () => {
    if (unmounted) return
    unmounted = true
    app.unmount()
  }
  t.after(unmount)
  return { store, c, props, ready, active, section, unmount }
}

test('资料分类未打开不读取，切换保留草稿和结果未知的原请求号', async (t) => {
  let reads = 0
  const ids = []
  const f = fixture(
    t,
    'mine',
    {
      mine: async () => {
        reads++
        return status
      },
      submit: async (id) => {
        ids.push(id)
        throw new ApiError('offline', 0)
      },
    },
    'OWNER',
    false,
  )
  await nextTick()
  assert.equal(reads, 0)
  f.active.value = true
  await nextTick()
  f.c.nickname.value = '分类草稿'
  await f.c.submit()
  f.active.value = false
  await nextTick()
  await f.c.load()
  await f.c.submit()
  assert.equal(f.c.nickname.value, '分类草稿')
  assert.equal(ids.length, 1)
  const before = reads
  f.active.value = true
  await nextTick()
  assert.equal(reads, before + 1)
  await f.c.submit()
  assert.equal(ids[0], ids[1])
})

test('切走分类后待处理申请正常收尾但不拉隐藏状态，返回只读刷新', async (t) => {
  const wait = deferred()
  let id
  let reads = 0
  let writes = 0
  const f = fixture(t, 'mine', {
    mine: async () => {
      reads++
      return status
    },
    submit: async (req) => {
      id = req
      writes++
      return wait.promise
    },
  })
  await nextTick()
  f.c.nickname.value = '等待响应'
  const operation = f.c.submit()
  f.active.value = false
  await nextTick()
  const before = reads
  wait.resolve({ ...record, requestId: id })
  await operation
  assert.equal(reads, before)
  assert.equal(f.c.saving.value, false)
  assert.match(f.c.notice.value, /审核通过/)
  f.active.value = true
  await nextTick()
  assert.equal(reads, before + 1)
  assert.equal(writes, 1)
})

test('换号或卸载立即清除草稿并丢弃旧申请响应', async (t) => {
  const scenarios = [
    {
      name: '同 Token 换用户',
      interrupt: (f) => {
        f.store.user.id = '9'
      },
      expectClearedStatus: false,
      expectIdleSaving: true,
    },
    {
      name: '换号退出登录',
      interrupt: async (f) => {
        f.store.token = 'new'
        f.store.isLoggedIn = false
        await nextTick()
      },
      expectClearedStatus: true,
      expectIdleSaving: true,
    },
    {
      name: '组件卸载',
      interrupt: (f) => {
        f.unmount()
      },
      expectClearedStatus: true,
      expectIdleSaving: false,
    },
  ]

  for (const scenario of scenarios) {
    await t.test(scenario.name, async (st) => {
      const wait = deferred()
      let id
      const f = fixture(st, 'mine', {
        submit: async (req) => {
          id = req
          return wait.promise
        },
      })
      f.c.nickname.value = '不能串号'
      const operation = f.c.submit()
      await scenario.interrupt(f)
      wait.resolve({ ...record, requestId: id })
      await operation
      assert.equal(f.c.notice.value, '')
      if (scenario.expectIdleSaving) {
        assert.equal(f.c.saving.value, false)
      }
      if (scenario.expectClearedStatus) {
        assert.equal(f.c.status.value, null)
        assert.equal(f.c.nickname.value, '')
      }
    })
  }
})

test('昵称提交去重且不抢先写公开资料，成功后刷新服务端状态', async (t) => {
  const wait = deferred()
  let calls = 0
  let request
  const f = fixture(t, 'mine', {
    submit: async (id) => {
      calls++
      request = id
      return wait.promise
    },
  })
  f.c.nickname.value = '新昵称'
  const a = f.c.submit()
  const b = f.c.submit()
  assert.equal(calls, 1)
  wait.resolve({ ...record, requestId: request })
  await Promise.all([a, b])
  assert.equal(f.store.user.nickname, '旧公开昵称')
  assert.equal(f.c.nickname.value, '')
  assert.ok(f.c.notice.value.includes('审核通过'))
})

test('结果未知不自动重发，显式原内容重试沿用同一编号', async (t) => {
  const ids = []
  const f = fixture(t, 'mine', {
    submit: async (id) => {
      ids.push(id)
      throw new ApiError('offline', 0)
    },
  })
  f.c.nickname.value = '原申请'
  await f.c.submit()
  await f.c.load()
  assert.equal(ids.length, 1)
  await f.c.submit()
  assert.equal(ids[0], ids[1])
  f.c.nickname.value = '另一个申请'
  await f.c.submit()
  assert.notEqual(ids[1], ids[2])
})

test('控制字符昵称和临时凭据不发写请求', async (t) => {
  let calls = 0
  const f = fixture(t, 'mine', { submit: async () => { calls++ } })
  for (const text of ['', 'x'.repeat(33), '非法\n换行', '隐藏\u200b字符']) {
    f.c.nickname.value = text
    await f.c.submit()
  }
  f.store.requiresPasswordChange = true
  await nextTick()
  f.c.nickname.value = '正常昵称'
  await f.c.submit()
  assert.equal(calls, 0)
})

test('仅 OWNER 加载审核页，旧查询结果不能覆盖新身份', async (t) => {
  let calls = 0
  const wait = deferred()
  const f = fixture(
    t,
    'review',
    {
      reviews: async () => {
        calls++
        return wait.promise
      },
    },
    'ADMIN',
  )
  await f.c.load()
  assert.equal(calls, 0)
  f.store.user.role = 'OWNER'
  await nextTick()
  assert.equal(calls, 1)
  f.store.user.role = 'INSIDER'
  await nextTick()
  wait.resolve(page)
  await nextTick()
  assert.deepEqual(f.c.items.value, [])
})

test('审核只在确认时发送一次，未知结果禁止盲目重发并在切分区返回后只查询', async (t) => {
  await t.test('并发确认去重且未知结果禁止再次提交', async (st) => {
    let calls = 0
    const wait = deferred()
    const f = fixture(st, 'review', {
      decide: async () => {
        calls++
        return wait.promise
      },
    })
    f.c.openDecision(record)
    assert.equal(calls, 0)
    f.c.reason.value = '已核对'
    const a = f.c.submit(true)
    const b = f.c.submit(true)
    assert.equal(calls, 1)
    wait.reject(new ApiError('offline', 0))
    await Promise.all([a, b])
    assert.equal(f.c.uncertain.value, true)
    await f.c.submit(true)
    assert.equal(calls, 1)
    f.c.close()
    await f.c.load()
    assert.equal(calls, 1)
  })

  await t.test('未知结果后切走再返回分区只查询不重发决定', async (st) => {
    let writes = 0
    const f = fixture(st, 'review', {
      decide: async () => {
        writes++
        throw new ApiError('offline', 0)
      },
    })
    f.c.openDecision(record)
    f.c.reason.value = '核对过内容'
    await f.c.submit(true)
    assert.equal(f.c.uncertain.value, true)
    f.c.close()
    f.section.value = 'published'
    await nextTick()
    f.section.value = 'pending'
    await nextTick()
    assert.equal(writes, 1)
    assert.equal(f.c.selection.value, null)
  })
})

test('409 审核冲突不伪报成功，旧成功响应不能改变换号后的界面', async (t) => {
  await t.test('409 审核冲突标记不确定且不伪报成功提示', async (st) => {
    const f = fixture(st, 'review', {
      decide: async () => {
        throw new ApiError('changed', 409)
      },
    })
    f.c.openDecision(record)
    f.c.reason.value = '驳回原因'
    await f.c.submit(false)
    assert.equal(f.c.uncertain.value, true)
    assert.equal(f.c.notice.value, '')
  })

  await t.test('旧审核成功响应晚于换号到达时不能写入新账号界面提示', async (st) => {
    const wait = deferred()
    const f = fixture(st, 'review', { decide: async () => wait.promise })
    f.c.openDecision(record)
    f.c.reason.value = '通过原因'
    const pending = f.c.submit(true)
    f.store.token = 'next-owner-token'
    f.store.user = { id: '9', role: 'OWNER', nickname: '新屋主' }
    await nextTick()
    wait.resolve(record)
    await pending
    assert.equal(f.c.notice.value, '')
    assert.equal(f.c.selection.value, null)
    assert.equal(f.c.busy.value, false)
  })
})

test('私有预览 URL 在换图和卸载时释放，旧 Blob 不串入新身份', async (t) => {
  const first = deferred()
  const second = deferred()
  let calls = 0
  let created = 0
  const revoked = []
  const originalCreate = URL.createObjectURL
  const originalRevoke = URL.revokeObjectURL
  URL.createObjectURL = () => `blob:fixture-${++created}`
  URL.revokeObjectURL = (x) => revoked.push(x)
  t.after(() => {
    URL.createObjectURL = originalCreate
    URL.revokeObjectURL = originalRevoke
  })

  const f = fixture(t, 'image', {
    image: async () => (++calls === 1 ? first.promise : second.promise),
  })
  f.store.token = 'new'
  await nextTick()
  first.resolve(new Blob(['old']))
  await nextTick()
  assert.equal(created, 0)
  assert.equal(f.ready.at(-1), false)
  second.resolve(new Blob(['new']))
  await nextTick()
  await nextTick()
  assert.equal(f.c.url.value, 'blob:fixture-1')
  assert.equal(f.ready.at(-1), false)

  patchGlobal(
    t,
    'HTMLImageElement',
    class {
      constructor(src) {
        this.currentSrc = src
        this.naturalWidth = 256
      }
    },
  )
  f.c.loaded({ currentTarget: new HTMLImageElement('blob:old') })
  assert.equal(f.ready.at(-1), false)
  f.c.loaded({ currentTarget: new HTMLImageElement('blob:fixture-1') })
  assert.equal(f.ready.at(-1), true)
  f.unmount()
  assert.deepEqual(revoked, ['blob:fixture-1'])
})

test('媒体错误沿用 401 清理与首次改密闸门，不把原始响应传播给页面', async (t) => {
  const originalAdapter = apiClient.defaults.adapter
  t.after(() => {
    apiClient.defaults.adapter = originalAdapter
    setUnauthorizedHandler(null)
    setPasswordChangeRequiredHandler(null)
  })
  let unauthorized = ''
  let first = ''
  setUnauthorizedHandler((token) => {
    unauthorized = token
  })
  setPasswordChangeRequiredHandler((token) => {
    first = token
  })

  apiClient.defaults.adapter = async (config) => {
    throw new AxiosError('fixture', undefined, config, null, {
      status: 401,
      data: new Blob(['{}'], { type: 'application/json' }),
      headers: {},
      config,
    })
  }
  await assert.rejects(
    apiImage('/fixture', 'submitted'),
    (e) => e instanceof ApiError && e.status === 401,
  )
  assert.equal(unauthorized, 'submitted')

  apiClient.defaults.adapter = async (config) => {
    throw new AxiosError('fixture', undefined, config, null, {
      status: 403,
      data: new Blob(['{"code":40301}'], { type: 'application/json' }),
      headers: {},
      config,
    })
  }
  await assert.rejects(
    apiImage('/fixture', 'initial'),
    (e) => e instanceof ApiError && e.code === 40301,
  )
  assert.equal(first, 'initial')
})

test('申请解码拒绝旧上传合同和非法字段，并丢弃私有路径', async (t) => {
  assert.deepEqual(decodeSubmission(record), record)
  assert.equal(decodeSubmission({ ...record, privateKey: 'not-forwarded' }).privateKey, undefined)
  for (const [label, bad] of [
    ['旧上传字段格式', { avatar: '/old.jpg', remainingToday: 4 }],
    ['非字符串 ID', { ...record, id: 42 }],
    ['非法申请类型 PASSWORD', { ...record, kind: 'PASSWORD' }],
    ['头像申请携带昵称值', { ...record, kind: 'AVATAR' }],
    ['未知状态值', { ...record, status: 'UNKNOWN' }],
  ]) {
    await t.test(`拒绝${label}`, () => assert.throws(() => decodeSubmission(bad), ApiError))
  }
})

test('审核只读当前分区，旧分区响应丢弃，各自筛选页码保留', async (t) => {
  const old = deferred()
  const pendingCalls = []
  const publicCalls = []
  const f = fixture(t, 'review', {
    reviews: async (...args) => {
      pendingCalls.push(args)
      return pendingCalls.length === 1 ? old.promise : page
    },
    published: async (...args) => {
      publicCalls.push(args)
      return { ...page, items: [{ userId: '3', username: 'published' }] }
    },
  })
  await nextTick()
  assert.equal(pendingCalls.length, 1)
  assert.equal(publicCalls.length, 0)
  f.c.page.value = 3
  f.section.value = 'published'
  await nextTick()
  await nextTick()
  assert.equal(publicCalls.length, 1)
  assert.equal(f.c.items.value.length, 0)
  assert.equal(f.c.published.value[0].username, 'published')
  old.resolve(page)
  await nextTick()
  await nextTick()
  assert.equal(f.c.items.value.length, 0)
  f.c.publicPage.value = 2
  f.section.value = 'pending'
  await nextTick()
  await nextTick()
  assert.equal(pendingCalls.at(-1)[0], 3)
  assert.equal(f.c.publicPage.value, 2)
  assert.equal(f.c.published.value.length, 0)
  f.c.publicKind.value = 'NICKNAME'
  await nextTick()
  assert.equal(publicCalls.length, 1)
  f.section.value = 'published'
  await nextTick()
  await nextTick()
  assert.equal(publicCalls.at(-1)[1], 'NICKNAME')
})

test('审核中切换分区只结算一次并刷新当前列表，同 Token 换号立即清理', async (t) => {
  const wait = deferred()
  let writes = 0
  let publicReads = 0
  const f = fixture(t, 'review', {
    decide: async () => {
      writes++
      return wait.promise
    },
    published: async () => {
      publicReads++
      return { ...page, items: [] }
    },
  })
  f.c.openDecision(record)
  f.c.reason.value = '审核原因'
  const operation = f.c.submit(true)
  f.section.value = 'published'
  await nextTick()
  const before = publicReads
  wait.resolve(record)
  await operation
  assert.equal(writes, 1)
  assert.equal(publicReads, before + 1)
  assert.equal(f.c.selection.value, null)
  f.c.openPublic({ userId: '2', username: 'fixture' }, 'KEEP')
  f.c.reason.value = '原账号私有原因'
  f.store.user.id = '9'
  assert.equal(f.c.selection.value, null)
  assert.equal(f.c.reason.value, '')
  assert.equal(f.c.notice.value, '')
})

test('头像和昵称预览不提交，通过可留空，驳回必须填写并能清除旧提示', async (t) => {
  const writes = []
  const f = fixture(t, 'review', {
    decide: async (item, approve, reason) => writes.push({ item, approve, reason }),
  })
  const avatar = { ...record, kind: 'AVATAR', proposedValue: null }
  f.c.openDecision(avatar)
  await f.c.submit()
  await f.c.submit(false)
  assert.equal(writes.length, 0)
  assert.match(f.c.dialogError.value, /请填写/)
  f.c.reason.value = '  头像内容不合适  '
  assert.equal(f.c.dialogError.value, '')
  await f.c.submit(false)
  f.c.openDecision(avatar)
  await f.c.submit(true)
  f.c.openDecision(record)
  await f.c.submit()
  await f.c.submit(false)
  assert.equal(writes.length, 2)
  assert.match(f.c.dialogError.value, /请填写/)
  await f.c.submit(true)
  assert.deepEqual(
    writes.map(({ item, approve, reason }) => [item.kind, approve, reason]),
    [
      ['AVATAR', false, '  头像内容不合适  '],
      ['AVATAR', true, '通过头像审核'],
      ['NICKNAME', true, '通过昵称审核'],
    ],
  )
})

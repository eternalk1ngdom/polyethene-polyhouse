import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, nextTick, reactive } from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'

const codeUrl = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`

function compile(code, replacements = {}) {
  let output = ts.transpileModule(code, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(replacements)) {
    output = output.replaceAll(`from '${from}'`, `from '${to}'`)
    output = output.replaceAll(`from "${from}"`, `from "${to}"`)
  }
  return codeUrl(output)
}

const fixturesUrl = codeUrl(`
  export const listeners = new Map()
  export const window = {
    addEventListener(type, fn) { listeners.set(type, fn) },
    removeEventListener(type, fn) { if (listeners.get(type) === fn) listeners.delete(type) },
  }
  export const URL = {
    createObjectURL: () => 'blob:cover',
    revokeObjectURL: () => {},
  }
  export const coverNames = { window: '窗边初晴', astrolabe: '黄铜星盘', koi: '末班信号' }
  export const handlers = {
    readCovers: async () => null,
    changeCover: async () => null,
    readLocalCover: async () => null,
  }
  export const readCovers = (...args) => handlers.readCovers(...args)
  export const changeCover = (...args) => handlers.changeCover(...args)
  export const readLocalCover = (...args) => handlers.readLocalCover(...args)
  export const saveLocalCover = async () => {}
  export const enableLocalCover = async () => {}
  export const deleteLocalCover = async () => {}
  export const localCoverChannel = 'polyhouse-local-cover'
  let userStore
  let growthStore
  export const installStores = (u, g) => { userStore = u; growthStore = g }
  export const useUserStore = () => userStore
  export const useUserGrowthStore = () => growthStore
  export const Picture = {}, ElButton = {}, ElDialog = {}, ElIcon = {}
  export default {}
`)

const { descriptor } = parse(
  await readFile(new URL('../../src/components/ProfileCoverPicker.vue', import.meta.url), 'utf8'),
)
let script = compileScript(descriptor, { id: 'profile-cover-picker-test' }).content
script = script.replace(
  /import\.meta\.glob<string>\([\s\S]*?\}\)/,
  '({})',
)
script = `import { window, URL } from '${fixturesUrl}';\n` + script
const { default: component } = await import(
  compile(script, {
    vue: import.meta.resolve('vue'),
    '@element-plus/icons-vue': fixturesUrl,
    'element-plus': fixturesUrl,
    '../api/profileCovers': fixturesUrl,
    '../stores/useUserStore': fixturesUrl,
    '../stores/useUserGrowthStore': fixturesUrl,
    './LocalCoverEditor.vue': fixturesUrl,
    '../utils/localProfileCover': fixturesUrl,
  })
)
const { handlers, installStores, listeners } = await import(fixturesUrl)

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

function sampleCatalog(overrides = {}) {
  return {
    userId: 'u-1',
    coins: '20',
    equipped: 'koi',
    quoteVersion: '1',
    covers: [
      { key: 'window', reason: 'default', price: 0, missingExperience: 0 },
      { key: 'astrolabe', reason: 'coins', price: 10, missingExperience: 0 },
      { key: 'koi', reason: 'level', price: 0, missingExperience: 0 },
    ],
    ...overrides,
  }
}

function mountPicker(initial = {}) {
  const user = reactive({
    token: 'tok-1',
    isLoggedIn: true,
    requiresPasswordChange: false,
    user: { id: 'u-1', role: 'USER', level: 2, coins: '20' },
    applyCoverBalance(_t, _id, coins) {
      this.user = { ...this.user, coins }
    },
    ...initial.user,
  })
  const growth = reactive({
    summary: { userId: 'u-1', rewardDate: '2026-11-25', level: 2 },
    status: 'ready',
    async refreshAfterPurchase() {
      this.status = 'loading'
      this.summary = null
      await nextTick()
      this.summary = { userId: 'u-1', rewardDate: '2026-11-25', level: 2 }
      this.status = 'ready'
    },
    ...initial.growth,
  })
  installStores(user, growth)
  let exposed
  const subject = {
    ...component,
    setup(props, context) {
      exposed = component.setup(props, context)
      return () => null
    },
  }
  const app = renderer.createApp({
    setup: () => () => h(subject, { accountId: 'u-1' }),
  })
  app.mount({})
  return { user, growth, exposed, unmount: () => app.unmount() }
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0))

test('同值资料替换与成长刷新加载抖动不触发多余目录请求，真实跨日与升级正常重读', async (t) => {
  let reads = 0
  handlers.readCovers = async () => {
    reads += 1
    return sampleCatalog()
  }
  const f = mountPicker()
  t.after(f.unmount)
  await settle()
  assert.equal(reads, 1)

  // 同值新对象替换 user 与 growth.summary 不应新增请求。
  f.user.user = { ...f.user.user }
  f.growth.summary = { ...f.growth.summary }
  await nextTick()
  await settle()
  assert.equal(reads, 1)

  // 成长刷新时经历 有值 -> loading/null -> 同值，不应新增请求。
  f.growth.status = 'loading'
  f.growth.summary = null
  await nextTick()
  f.growth.summary = { userId: 'u-1', rewardDate: '2026-11-25', level: 2 }
  f.growth.status = 'ready'
  await nextTick()
  await settle()
  assert.equal(reads, 1)

  // 真实跨日（rewardDate 变更）与真实升级（level 变更）必须触发新目录查询。
  f.growth.summary = { userId: 'u-1', rewardDate: '2026-11-26', level: 2 }
  await nextTick()
  await settle()
  assert.equal(reads, 2)

  f.growth.summary = { userId: 'u-1', rewardDate: '2026-11-26', level: 3 }
  await nextTick()
  await settle()
  assert.equal(reads, 3)
})

test('回焦与打开弹窗并发复用同一在途请求，并正确选中当前佩戴卡面', async (t) => {
  const first = deferred()
  let reads = 0
  handlers.readCovers = () => {
    reads += 1
    return first.promise
  }
  const f = mountPicker()
  t.after(f.unmount)
  assert.equal(reads, 1)

  // 初始化请求仍在途时，触发窗口回焦并打开弹窗，应复用在途请求。
  listeners.get('focus')?.()
  f.exposed.open()
  assert.equal(reads, 1)
  assert.equal(f.exposed.picked.value, 'window')

  first.resolve(sampleCatalog({ equipped: 'koi' }))
  await settle()
  assert.equal(reads, 1)
  assert.equal(f.exposed.picked.value, 'koi')
})

test('购买失败后的权威重读始终发起新请求，且旧在途结果不能覆盖新余额或权益', async (t) => {
  const stale = deferred()
  let reads = 0
  handlers.readCovers = () => {
    reads += 1
    if (reads === 1) return Promise.resolve(sampleCatalog({ equipped: 'window', coins: '20' }))
    if (reads === 2) return stale.promise
    return Promise.resolve(
      sampleCatalog({
        equipped: 'astrolabe',
        coins: '10',
        covers: [
          { key: 'window', reason: 'default', price: 0, missingExperience: 0 },
          { key: 'astrolabe', reason: 'purchased', price: 10, missingExperience: 0 },
          { key: 'koi', reason: 'level', price: 0, missingExperience: 0 },
        ],
      }),
    )
  }
  handlers.changeCover = async () => {
    throw new Error('temporary failure')
  }

  const f = mountPicker()
  t.after(f.unmount)
  await settle()
  assert.equal(reads, 1)

  // 制造一次尚未返回的回焦在途读取，随后用户发起购买并失败回滚。
  listeners.get('focus')?.()
  assert.equal(reads, 2)
  f.exposed.ready.value = true
  f.exposed.pick('astrolabe')
  await f.exposed.submit() // 第一次进入确认态
  await f.exposed.submit() // 第二次确认支付，触发失败后权威重读
  await settle()

  assert.equal(reads, 3)
  assert.equal(f.exposed.catalog.value.coins, '10')
  assert.equal(f.exposed.catalog.value.equipped, 'astrolabe')

  // 旧在途读迟到返回时，不得覆盖最新余额与佩戴状态。
  stale.resolve(sampleCatalog({ equipped: 'window', coins: '20' }))
  await settle()
  assert.equal(f.exposed.catalog.value.coins, '10')
  assert.equal(f.exposed.catalog.value.equipped, 'astrolabe')
})

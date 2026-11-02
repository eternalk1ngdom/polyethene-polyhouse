import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const url = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
async function compile(path, imports = {}) {
  let code = ts.transpileModule(await readFile(new URL(path, import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(imports))
    code = code.replaceAll(`from '${from}'`, `from '${to}'`)
  return url(code)
}
const libraryUrl = await compile('../../src/utils/ruleLibrary.ts')
const pureUrl = await compile('../../src/utils/ruleShare.ts', { './ruleLibrary': libraryUrl })
const controllerUrl = await compile('../../src/composables/useRuleShare.ts', {
  vue: import.meta.resolve('vue'),
  '../utils/ruleShare': pureUrl,
})
const { encodeRuleShare, parseRuleShare, createRuleShareUrl, RULE_SHARE_MAX_LENGTH } = await import(
  pureUrl
)
const { useRuleShare } = await import(controllerUrl)
const rules = [
  { search: '小明😀 ', replace: '小蓝\n ' },
  { search: '广告', replace: '' },
]
const payload = (value) => '#r=' + Buffer.from(JSON.stringify(value)).toString('base64url')
const snapshot = (value) => JSON.parse(JSON.stringify(value))

test('分享只往返当前规则，保留 Unicode/空白/删除/字面 HTML，副本深冻结', () => {
  const input = [...snapshot(rules), { search: 'constructor', replace: '<script>文本</script>' }]
  const expected = snapshot(input),
    hash = encodeRuleShare(input)
  input[0].search = '已改'
  const value = parseRuleShare(hash)
  assert.deepEqual(value, { version: 1, rules: expected })
  assert.ok(
    Object.isFrozen(value) && Object.isFrozen(value.rules) && Object.isFrozen(value.rules[0]),
  )
  assert.equal(encodeRuleShare(value.rules), hash)
  assert.equal(parseRuleShare('#tools'), null)
})

test('分享拒绝未知版本/字段、原型字段、空查找、重复、超限和不完整 Unicode', () => {
  for (const value of [
    null,
    [],
    { version: 2, rules },
    { version: 1, rules, extra: true },
    JSON.parse('{"version":1,"rules":[],"__proto__":{}}'),
    { version: 1, rules: [] },
    { version: 1, rules: [{ search: '', replace: '' }] },
    {
      version: 1,
      rules: [
        { search: 'A', replace: '' },
        { search: 'A', replace: 'B' },
      ],
    },
    { version: 1, rules: [{ search: 'x'.repeat(201), replace: '' }] },
    { version: 1, rules: [{ search: 'A', replace: '\ud800' }] },
    { version: 1, rules: [{ search: 'A', replace: '', constructor: 'x' }] },
    {
      version: 1,
      rules: Array.from({ length: 21 }, (_, i) => ({ search: String(i), replace: '' })),
    },
  ])
    assert.throws(() => parseRuleShare(payload(value)))
  assert.throws(() => encodeRuleShare([{ search: '', replace: '半成品' }]))
})

test('损坏编码、非法 UTF-8、截断 JSON、非规范填充及超长片段均拒绝', () => {
  for (const hash of [
    '#r=',
    '#r=a',
    '#r=%%%25',
    '#r=eyJ9=',
    '#r=Zh',
    '#r=' + Buffer.from([0xff, 0xfe]).toString('base64url'),
    '#r=' + Buffer.from('{"version":1').toString('base64url'),
    '#r=' + 'A'.repeat(RULE_SHARE_MAX_LENGTH),
  ])
    assert.throws(() => parseRuleShare(hash))
})

test('完整网址保留部署子路径但不带查询；8000 边界不截断，不生成超长/非 HTTP 链接', () => {
  const link = createRuleShareUrl('https://example.test/app/replace?temporary=private#old', rules)
  const parsed = new URL(link)
  assert.equal(parsed.pathname, '/app/replace')
  assert.equal(parsed.search, '')
  assert.deepEqual(parseRuleShare(parsed.hash).rules, rules)
  const padding = 'p'.repeat(RULE_SHARE_MAX_LENGTH - link.length)
  assert.equal(
    createRuleShareUrl('https://example.test/' + padding + 'app/replace', rules).length,
    8000,
  )
  assert.throws(() => createRuleShareUrl('https://example.test/x' + padding + 'app/replace', rules))
  assert.throws(() => createRuleShareUrl('javascript:alert(1)', rules))
  assert.throws(() => createRuleShareUrl('https://user:pass@example.test/replace', rules))
  assert.throws(() =>
    encodeRuleShare(
      Array.from({ length: 20 }, (_, i) => ({
        search: String(i) + '中'.repeat(190),
        replace: '文'.repeat(200),
      })),
    ),
  )
})

function fixture() {
  const calls = [],
    state = {
      hash: '',
      allowed: true,
      load: true,
      restore: true,
      keep: true,
      clear: true,
      copy: true,
    }
  let controller
  const services = {
    baseUrl: () => 'https://example.test/app/replace?private=x',
    clearHash: async (expected) => {
      calls.push(['clear', expected])
      if (typeof state.clear === 'function') return state.clear(expected)
      if (!state.clear) return false
      if (state.hash === expected) {
        state.hash = ''
        await controller.receive('')
      }
      return true
    },
    copy: async (text) => {
      calls.push(['copy', text])
      if (typeof state.copy === 'function') return state.copy()
      if (!state.copy) throw Error('fixture clipboard denied')
    },
    canChoose: () => state.allowed,
    loadRules: (value) => {
      calls.push(['load', snapshot(value)])
      return state.load
    },
    restoreDraft: (id) => {
      calls.push(['restore', id])
      return state.restore
    },
    keepCurrent: () => {
      calls.push(['keep'])
      return state.keep
    },
  }
  controller = useRuleShare(services)
  return {
    controller,
    calls,
    state,
    async receive(hash = encodeRuleShare(rules)) {
      state.hash = hash
      await controller.receive(hash)
    },
  }
}

test('接收只预览；未就绪不应用，明确载入后一次应用并清理片段，成功提示不被路由吞掉', async () => {
  const f = fixture(),
    c = f.controller
  await f.receive()
  assert.equal(c.pending.value, true)
  assert.deepEqual(f.calls, [])
  f.state.allowed = false
  assert.equal(await c.choose('link'), false)
  assert.deepEqual(f.calls, [])
  f.state.allowed = true
  assert.equal(await c.choose('link'), true)
  assert.deepEqual(f.calls[0], ['load', rules])
  assert.equal(f.state.hash, '')
  assert.equal(c.pending.value, false)
  assert.match(c.notice.value, /已载入链接中的 2 条/)
  assert.equal(await c.choose('link'), false)
})

test('恢复草稿与暂不载入均不应用链接；失效草稿/拒绝应用保留候选及网址', async () => {
  const f = fixture(),
    c = f.controller
  await f.receive()
  f.state.restore = false
  assert.equal(await c.choose('draft', 'old'), false)
  assert.ok(c.incoming.value)
  assert.notEqual(f.state.hash, '')
  f.state.restore = true
  assert.equal(await c.choose('draft', 'old'), true)
  assert.equal(f.calls.filter(([name]) => name === 'load').length, 0)
  await f.receive()
  assert.equal(await c.choose('keep'), true)
  assert.equal(f.calls.filter(([name]) => name === 'load').length, 0)
  assert.match(c.notice.value, /暂不载入/)
})

test('无效分享明确提示并清理，编辑/草稿动作不触发；普通锚点不清理', async () => {
  const f = fixture(),
    c = f.controller
  await f.receive('#r=broken')
  assert.equal(c.incoming.value, null)
  assert.equal(f.state.hash, '')
  assert.match(c.notice.value, /无效、已损坏或过长/)
  assert.deepEqual(
    f.calls.map(([name]) => name),
    ['clear'],
  )
  f.calls.length = 0
  await f.receive('#tools')
  assert.deepEqual(f.calls, [])
})

test('网址清理失败不重复应用，重试只清理且修正提示', async () => {
  const f = fixture(),
    c = f.controller
  f.state.clear = false
  await f.receive()
  await c.choose('link')
  assert.match(c.notice.value, /已载入.*网址尚未清理/u)
  assert.notEqual(c.cleanupHash.value, '')
  assert.equal(await c.choose('link'), false)
  f.state.clear = true
  await c.retryCleanup()
  assert.equal(c.cleanupHash.value, '')
  assert.match(c.notice.value, /网址已清理/)
  assert.doesNotMatch(c.notice.value, /尚未清理/)
  assert.equal(f.calls.filter(([name]) => name === 'load').length, 1)
})

test('重复确认只应用一次；旧清理等待期间的新链接保留，不被迟到结果吞掉', async () => {
  const f = fixture(),
    c = f.controller
  let finish
  f.state.clear = () =>
    new Promise((resolve) => {
      finish = resolve
    })
  await f.receive()
  const choosing = c.choose('link')
  assert.equal(await c.choose('link'), false)
  const next = encodeRuleShare([{ search: '新', replace: '来源' }])
  await f.receive(next)
  finish(false)
  await choosing
  assert.equal(c.incoming.value.hash, next)
  assert.equal(c.cleanupHash.value, '')
  assert.equal(f.calls.filter(([name]) => name === 'load').length, 1)
})

test('生成失败不显示半截链接；复制失败提供完整链接，成功才报告已复制', async () => {
  const f = fixture(),
    c = f.controller
  assert.equal(c.generate(rules), true)
  const link = c.generated.value
  f.state.copy = false
  await c.copy()
  assert.equal(c.generated.value, link)
  assert.match(c.copyNotice.value, /手动复制/)
  f.state.copy = true
  await c.copy()
  assert.match(c.copyNotice.value, /已复制/)
  assert.equal(c.generate([{ search: '', replace: '' }]), false)
  assert.equal(c.generated.value, '')
  assert.match(c.generationIssue.value, /当前规则无法分享/)
  assert.equal(c.generate(rules), true)
  assert.equal(c.generationIssue.value, '')
})

test('关闭/换快照/卸载后迟到剪贴板结果失效，不报告另一条链接已复制', async () => {
  const f = fixture(),
    c = f.controller
  let finish
  f.state.copy = () =>
    new Promise((resolve) => {
      finish = resolve
    })
  c.generate(rules)
  const copying = c.copy()
  c.closeGenerated()
  c.generate([{ search: '别的', replace: '规则' }])
  finish()
  await copying
  assert.equal(c.copyNotice.value, '')
  const later = c.copy()
  c.dispose()
  finish()
  await later
  assert.equal(c.copyNotice.value, '')
  assert.equal(await c.choose('link'), false)
})

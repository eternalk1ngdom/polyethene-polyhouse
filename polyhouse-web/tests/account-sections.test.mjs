import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../src/utils/accountSections.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const { resolveAccountSection, needsAccountSectionRedirect } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
)

test('分类仅接受单个白名单值，默认入口不制造重定向', () => {
  for (const value of ['growth', 'profile', 'tools', 'security']) {
    assert.equal(resolveAccountSection(value), value)
    assert.equal(needsAccountSectionRedirect(value, ''), false)
  }
  assert.equal(resolveAccountSection(undefined), 'growth')
  assert.equal(needsAccountSectionRedirect(undefined, ''), false)
  for (const value of [null, '', 'unknown', ['profile', 'tools'], { section: 'profile' }]) {
    assert.equal(resolveAccountSection(value), 'growth')
    assert.equal(needsAccountSectionRedirect(value, ''), true)
  }
})

test('旧锚点映射精确分类，未知 hash 和原型属性不成为分类', () => {
  for (const [hash, section] of Object.entries({
    '#my-growth': 'growth', '#profile-details-title': 'profile',
    '#my-tools-title': 'tools', '#account-management-title': 'security',
  })) {
    assert.equal(resolveAccountSection(undefined, hash), section)
    assert.equal(needsAccountSectionRedirect(undefined, hash), true)
  }
  for (const hash of ['#unknown', 'toString', '__proto__']) {
    assert.equal(resolveAccountSection('profile', hash), 'profile')
    assert.equal(needsAccountSectionRedirect('profile', hash), false)
  }
})

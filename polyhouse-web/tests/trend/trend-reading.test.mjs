import assert from 'node:assert/strict'
import test from 'node:test'
import { typeScriptModuleUrl } from '../helpers/test-runtime.mjs'

const presentation = await import(
  await typeScriptModuleUrl(new URL('../../src/features/trend/presentation.ts', import.meta.url))
)

test('isTrendCategoryFilter 仅接受八大类白名单与未分类，拒绝非法参数', () => {
  const { isTrendCategoryFilter, trendCategoryKeys, UNCATEGORIZED } = presentation

  assert.equal(trendCategoryKeys.length, 8)
  for (const key of trendCategoryKeys) {
    assert.equal(isTrendCategoryFilter(key), true)
  }
  assert.equal(isTrendCategoryFilter(UNCATEGORIZED), true)
  for (const invalid of ['', 'UNKNOWN', 'ai_agent', null, undefined]) {
    assert.equal(isTrendCategoryFilter(invalid), false, `非法筛选值应被拒绝: ${String(invalid)}`)
  }
})

test('projectCategory 与 categoryCounts 正确归类八大类、未分类及未知扩展代号', () => {
  const { categoryCounts, projectCategory, trendCategoryKeys, UNCATEGORIZED } = presentation

  assert.equal(projectCategory('AI_AGENT'), 'AI_AGENT')
  assert.equal(projectCategory(null), UNCATEGORIZED)
  assert.equal(projectCategory(undefined), UNCATEGORIZED)
  assert.equal(projectCategory('FUTURE_CAT'), 'OTHER')

  const counts = categoryCounts(['AI_AGENT', 'AI_AGENT', 'DEVTOOLS', null, undefined, 'NEW_ENUM'])
  assert.equal(counts.get('AI_AGENT'), 2)
  assert.equal(counts.get('DEVTOOLS'), 1)
  assert.equal(counts.get('OTHER'), 1)
  assert.equal(counts.get(UNCATEGORIZED), 2)
  for (const key of trendCategoryKeys) {
    assert.equal(counts.has(key), true)
  }
  assert.equal(counts.get('FRONTEND'), 0)
})

test('siblingProjects 在筛选列表与首尾位置计算准确，不在筛选内时回退整期顺序', () => {
  const { siblingProjects } = presentation

  const all = [
    { fullName: 'org/alpha', rankNum: 1 },
    { fullName: 'org/beta', rankNum: 2 },
    { fullName: 'org/gamma', rankNum: 3 },
    { fullName: 'org/delta', rankNum: 4 },
  ]
  const filtered = [all[0], all[2], all[3]]

  const mid = siblingProjects(filtered, all, 'ORG/GAMMA')
  assert.deepEqual(mid, {
    previous: all[0],
    next: all[3],
    position: 2,
    total: 3,
    filtered: true,
  })

  const first = siblingProjects(filtered, all, 'org/alpha')
  assert.equal(first?.previous, null)
  assert.equal(first?.next, all[2])
  assert.equal(first?.position, 1)

  const last = siblingProjects(filtered, all, 'org/delta')
  assert.equal(last?.previous, all[2])
  assert.equal(last?.next, null)
  assert.equal(last?.position, 3)

  const fallback = siblingProjects(filtered, all, 'org/beta')
  assert.deepEqual(fallback, {
    previous: all[0],
    next: all[2],
    position: 2,
    total: 4,
    filtered: false,
  })

  const unfiltered = siblingProjects(all, all, 'org/beta')
  assert.equal(unfiltered?.filtered, false)
  assert.equal(unfiltered?.total, 4)

  assert.equal(siblingProjects(filtered, all, 'org/missing'), null)
})

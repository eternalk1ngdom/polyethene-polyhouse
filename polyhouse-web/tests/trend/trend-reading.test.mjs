import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const dataUrl = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`

async function loadPresentation() {
  const source = await readFile(
    new URL('../../src/features/trend/presentation.ts', import.meta.url),
    'utf8',
  )
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  return import(dataUrl(code))
}

test('isTrendCategoryFilter 仅接受八大类白名单与未分类，拒绝非法参数', async () => {
  const { isTrendCategoryFilter, trendCategoryKeys, UNCATEGORIZED } = await loadPresentation()

  assert.equal(trendCategoryKeys.length, 8)
  for (const key of trendCategoryKeys) {
    assert.equal(isTrendCategoryFilter(key), true)
  }
  assert.equal(isTrendCategoryFilter(UNCATEGORIZED), true)
  assert.equal(isTrendCategoryFilter(''), false)
  assert.equal(isTrendCategoryFilter('UNKNOWN'), false)
  assert.equal(isTrendCategoryFilter('ai_agent'), false)
  assert.equal(isTrendCategoryFilter(null), false)
  assert.equal(isTrendCategoryFilter(undefined), false)
})

test('projectCategory 与 categoryCounts 正确归类八大类、未分类及未知扩展代号', async () => {
  const { categoryCounts, projectCategory, trendCategoryKeys, UNCATEGORIZED } =
    await loadPresentation()

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

test('siblingProjects 在筛选列表与首尾位置计算准确，不在筛选内时回退整期顺序', async () => {
  const { siblingProjects } = await loadPresentation()

  const all = [
    { fullName: 'org/alpha', rankNum: 1 },
    { fullName: 'org/beta', rankNum: 2 },
    { fullName: 'org/gamma', rankNum: 3 },
    { fullName: 'org/delta', rankNum: 4 },
  ]
  const filtered = [all[0], all[2], all[3]]

  // 筛选列表中间项：前后按筛选结果，且大小写不敏感
  const mid = siblingProjects(filtered, all, 'ORG/GAMMA')
  assert.deepEqual(mid, {
    previous: all[0],
    next: all[3],
    position: 2,
    total: 3,
    filtered: true,
  })

  // 筛选列表首项与末项
  const first = siblingProjects(filtered, all, 'org/alpha')
  assert.equal(first?.previous, null)
  assert.equal(first?.next, all[2])
  assert.equal(first?.position, 1)

  const last = siblingProjects(filtered, all, 'org/delta')
  assert.equal(last?.previous, all[2])
  assert.equal(last?.next, null)
  assert.equal(last?.position, 3)

  // 当前篇不在筛选结果中：退回整期顺序，filtered 为 false
  const fallback = siblingProjects(filtered, all, 'org/beta')
  assert.deepEqual(fallback, {
    previous: all[0],
    next: all[2],
    position: 2,
    total: 4,
    filtered: false,
  })

  // 筛选结果与整期一致时不算筛选态
  const unfiltered = siblingProjects(all, all, 'org/beta')
  assert.equal(unfiltered?.filtered, false)
  assert.equal(unfiltered?.total, 4)

  // 不在本期列表中的仓库返回 null
  assert.equal(siblingProjects(filtered, all, 'org/missing'), null)
})

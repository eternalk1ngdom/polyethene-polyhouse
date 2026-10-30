import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createRouter, createMemoryHistory } from 'vue-router'
import { parse, compileScript } from 'vue/compiler-sfc'
import ts from 'typescript'

const { descriptor } = parse(await readFile(new URL('../src/components/ManagementWorkspace.vue', import.meta.url), 'utf8'))
let source = ts.transpileModule(compileScript(descriptor, { id: 'workspace-test', inlineTemplate: true }).content, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
for (const module of ['vue', 'vue-router', '@element-plus/icons-vue']) {
  source = source.replaceAll(`from "${module}"`, `from '${import.meta.resolve(module)}'`)
    .replaceAll(`from '${module}'`, `from '${import.meta.resolve(module)}'`)
}
const { default: Workspace } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)

async function render(path, props) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }] })
  await router.push(path)
  await router.isReady()
  const app = createSSRApp({ render: () => h(Workspace, props, { default: () => h('section', { id: 'task-content' }, '独立业务内容') }) })
  app.use(router)
  return renderToString(app)
}

test('开户直达保留账号管理当前项和内部返回，业务内容仍由页面 slot 提供', async () => {
  const html = await render('/manage/accounts', { owner: true, allowed: true, section: 'accounts', creating: true })
  assert.match(html, /href="\/"[^>]*>[\s\S]*?返回首页/)
  assert.match(html, /<a(?=[^>]*href="\/manage")(?=[^>]*aria-current="page")[^>]*>/)
  assert.match(html, /aria-label="当前任务路径"/)
  assert.match(html, /aria-current="page">创建账号/)
  assert.match(html, /id="task-content">独立业务内容/)
  assert.doesNotMatch(html, /返回个人中心/)
})

test('审核 URL 当前项精确，ADMIN 不获得审核导航，未授权时不渲染工作区入口', async () => {
  const owner = await render('/manage/reviews?view=published', { owner: true, allowed: true, section: 'reviews' })
  assert.match(owner, /<a(?=[^>]*href="\/manage\/reviews")(?=[^>]*aria-current="page")[^>]*>/)
  assert.match(owner, /<a(?=[^>]*href="\/manage")(?=[^>]*aria-current="false")[^>]*>/)
  const admin = await render('/manage', { owner: false, allowed: true, section: 'accounts' })
  assert.match(admin, /管理中心/)
  assert.doesNotMatch(admin, /href="\/manage\/reviews"/)
  const denied = await render('/manage', { owner: false, allowed: false, section: 'accounts' })
  assert.doesNotMatch(denied, /aria-label="工作区导航"/)
  assert.match(denied, /id="task-content"/)
})

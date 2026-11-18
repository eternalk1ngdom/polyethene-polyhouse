import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { createServer } from 'vite'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.POLYHOUSE_PLAYWRIGHT_MODULE || 'playwright')
const port = Number(process.env.POLYHOUSE_TREND_BROWSER_PORT)
const username = process.env.POLYHOUSE_TREND_BROWSER_USER
const password = process.env.POLYHOUSE_TREND_BROWSER_PASSWORD
const history = process.env.POLYHOUSE_TREND_BROWSER_HISTORY
assert.ok(Number.isInteger(port) && port >= 1024 && port <= 65535 && port !== 8080)
assert.ok(/^g4_[a-f0-9]{12}$/.test(username) && password && /^\d{4}-\d{2}-\d{2}$/.test(history))

// 跨端请求经实际 Vite 代理，测试仅在本进程把后端目标改到随机回环端口。
process.env.VITE_API_BASE_URL = '/api'
const frontend = await createServer({
  logLevel: 'error',
  server: {
    host: '127.0.0.1',
    port: 0,
    strictPort: true,
    // 随机前端端口保留浏览器 Host，让真实 CORS 过滤器按同源处理此回环代理。
    proxy: { '/api': { target: `http://127.0.0.1:${port}`, changeOrigin: false } },
  },
})
let browser
let stage = '启动隔离浏览器'
const responses = []
try {
  await frontend.listen(0)
  const base = `http://127.0.0.1:${frontend.httpServer.address().port}/polyethene-polyhouse/`
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 } })
  const pageErrors = []
  page.on('pageerror', () => pageErrors.push(stage))
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname
    if (path.startsWith('/api/')) responses.push(`${response.request().method()} ${path} ${response.status()}`)
  })
  const submissions = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/manage/trend/jobs') {
      submissions.push(request.postDataJSON().requestId)
    }
  })

  stage = '首页进入星潮并核对历史档案'
  await page.goto(base)
  await page.getByRole('link', { name: '阅读星潮', exact: true }).click()
  await page.locator('.issue-meta').getByText(history, { exact: true }).waitFor()
  assert.equal(await page.getByRole('link', { name: '星潮发布台', exact: true }).count(), 0)

  stage = '通过登录弹窗建立真实屋主会话'
  await page.locator('.login-trigger').click()
  const login = page.locator('.auth-dialog')
  await login.getByLabel('账号', { exact: true }).fill(username)
  await login.getByLabel('密码', { exact: true }).fill(password)
  const signedIn = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/auth/login')
  await login.getByRole('button', { name: '登录，回到小屋' }).click()
  assert.equal((await signedIn).status(), 200)
  await page.getByRole('link', { name: '星潮发布台', exact: true }).click()
  await page.getByText('剩余可用', { exact: true }).waitFor()

  stage = '提交更新、刷新任务和确认正式发布'
  await page.getByRole('button', { name: '手动更新日报' }).click()
  await page.getByText('已受理本次更新。', { exact: true }).waitFor()
  await page.reload()
  await page.getByRole('heading', { name: /^已发布/ }).waitFor({ timeout: 210_000 })
  await page.getByText(/手动触发.*仅采集资料/).waitFor()
  assert.equal(submissions.length, 1)
  await page.getByRole('link', { name: '阅读最新日报' }).click()
  const rows = page.locator('.entry')
  await rows.first().waitFor()
  const total = await rows.count()
  assert.ok(total > 0 && total <= 25)
  const issue = page.locator('.issue-meta strong')
  const publishedDay = await issue.textContent()
  assert.notEqual(publishedDay, history)
  const fullNames = await rows.locator('h2').allTextContents()

  stage = '读历史并切回当期，同时核对语言筛选保留排名'
  await page.locator('#trend-date').selectOption(history)
  await page.waitForFunction((day) => document.querySelector('.issue-meta strong')?.textContent === day, history)
  await page.locator('#trend-date').selectOption('')
  await page.waitForFunction((day) => document.querySelector('.issue-meta strong')?.textContent === day, publishedDay)
  const languages = page.locator('#trend-language')
  await languages.waitFor()
  const ranks = await rows.locator('.rank').allTextContents()
  const language = await languages.locator('option').nth(1).getAttribute('value')
  assert.ok(language)
  await languages.selectOption(language)
  await page.getByRole('status').filter({ hasText: '保留来源排名' }).waitFor()
  const selected = await rows.locator('.rank').allTextContents()
  assert.ok(selected.length > 0 && selected.every((rank) => ranks.includes(rank)))

  stage = '从筛选结果读项目、刷新详情并返回同一条'
  const entryLink = rows.first().getByRole('link')
  const address = await entryLink.getAttribute('href')
  const entryId = await entryLink.getAttribute('id')
  await entryLink.focus()
  await page.keyboard.press('Enter')
  await page.waitForURL(url => url.href.endsWith(address))
  await page.reload()
  await page.getByRole('link', { name: '在 GitHub 查看' }).waitFor()
  await page.getByRole('link', { name: '返回当期榜单' }).click()
  await page.waitForFunction(id => document.activeElement?.id === id, entryId)
  assert.equal(await languages.inputValue(), language)

  stage = '模拟上游失败，公开页面继续读上一版本'
  await page.getByRole('link', { name: '星潮发布台', exact: true }).click()
  stage = '重新进入工作台并恢复第一笔任务'
  const waitAgain = page.getByText('请求较频繁或更新正在冷却，请稍后重试。', { exact: true })
  const firstJob = page.getByRole('heading', { name: /^已发布/ })
  await firstJob.or(waitAgain).first().waitFor()
  if (await waitAgain.isVisible()) await page.getByRole('button', { name: '重查任务' }).click()
  await firstJob.waitFor({ timeout: 12_000 })
  stage = '第二笔更新由执行器返回失败'
  const waitForSubmit = () => page.waitForResponse((response) =>
    response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/manage/trend/jobs')
  const secondSubmission = waitForSubmit()
  await page.getByRole('button', { name: '手动更新日报' }).click()
  let updateResponse = await secondSubmission
  // 快速操作会触发真实限流。界面恢复可点后沿用该意图，数据库仍只收到两个任务。
  if (updateResponse.status() === 429) {
    const retry = waitForSubmit()
    await page.getByRole('button', { name: '继续同一次更新' }).click({ timeout: 65_000 })
    updateResponse = await retry
    assert.equal(submissions.length, 3)
    assert.equal(submissions[1], submissions[2])
  }
  assert.equal(updateResponse.status(), 202)
  await page.getByRole('heading', { name: '更新失败' }).waitFor({ timeout: 30_000 })
  assert.equal(new Set(submissions).size, 2)
  stage = '退出屋主会话并重新匿名访问'
  await page.getByRole('button', { name: '打开账号菜单' }).click()
  await page.getByRole('button', { name: '退出登录' }).click()
  await page.getByText(/已退出登录/).first().waitFor()
  await page.getByRole('link', { name: '返回星潮日报' }).click()
  await rows.first().waitFor()
  assert.deepEqual(await rows.locator('h2').allTextContents(), fullNames)
  assert.equal(await page.getByRole('link', { name: '星潮发布台', exact: true }).count(), 0)
  await page.goto(`${base}#/manage/trend`)
  await page.getByRole('heading', { name: '屋主专用' }).waitFor()
  assert.equal(pageErrors.length, 0)
  console.log(`星潮浏览器通过：${total} 项，历史、语言筛选、刷新、失败保留及退出均已验证。`)
} catch (failure) {
  // 浏览器库的原始错误可能带操作参数，日志只留阶段和稳定错误类型。
  console.error(`星潮浏览器未通过：${stage}（${failure.name}）。`)
  console.error(responses.slice(-12).join('\n'))
  process.exitCode = 1
} finally {
  try { await browser?.close() } finally { await frontend.close() }
}

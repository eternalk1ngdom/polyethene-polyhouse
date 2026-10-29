/* 可选真实浏览器验收：只由隔离库测试传入随机端口和测试凭据，不接触个人浏览器或真实屋主。 */
const assert = require('node:assert/strict')
const path = require('node:path')
const { chromium } = require(process.env.POLYHOUSE_PLAYWRIGHT_MODULE || 'playwright')
const base = process.env.POLYHOUSE_BROWSER_BASE_URL || 'http://127.0.0.1:5173'
const backend = process.env.POLYHOUSE_BROWSER_API_BASE_URL
const ownerPassword = process.env.POLYHOUSE_BROWSER_OWNER_PASSWORD
let phase = 'startup'
let activePage
const nextPassword = 'P2-browser-personal-fixture!'
for (const endpoint of [base, backend]) {
  assert.ok(endpoint && ['localhost', '127.0.0.1'].includes(new URL(endpoint).hostname), 'Only local fixture endpoints are allowed')
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1050 } })
    // 将前端真实请求代理到随机隔离测试端口；不修改生产 Vite 配置，也不触及 8080 开发账号。
    await context.route('**/api/**', async (route) => {
      const request = new URL(route.request().url())
      // 开发服务器还有 /src/api/*.ts 模块；只能代理根 /api/，不能误把源码模块当成业务请求。
      if (!request.pathname.startsWith('/api/')) { await route.continue(); return }
      const response = await route.fetch({ url: `${backend}${request.pathname}${request.search}` })
      await route.fulfill({ response })
    })
    const page = await context.newPage()
    activePage = page
    const errors = []
    page.on('pageerror', () => errors.push('page error'))
    let creations = 0
    page.on('request', request => { if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/manage/accounts') creations++ })
    await page.goto(base, { waitUntil: 'networkidle' })
    phase = 'open-login'
    await page.locator('.login-trigger').click()
    const auth = page.locator('.auth-dialog')
    assert.equal(await auth.getByRole('button', { name: '注册', exact: true }).count(), 0)
    await auth.getByLabel('账号', { exact: true }).fill('p2_owner')
    await auth.getByLabel('密码', { exact: true }).fill(ownerPassword)
    phase = 'owner-login'
    await auth.getByRole('button', { name: '登录，回到小屋' }).click()
    phase = 'open-owner-menu'
    await page.getByRole('button', { name: '打开账号菜单' }).click()
    await page.getByRole('link', { name: '屋主工作台' }).click()
    phase = 'open-workspace'
    await page.getByRole('heading', { name: '创建账号', exact: true }).waitFor()
    phase = 'create-user'
    await page.getByRole('button', { name: '创建并交付账号' }).dblclick()
    await page.getByRole('heading', { name: '本次创建结果' }).waitFor()
    assert.equal(creations, 1, 'double click must not create a second account')
    const temporaryPassword = await page.locator('.creation-panel input[readonly]').inputValue()
    const username = await page.locator('.credential-summary dd').first().textContent()
    assert.ok(/^ph\d{6,}$/.test(username), 'server must assign the username')
    assert.ok(temporaryPassword.length === 20, 'initial password must have expected length')
    assert.ok(await page.evaluate(password => !JSON.stringify({ ...localStorage, ...sessionStorage }).includes(password), temporaryPassword), 'no plaintext credential may be persisted')
    for (const width of [320, 375, 600, 601, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 1050 })
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `no horizontal overflow at ${width}`)
      const bounds = await page.locator('.creation-panel').boundingBox()
      assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width + 1, 'panel stays within viewport')
    }
    await page.screenshot({ path: path.resolve('../polyhouse/target/p2-owner-workspace.png'), fullPage: true })
    await page.getByRole('button', { name: '已保存，清除本次凭据' }).click()
    assert.equal(await page.locator('.creation-panel input[readonly]').count(), 0)
    phase = 'create-admin'
    await page.locator('.el-radio').filter({ hasText: '管理员' }).click()
    await page.getByRole('button', { name: '创建并交付账号' }).click()
    await page.getByText('创建管理员需要填写屋主当前密码', { exact: true }).waitFor()
    await page.getByLabel('屋主当前密码（再次验证）').fill(ownerPassword)
    await page.getByRole('button', { name: '创建并交付账号' }).click()
    await page.getByRole('heading', { name: '本次创建结果' }).waitFor()
    phase = 'reload-confirmation'
    await page.reload({ waitUntil: 'networkidle' })
    await page.getByRole('button', { name: '核对本次创建结果' }).click()
    await page.getByRole('heading', { name: '本次创建结果' }).waitFor()
    assert.equal(await page.locator('.creation-panel input[readonly]').count(), 0, 'refresh and confirmation must not reveal password')
    await page.getByRole('button', { name: '已核对，结束本次操作' }).click()
    await page.getByRole('button', { name: '打开账号菜单' }).click()
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.locator('.login-trigger').click()
    await auth.getByLabel('账号', { exact: true }).fill(username)
    await auth.getByLabel('密码', { exact: true }).fill(temporaryPassword)
    await auth.getByRole('button', { name: '登录，回到小屋' }).click()
    phase = 'first-password-dialog'
    const passwordDialog = page.getByRole('dialog', { name: '修改密码' })
    await passwordDialog.waitFor()
    await passwordDialog.getByLabel('当前密码', { exact: true }).fill(temporaryPassword)
    await passwordDialog.getByLabel('新密码', { exact: true }).fill(nextPassword)
    await passwordDialog.getByLabel('确认新密码', { exact: true }).fill(nextPassword)
    await passwordDialog.getByRole('button', { name: '保存并重新登录' }).click()
    await passwordDialog.waitFor({ state: 'hidden' })
    if (!await auth.isVisible()) await page.locator('.login-trigger').click()
    await auth.getByLabel('账号', { exact: true }).fill(username)
    await auth.getByLabel('密码', { exact: true }).fill(nextPassword)
    await auth.getByRole('button', { name: '登录，回到小屋' }).click()
    await page.getByRole('button', { name: '打开账号菜单' }).waitFor()
    await page.goto(`${base}/account`, { waitUntil: 'networkidle' })
    await page.getByRole('heading', { name: /小屋住户/ }).waitFor()
    assert.equal(await page.getByRole('link', { name: '屋主工作台' }).count(), 0)
    await page.goto(`${base}/manage/accounts`, { waitUntil: 'networkidle' })
    await page.getByRole('heading', { name: '仅屋主可创建账号' }).waitFor()
    assert.equal(errors.length, 0, 'browser must not raise uncaught errors')
    console.log('P2 browser passed: OWNER USER/ADMIN creation, one-time delivery, reload confirmation, first password change, USER denial, seven viewport widths.')
  } catch (failure) {
    console.error(`Browser phase: ${phase}; error type: ${failure.name}`)
    if (activePage) await activePage.screenshot({ path: path.resolve('../polyhouse/target/p2-browser-failure.png'), fullPage: true })
    throw new Error('Browser check failed')
  } finally { await browser.close() }
})().catch(() => { console.error('P2 browser acceptance failed; no credentials are printed. Inspect the local masked screenshot and selectors.'); process.exitCode = 1 })

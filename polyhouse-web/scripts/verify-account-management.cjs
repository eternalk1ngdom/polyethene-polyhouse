/* 仅操作随机隔离库，禁止连接个人浏览器或 8080 实际账号。 */
const assert = require('node:assert/strict')
const path = require('node:path')
const { chromium } = require(process.env.POLYHOUSE_PLAYWRIGHT_MODULE || 'playwright')
const base = 'http://127.0.0.1:5173'
const backend = process.env.POLYHOUSE_BROWSER_API_BASE_URL
const password = process.env.POLYHOUSE_BROWSER_OWNER_PASSWORD
assert.ok(backend && new URL(backend).hostname === '127.0.0.1' && new URL(backend).port !== '8080')
let phase = 'startup'
;(async () => {
 const browser = await chromium.launch({ channel:'msedge', headless:true })
 try {
  const context = await browser.newContext({viewport:{width:1440,height:1050}})
  await context.route('**/api/**', async route => {
   const url = new URL(route.request().url())
   if (!url.pathname.startsWith('/api/')) return route.continue()
   const response = await route.fetch({url:`${backend}${url.pathname}${url.search}`})
   await route.fulfill({response})
  })
  const page = await context.newPage();const errors=[];page.on('pageerror',()=>errors.push('page error'))
  let writes=0;page.on('request',r=>{if(r.method()==='POST' && /\/(ban|unban|password\/reset)$/.test(new URL(r.url()).pathname))writes++})
  async function login(username,pwd=password) {
   await page.goto(base,{waitUntil:'networkidle'});await page.locator('.login-trigger').click()
   const dialog=page.locator('.auth-dialog');await dialog.getByLabel('账号',{exact:true}).fill(username)
   await dialog.getByLabel('密码',{exact:true}).fill(pwd);await dialog.getByRole('button',{name:'登录，回到小屋'}).click()
   await page.getByRole('button',{name:'打开账号菜单'}).waitFor()
  }
  async function logout(){await page.getByRole('button',{name:'打开账号菜单'}).click();await page.getByRole('button',{name:'退出登录',exact:true}).click()}
  phase='owner-list';await login('p3_owner');await page.getByRole('button',{name:'打开账号菜单'}).click();await page.getByRole('link',{name:'屋主工作台'}).click()
  await page.getByRole('heading',{name:'账号列表'}).waitFor();await page.locator('.account-row').filter({hasText:'p3_user'}).waitFor()
  assert.equal(await page.locator('.account-row').count(),4)
  await page.getByRole('link',{name:'创建账号 →'}).click();await page.getByRole('heading',{name:'创建账号',exact:true}).waitFor()
  await page.getByRole('link',{name:'返回账号管理',exact:false}).click()
  phase='reset-confirmation';await page.getByRole('button',{name:'重置密码 p3_user',exact:true}).click()
  const dialog=page.getByRole('dialog');await dialog.getByLabel('操作原因（必填）').fill('隔离浏览器验收')
  await dialog.getByLabel('屋主当前密码（再次确认）').fill(password)
  await dialog.getByRole('button',{name:'确认重置密码'}).dblclick()
  await dialog.getByText('临时密码 · 仅本次展示',{exact:true}).waitFor()
  const temporary=await dialog.locator('input[readonly]').inputValue();assert.match(temporary,/^[A-Za-z2-9]{20}$/);assert.equal(writes,1)
  assert.equal(await page.evaluate(pwd=>JSON.stringify({...localStorage,...sessionStorage}).includes(pwd),temporary),false)
  for(const width of [320,375,600,601,768,1024,1440]){
   await page.setViewportSize({width,height:1050})
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`no overflow at ${width}`)
   const box=await dialog.boundingBox();assert.ok(box.x>=0 && box.x+box.width<=width+1)
  }
  await page.screenshot({path:path.resolve('../polyhouse/target/p3-owner-management.png'),fullPage:true})
  phase='refresh-confirm';await page.reload({waitUntil:'networkidle'});await page.getByRole('button',{name:'继续核对操作'}).click()
  await dialog.getByText('原密码不能再次取回。若未保存，请结束核对，确认最新账号后显式重新重置。',{exact:true}).waitFor()
  assert.equal(await dialog.locator('input[readonly]').count(),0);assert.equal(writes,1)
  await dialog.getByRole('button',{name:'已核对，清除本次结果'}).click();await logout()
  phase='first-password';await login('p3_user',temporary)
  const change=page.getByRole('dialog',{name:'修改密码'});await change.getByLabel('当前密码',{exact:true}).fill(temporary)
  const next='P3-browser-personal-fixture!';await change.getByLabel('新密码',{exact:true}).fill(next);await change.getByLabel('确认新密码',{exact:true}).fill(next)
  await change.getByRole('button',{name:'保存并重新登录'}).click();await change.waitFor({state:'hidden'})
  // 改密完成会打开登录框，重新导航仍沿用同一可见表单。
  await page.goto(base,{waitUntil:'networkidle'});const auth=page.locator('.auth-dialog')
  if(!await auth.isVisible())await page.locator('.login-trigger').click()
  await auth.getByLabel('账号',{exact:true}).fill('p3_user');await auth.getByLabel('密码',{exact:true}).fill(next);await auth.getByRole('button',{name:'登录，回到小屋'}).click()
  await page.getByRole('button',{name:'打开账号菜单'}).waitFor();await page.goto(`${base}/manage`,{waitUntil:'networkidle'})
  await page.getByRole('heading',{name:'需要管理权限'}).waitFor();await logout()
  phase='admin-ban';await login('p3_admin');await page.getByRole('button',{name:'打开账号菜单'}).click();await page.getByRole('link',{name:'管理中心'}).click()
  await page.locator('.account-row').filter({hasText:'p3_user'}).waitFor();assert.equal(await page.locator('.account-row').count(),1)
  assert.equal(await page.getByRole('button',{name:/重置密码 p3_/}).count(),0)
  await page.getByRole('button',{name:'封禁 p3_user',exact:true}).click();await dialog.getByLabel('操作原因（必填）').fill('隔离封禁验收')
  await dialog.getByRole('button',{name:'确认封禁账号'}).click();await dialog.getByText('已成功记录：p3_user · 封禁',{exact:true}).waitFor()
  await dialog.getByRole('button',{name:'已核对，清除本次结果'}).click();await logout()
  phase='owner-unban';await login('p3_owner');await page.goto(`${base}/manage`,{waitUntil:'networkidle'})
  await page.getByRole('button',{name:'解封 p3_user',exact:true}).click();await dialog.getByLabel('操作原因（必填）').fill('隔离解封验收')
  await dialog.getByRole('button',{name:'确认解封账号'}).click();await dialog.getByText('已成功记录：p3_user · 解封',{exact:true}).waitFor();await dialog.getByRole('button',{name:'已核对，清除本次结果'}).click()
  for(const width of [320,375,600,601,768,1024,1440]){await page.setViewportSize({width,height:1050});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth))}
  assert.equal(errors.length,0);assert.equal(writes,3)
  console.log(JSON.stringify({passed:true,widths:7,managementWrites:writes,pageErrors:errors.length}))
 } catch(error) { console.error(JSON.stringify({passed:false,phase,errorType:error.name}));process.exitCode=1 }
 finally{await browser.close()}
})().catch(error=>{console.error(JSON.stringify({passed:false,phase,errorType:error.name}));process.exitCode=1})

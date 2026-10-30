/* 本脚本只用于隔离库，独立 Edge，不继承真实数据库/JWT 预设。 */
const assert=require('node:assert/strict')
const path=require('node:path')
const {chromium}=require(process.env.POLYHOUSE_PLAYWRIGHT_MODULE||'playwright')
const base='http://127.0.0.1:5173',backend=process.env.POLYHOUSE_BROWSER_API_BASE_URL,password=process.env.POLYHOUSE_BROWSER_FIXTURE_PASSWORD
assert.ok(backend&&new URL(backend).hostname==='127.0.0.1'&&new URL(backend).port!=='8080')
let phase='startup'
;(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true})
 const errors=[]
 async function session(username){
  const c=await browser.newContext({viewport:{width:1440,height:1050}})
  await c.route('**/api/**',async route=>{const u=new URL(route.request().url());if(!u.pathname.startsWith('/api/'))return route.continue();const response=await route.fetch({url:`${backend}${u.pathname}${u.search}`});await route.fulfill({response})})
  const page=await c.newPage();page.on('pageerror',()=>errors.push('page error'))
  await page.goto(base,{waitUntil:'networkidle'});await page.locator('.login-trigger').click();const auth=page.locator('.auth-dialog')
  await auth.getByLabel('账号',{exact:true}).fill(username);await auth.getByLabel('密码',{exact:true}).fill(password);await auth.getByRole('button',{name:'登录，回到小屋'}).click();await page.getByRole('button',{name:'打开账号菜单'}).waitFor()
  return page
 }
 let owner,user
 try{
  phase='user-login';user=await session('p4_user');await user.goto(`${base}/account`,{waitUntil:'networkidle'})
  // 个人中心默认成长；使用真实导航进入资料，不能再假定所有表单同时挂载。
  phase='profile-section';await user.getByRole('link',{name:'账号资料',exact:true}).click()
  phase='nickname-input';await user.getByLabel('申请新昵称',{exact:true}).fill('审核后的昵称');phase='nickname-submit';await user.getByRole('button',{name:'提交昵称审核'}).click()
  await user.getByText('昵称 · 待审核',{exact:true}).waitFor();assert.ok((await user.locator('#profile-name').textContent()).includes('旧昵称'))
  phase='avatar-submission';await user.getByRole('button',{name:'更换头像',exact:true}).click();const upload=user.getByRole('dialog',{name:'换一张头像'})
  await upload.getByLabel('选择头像图片').setInputFiles(process.env.POLYHOUSE_BROWSER_INPUT)
  await upload.getByRole('button',{name:'提交头像审核'}).click();await upload.waitFor({state:'hidden'})
  await user.getByText('头像 · 待审核',{exact:true}).waitFor();await user.locator('.private-preview img').waitFor()
  assert.equal(await user.locator('.avatar-edit img').count(),0)
  phase='owner-review';owner=await session('p4_owner');await owner.goto(`${base}/manage`,{waitUntil:'networkidle'});await owner.getByRole('link',{name:'资料审核 →'}).click()
  await owner.getByRole('button',{name:'通过 p4_user NICKNAME',exact:true}).click();const dialog=owner.getByRole('dialog')
  await dialog.getByLabel('处理原因（必填）').fill('已核对测试昵称');await dialog.getByRole('button',{name:'确认通过资料审核'}).click();await dialog.waitFor({state:'hidden'})
  await user.getByRole('button',{name:'刷新资料',exact:true}).click();await user.locator('#profile-name').filter({hasText:'审核后的昵称'}).waitFor()
  phase='avatar-approval';await owner.getByRole('button',{name:'通过 p4_user AVATAR',exact:true}).click();await dialog.locator('.private-preview img').waitFor()
  for(const width of [320,375,600,601,768,1024,1440]){await owner.setViewportSize({width,height:1050});assert.ok(await owner.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));const b=await dialog.boundingBox();assert.ok(b.x>=0&&b.x+b.width<=width+1)}
  await owner.screenshot({path:path.resolve('../polyhouse/target/p4-review-desktop.png'),fullPage:true})
  await dialog.getByLabel('处理原因（必填）').fill('已核对测试图片');await dialog.getByRole('button',{name:'确认通过资料审核'}).click();await dialog.waitFor({state:'hidden'})
  await user.getByRole('button',{name:'刷新资料',exact:true}).click();await user.locator('.avatar-edit img').waitFor();assert.match(await user.locator('.avatar-edit img').getAttribute('src'),/^\/api\/media\/avatars\/[a-f0-9]{32}\.jpg$/)
  phase='reject-next';await user.getByLabel('申请新昵称',{exact:true}).fill('驳回后不应公开');await user.getByRole('button',{name:'提交昵称审核'}).click();await user.getByText('昵称 · 待审核',{exact:true}).waitFor()
  await owner.getByRole('button',{name:'刷新审核列表'}).click();await owner.getByRole('button',{name:'驳回 p4_user NICKNAME',exact:true}).click();await dialog.getByLabel('处理原因（必填）').fill('请换一个测试昵称');await dialog.getByRole('button',{name:'确认驳回资料申请'}).click();await dialog.waitFor({state:'hidden'})
  await user.getByRole('button',{name:'刷新资料',exact:true}).click();await user.getByText('昵称 · 已驳回',{exact:true}).waitFor();assert.ok((await user.locator('#profile-name').textContent()).includes('审核后的昵称'))
  phase='published-takedown';await owner.locator('.el-checkbox').filter({hasText:'仅看未核对历史'}).click();phase='select-published-kind';await owner.locator('.filters .el-select').click();await owner.getByRole('option',{name:'昵称',exact:true}).click()
  phase='takedown-confirm';await owner.getByRole('button',{name:'下架 p4_user NICKNAME',exact:true}).click();await dialog.getByLabel('处理原因（必填）').fill('恢复默认昵称测试');await dialog.getByRole('button',{name:'确认下架公开资料'}).click();await dialog.waitFor({state:'hidden'})
  await owner.getByRole('button',{name:'保留 p4_other NICKNAME',exact:true}).click();await dialog.getByLabel('处理原因（必填）').fill('核对历史测试资料');await dialog.getByRole('button',{name:'确认保留历史资料'}).click();await dialog.waitFor({state:'hidden'})
  await user.getByRole('button',{name:'刷新资料',exact:true}).click();await user.locator('#profile-name').filter({hasText:'小屋住户'}).waitFor()
  phase='role-boundaries';for(const name of ['p4_admin','p4_other']){const page=await session(name);await page.goto(`${base}/manage/reviews`,{waitUntil:'networkidle'});await page.getByRole('heading',{name:'需要屋主权限'}).waitFor()}
  phase='responsive';for(const width of [320,375,600,601,768,1024,1440]){for(const page of [user,owner]){await page.setViewportSize({width,height:1050});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth))}}
  await owner.setViewportSize({width:375,height:1050});await owner.screenshot({path:path.resolve('../polyhouse/target/p4-review-mobile.png'),fullPage:true})
  assert.equal(errors.length,0);console.log(JSON.stringify({passed:true,widths:7,pageErrors:0,roles:4,journeys:['pending-private','approve-nickname','approve-avatar','reject','takedown','legacy-keep']}))
 }catch(e){console.error(JSON.stringify({passed:false,phase,errorType:e.name,detail:String(e.message).split('\n').slice(0,5).join(' ')}));if(!owner&&user)await user.screenshot({path:path.resolve('../polyhouse/target/p4-user-failure.png'),fullPage:true});if(owner)await owner.screenshot({path:path.resolve('../polyhouse/target/p4-browser-failure.png'),fullPage:true});process.exitCode=1}
 finally{await browser.close()}
})().catch(e=>{console.error(JSON.stringify({passed:false,phase,errorType:e.name,detail:String(e.message).split('\n').slice(0,5).join(' ')}));process.exitCode=1})

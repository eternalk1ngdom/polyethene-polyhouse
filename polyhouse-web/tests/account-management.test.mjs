import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createRenderer, h, nextTick, reactive } from 'vue'
import ts from 'typescript'

const url = (source) => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
async function compile(path, replacements) {
  let output = ts.transpileModule(await readFile(new URL(path, import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  for (const [from, to] of Object.entries(replacements)) output = output.replaceAll(`from '${from}'`, `from '${to}'`)
  return url(output)
}
const apiUrl = await compile('../src/api/index.ts', { axios: import.meta.resolve('axios') })
const { ApiError } = await import(apiUrl)
const realApiUrl = await compile('../src/api/accountManagement.ts', { './index': apiUrl })
const { decodeAccountPage, decodeManagementResult } = await import(realApiUrl)
const userUrl = url('let store; export const useUserStore=()=>store; export const install=value=>{store=value}')
const managementUrl = url('let list,write,confirm; export const install=(a,b,c)=>{list=a;write=b;confirm=c}; export const listManagedAccounts=(...a)=>list(...a); export const manageAccount=(...a)=>write(...a); export const confirmManagementOperation=(...a)=>confirm(...a)')
const { install: installStore } = await import(userUrl)
const { install: installApi } = await import(managementUrl)
const validationUrl = await compile('../src/utils/authValidation.ts', {})
const { useAccountManagement } = await import(await compile('../src/composables/useAccountManagement.ts', {
  vue: import.meta.resolve('vue'), '../api': apiUrl, '../api/accountManagement': managementUrl,
  '../stores/useUserStore': userUrl, '../utils/authValidation': validationUrl,
}))
const renderer = createRenderer({ createElement:()=>({}), createText:()=>({}), createComment:()=>({}), insert(){},remove(){},setText(){},setElementText(){},patchProp(){},parentNode:()=>null,nextSibling:()=>null })
const account = { id:'42',username:'ph000001',nickname:'测试',role:'USER',status:1,version:'0',passwordChangeRequired:false,readOnly:false,createdAt:'2026-10-29T12:00:00' }
const page = { items:[account], total:'1',page:1,pageSize:20 }
const password='AbCdEfGhJkMnPqRs2345'
const resultFor=(request,action='BAN')=>({requestId:request.requestId,targetId:'42',username:account.username,action,version:'1',status:action==='BAN'?0:1,firstDelivery:action==='PASSWORD_RESET',temporaryPassword:action==='PASSWORD_RESET'?password:null})
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
function fixture({write=async(_id,action,request)=>resultFor(request,action),confirm=async()=>{},list=async()=>page,storage=new Map()}={}) {
 globalThis.sessionStorage={getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)}
 const store=reactive({isLoggedIn:true,token:'fixture',user:{id:'1',role:'OWNER'},requiresPasswordChange:false})
 installStore(store); installApi(list,write,confirm)
 let c;const app=renderer.createApp({setup(){c=useAccountManagement();return()=>h('div')}});app.mount({})
 return {c,store,storage,unmount:()=>app.unmount()}
}
function select(f,action='BAN'){f.c.open(account,action);f.c.reason.value='测试原因';if(action==='PASSWORD_RESET')f.c.ownerPassword.value='owner-fixture'}

test('重复点击只执行一次，确认前不写入，原因和密码不落浏览器存储',async(t)=>{
 const response=deferred();let calls=0,req
 const f=fixture({write:async(_id,_action,request)=>{calls++;req=request;return response.promise}});t.after(f.unmount)
 select(f,'PASSWORD_RESET');assert.equal(calls,0)
 const a=f.c.submit(),b=f.c.submit();assert.equal(calls,1)
 assert.ok(![...f.storage.values()].join('').includes('owner-fixture'));assert.ok(![...f.storage.values()].join('').includes('测试原因'))
 response.resolve(resultFor(req,'PASSWORD_RESET'));await Promise.all([a,b]);assert.equal(f.c.result.value.temporaryPassword,password)
 f.c.close();assert.equal(f.c.result.value,null);assert.equal(f.c.ownerPassword.value,'');assert.ok(f.c.pending.value)
 f.c.finish();assert.equal(f.storage.size,0)
})
test('未知结果刷新后仅 GET 核对，不自动重发或重新交付密码',async(t)=>{
 let intent,calls=0
 const f=fixture({write:async(_id,_action,request)=>{intent=request;calls++;throw new ApiError('offline',0)}})
 select(f,'PASSWORD_RESET');await f.c.submit();const storage=f.storage;f.unmount()
 const next=fixture({storage,write:async()=>{calls++},confirm:async id=>{assert.equal(id,intent.requestId);return {...resultFor(intent,'PASSWORD_RESET'),firstDelivery:false,temporaryPassword:null}}});t.after(next.unmount)
 assert.ok(next.c.pending.value);await next.c.submit();assert.equal(calls,1)
 await next.c.confirm();assert.equal(next.c.result.value.temporaryPassword,null);assert.equal(calls,1)
})
test('换号和退出丢弃旧写响应，不能恢复明文',async(t)=>{
 const response=deferred();let request
 const f=fixture({write:async(_id,_action,req)=>{request=req;return response.promise}});t.after(f.unmount)
 select(f,'PASSWORD_RESET');const pending=f.c.submit();f.store.token='next';f.store.user={id:'2',role:'ADMIN'};await nextTick()
 response.resolve(resultFor(request,'PASSWORD_RESET'));await pending
 assert.equal(f.c.result.value,null);assert.equal(f.c.ownerPassword.value,'');assert.equal(f.c.pending.value,null)
})
test('卸载清除临时凭据，旧响应不回填',async()=>{
 const response=deferred();let request
 const f=fixture({write:async(_id,_action,req)=>{request=req;return response.promise}})
 select(f,'PASSWORD_RESET');const operation=f.c.submit();f.unmount();response.resolve(resultFor(request,'PASSWORD_RESET'));await operation
 assert.equal(f.c.result.value,null);assert.equal(f.c.ownerPassword.value,'')
})
test('409 必须从最新列表重新选择，400 可以修正再验密码',async(t)=>{
 let status=409,calls=0
 const f=fixture({write:async()=>{calls++;throw new ApiError('conflict',status)}});t.after(f.unmount)
 select(f);await f.c.submit();assert.equal(f.c.stale.value,true);assert.equal(f.c.pending.value,null)
 await f.c.submit();assert.equal(calls,1);f.c.close();status=400
 select(f,'PASSWORD_RESET');await f.c.submit();assert.equal(calls,2);assert.equal(f.c.ownerPassword.value,'');assert.equal(f.c.pending.value,null)
})
test('ADMIN 不能重置、管理 ADMIN/INSIDER/OWNER，首次改密或其他角色不发请求',async(t)=>{
 let writes=0;const f=fixture({write:async()=>{writes++}});t.after(f.unmount)
 f.store.user.role='ADMIN';await nextTick()
 for(const role of ['ADMIN','INSIDER','OWNER']){f.c.open({...account,role,readOnly:role==='OWNER'},'BAN');assert.equal(f.c.dialogOpen.value,false)}
 f.c.open(account,'PASSWORD_RESET');assert.equal(f.c.dialogOpen.value,false)
 for(const role of ['USER','INSIDER']){f.store.user.role=role;await nextTick();select(f);await f.c.submit()}
 f.store.user.role='OWNER';f.store.requiresPasswordChange=true;await nextTick();select(f);await f.c.submit();assert.equal(writes,0)
})
test('旧列表响应不覆盖后发查询，过滤条件非法不请求',async(t)=>{
 const response=deferred();let calls=0
 const f=fixture({list:async()=>++calls===1?response.promise:{...page,items:[],total:'0'}});t.after(f.unmount)
 await f.c.load();response.resolve(page);await nextTick();assert.equal(f.c.items.value.length,0)
 f.c.filters.username='%';await f.c.load();assert.equal(calls,2)
})
test('未找到成功记录保留原意图，响应编号错配不当作成功',async(t)=>{
 const f=fixture({write:async()=>({...resultFor({requestId:'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'}),targetId:'99'}),confirm:async()=>{throw new ApiError('missing',404)}});t.after(f.unmount)
 select(f);await f.c.submit();assert.equal(f.c.result.value,null);assert.ok(f.c.pending.value)
 await f.c.confirm();assert.ok(f.c.pending.value);assert.match(f.c.error.value,/仍可能/)
})
test('解码严格验证角色、精度、只读标记与一次明文，额外字段不传播',()=>{
 assert.deepEqual(decodeAccountPage(page),page)
 assert.equal(decodeAccountPage({...page,items:[{...account,passwordHash:'not-forwarded'}]}).items[0].passwordHash,undefined)
 for(const item of [{...account,id:42},{...account,role:'SUPER'},{...account,readOnly:true},{...account,status:2}])assert.throws(()=>decodeAccountPage({...page,items:[item]}),ApiError)
 const result=resultFor({requestId:'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'},'PASSWORD_RESET')
 assert.deepEqual(decodeManagementResult(result),result)
 for(const invalid of [{...result,requestId:'a'.repeat(36)},{...result,firstDelivery:false},{...result,temporaryPassword:null},{...result,action:'BAN'}])assert.throws(()=>decodeManagementResult(invalid),ApiError)
})

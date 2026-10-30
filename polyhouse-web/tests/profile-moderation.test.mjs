import assert from 'node:assert/strict'
import test from 'node:test'
import {readFile} from 'node:fs/promises'
import {createRenderer,h,nextTick,reactive,ref} from 'vue'
import {compileScript,parse} from 'vue/compiler-sfc'
import {AxiosError} from 'axios'
import ts from 'typescript'
const url=s=>`data:text/javascript;base64,${Buffer.from(s).toString('base64')}`
async function compile(path,replacements={},source){let code=ts.transpileModule(source??await readFile(new URL(path,import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText;for(const [a,b]of Object.entries(replacements)){code=code.replaceAll(`from '${a}'`,`from '${b}'`).replaceAll(`from "${a}"`,`from '${b}'`)}return url(code)}
const apiUrl=await compile('../src/api/index.ts',{axios:import.meta.resolve('axios')})
const {ApiError,apiImage,apiClient,setUnauthorizedHandler,setPasswordChangeRequiredHandler}=await import(apiUrl)
const authUrl=await compile('../src/api/auth.ts',{'./index':apiUrl})
const actualUrl=await compile('../src/api/profileModeration.ts',{'./index':apiUrl})
const {decodeSubmission}=await import(actualUrl)
const storeUrl=url('let store;export const useUserStore=()=>store;export const install=s=>{store=s}')
const mockUrl=url('let a;export const install=v=>{a=v};export const getProfileStatus=(...x)=>a.mine(...x);export const submitNickname=(...x)=>a.submit(...x);export const getReviews=(...x)=>a.reviews(...x);export const getPublished=(...x)=>a.published(...x);export const decideReview=(...x)=>a.decide(...x);export const changePublished=(...x)=>a.change(...x);export const privateImage=(...x)=>a.image(...x)')
const {install:installStore}=await import(storeUrl),{install:installApi}=await import(mockUrl)
const replacements={vue:import.meta.resolve('vue'),'../api':apiUrl,'../api/auth':authUrl,'../api/profileModeration':mockUrl,'../stores/useUserStore':storeUrl}
const {useProfileSubmissions}=await import(await compile('../src/composables/useProfileSubmissions.ts',replacements))
const {useProfileReview}=await import(await compile('../src/composables/useProfileReview.ts',replacements))
const {descriptor}=parse(await readFile(new URL('../src/components/PrivateProfileImage.vue',import.meta.url),'utf8'))
const {default:PrivateImage}=await import(await compile('',{...replacements,'element-plus':url('export const ElButton={}'), '../api/profileModeration':mockUrl},compileScript(descriptor,{id:'private-preview-test'}).content))
const renderer=createRenderer({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),insert(){},remove(){},setText(){},setElementText(){},patchProp(){},parentNode:()=>null,nextSibling:()=>null})
const record={id:'42',userId:'2',username:'fixture',kind:'NICKNAME',status:'PENDING',version:'1',requestId:'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',proposedValue:'测试申请',reason:null,createdAt:'2026-10-30T13:00:00',expiresAt:'2026-11-06T13:00:00',remainingToday:1}
const status={submissions:[record],avatarRemainingToday:5,nicknameRemainingToday:1}
const page={items:[record],total:'1',page:1,pageSize:20}
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
function fixture(kind='mine',api={},role='OWNER', initiallyActive=true){
 const active=ref(initiallyActive)
 const store=reactive({isLoggedIn:true,requiresPasswordChange:false,token:'fixture',user:{id:'1',role,nickname:'旧公开昵称'}})
 installStore(store);installApi({mine:async()=>status,submit:async(id,nickname)=>({...record,requestId:id,proposedValue:nickname}),reviews:async()=>page,published:async()=>({...page,items:[]}),decide:async()=>record,change:async()=>({}),image:async()=>new Blob(['fixture']),...api})
 let c;const props=reactive({submissionId:'42'})
 const component=kind==='image'?{...PrivateImage,setup(p,ctx){c=PrivateImage.setup(p,ctx);return()=>h('div')}}:{setup(){c=kind==='mine'?useProfileSubmissions(()=>active.value):useProfileReview();return()=>h('div')}}
 const ready=[];props.onReady=v=>ready.push(v);const app=renderer.createApp({setup:()=>()=>h(component,props)});app.mount({})
 return{store,c,props,ready,active,unmount:()=>app.unmount()}
}

test('资料分类未打开不读取，切换保留草稿和结果未知的原请求号',async(t)=>{
 let reads=0;const ids=[]
 const f=fixture('mine',{mine:async()=>{reads++;return status},submit:async(id)=>{ids.push(id);throw new ApiError('offline',0)}},'OWNER',false);t.after(f.unmount)
 await nextTick();assert.equal(reads,0)
 f.active.value=true;await nextTick();f.c.nickname.value='分类草稿';await f.c.submit()
 f.active.value=false;await nextTick();await f.c.load();await f.c.submit()
 assert.equal(f.c.nickname.value,'分类草稿');assert.equal(ids.length,1);const before=reads
 f.active.value=true;await nextTick();assert.equal(reads,before+1);await f.c.submit();assert.equal(ids[0],ids[1])
})

test('切走分类后待处理申请正常收尾但不拉隐藏状态，返回只读刷新',async(t)=>{
 const wait=deferred();let id,reads=0,writes=0
 const f=fixture('mine',{mine:async()=>{reads++;return status},submit:async(req)=>{id=req;writes++;return wait.promise}});t.after(f.unmount)
 await nextTick();f.c.nickname.value='等待响应';const operation=f.c.submit()
 f.active.value=false;await nextTick();const before=reads;wait.resolve({...record,requestId:id});await operation
 assert.equal(reads,before);assert.equal(f.c.saving.value,false);assert.match(f.c.notice.value,/审核通过/)
 f.active.value=true;await nextTick();assert.equal(reads,before+1);assert.equal(writes,1)
})

test('同 Token 换用户也立即清除页内草稿并丢弃旧申请响应',async(t)=>{
 const wait=deferred();let id
 const f=fixture('mine',{submit:async(req)=>{id=req;return wait.promise}});t.after(f.unmount)
 f.c.nickname.value='旧用户申请';const operation=f.c.submit();f.store.user.id='9'
 assert.equal(f.c.nickname.value,'');wait.resolve({...record,requestId:id});await operation
 assert.equal(f.c.notice.value,'');assert.equal(f.c.saving.value,false)
})
test('昵称提交去重且不抢先写公开资料，成功后刷新服务端状态',async(t)=>{
 const wait=deferred();let calls=0,request
 const f=fixture('mine',{submit:async(id)=>{calls++;request=id;return wait.promise}});t.after(f.unmount)
 f.c.nickname.value='新昵称';const a=f.c.submit(),b=f.c.submit();assert.equal(calls,1)
 wait.resolve({...record,requestId:request});await Promise.all([a,b]);assert.equal(f.store.user.nickname,'旧公开昵称');assert.equal(f.c.nickname.value,'');assert.ok(f.c.notice.value.includes('审核通过'))
})
test('结果未知不自动重发，显式原内容重试沿用同一编号',async(t)=>{
 const ids=[];const f=fixture('mine',{submit:async(id)=>{ids.push(id);throw new ApiError('offline',0)}});t.after(f.unmount)
 f.c.nickname.value='原申请';await f.c.submit();await f.c.load();assert.equal(ids.length,1);await f.c.submit();assert.equal(ids[0],ids[1])
 f.c.nickname.value='另一个申请';await f.c.submit();assert.notEqual(ids[1],ids[2])
})
test('换号/卸载丢弃旧申请响应和待审内容',async(t)=>{
 const wait=deferred();let id;const f=fixture('mine',{submit:async(req)=>{id=req;return wait.promise}});t.after(f.unmount)
 f.c.nickname.value='不能串号';const operation=f.c.submit();f.store.token='new';f.store.isLoggedIn=false;await nextTick();wait.resolve({...record,requestId:id});await operation
 assert.equal(f.c.status.value,null);assert.equal(f.c.notice.value,'');assert.equal(f.c.nickname.value,'')
})
test('控制字符昵称和临时凭据不发写请求',async(t)=>{
 let calls=0;const f=fixture('mine',{submit:async()=>{calls++}});t.after(f.unmount)
 for(const text of ['', 'x'.repeat(33),'非法\n换行','隐藏\u200b字符']){f.c.nickname.value=text;await f.c.submit()}
 f.store.requiresPasswordChange=true;await nextTick();f.c.nickname.value='正常昵称';await f.c.submit();assert.equal(calls,0)
})
test('仅 OWNER 加载审核页，旧查询结果不能覆盖新身份',async(t)=>{
 let calls=0;const wait=deferred();const f=fixture('review',{reviews:async()=>{calls++;return wait.promise}},'ADMIN');t.after(f.unmount)
 await f.c.load();assert.equal(calls,0);f.store.user.role='OWNER';await nextTick();assert.equal(calls,1)
 f.store.user.role='INSIDER';await nextTick();wait.resolve(page);await nextTick();assert.deepEqual(f.c.items.value,[])
})
test('审核只在确认时发送一次，未知结果强制刷新而不盲目重发',async(t)=>{
 let calls=0;const wait=deferred();const f=fixture('review',{decide:async()=>{calls++;return wait.promise}});t.after(f.unmount)
 f.c.openDecision(record,true);assert.equal(calls,0);f.c.reason.value='已核对';const a=f.c.submit(),b=f.c.submit();assert.equal(calls,1)
 wait.reject(new ApiError('offline',0));await Promise.all([a,b]);assert.equal(f.c.uncertain.value,true);await f.c.submit();assert.equal(calls,1)
 f.c.close();await f.c.load();assert.equal(calls,1)
})
test('409 审核冲突不伪报成功，旧成功响应不能改变换号后的界面',async(t)=>{
 const f=fixture('review',{decide:async()=>{throw new ApiError('changed',409)}});t.after(f.unmount)
 f.c.openDecision(record,false);f.c.reason.value='驳回原因';await f.c.submit();assert.equal(f.c.uncertain.value,true);assert.equal(f.c.notice.value,'')
})
test('私有预览 URL 在换图和卸载时释放，旧 Blob 不串入新身份',async()=>{
 const first=deferred(),second=deferred();let calls=0,created=0;const revoked=[]
 const originalCreate=URL.createObjectURL,originalRevoke=URL.revokeObjectURL
 URL.createObjectURL=()=>`blob:fixture-${++created}`;URL.revokeObjectURL=x=>revoked.push(x)
 try{
  const f=fixture('image',{image:async()=>++calls===1?first.promise:second.promise});f.store.token='new';await nextTick()
  first.resolve(new Blob(['old']));await nextTick();assert.equal(created,0);assert.equal(f.ready.at(-1),false)
  second.resolve(new Blob(['new']));await nextTick();await nextTick();assert.equal(f.c.url.value,'blob:fixture-1');assert.equal(f.ready.at(-1),false)
  const previousImage=globalThis.HTMLImageElement;globalThis.HTMLImageElement=class {constructor(src){this.currentSrc=src;this.naturalWidth=256}}
  try { f.c.loaded({currentTarget:new HTMLImageElement('blob:old')});assert.equal(f.ready.at(-1),false);f.c.loaded({currentTarget:new HTMLImageElement('blob:fixture-1')});assert.equal(f.ready.at(-1),true) } finally { if(previousImage)globalThis.HTMLImageElement=previousImage;else delete globalThis.HTMLImageElement }
  f.unmount();assert.deepEqual(revoked,['blob:fixture-1'])
 }finally{URL.createObjectURL=originalCreate;URL.revokeObjectURL=originalRevoke}
})
test('媒体错误沿用 401 清理与首次改密闸门，不把原始响应传播给页面',async()=>{
 let unauthorized='',first='';setUnauthorizedHandler(t=>{unauthorized=t});setPasswordChangeRequiredHandler(t=>{first=t})
 apiClient.defaults.adapter=async config=>{throw new AxiosError('fixture',undefined,config,null,{status:401,data:new Blob(['{}'],{type:'application/json'}),headers:{},config})}
 await assert.rejects(apiImage('/fixture','submitted'),e=>e instanceof ApiError&&e.status===401);assert.equal(unauthorized,'submitted')
 apiClient.defaults.adapter=async config=>{throw new AxiosError('fixture',undefined,config,null,{status:403,data:new Blob(['{"code":40301}'],{type:'application/json'}),headers:{},config})}
 await assert.rejects(apiImage('/fixture','initial'),e=>e instanceof ApiError&&e.code===40301);assert.equal(first,'initial')
})
test('申请解码拒绝旧上传合同和非法字段，并丢弃私有路径',()=>{
 assert.deepEqual(decodeSubmission(record),record);assert.equal(decodeSubmission({...record,privateKey:'not-forwarded'}).privateKey,undefined)
 for(const bad of [{avatar:'/old.jpg',remainingToday:4},{...record,id:42},{...record,kind:'PASSWORD'},{...record,kind:'AVATAR'},{...record,status:'UNKNOWN'}])assert.throws(()=>decodeSubmission(bad),ApiError)
})

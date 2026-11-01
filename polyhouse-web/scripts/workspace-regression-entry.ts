// 仅由显式隔离验收复制到被 Git 忽略的 temp；生产 main.ts 不导入此入口。
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'
import App from '../src/App.vue'
import router from '../src/router'
import { setUnauthorizedHandler, setPasswordChangeRequiredHandler } from '../src/api'
import { useUserStore } from '../src/stores/useUserStore'
import type { LoginResponse } from '../src/api/auth'

/** 登录响应来自隔离后端的真实 AuthService，不在浏览器伪造角色或业务数据。 */
interface Sessions {
  /** 完整屋主。 */ OWNER: LoginResponse
  /** 管理员。 */ ADMIN: LoginResponse
  /** 普通用户。 */ USER: LoginResponse
  /** 无管理资格的内部用户。 */ INSIDER: LoginResponse
  /** 需要首次改密的用户。 */ FIRST: LoginResponse
}
const sessions: Sessions = await (await fetch('/__fixture/sessions')).json()
const app = createApp(App),
  pinia = createPinia()
app.use(pinia)
const store = useUserStore(pinia)
setUnauthorizedHandler(store.expireSession)
setPasswordChangeRequiredHandler(store.requirePasswordChange)
const controls = document.createElement('aside')
controls.setAttribute('aria-label', 'U4 隔离验收控制')
controls.style.cssText =
  'padding:8px 16px;background:#f7f7f9;display:flex;gap:8px;flex-wrap:wrap;font:12px sans-serif'
const status = document.createElement('output')
// 请求统计可能较长，验收工具自身不应制造横向溢出而干扰产品布局读数。
status.style.cssText = 'max-width:100%;overflow-wrap:anywhere'
status.textContent = '真实隔离后端；不操作开发库'
/** 通过可见控件切换本次专属会话，不暴露凭据到文字、日志或 URL。 */
function button(label: string, action: () => void): void {
  const element = document.createElement('button')
  element.textContent = label
  element.onclick = action
  controls.append(element)
}
for (const role of ['OWNER', 'ADMIN', 'USER', 'INSIDER', 'FIRST'] as const) {
  button(`测试身份 ${role}`, () =>
    store.setSession(sessions[role].accessToken, sessions[role].user),
  )
}
button('测试身份 ANONYMOUS', () => store.clearSession())
for (const mode of ['normal', 'offline', 'delay', 'lose-next-write']) {
  button(`网络 ${mode}`, () => {
    void fetch('/__fixture/network', { method: 'POST', body: JSON.stringify({ mode }) })
      .then(() => {
        status.textContent = `网络模式：${mode}`
      })
      .catch(() => {
        status.textContent = '网络设置失败'
      })
  })
}
button('显示请求统计', () => {
  void fetch('/__fixture/metrics')
    .then((r) => r.json())
    .then((value) => {
      status.textContent = JSON.stringify(value)
    })
    .catch(() => {
      status.textContent = '统计读取失败'
    })
})
button('结束隔离验收', () => {
  void fetch('/__fixture/finish', { method: 'POST' })
    .then(() => {
      status.textContent = '验收已结束，等待后端数据断言和清理'
    })
    .catch(() => {
      status.textContent = '结束请求失败'
    })
})
controls.append(status)
document.body.prepend(controls)
if (!store.token) store.setSession(sessions.OWNER.accessToken, sessions.OWNER.user)
app.use(router)
await router.isReady()
app.mount('#app')
void store.restoreSession()
window.addEventListener('storage', (event) => {
  if (event.key === 'polyhouse_token' || event.key === null) void store.syncStoredSession()
})

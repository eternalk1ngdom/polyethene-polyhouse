import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api'
import { useUserStore } from './stores/useUserStore'
import { useUserGrowthStore } from './stores/useUserGrowthStore'

const app = createApp(App)

const pinia = createPinia()
app.use(pinia)
app.use(router)

const userStore = useUserStore(pinia)
const growthStore = useUserGrowthStore(pinia)
setUnauthorizedHandler(userStore.expireSession)

app.mount('#app')

// 不阻塞首页渲染；恢复失败由状态栏提供重试，公开页面仍然可以浏览。
void userStore.restoreSession()
window.addEventListener('storage', (event: StorageEvent) => {
  if (event.key === 'polyhouse_token' || event.key === null) void userStore.syncStoredSession()
  // 其他页修改头像后重新读取服务端，不把 localStorage 中的头像或角色当作可信响应。
  else if (event.key === 'polyhouse_user' && userStore.isLoggedIn) void userStore.restoreSession()
})
document.addEventListener('visibilitychange', async () => {
  // 回到页面时核对最新身份，也让有效 Token 的跨日活跃奖励在服务端正常结算。
  if (document.visibilityState === 'visible') {
    await userStore.restoreSession()
    if (userStore.isLoggedIn) await growthStore.refresh()
  }
})

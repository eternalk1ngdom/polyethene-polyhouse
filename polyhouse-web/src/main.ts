import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api'
import { useUserStore } from './stores/useUserStore'

const app = createApp(App)

const pinia = createPinia()
app.use(pinia)
app.use(router)

const userStore = useUserStore(pinia)
setUnauthorizedHandler(userStore.expireSession)

app.mount('#app')

// 不阻塞首页渲染；恢复失败由状态栏提供重试，公开页面仍然可以浏览。
void userStore.restoreSession()
window.addEventListener('storage', (event: StorageEvent) => {
  if (event.key === 'polyhouse_token' || event.key === null) void userStore.syncStoredSession()
})
document.addEventListener('visibilitychange', () => {
  // 回到页面时核对最新身份，也让有效 Token 的跨日活跃奖励在服务端正常结算。
  if (document.visibilityState === 'visible') void userStore.restoreSession()
})

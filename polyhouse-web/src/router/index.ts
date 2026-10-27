import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import { toolRegistry } from '../config/toolRegistry'

// 页面按需加载；导航与卡片只读取注册表，不维护第二份入口列表。
const toolViews = {
  'poly-replace': () => import('../views/PolyReplaceView.vue'),
  'target-kb': () => import('../views/TargetKbView.vue'),
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'home', component: HomeView, meta: { title: '日常小事，顺手一点' } },
    ...toolRegistry.map((tool) => {
      // 遗漏页面映射时及早报错，避免发布一个能点击却空白的工具入口。
      if (!(tool.code in toolViews)) throw new Error(`工具缺少页面映射：${tool.code}`)
      return {
        path: tool.route,
        name: tool.code,
        component: toolViews[tool.code as keyof typeof toolViews],
        meta: { title: `${tool.title} · ${tool.tag}` },
      }
    }),
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('../views/NotFoundView.vue'),
      meta: { title: '页面未找到' },
    },
  ],
  scrollBehavior(to, _from, savedPosition) {
    // 浏览器后退优先恢复原位置，锚点等待已挂载的页面，普通切页回到顶部。
    if (savedPosition) return savedPosition
    if (to.hash === '#tools' || to.hash === '#about') return { el: to.hash, top: 24 }
    return { top: 0 }
  },
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : '日常工具箱'
  document.title = `${title} · 聚妙屋 Polyhouse`
})

export default router

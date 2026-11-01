<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ArrowLeft, ArrowRight, User, Checked } from '@element-plus/icons-vue'

/** 三个管理页只共享展示框架；请求、授权和敏感状态仍归各自页面管理。 */
interface Props {
  /** 由当前页面的既有权限判定提供，不按角色权重推导。 */
  owner: boolean
  /** 身份核对通过后才展示可用导航，隐藏入口不替代后端授权。 */
  allowed: boolean
  /** 开户属于账号管理的子任务，主导航仍标记账号管理。 */
  section: 'accounts' | 'reviews'
  /** 仅开户页展示任务层级，不把个人中心当作工作台上级。 */
  creating?: boolean
}
defineProps<Props>()
</script>

<template>
  <div class="workspace">
    <RouterLink class="back-link" to="/"> <ArrowLeft aria-hidden="true" />返回首页 </RouterLink>
    <header class="workspace-heading">
      <p>POLYHOUSE WORKSPACE</p>
      <h1>{{ owner ? '屋主工作台' : '管理中心' }}</h1>
      <span>受控内测 · {{ section === 'reviews' ? '资料审核' : '账号管理' }}</span>
    </header>
    <nav v-if="allowed" class="workspace-nav" aria-label="工作区导航">
      <RouterLink to="/manage" :aria-current="section === 'accounts' ? 'page' : 'false'"
        ><User aria-hidden="true" />账号管理</RouterLink
      >
      <RouterLink
        v-if="owner"
        to="/manage/reviews"
        :aria-current="section === 'reviews' ? 'page' : 'false'"
        ><Checked aria-hidden="true" />资料审核</RouterLink
      >
    </nav>
    <nav v-if="allowed && creating" class="task-path" aria-label="当前任务路径">
      <span>工作台</span><ArrowRight aria-hidden="true" />
      <RouterLink to="/manage">账号管理</RouterLink><ArrowRight aria-hidden="true" />
      <span aria-current="page">创建账号</span>
    </nav>
    <slot />
  </div>
</template>

<style scoped>
.workspace {
  max-width: 1120px;
  margin: 0 auto;
  padding: 40px 24px 80px;
  color: var(--ph-ink);
}
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  font-size: 14px;
  color: var(--ph-muted);
}
.workspace-heading {
  margin: 24px 0 28px;
}
.workspace-heading p {
  font-size: 11px;
  letter-spacing: 0.16em;
}
.workspace-heading h1 {
  margin: 10px 0;
  font-size: clamp(28px, 4vw, 40px);
}
.workspace-heading span {
  font-size: 14px;
  color: var(--ph-muted);
}
.workspace-nav {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  border-bottom: 1px solid var(--ph-line);
  padding-bottom: 12px;
  margin-bottom: 24px;
}
.workspace-nav a {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  padding: 10px 16px;
  border-radius: 12px;
  font-size: 14px;
}
.workspace-nav a:hover {
  background: var(--ph-soft);
}
.workspace-nav a[aria-current='page'] {
  background: var(--ph-lilac);
  font-weight: 600;
}
svg {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}
.task-path {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  color: var(--ph-muted);
  font-size: 13px;
  margin-bottom: 24px;
}
.task-path a {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
}
.task-path [aria-current='page'] {
  color: var(--ph-ink);
}
@media (max-width: 600px) {
  .workspace {
    padding: 28px 16px 56px;
  }
}
</style>

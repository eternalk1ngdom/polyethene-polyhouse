<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import TopNavBar from './components/TopNavBar.vue'
</script>

<template>
  <div class="app-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <TopNavBar />
    <main id="main-content" class="main-content" tabindex="-1">
      <RouterView />
    </main>
    <footer class="site-footer">
      <RouterLink class="footer-brand" to="/">polyhouse<span>聚妙屋</span></RouterLink>
      <p>给日常一点灵感，给繁琐一个解法。</p>
      <RouterLink class="footer-link" to="/account">个人空间</RouterLink>
      <span class="version-note">Small tools. More possibilities.</span>
    </footer>
  </div>
</template>

<style scoped>
/* 方案二的全站语义色由外壳统一定义，弹窗和业务页共享，不改变账号或算法状态。 */
:global(:root) {
  --ph-font: 'Inter', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-family: var(--ph-font);
  color: var(--ph-ink);
  background: var(--ph-surface);
  font-synthesis: none;
  text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased;
  --ph-ink: #0d0c22;
  --ph-muted: #626274;
  --ph-line: #e6e6eb;
  --ph-brand: #0d0c22;
  --ph-accent: #ea4c89;
  --ph-accent-strong: #b52b62;
  --ph-surface: #fff;
  --ph-soft: #f7f7f9;
  --ph-cream: #f8f7f4;
  --ph-lilac: #e7e1f7;
  --ph-peach: #f5dfce;
  --ph-pink-soft: #fff0f6;
  --el-color-primary: #0d0c22;
  --el-color-primary-light-3: #555466;
  --el-color-primary-light-5: #8b8a98;
  --el-color-primary-light-7: #c2c1ca;
  --el-color-primary-light-8: #e2e1e7;
  --el-color-primary-light-9: #f7f7f9;
  --el-color-primary-dark-2: #25243b;
  --el-text-color-primary: #0d0c22;
  --el-text-color-regular: #515162;
  --el-border-color: #dddde5;
  --el-border-radius-base: 12px;
  --el-border-radius-small: 8px;
}
:global(*) {
  box-sizing: border-box;
}
:global(body) {
  margin: 0;
  min-width: 280px;
}
:global(a) {
  color: inherit;
  text-decoration: none;
}
:global(button),
:global(input),
:global(select),
:global(textarea) {
  font: inherit;
}
:global(button:not(:disabled)),
:global(a) {
  -webkit-tap-highlight-color: transparent;
}
:global(a:focus-visible),
:global(button:focus-visible),
:global([tabindex]:focus-visible),
:global(select:focus-visible),
:global(textarea:focus-visible) {
  outline: 3px solid var(--ph-accent-strong);
  outline-offset: 4px;
}
:global(::selection) {
  background: #f8cde0;
  color: #0d0c22;
}
:global(.el-button) {
  min-height: 44px;
  border-radius: 99px;
  font-weight: 500;
}
:global(.el-button.is-text) {
  border-radius: 10px;
}
:global(.el-dialog) {
  border-radius: 22px;
}
:global(.el-popover.el-popper) {
  border-radius: 18px;
  border-color: var(--ph-line);
  box-shadow: 0 12px 45px #0d0c2214;
}
:global(.el-input__wrapper) {
  transition: box-shadow 0.18s;
}
:global([id]) {
  scroll-margin-top: 24px;
}
@media (prefers-reduced-motion: reduce) {
  :global(*),
  :global(*::before),
  :global(*::after) {
    scroll-behavior: auto !important;
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
.app-shell {
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
}
.main-content {
  flex: 1;
  outline: none;
}
.skip-link {
  position: fixed;
  z-index: 3000;
  top: 12px;
  left: 20px;
  padding: 12px 20px;
  background: white;
  border: 1px solid var(--ph-brand);
  border-radius: 99px;
  transform: translateY(-160%);
}
.skip-link:focus {
  transform: translateY(0);
}
.site-footer {
  width: min(1320px, calc(100% - 96px));
  margin: 72px auto 0;
  padding: 32px 0;
  border-top: 1px solid var(--ph-line);
  display: flex;
  align-items: center;
  gap: 28px;
  color: var(--ph-muted);
  font-size: 12px;
}
.footer-brand {
  font-family: Georgia, serif;
  font-style: italic;
  color: var(--ph-ink);
  font-size: 25px;
  font-weight: 700;
}
.footer-brand span {
  margin-left: 9px;
  font-family: var(--ph-font);
  font-style: normal;
  font-size: 11px;
  font-weight: 500;
}
.site-footer p {
  margin: 0;
}
.footer-link {
  margin-left: auto;
  padding: 10px 0;
}
.version-note {
  font-size: 10px;
}
@media (max-width: 760px) {
  .site-footer {
    width: calc(100% - 40px);
    gap: 16px;
    flex-wrap: wrap;
    margin-top: 44px;
  }
  .site-footer p {
    order: 3;
    flex-basis: 100%;
  }
  .version-note {
    display: none;
  }
}
</style>

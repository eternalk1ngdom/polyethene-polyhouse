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
      <RouterLink class="footer-brand" to="/">聚妙屋 <span>Polyhouse</span></RouterLink>
      <p>为日常的小事，留一个顺手的地方。</p>
      <span class="version-note">用心构建中</span>
    </footer>
  </div>
</template>

<style scoped>
/* 全站基础由外壳统一设置，后续工具页继承相同的颜色、间距和键盘焦点。 */
:global(:root) {
  font-family: 'Inter', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: #23364d;
  background: var(--ph-surface);
  font-synthesis: none;
  text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased;
  --ph-ink: #23364d;
  --ph-muted: #5f7084;
  --ph-line: #dce6ef;
  --ph-brand: #356fa8;
  --ph-green: var(--ph-brand);
  --ph-surface: #f6f9fd;
  --ph-soft: #eaf3fc;
  --el-color-primary: #356fa8;
  --el-color-primary-light-3: #729cc2;
  --el-color-primary-light-5: #a1bfd9;
  --el-color-primary-light-7: #c9deef;
  --el-color-primary-light-9: #edf5fc;
  --el-color-primary-dark-2: #285982;
  --el-border-radius-base: 10px;
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
:global(input) {
  font: inherit;
}
:global(a:focus-visible),
:global(button:focus-visible),
:global([tabindex]:focus-visible) {
  outline: 3px solid #356fa8;
  outline-offset: 5px;
}
:global(::selection) {
  background: #d9ebf9;
  color: #23364d;
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
  z-index: 100;
  top: 12px;
  left: 20px;
  padding: 12px 20px;
  background: white;
  border: 1px solid var(--ph-green);
  border-radius: 8px;
  transform: translateY(-150%);
}
.skip-link:focus {
  transform: translateY(0);
}
.site-footer {
  width: min(1200px, calc(100% - 96px));
  margin: 64px auto 0;
  padding: 28px 0;
  border-top: 1px solid var(--ph-line);
  display: flex;
  align-items: center;
  gap: 28px;
  color: var(--ph-muted);
  font-size: 12px;
}
.footer-brand {
  color: var(--ph-ink);
  font-size: 15px;
  font-weight: 700;
}
.footer-brand span {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 500;
}
.site-footer p {
  margin: 0;
}
.version-note {
  margin-left: auto;
}
@media (max-width: 640px) {
  .site-footer {
    width: calc(100% - 40px);
    margin-top: 40px;
    gap: 14px;
    flex-wrap: wrap;
  }
  .site-footer p {
    order: 3;
    flex-basis: 100%;
  }
}
</style>

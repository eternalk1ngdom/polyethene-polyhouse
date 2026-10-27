<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ref } from 'vue'
import { ElIcon, ElPopover } from 'element-plus'
import { ArrowDown, User } from '@element-plus/icons-vue'
import { toolRegistry } from '../config/toolRegistry'

// 显式管理展开状态，让键盘 Escape 与鼠标点击外部都能关闭账号说明。
const accountVisible = ref(false)
</script>

<template>
  <header class="site-header" @keydown.esc="accountVisible = false">
    <div class="header-inner">
      <RouterLink to="/" class="brand" aria-label="聚妙屋首页">
        <span class="brand-mark" aria-hidden="true"><span /></span>
        <span class="brand-name">聚妙屋<span>Polyhouse</span></span>
      </RouterLink>
      <nav class="primary-nav" aria-label="主导航">
        <RouterLink to="/" exact-active-class="is-active">首页</RouterLink>
        <RouterLink
          v-for="tool in toolRegistry"
          :key="tool.code"
          :to="tool.route"
          active-class="is-active"
        >
          {{ tool.title }}<span class="nav-description">{{ tool.tag }}</span>
        </RouterLink>
      </nav>
      <!-- 只说明账号入口的开发状态，不把本地缓存误认为已验证身份。 -->
      <ElPopover
        v-model:visible="accountVisible"
        trigger="click"
        :width="272"
        placement="bottom-end"
        :teleported="false"
      >
        <template #reference>
          <button class="account-trigger" type="button" aria-label="账号功能说明">
            <span class="account-avatar"
              ><ElIcon><User /></ElIcon
            ></span>
            <span class="account-label">个人空间</span>
            <ElIcon class="account-chevron"><ArrowDown /></ElIcon>
          </button>
        </template>
        <div class="account-preview">
          <span class="preview-eyebrow">你的个人空间</span>
          <h2>在这里，积攒一点成长。</h2>
          <p>登录、个人资料与等级硬币将在接下来的版本接入。</p>
          <span class="preview-status">账号功能准备中</span>
        </div>
      </ElPopover>
    </div>
  </header>
</template>

<style scoped>
.site-header {
  border-bottom: 1px solid var(--ph-line);
  background: #fafbf8;
}
.header-inner {
  width: min(1200px, calc(100% - 96px));
  min-height: 88px;
  margin: auto;
  display: flex;
  align-items: center;
  gap: 60px;
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  flex-shrink: 0;
}
.brand-mark {
  width: 36px;
  height: 38px;
  position: relative;
  display: grid;
  place-items: center;
}
.brand-mark::before {
  content: '';
  width: 25px;
  height: 25px;
  position: absolute;
  top: 6px;
  border-radius: 5px;
  background: #325e4d;
  transform: rotate(45deg);
}
.brand-mark span {
  width: 31px;
  height: 25px;
  position: absolute;
  bottom: 1px;
  background: #325e4d;
  border-radius: 4px;
}
.brand-mark span::after {
  content: '';
  position: absolute;
  width: 9px;
  height: 13px;
  bottom: 0;
  left: 11px;
  background: #fafbf8;
  border-radius: 5px 5px 0 0;
}
.brand-name {
  font-size: 20px;
  font-weight: 750;
  letter-spacing: 1px;
}
.brand-name > span {
  display: block;
  font-size: 10px;
  line-height: 1.4;
  letter-spacing: 1.8px;
  font-weight: 500;
  color: var(--ph-muted);
}
.primary-nav {
  display: flex;
  align-items: center;
  gap: 34px;
}
.primary-nav a {
  padding: 33px 0;
  font-size: 14px;
  color: #69716d;
  position: relative;
  white-space: nowrap;
}
.primary-nav a:hover,
.primary-nav a.is-active {
  color: var(--ph-green);
}
.primary-nav a.is-active::after {
  content: '';
  height: 3px;
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--ph-green);
  border-radius: 3px 3px 0 0;
}
.nav-description {
  margin-left: 7px;
  font-size: 11px;
  color: #7a827d;
}
.account-trigger {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-left: auto;
  cursor: pointer;
  border: 0;
  background: transparent;
  padding: 8px 0 8px 8px;
  color: var(--ph-ink);
}
.account-avatar {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  background: #e7ece4;
  border: 1px solid #dbe2d7;
  border-radius: 50%;
  font-size: 18px;
}
.account-label {
  font-size: 13px;
}
.account-chevron {
  color: var(--ph-muted);
  font-size: 11px;
}
.account-preview {
  padding: 12px 8px;
}
.preview-eyebrow {
  color: #69716d;
  font-size: 12px;
}
.account-preview h2 {
  font-size: 17px;
  margin: 12px 0 10px;
  color: var(--ph-ink);
}
.account-preview p {
  color: #69716d;
  line-height: 1.8;
  font-size: 13px;
  margin-bottom: 20px;
}
.preview-status {
  display: inline-block;
  background: #edf3ee;
  border-radius: 6px;
  padding: 7px 10px;
  color: #325e4d;
  font-size: 12px;
}
@media (max-width: 960px) {
  .header-inner {
    gap: 36px;
  }
  .primary-nav {
    gap: 24px;
  }
  .nav-description {
    display: none;
  }
}
@media (max-width: 640px) {
  .header-inner {
    width: calc(100% - 40px);
    min-height: 76px;
    gap: 22px;
    flex-wrap: wrap;
    padding-top: 16px;
  }
  .brand-name {
    font-size: 18px;
  }
  .primary-nav {
    order: 3;
    flex-basis: 100%;
    gap: 30px;
  }
  .primary-nav a {
    padding: 12px 0 15px;
  }
  .account-label {
    font-size: 12px;
  }
  .account-trigger {
    padding-left: 0;
  }
}
</style>

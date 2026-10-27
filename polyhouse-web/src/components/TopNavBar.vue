<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ref } from 'vue'
import { ElButton, ElIcon, ElPopover } from 'element-plus'
import { ArrowDown, User } from '@element-plus/icons-vue'
import { toolRegistry } from '../config/toolRegistry'
import AuthDialog from './AuthDialog.vue'
import { ApiError } from '../api'
import { useUserStore } from '../stores/useUserStore'

// 显式管理展开状态，让键盘 Escape 与鼠标点击外部都能关闭账号说明。
const accountVisible = ref(false)
const authVisible = ref(false)
const signingOut = ref(false)
const accountError = ref('')
const userStore = useUserStore()

async function logout(): Promise<void> {
  if (signingOut.value) return
  signingOut.value = true
  accountError.value = ''
  try {
    await userStore.logout()
    accountVisible.value = false
  } catch (error: unknown) {
    accountError.value = error instanceof ApiError ? error.message : '暂时无法退出，请稍后重试'
  } finally {
    signingOut.value = false
  }
}

function openLogin(): void {
  accountError.value = ''
  authVisible.value = true
}
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
      <button
        v-if="!userStore.isLoggedIn"
        class="account-trigger login-trigger"
        type="button"
        :disabled="userStore.status === 'checking'"
        :aria-busy="userStore.status === 'checking'"
        @click="openLogin"
      >
        <span class="account-avatar"
          ><ElIcon><User /></ElIcon
        ></span>
        <span class="account-label">{{
          userStore.status === 'checking' ? '正在恢复' : '登录 / 注册'
        }}</span>
      </button>
      <!-- 只有 /me 或登录响应确认的资料才显示为已登录，缓存用户资料不会进入此分支。 -->
      <ElPopover
        v-else
        v-model:visible="accountVisible"
        trigger="click"
        :width="272"
        placement="bottom-end"
        :teleported="false"
      >
        <template #reference>
          <button class="account-trigger" type="button" aria-label="打开账号菜单">
            <span class="account-avatar"
              ><ElIcon><User /></ElIcon
            ></span>
            <span class="account-label">{{
              userStore.user?.nickname || userStore.user?.username
            }}</span>
            <ElIcon class="account-chevron"><ArrowDown /></ElIcon>
          </button>
        </template>
        <div class="account-preview">
          <span class="preview-eyebrow"
            >欢迎回到小屋 <span class="verified-label">已登录</span></span
          >
          <h2>{{ userStore.user?.nickname || userStore.user?.username }}</h2>
          <p class="account-username">账号：{{ userStore.user?.username }}</p>
          <p v-if="accountError" class="account-error" role="alert">{{ accountError }}</p>
          <ElButton class="logout-button" :loading="signingOut" @click="logout">退出登录</ElButton>
        </div>
      </ElPopover>
    </div>
    <div
      v-if="userStore.sessionMessage || userStore.storageMessage"
      class="session-notice"
      role="status"
    >
      <span>{{ userStore.sessionMessage || userStore.storageMessage }}</span>
      <button
        v-if="userStore.status === 'unavailable'"
        type="button"
        @click="userStore.restoreSession()"
      >
        重试连接
      </button>
      <button
        v-if="!userStore.isLoggedIn && userStore.status !== 'checking'"
        type="button"
        @click="openLogin"
      >
        重新登录
      </button>
    </div>
    <AuthDialog v-model="authVisible" />
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
.account-trigger:disabled {
  cursor: wait;
  opacity: 0.65;
}
.account-label {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.login-trigger {
  padding: 5px 12px 5px 5px;
  background: #edf2e8;
  border: 1px solid #e0e7d8;
  border-radius: 28px;
}
.login-trigger:hover:not(:disabled) {
  background: #e3ebdc;
}
.verified-label {
  margin-left: 8px;
  padding: 3px 6px;
  border-radius: 4px;
  background: #edf3ee;
  color: #325e4d;
  font-size: 10px;
}
.account-username {
  overflow-wrap: anywhere;
}
.account-preview h2 {
  overflow-wrap: anywhere;
}
.account-preview .account-error {
  color: #a74738;
}
.logout-button {
  width: 100%;
}
.session-notice {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  border-top: 1px solid #e5e8da;
  background: #f2f5eb;
  padding: 11px 24px;
  color: #59664c;
  font-size: 12px;
  line-height: 1.8;
}
.session-notice button {
  flex-shrink: 0;
  background: none;
  border: 0;
  border-bottom: 1px solid currentColor;
  padding: 0;
  color: var(--ph-green);
  cursor: pointer;
  font-size: 12px;
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
    max-width: 110px;
  }
  .account-trigger {
    padding-left: 0;
  }
  .login-trigger {
    padding-left: 4px;
    padding-right: 10px;
  }
  .session-notice {
    justify-content: flex-start;
    gap: 10px;
    flex-wrap: wrap;
    padding: 10px 20px;
  }
}
</style>

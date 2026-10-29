<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'
import { ref, watch } from 'vue'
import { ElIcon, ElPopover } from 'element-plus'
import { ArrowDown, User } from '@element-plus/icons-vue'
import BrandLogo from './BrandLogo.vue'
import { toolRegistry } from '../config/toolRegistry'
import AuthDialog from './AuthDialog.vue'
import DeregisterDialog from './DeregisterDialog.vue'
import PasswordChangeDialog from './PasswordChangeDialog.vue'
import UserAvatar from './UserAvatar.vue'
import UserPopoverCard from './UserPopoverCard.vue'
import { useAccountUiStore } from '../stores/useAccountUiStore'
import { useUserStore } from '../stores/useUserStore'
import { userDisplayName } from '../utils/userPresentation'

// 点击与键盘都能打开；切页或会话变化时收起，避免旧资料留在小窗中。
const accountVisible = ref(false)
const userStore = useUserStore()
const accountUi = useAccountUiStore()
const route = useRoute()

async function logout(): Promise<void> {
  if (await accountUi.logout()) accountVisible.value = false
}

watch(
  () => route.fullPath,
  () => {
    accountVisible.value = false
  },
)
watch(
  () => userStore.isLoggedIn,
  (loggedIn) => {
    if (!loggedIn) accountVisible.value = false
  },
)
</script>

<template>
  <header class="site-header" @keydown.esc="accountVisible = false">
    <div class="header-inner">
      <RouterLink to="/" class="brand" aria-label="聚妙屋首页">
        <BrandLogo />
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
        @click="accountUi.openLogin"
      >
        <span class="account-avatar"
          ><ElIcon><User /></ElIcon
        ></span>
        <span class="account-label">{{
          userStore.status === 'checking' ? '正在恢复' : '登录'
        }}</span>
      </button>
      <!-- 只有 /me 或登录响应确认的资料才显示为已登录，缓存用户资料不会进入此分支。 -->
      <ElPopover
        v-else
        v-model:visible="accountVisible"
        trigger="click"
        width="min(288px, calc(100vw - 48px))"
        placement="bottom-end"
        :teleported="false"
      >
        <template #reference>
          <button
            class="account-trigger"
            type="button"
            aria-label="打开账号菜单"
            :aria-expanded="accountVisible"
          >
            <UserAvatar
              v-if="userStore.user"
              :name="userDisplayName(userStore.user)"
              :src="userStore.user.avatar"
              :size="36"
            />
            <span class="account-label">{{
              userStore.user?.nickname || userStore.user?.username
            }}</span>
            <ElIcon class="account-chevron"><ArrowDown /></ElIcon>
          </button>
        </template>
        <UserPopoverCard
          v-if="userStore.user"
          :profile="userStore.user"
          :busy="!!accountUi.pending"
          :error="accountUi.actionError"
          @navigate="accountVisible = false"
          @logout="logout"
        />
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
        @click="accountUi.openLogin"
      >
        重新登录
      </button>
    </div>
    <div v-if="accountUi.notice" class="account-notice" role="status">
      <span>{{ accountUi.notice }}</span
      ><button type="button" aria-label="收起账号操作提示" @click="accountUi.dismissNotice">
        收起
      </button>
    </div>
    <AuthDialog v-model="accountUi.authVisible" />
    <DeregisterDialog />
    <PasswordChangeDialog v-model="accountUi.passwordVisible" />
  </header>
</template>

<style scoped>
.site-header {
  background: var(--ph-surface);
}
.header-inner {
  width: min(1440px, calc(100% - 80px));
  min-height: 96px;
  margin: auto;
  display: flex;
  align-items: center;
  gap: 42px;
}
.brand {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  gap: 9px;
  flex-shrink: 0;
}
.primary-nav {
  display: flex;
  align-items: center;
  gap: 6px;
}
.primary-nav a {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  padding: 12px 15px;
  color: var(--ph-ink);
  font-size: 14px;
  font-weight: 550;
  white-space: nowrap;
  border-radius: 99px;
  transition: background 0.2s;
}
.primary-nav a:hover,
.primary-nav a.is-active {
  background: var(--ph-soft);
}
.nav-description {
  margin-left: 7px;
  color: var(--ph-muted);
  font-size: 12px;
  font-weight: 400;
}
.account-trigger {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-left: auto;
  min-height: 46px;
  cursor: pointer;
  border: 0;
  background: transparent;
  padding: 5px 8px;
  color: var(--ph-ink);
  border-radius: 99px;
}
.account-trigger:disabled {
  cursor: wait;
  opacity: 0.65;
}
.account-label {
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}
.login-trigger {
  background: var(--ph-ink);
  color: white;
  padding: 8px 20px;
}
.login-trigger:hover:not(:disabled) {
  background: #39384b;
}
.account-avatar {
  display: none;
}
.account-chevron {
  font-size: 11px;
  color: var(--ph-muted);
}
.session-notice,
.account-notice {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 12px 24px;
  background: var(--ph-cream);
  color: var(--ph-ink);
  font-size: 12px;
  line-height: 1.8;
}
.account-notice {
  background: var(--ph-pink-soft);
}
.session-notice button,
.account-notice button {
  border: 0;
  background: none;
  color: var(--ph-accent-strong);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 4px;
  flex-shrink: 0;
  min-height: 32px;
}
@media (max-width: 1000px) {
  .header-inner {
    gap: 24px;
  }
  .nav-description {
    display: none;
  }
  .primary-nav a {
    padding: 12px;
  }
}
@media (max-width: 640px) {
  .header-inner {
    width: calc(100% - 40px);
    min-height: 82px;
    gap: 12px;
    flex-wrap: wrap;
    padding-top: 16px;
    padding-bottom: 10px;
  }
  .primary-nav {
    order: 3;
    flex-basis: 100%;
    justify-content: center;
    gap: 8px;
  }
  .primary-nav a {
    flex: 1;
    justify-content: center;
    padding: 10px;
    font-size: 13px;
  }
  .account-label {
    max-width: 95px;
    font-size: 12px;
  }
  .login-trigger {
    padding: 10px 16px;
  }
  .session-notice,
  .account-notice {
    justify-content: flex-start;
    flex-wrap: wrap;
    gap: 8px;
    padding: 10px 20px;
  }
}
</style>

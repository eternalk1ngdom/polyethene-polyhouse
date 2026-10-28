<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'
import { ref, watch } from 'vue'
import { ElIcon, ElPopover } from 'element-plus'
import { ArrowDown, User } from '@element-plus/icons-vue'
import { toolRegistry } from '../config/toolRegistry'
import AuthDialog from './AuthDialog.vue'
import DeregisterDialog from './DeregisterDialog.vue'
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
        @click="accountUi.openLogin"
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
        width="min(288px, calc(100vw - 48px))"
        placement="bottom-end"
        :teleported="false"
      >
        <template #reference>
          <button class="account-trigger" type="button" aria-label="打开账号菜单">
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
  </header>
</template>

<style scoped>
.site-header {
  border-bottom: 1px solid var(--ph-line);
  background: #fffafd;
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
  background: #a25087;
  transform: rotate(45deg);
}
.brand-mark span {
  width: 31px;
  height: 25px;
  position: absolute;
  bottom: 1px;
  background: #a25087;
  border-radius: 4px;
}
.brand-mark span::after {
  content: '';
  position: absolute;
  width: 9px;
  height: 13px;
  bottom: 0;
  left: 11px;
  background: #fffafd;
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
  color: #80636d;
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
  color: #836670;
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
  background: #fbf1f7;
  border: 1px solid #f0dde4;
  border-radius: 28px;
}
.login-trigger:hover:not(:disabled) {
  background: #f6e3ea;
}
.session-notice {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  border-top: 1px solid #efdee4;
  background: #faf1f4;
  padding: 11px 24px;
  color: #7e5765;
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
.account-notice {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 11px 20px;
  background: #fbf1f7;
  color: #7b3d53;
  border-top: 1px solid #f0dbe3;
  font-size: 12px;
  line-height: 1.8;
}
.account-notice button {
  flex-shrink: 0;
  font-size: 11px;
  border: 0;
  background: none;
  color: #7b3d53;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.account-avatar {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  background: #f6e7ec;
  border: 1px solid #eddae1;
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

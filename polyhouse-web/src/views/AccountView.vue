<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElAlert, ElButton, ElIcon } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  Crop,
  Document,
  Lock,
  Switch,
  Refresh,
  SwitchButton,
  User,
} from '@element-plus/icons-vue'
import UserAvatar from '../components/UserAvatar.vue'
import AvatarUploadDialog from '../components/AvatarUploadDialog.vue'
import UserGrowthPanel from '../components/UserGrowthPanel.vue'
import { toolRegistry } from '../config/toolRegistry'
import { useAccountUiStore } from '../stores/useAccountUiStore'
import { useUserStore } from '../stores/useUserStore'
import { useUserGrowthStore } from '../stores/useUserGrowthStore'
import { formatAccountNumber, userDisplayName, userRoleLabel } from '../utils/userPresentation'

const userStore = useUserStore()
const accountUi = useAccountUiStore()
const growth = useUserGrowthStore()
const stats = computed(() => growth.summary ?? userStore.user)
const refreshing = ref(false)
const avatarVisible = ref(false)
const avatarNotice = ref('')
const toolIcons = { Switch, Crop }

function openAvatar(): void {
  avatarVisible.value = true
  avatarNotice.value = ''
}

async function refreshProfile(): Promise<void> {
  if (refreshing.value || accountUi.pending) return
  refreshing.value = true
  try {
    await userStore.restoreSession()
    if (userStore.isLoggedIn) await growth.refresh()
  } finally {
    refreshing.value = false
  }
}

// 直接访问和从菜单进入都再次核对资料；与启动请求并发时复用已有恢复请求。
onMounted(() => {
  void refreshProfile()
})
</script>

<template>
  <div class="account-page">
    <RouterLink class="back-link" to="/"
      ><ElIcon><ArrowLeft /></ElIcon>返回首页</RouterLink
    >
    <template v-if="userStore.isLoggedIn && userStore.user">
      <section class="profile-hero" aria-labelledby="profile-name">
        <div class="profile-cover">
          <span>MY SPACE / 我的个人空间</span
          ><span class="cover-word" aria-hidden="true">Hello, you.</span
          ><span class="cover-stamp" aria-hidden="true">MAKE ROOM<br />FOR YOURSELF</span>
        </div>
        <div class="profile-overview">
          <button
            class="avatar-edit"
            type="button"
            aria-label="更换头像"
            :disabled="!!accountUi.pending || userStore.requiresPasswordChange"
            @click="openAvatar"
          >
            <UserAvatar
              :name="userDisplayName(userStore.user)"
              :src="userStore.user.avatar"
              :size="92"
            />
            <span class="avatar-edit-label">更换头像</span>
          </button>
          <div class="identity-copy">
            <p class="eyebrow">欢迎回来，今天也有新的可能</p>
            <h1 id="profile-name">
              {{ userDisplayName(userStore.user)
              }}<span class="role-badge">{{ userRoleLabel(userStore.user.role) }}</span>
            </h1>
            <p class="profile-account">@{{ userStore.user.username }}</p>
          </div>
          <ElButton
            class="refresh-button"
            :loading="refreshing"
            :disabled="!!accountUi.pending"
            @click="refreshProfile"
            ><ElIcon><Refresh /></ElIcon>刷新资料</ElButton
          >
        </div>
        <dl class="profile-stats">
          <div>
            <dt>当前等级</dt>
            <dd>Lv.{{ stats?.level }}</dd>
          </div>
          <div>
            <dt>累计经验</dt>
            <dd>{{ formatAccountNumber(stats?.experience ?? userStore.user.experience) }}</dd>
          </div>
          <div>
            <dt>硬币余额</dt>
            <dd>{{ formatAccountNumber(stats?.coins ?? userStore.user.coins) }}</dd>
          </div>
        </dl>
      </section>

      <nav class="profile-nav" aria-label="个人空间分区">
        <a href="#my-growth">我的成长</a><a href="#profile-details-title">账号资料</a
        ><a href="#my-tools-title">常用工具</a><a href="#account-management-title">账号管理</a>
      </nav>
      <UserGrowthPanel v-if="!userStore.requiresPasswordChange" id="my-growth" />
      <ElAlert
        v-if="avatarNotice"
        class="avatar-notice"
        :title="avatarNotice"
        type="success"
        show-icon
        @close="avatarNotice = ''"
      />
      <AvatarUploadDialog
        v-model="avatarVisible"
        @saved="(remaining) => (avatarNotice = `头像已更新，今天还可以修改 ${remaining} 次。`)"
      />

      <div class="account-grid">
        <section class="info-panel" aria-labelledby="profile-details-title">
          <div class="panel-title">
            <span class="panel-icon"
              ><ElIcon><User /></ElIcon
            ></span>
            <div>
              <p class="eyebrow">PROFILE</p>
              <h2 id="profile-details-title">账号资料</h2>
            </div>
          </div>
          <dl class="profile-details">
            <div>
              <dt>昵称</dt>
              <dd>{{ userDisplayName(userStore.user) }}</dd>
            </div>
            <div>
              <dt>登录账号</dt>
              <dd>{{ userStore.user.username }}</dd>
            </div>
            <div>
              <dt>用户编号</dt>
              <dd class="user-id">{{ userStore.user.id }}</dd>
            </div>
            <div>
              <dt>当前身份</dt>
              <dd>{{ userRoleLabel(userStore.user.role) }}</dd>
            </div>
          </dl>
          <p class="panel-note">点击上方头像即可更换图片，其他资料编辑稍后开放。</p>
        </section>
        <section class="info-panel" aria-labelledby="my-tools-title">
          <div class="panel-title">
            <span class="panel-icon apricot"
              ><ElIcon><Document /></ElIcon
            ></span>
            <div>
              <p class="eyebrow">TOOLBOX</p>
              <h2 id="my-tools-title">常用工具</h2>
            </div>
          </div>
          <RouterLink
            v-for="tool in toolRegistry"
            :key="tool.code"
            :to="tool.route"
            class="tool-shortcut"
            ><span class="shortcut-icon"
              ><ElIcon><component :is="toolIcons[tool.icon]" /></ElIcon></span
            ><span class="shortcut-copy"
              ><strong>{{ tool.title }}</strong
              ><small
                >{{ tool.tag }} · {{ tool.status === 'planned' ? '开发中' : '可使用' }}</small
              ></span
            ><ElIcon><ArrowRight /></ElIcon
          ></RouterLink>
          <p class="panel-note">云端词库模板与压缩预设尚未开放。</p>
        </section>
      </div>

      <section class="management-panel" aria-labelledby="account-management-title">
        <div class="panel-title">
          <span class="panel-icon neutral"
            ><ElIcon><Lock /></ElIcon
          ></span>
          <div>
            <p class="eyebrow">ACCOUNT</p>
            <h2 id="account-management-title">账号管理</h2>
          </div>
        </div>
        <ElAlert
          v-if="accountUi.actionError"
          :title="accountUi.actionError"
          type="error"
          :closable="false"
          show-icon
          class="action-error"
        />
        <div class="management-row">
          <div>
            <h3>修改密码</h3>
            <p>验证当前密码后设置新密码，成功后所有终端都需要重新登录。</p>
          </div>
          <ElButton :disabled="!!accountUi.pending" @click="accountUi.passwordVisible = true"
            >修改密码</ElButton
          >
        </div>
        <div class="management-row">
          <div>
            <h3>退出当前登录</h3>
            <p>仅退出当前浏览器终端，其他终端仍可继续使用。</p>
          </div>
          <ElButton
            :loading="accountUi.pending === 'logout'"
            :disabled="!!accountUi.pending"
            @click="accountUi.logout()"
            ><ElIcon><SwitchButton /></ElIcon>退出登录</ElButton
          >
        </div>
        <div class="management-row cancellation-row">
          <div>
            <h3>注销账号</h3>
            <p v-if="userStore.user.role === 'OWNER'">
              屋主账号不能直接注销，需先完成专门的移交或恢复流程。
            </p>
            <p v-else>停用账号并退出所有终端。资料仍会保留，操作前请查看详细说明。</p>
          </div>
          <ElButton
            type="danger"
            plain
            :disabled="!!accountUi.pending || refreshing || !accountUi.canDeregister"
            @click="accountUi.openCancellation()"
            >注销账号</ElButton
          >
        </div>
      </section>
    </template>
    <section v-else class="anonymous-panel" aria-labelledby="account-entry-title">
      <span class="anonymous-icon"
        ><ElIcon><User /></ElIcon
      ></span>
      <p class="eyebrow">YOUR LITTLE SPACE</p>
      <h1 id="account-entry-title">这里是你的个人空间</h1>
      <p>
        {{
          userStore.status === 'checking'
            ? '正在确认登录状态，请稍候。'
            : userStore.status === 'unavailable'
              ? '暂时无法确认身份，连接恢复后即可继续。'
              : '登录后，查看自己的账号资料和成长记录。'
        }}
      </p>
      <ElButton v-if="userStore.status === 'unavailable'" type="primary" @click="refreshProfile"
        >重试连接</ElButton
      ><ElButton
        v-else
        type="primary"
        :loading="userStore.status === 'checking'"
        @click="accountUi.openLogin()"
        >登录 / 注册</ElButton
      ><RouterLink to="/" class="anonymous-home"
        >先回首页看看<ElIcon><ArrowRight /></ElIcon
      ></RouterLink>
    </section>
  </div>
</template>

<style scoped>
.account-page {
  width: min(1160px, calc(100% - 96px));
  margin: 24px auto 0;
}
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ph-muted);
  font-size: 13px;
  min-height: 40px;
  margin-bottom: 20px;
}
.profile-hero {
  border: 1px solid var(--ph-line);
  border-radius: 20px;
  overflow: hidden;
  background: white;
}
.profile-cover {
  height: 180px;
  background: var(--ph-lilac);
  position: relative;
  overflow: hidden;
  padding: 25px 34px;
  color: var(--ph-ink);
  font-size: 10px;
  letter-spacing: 2px;
}
.cover-word {
  position: absolute;
  right: 13%;
  bottom: -20px;
  font-family: Georgia, serif;
  font-style: italic;
  font-weight: 500;
  font-size: clamp(75px, 10vw, 140px);
  letter-spacing: -6px;
  color: #443259;
  transform: rotate(-6deg);
}
.cover-stamp {
  position: absolute;
  right: 30px;
  top: 27px;
  padding: 13px;
  border: 1px solid #89729e;
  font-size: 8px;
  line-height: 1.6;
  transform: rotate(8deg);
  border-radius: 50%;
}
.profile-overview {
  display: flex;
  gap: 24px;
  align-items: center;
  padding: 0 34px;
  margin-top: -36px;
  position: relative;
}
.avatar-edit {
  position: relative;
  align-self: flex-start;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  cursor: pointer;
  flex-shrink: 0;
}
.profile-overview :deep(.user-avatar) {
  border-width: 5px;
  font-size: 34px;
}
.avatar-edit-label {
  position: absolute;
  bottom: 2px;
  left: 7px;
  right: 7px;
  padding: 5px 0;
  background: #0d0c22db;
  color: white;
  font-size: 10px;
  border-radius: 0 0 28px 28px;
}
.avatar-edit:disabled {
  opacity: 0.6;
  cursor: wait;
}
.identity-copy {
  min-width: 0;
  padding-top: 48px;
}
.eyebrow {
  font-size: 10px;
  letter-spacing: 1px;
  color: var(--ph-muted);
  margin: 0 0 8px;
}
h1 {
  font-size: 30px;
  line-height: 1.5;
  overflow-wrap: anywhere;
  margin: 0;
}
.role-badge {
  display: inline-block;
  margin-left: 14px;
  font-size: 11px;
  font-weight: 500;
  padding: 4px 10px;
  border-radius: 99px;
  color: var(--ph-ink);
  background: var(--ph-cream);
  vertical-align: middle;
}
.profile-account {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--ph-muted);
  overflow-wrap: anywhere;
}
.refresh-button {
  margin-left: auto;
  margin-top: 40px;
  flex-shrink: 0;
}
.refresh-button .el-icon,
.management-row .el-icon {
  margin-right: 7px;
}
.profile-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 28px;
  padding: 28px 34px 30px 150px;
  margin: 0;
}
.profile-stats > div {
  min-width: 0;
  display: flex;
  flex-direction: column-reverse;
  gap: 8px;
}
.profile-stats dt {
  color: var(--ph-muted);
  font-size: 12px;
}
.profile-stats dd {
  margin: 0;
  font-size: 28px;
  font-weight: 650;
  overflow-wrap: anywhere;
}
.profile-nav {
  display: flex;
  gap: 24px;
  margin-top: 24px;
  padding: 0 6px;
  border-bottom: 1px solid var(--ph-line);
  overflow-x: auto;
}
.profile-nav a {
  display: block;
  padding: 17px 4px;
  font-size: 14px;
  white-space: nowrap;
}
.profile-nav a:hover {
  color: var(--ph-accent-strong);
}
.account-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
  margin-top: 24px;
}
.info-panel,
.management-panel {
  min-width: 0;
  padding: 30px;
  border: 1px solid var(--ph-line);
  border-radius: 18px;
  background: white;
}
.panel-title {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 26px;
}
.panel-title .eyebrow {
  font-size: 9px;
  margin-bottom: 5px;
}
.panel-title h2 {
  font-size: 20px;
  margin: 0;
  font-weight: 600;
}
.panel-icon {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  font-size: 21px;
  background: var(--ph-pink-soft);
  color: var(--ph-ink);
}
.panel-icon.apricot {
  background: var(--ph-lilac);
}
.panel-icon.neutral {
  background: var(--ph-soft);
}
.profile-details {
  display: grid;
  gap: 20px;
  margin: 0;
}
.profile-details > div {
  display: grid;
  grid-template-columns: 80px minmax(0, 1fr);
  gap: 12px;
  font-size: 14px;
  line-height: 1.6;
}
.profile-details dt {
  color: var(--ph-muted);
}
.profile-details dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.user-id {
  font-variant-numeric: tabular-nums;
}
.panel-note {
  color: var(--ph-muted);
  font-size: 12px;
  line-height: 1.8;
  margin: 24px 0 0;
}
.tool-shortcut {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 0;
  border-bottom: 1px solid var(--ph-line);
}
.tool-shortcut:hover {
  color: var(--ph-accent-strong);
}
.shortcut-icon {
  font-size: 21px;
}
.shortcut-copy {
  flex: 1;
}
.shortcut-copy strong {
  display: block;
  font-size: 14px;
  font-weight: 500;
}
.shortcut-copy small {
  display: block;
  margin-top: 5px;
  color: var(--ph-muted);
  font-size: 12px;
}
.management-panel {
  margin-top: 24px;
}
.management-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  padding: 20px 0 4px;
}
.management-row h3 {
  font-size: 15px;
  font-weight: 550;
  margin: 0 0 8px;
}
.management-row p {
  font-size: 13px;
  color: var(--ph-muted);
  line-height: 1.8;
  margin: 0;
}
.management-row .el-button {
  flex-shrink: 0;
}
.cancellation-row {
  margin-top: 22px;
  padding-top: 24px;
  border-top: 1px solid var(--ph-line);
}
.cancellation-row h3 {
  color: #b42335;
}
.action-error {
  margin-bottom: 12px;
}
.avatar-notice {
  margin-top: 18px;
}
.anonymous-panel {
  text-align: center;
  padding: 70px 24px;
  border-radius: 22px;
  background: var(--ph-cream);
}
.anonymous-icon {
  width: 70px;
  height: 70px;
  display: grid;
  place-items: center;
  background: var(--ph-lilac);
  color: var(--ph-ink);
  font-size: 32px;
  border-radius: 50%;
  margin: 0 auto 24px;
}
.anonymous-panel > p:not(.eyebrow) {
  color: var(--ph-muted);
  line-height: 1.9;
  font-size: 14px;
  margin: 18px 0 28px;
}
.anonymous-home {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  margin-left: 24px;
  font-size: 13px;
}
@media (max-width: 760px) {
  .account-page {
    width: calc(100% - 40px);
    margin-top: 14px;
  }
  .account-grid {
    grid-template-columns: 1fr;
  }
  .profile-cover {
    height: 145px;
    padding: 20px;
  }
  .cover-stamp {
    right: 14px;
    font-size: 7px;
  }
  .cover-word {
    right: 12%;
    font-size: 90px;
    bottom: -8px;
  }
  .profile-overview {
    /* 固定头像列和资料列，刷新按钮独占下一行，避免中间宽度把昵称挤成竖排。 */
    display: grid;
    grid-template-columns: 92px minmax(0, 1fr);
    padding: 0 20px;
    gap: 14px;
  }
  .identity-copy {
    padding-top: 45px;
  }
  h1 {
    font-size: 23px;
  }
  .role-badge {
    display: table;
    margin: 7px 0 0;
  }
  .refresh-button {
    grid-column: 2;
    justify-self: start;
    margin: 0;
  }
  .profile-stats {
    padding: 24px 20px;
    gap: 16px;
  }
  .profile-stats dd {
    font-size: 22px;
  }
  .profile-nav {
    gap: 18px;
  }
  .profile-nav a {
    font-size: 13px;
  }
  .info-panel,
  .management-panel {
    padding: 24px 20px;
  }
  .management-row {
    flex-wrap: wrap;
    gap: 16px;
  }
  .management-row .el-button {
    width: 100%;
  }
  .anonymous-home {
    display: flex;
    justify-content: center;
    margin: 22px 0 0;
  }
}
</style>

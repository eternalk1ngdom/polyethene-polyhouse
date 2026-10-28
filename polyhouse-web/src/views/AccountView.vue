<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElAlert, ElButton, ElIcon } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  Document,
  Lock,
  Picture,
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
const toolIcons = { Document, Picture }

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
          <span>YOUR LITTLE CORNER / 个人空间</span
          ><span class="cover-star" aria-hidden="true">✳</span>
        </div>
        <div class="profile-overview">
          <button
            class="avatar-edit"
            type="button"
            aria-label="更换头像"
            :disabled="!!accountUi.pending"
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
            <p class="eyebrow">很高兴，在小屋见到你</p>
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

      <UserGrowthPanel />
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
  width: min(1120px, calc(100% - 96px));
  margin: 32px auto 0;
}
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ph-muted);
  padding: 8px 0;
  margin-bottom: 22px;
}
.profile-hero {
  border: 1px solid var(--ph-line);
  border-radius: 18px;
  background: #ffffff;
  overflow: hidden;
}
.profile-cover {
  height: 134px;
  background: #f8e6ed;
  position: relative;
  overflow: hidden;
  padding: 24px 34px;
  color: #8f6775;
  font-size: 9px;
  letter-spacing: 2px;
}
.profile-cover::before,
.profile-cover::after {
  content: '';
  position: absolute;
  width: 350px;
  height: 350px;
  border-radius: 50%;
  border: 1px solid #e7cbd5;
  right: 60px;
  top: -220px;
}
.profile-cover::after {
  right: -90px;
  top: -120px;
}
.cover-star {
  position: absolute;
  right: 180px;
  top: 26px;
  font-size: 75px;
  color: #c49fac;
}
.profile-overview {
  display: flex;
  gap: 24px;
  align-items: center;
  padding: 0 34px;
  margin-top: -32px;
  position: relative;
}
.profile-overview :deep(.user-avatar) {
  align-self: flex-start;
  border-width: 5px;
  font-size: 34px;
}
.avatar-edit {
  align-self: flex-start;
  flex-shrink: 0;
  position: relative;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  border-radius: 50%;
}
.avatar-edit > .avatar-edit-label {
  position: absolute;
  bottom: 4px;
  left: 7px;
  right: 7px;
  padding: 4px 0;
  background: #956073d9;
  color: white;
  border-radius: 0 0 28px 28px;
  font-size: 10px;
}
.avatar-edit:disabled {
  opacity: 0.6;
  cursor: wait;
}
.avatar-notice {
  margin-top: 18px;
}
.identity-copy {
  min-width: 0;
  padding-top: 48px;
}
.eyebrow {
  margin: 0 0 10px;
  font-size: 9px;
  color: #8e6a77;
  letter-spacing: 1px;
}
h1 {
  font-size: 29px;
  margin: 0;
  overflow-wrap: anywhere;
  line-height: 1.5;
}
.role-badge {
  display: inline-block;
  margin-left: 14px;
  vertical-align: middle;
  background: #fdf7f9;
  color: #80485c;
  padding: 4px 9px;
  font-size: 10px;
  font-weight: 500;
  border-radius: 5px;
}
.profile-account {
  font-size: 12px;
  color: var(--ph-muted);
  margin: 7px 0 0;
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
  padding: 26px 34px 30px 150px;
  gap: 28px;
  margin: 0;
}
.profile-stats > div {
  min-width: 0;
  border-top: 1px solid var(--ph-line);
  padding-top: 18px;
  display: flex;
  flex-direction: column-reverse;
  gap: 7px;
}
.profile-stats dt {
  color: var(--ph-muted);
  font-size: 11px;
}
.profile-stats dd {
  margin: 0;
  font-size: 25px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.account-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  margin-top: 24px;
}
.info-panel,
.management-panel {
  min-width: 0;
  padding: 30px;
  border: 1px solid var(--ph-line);
  border-radius: 15px;
  background: white;
}
.panel-title {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 26px;
}
.panel-title .eyebrow {
  font-size: 8px;
  margin-bottom: 6px;
}
.panel-title h2 {
  margin: 0;
  font-size: 19px;
  font-weight: 600;
}
.panel-icon {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 11px;
  background: #fdf7f9;
  color: #93596e;
  font-size: 21px;
}
.apricot {
  background: #f9ecdf;
  color: #9e7b50;
}
.neutral {
  background: #f7f2f4;
  color: #886974;
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
  font-size: 13px;
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
  font-size: 11px;
  line-height: 1.8;
  margin: 24px 0 0;
}
.tool-shortcut {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 0;
  border-bottom: 1px solid #f4ebee;
}
.tool-shortcut:hover {
  color: var(--ph-green);
}
.shortcut-icon {
  color: #a27e8b;
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
  font-size: 11px;
}
.tool-shortcut > .el-icon {
  color: #a27e8b;
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
  font-size: 14px;
  margin: 0 0 8px;
  font-weight: 500;
}
.management-row p {
  margin: 0;
  font-size: 12px;
  color: var(--ph-muted);
  line-height: 1.8;
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
  color: #9d5b47;
}
.action-error {
  margin-bottom: 12px;
}
.anonymous-panel {
  text-align: center;
  padding: 68px 24px;
  border: 1px solid var(--ph-line);
  border-radius: 18px;
  background: #ffffff;
}
.anonymous-icon {
  width: 64px;
  height: 64px;
  margin: 0 auto 24px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #f8e8ee;
  color: #915e70;
  font-size: 30px;
}
.anonymous-panel > p:not(.eyebrow) {
  font-size: 13px;
  color: var(--ph-muted);
  line-height: 1.9;
  margin: 18px 0 28px;
}
.anonymous-home {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin-left: 24px;
  color: var(--ph-green);
}
@media (max-width: 760px) {
  .account-grid {
    grid-template-columns: 1fr;
  }
  .profile-overview {
    gap: 18px;
    flex-wrap: wrap;
  }
  .identity-copy {
    padding-top: 48px;
    flex: 1;
  }
  .refresh-button {
    margin: 0 0 0 110px;
  }
  .profile-stats {
    padding-left: 34px;
  }
}
@media (max-width: 640px) {
  .account-page {
    width: calc(100% - 40px);
    margin-top: 22px;
  }
  .profile-cover {
    padding: 20px;
    height: 115px;
    font-size: 8px;
  }
  .cover-star {
    right: 40px;
  }
  .profile-overview {
    padding: 0 20px;
    gap: 14px;
  }
  .profile-overview :deep(.user-avatar) {
    width: 70px !important;
    height: 70px !important;
    font-size: 27px;
  }
  .identity-copy {
    padding-top: 40px;
    flex-basis: calc(100% - 84px);
  }
  h1 {
    font-size: 22px;
  }
  .role-badge {
    margin: 7px 0 0;
    display: table;
  }
  .refresh-button {
    margin: 0 0 0 84px;
  }
  .profile-stats {
    gap: 16px;
    padding: 24px 20px;
  }
  .profile-stats dd {
    font-size: 20px;
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
  .anonymous-panel {
    padding: 45px 20px;
  }
  .anonymous-home {
    display: flex;
    justify-content: center;
    margin: 22px 0 0;
  }
}
</style>

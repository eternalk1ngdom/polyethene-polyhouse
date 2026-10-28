<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { computed } from 'vue'
import { ElButton, ElIcon } from 'element-plus'
import { ArrowRight, SwitchButton, User } from '@element-plus/icons-vue'
import type { UserProfile } from '../api/auth'
import { formatAccountNumber, userDisplayName, userRoleLabel } from '../utils/userPresentation'
import UserAvatar from './UserAvatar.vue'
import UserGrowthPanel from './UserGrowthPanel.vue'
import { useUserGrowthStore } from '../stores/useUserGrowthStore'

/** 卡片只展示已验证资料，账号请求由外部共享操作层负责。 */
interface Props {
  /** 同一个资料来源用于菜单和个人中心。 */
  profile: UserProfile
  /** 阻止重复退出及跨入口同时操作。 */
  busy: boolean
  /** 请求失败保留原资料并显示安全错误。 */
  error: string
}
/** 导航时关闭小窗，退出时交给共享账号操作。 */
interface Emits {
  (event: 'navigate'): void
  (event: 'logout'): void
}
const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const growth = useUserGrowthStore()
const stats = computed(() => growth.summary ?? props.profile)
</script>

<template>
  <section class="user-card" aria-label="个人资料卡">
    <div class="card-cover" aria-hidden="true"><span>MY LITTLE SPACE</span></div>
    <div class="card-identity">
      <UserAvatar :name="userDisplayName(profile)" :src="profile.avatar" :size="64" />
      <h2>{{ userDisplayName(profile) }}</h2>
      <span class="role-badge">{{ userRoleLabel(profile.role) }}</span>
      <p class="username">@{{ profile.username }}</p>
    </div>
    <dl class="card-stats">
      <div>
        <dt>等级</dt>
        <dd>Lv.{{ stats.level }}</dd>
      </div>
      <div>
        <dt>累计经验</dt>
        <dd>{{ formatAccountNumber(stats.experience) }}</dd>
      </div>
      <div>
        <dt>硬币</dt>
        <dd>{{ formatAccountNumber(stats.coins) }}</dd>
      </div>
    </dl>
    <UserGrowthPanel compact />
    <RouterLink class="profile-link" to="/account" @click="emit('navigate')"
      ><span
        ><ElIcon><User /></ElIcon>个人中心</span
      ><ElIcon><ArrowRight /></ElIcon
    ></RouterLink>
    <p v-if="error" class="action-error" role="alert">{{ error }}</p>
    <ElButton class="logout-button" text :loading="busy" @click="emit('logout')"
      ><ElIcon><SwitchButton /></ElIcon>退出登录</ElButton
    >
  </section>
</template>

<style scoped>
.user-card {
  color: var(--ph-ink);
  max-height: calc(100dvh - 130px);
  overflow-y: auto;
  overflow-x: hidden;
}
.card-cover {
  height: 64px;
  background: #fbf1f7;
  border-radius: 8px 8px 0 0;
  overflow: hidden;
  position: relative;
  padding: 13px;
  text-align: right;
}
.card-cover::after {
  content: '';
  position: absolute;
  width: 100px;
  height: 100px;
  border: 1px solid #e6cad5;
  border-radius: 50%;
  right: -20px;
  top: -46px;
}
.card-cover span {
  font-size: 7px;
  letter-spacing: 1px;
  color: #8d6a77;
}
.card-identity {
  padding: 0 18px;
  text-align: center;
  margin-top: -32px;
  position: relative;
}
.card-identity h2 {
  font-size: 18px;
  margin: 8px 0;
  overflow-wrap: anywhere;
}
.role-badge {
  display: inline-block;
  background: #fbf1f7;
  color: #80485c;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 10px;
}
.username {
  color: var(--ph-muted);
  font-size: 11px;
  margin: 9px 0 18px;
  overflow-wrap: anywhere;
}
.card-stats {
  display: grid;
  grid-template-columns: 0.8fr 1.4fr 1.2fr;
  gap: 7px;
  margin: 0 8px 18px;
  text-align: center;
}
.card-stats > div {
  min-width: 0;
  display: flex;
  flex-direction: column-reverse;
  gap: 6px;
}
dt {
  color: var(--ph-muted);
  font-size: 10px;
}
dd {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.profile-link {
  border-top: 1px solid var(--ph-line);
  border-bottom: 1px solid var(--ph-line);
  margin: 0 10px;
  padding: 15px 4px;
  display: flex;
  justify-content: space-between;
  color: var(--ph-green);
  font-size: 13px;
}
.profile-link > span {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.profile-link:hover {
  background: #fffafd;
}
.action-error {
  font-size: 12px;
  color: #a74738;
  line-height: 1.8;
  padding: 0 14px;
}
.logout-button {
  width: calc(100% - 20px);
  margin: 8px 10px 2px;
  justify-content: flex-start;
  color: var(--ph-muted);
}
.logout-button .el-icon {
  margin-right: 10px;
}
</style>

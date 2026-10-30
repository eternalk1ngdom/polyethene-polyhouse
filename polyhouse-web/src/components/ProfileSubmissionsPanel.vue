<script setup lang="ts">
import { computed } from 'vue'
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput } from 'element-plus'
import PrivateProfileImage from './PrivateProfileImage.vue'
import type { ProfileSubmissionsState } from '../composables/useProfileSubmissions'
import type { ReviewStatus } from '../api/profileModeration'
/** 页面拥有申请生命周期；面板卸载只释放视图和私有预览。 */
interface Props {
  /** 当前本人在个人页内共享的申请状态，不包含公开资料更新权限。 */
  state: ProfileSubmissionsState
}
const props = defineProps<Props>()
const { allowed, status, nickname, loading, saving, error, notice, load, submit } = props.state
const labels: Record<ReviewStatus, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  SUPERSEDED: '已被新申请替代',
  EXPIRED: '已过期',
  INVALIDATED: '已失效',
}
const pendingAvatar = computed(() =>
  status.value?.submissions.find((s) => s.kind === 'AVATAR' && s.status === 'PENDING'),
)
</script>
<template>
  <div v-if="allowed" class="submissions-panel">
    <h3>资料申请</h3>
    <p>昵称与头像审核通过后公开；新申请会替代同类待审申请。待审有效期为七天。</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <ElAlert v-if="notice" :title="notice" type="success" :closable="false" role="status" />
    <p v-if="status" class="quota">
      今日剩余：头像 {{ status.avatarRemainingToday }} 次 · 昵称
      {{ status.nicknameRemainingToday }} 次
    </p>
    <ElForm label-position="top" @submit.prevent="submit"
      ><ElFormItem label="申请新昵称"
        ><ElInput
          v-model="nickname"
          maxlength="32"
          show-word-limit
          :disabled="saving"
          placeholder="通过审核前继续显示原昵称"
      /></ElFormItem>
      <div class="actions">
        <ElButton
          type="primary"
          native-type="submit"
          :loading="saving"
          :disabled="status?.nicknameRemainingToday === 0"
          >提交昵称审核</ElButton
        ><ElButton :loading="loading" :disabled="saving" @click="load">刷新申请状态</ElButton>
      </div></ElForm
    >
    <PrivateProfileImage v-if="pendingAvatar" :submission-id="pendingAvatar.id" />
    <ul v-if="status?.submissions.length" class="history">
      <li v-for="item in status.submissions" :key="item.id">
        <strong>{{ item.kind === 'AVATAR' ? '头像' : '昵称' }} · {{ labels[item.status] }}</strong
        ><span v-if="item.proposedValue">申请内容：{{ item.proposedValue }}</span
        ><small>{{ item.createdAt.replace('T', ' ') }} · 北京时间</small
        ><span v-if="item.reason">说明：{{ item.reason }}</span
        ><span v-else-if="item.status === 'INVALIDATED'">账号安全状态已变化，请重新提交。</span>
      </li>
    </ul>
    <p v-else-if="!loading && !error">暂无申请记录。</p>
  </div>
</template>
<style scoped>
.submissions-panel {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--ph-line);
}
.submissions-panel h3 {
  font-size: 17px;
}
.submissions-panel p,
.quota {
  font-size: 13px;
  line-height: 1.8;
  color: var(--ph-muted);
}
.actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.actions .el-button + .el-button {
  margin-left: 0;
}
.history {
  list-style: none;
  padding: 0;
  margin: 20px 0 0;
}
.history li {
  display: grid;
  gap: 7px;
  padding: 14px 0;
  border-top: 1px solid var(--ph-line);
  font-size: 13px;
  overflow-wrap: anywhere;
}
.history small {
  color: var(--ph-muted);
}
.submissions-panel :deep(.el-alert) {
  margin: 12px 0;
}
</style>

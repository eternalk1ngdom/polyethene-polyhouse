<script setup lang="ts">
import { ElAvatar } from 'element-plus'
import { avatarInitial } from '../utils/userPresentation'

/** 统一使用昵称首字作为本地头像，不向第三方头像站发送账号页面访问。 */
interface Props {
  /** 已验证资料中的展示名称。 */
  name: string
  /** 导航、小窗和个人中心按各自布局指定大小。 */
  size?: number
}
withDefaults(defineProps<Props>(), { size: 48 })
</script>

<template>
  <ElAvatar class="user-avatar" :size="size" aria-hidden="true">{{ avatarInitial(name) }}</ElAvatar>
</template>

<style scoped>
.user-avatar {
  background: #e2eadb;
  color: #325e4d;
  border: 3px solid #fffefb;
  font-weight: 650;
  font-size: calc(var(--el-avatar-size, 48px) * 0.38);
  flex-shrink: 0;
}
</style>

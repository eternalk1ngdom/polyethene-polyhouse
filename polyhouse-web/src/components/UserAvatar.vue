<script setup lang="ts">
import { ElAvatar } from 'element-plus'
import { computed } from 'vue'
import { isAvatarPath } from '../api/auth'
import { avatarInitial } from '../utils/userPresentation'

/** 仅加载本项目已处理的头像；空值、旧第三方默认图和加载失败都显示昵称首字。 */
interface Props {
  /** 已验证资料中的展示名称。 */
  name: string
  /** 导航、小窗和个人中心按各自布局指定大小。 */
  size?: number
  /** 只接收已验证资料的地址，不接受任意外部 URL。 */
  src?: string | null
}
const props = withDefaults(defineProps<Props>(), { size: 48, src: null })
const safeSource = computed(() => (isAvatarPath(props.src) ? props.src : undefined))
</script>

<template>
  <ElAvatar
    :key="safeSource"
    class="user-avatar"
    :src="safeSource"
    :size="size"
    aria-hidden="true"
    >{{ avatarInitial(name) }}</ElAvatar
  >
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

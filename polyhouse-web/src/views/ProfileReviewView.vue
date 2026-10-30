<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
} from 'element-plus'
import PrivateProfileImage from '../components/PrivateProfileImage.vue'
import UserAvatar from '../components/UserAvatar.vue'
import { useProfileReview } from '../composables/useProfileReview'
import { useUserStore } from '../stores/useUserStore'
import { useAccountUiStore } from '../stores/useAccountUiStore'
const store = useUserStore(),
  accountUi = useAccountUiStore()
const {
  allowed,
  kind,
  publicKind,
  unverified,
  page,
  publicPage,
  items,
  published,
  total,
  publicTotal,
  loading,
  busy,
  error,
  notice,
  dialogError,
  reason,
  selection,
  uncertain,
  hasNext,
  publicHasNext,
  load,
  openDecision,
  openPublic,
  close,
  submit,
  next,
} = useProfileReview()
const title = computed(() =>
  selection.value?.type === 'decision'
    ? selection.value.approve
      ? '通过资料审核'
      : '驳回资料申请'
    : selection.value?.action === 'KEEP'
      ? '保留历史资料'
      : '下架公开资料',
)
const selectedImage = computed(() =>
  selection.value?.type === 'decision' && selection.value.submission?.kind === 'AVATAR'
    ? selection.value.submission.id
    : null,
)
const previewReady = ref(false)
watch(
  selectedImage,
  () => {
    previewReady.value = false
  },
  { flush: 'sync' },
)
onMounted(() => {
  void store.restoreSession()
})
</script>
<template>
  <div class="review-page">
    <RouterLink class="back" to="/manage">← 返回账号管理</RouterLink>
    <header>
      <p>PROFILE REVIEW</p>
      <h1>资料审核</h1>
      <span>先确认，再与小屋见面。</span>
    </header>
    <template v-if="allowed">
      <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" /><ElAlert
        v-if="notice"
        :title="notice"
        type="success"
        :closable="false"
        role="status"
      />
      <section aria-labelledby="pending-title">
        <div class="section-head">
          <h2 id="pending-title">待审申请</h2>
          <ElButton :loading="loading" :disabled="busy" @click="load">刷新审核列表</ElButton>
        </div>
        <p>待审内容只有本人和屋主可见。新申请替代旧申请，超过七天或账号安全状态变化后失效。</p>
        <ElSelect v-model="kind" aria-label="申请类型" placeholder="全部资料" class="filter"
          ><ElOption label="全部资料" value="ALL" /><ElOption
            label="头像"
            value="AVATAR" /><ElOption label="昵称" value="NICKNAME"
        /></ElSelect>
        <p v-if="loading" role="status">正在加载…</p>
        <p v-else-if="!items.length">暂无有效待审申请。</p>
        <ul class="review-list">
          <li v-for="item in items" :key="item.id">
            <div class="copy">
              <strong>{{ item.username }} · {{ item.kind === 'AVATAR' ? '头像' : '昵称' }}</strong
              ><span v-if="item.proposedValue">申请内容：{{ item.proposedValue }}</span
              ><small>版本 {{ item.version }} · {{ item.createdAt.replace('T', ' ') }}</small>
            </div>
            <div class="actions">
              <ElButton
                :aria-label="`通过 ${item.username} ${item.kind}`"
                :disabled="busy"
                @click="openDecision(item, true)"
                >预览并通过</ElButton
              ><ElButton
                :aria-label="`驳回 ${item.username} ${item.kind}`"
                :disabled="busy"
                @click="openDecision(item, false)"
                >驳回</ElButton
              >
            </div>
          </li>
        </ul>
        <nav class="pager" aria-label="待审分页">
          <span>共 {{ total }} 项 · 第 {{ page }} 页</span>
          <div>
            <ElButton :disabled="loading || busy || page === 1" @click="next(-1)">上一页</ElButton
            ><ElButton :disabled="loading || busy || !hasNext" @click="next(1)">下一页</ElButton>
          </div>
        </nav>
      </section>
      <section aria-labelledby="published-title">
        <h2 id="published-title">公开资料与历史核对</h2>
        <p>旧内容保留当前展示，尚未自动算作已审。请逐项确认保留或下架；下架只影响所选字段。</p>
        <div class="filters">
          <ElSelect v-model="publicKind" aria-label="公开资料类型"
            ><ElOption label="头像" value="AVATAR" /><ElOption
              label="昵称"
              value="NICKNAME" /></ElSelect
          ><ElCheckbox v-model="unverified">仅看未核对历史</ElCheckbox>
        </div>
        <p v-if="!loading && !published.length">没有符合条件的公开资料。</p>
        <ul class="review-list">
          <li v-for="item in published" :key="`${item.userId}-${item.kind}`">
            <div class="copy">
              <strong>{{ item.username }} · {{ item.verified ? '已核对' : '历史待核对' }}</strong
              ><UserAvatar
                v-if="item.kind === 'AVATAR'"
                :name="item.username"
                :src="item.value"
                :size="60"
              /><span v-else>当前昵称：{{ item.value }}</span
              ><small
                >版本 {{ item.version }} · {{ item.role }} ·
                {{ item.status === 1 ? '正常' : '已封禁' }}</small
              >
            </div>
            <div class="actions">
              <ElButton
                v-if="!item.verified"
                :disabled="busy"
                :aria-label="`保留 ${item.username} ${item.kind}`"
                @click="openPublic(item, 'KEEP')"
                >确认保留</ElButton
              ><ElButton
                v-if="item.value"
                :disabled="busy"
                :aria-label="`下架 ${item.username} ${item.kind}`"
                @click="openPublic(item, 'TAKEDOWN')"
                >下架</ElButton
              >
            </div>
          </li>
        </ul>
        <nav class="pager" aria-label="公开资料分页">
          <span>共 {{ publicTotal }} 项 · 第 {{ publicPage }} 页</span>
          <div>
            <ElButton :disabled="loading || busy || publicPage === 1" @click="next(-1, true)"
              >上一页</ElButton
            ><ElButton :disabled="loading || busy || !publicHasNext" @click="next(1, true)"
              >下一页</ElButton
            >
          </div>
        </nav>
      </section>
    </template>
    <section v-else>
      <h2>需要屋主权限</h2>
      <p>仅完成首次改密的屋主可以审核、保留或下架资料。</p>
      <ElButton v-if="!store.isLoggedIn" @click="accountUi.openLogin()">登录账号</ElButton>
    </section>
    <ElDialog
      :model-value="!!selection"
      :title="title"
      width="min(560px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      @update:model-value="
        (v) => {
          if (!v) close()
        }
      "
    >
      <template v-if="selection"
        ><p>目标账号：{{ selection.submission?.username ?? selection.published?.username }}</p>
        <p>
          资料类型：{{
            (selection.submission?.kind ?? selection.published?.kind) === 'AVATAR' ? '头像' : '昵称'
          }}
        </p>
        <PrivateProfileImage
          v-if="selectedImage"
          :submission-id="selectedImage"
          @ready="previewReady = $event"
        />
        <p v-if="selection.submission?.proposedValue" class="proposed">
          申请内容：{{ selection.submission.proposedValue }}
        </p>
        <p
          v-if="selection.type === 'public' && selection.published?.kind === 'NICKNAME'"
          class="proposed"
        >
          当前内容：{{ selection.published.value }}
        </p>
        <p v-if="selection.action === 'TAKEDOWN'">
          下架后头像恢复默认、昵称恢复系统默认值；同类旧待审将失效。其他字段不变。
        </p>
        <ElAlert
          v-if="dialogError"
          :title="dialogError"
          type="error"
          :closable="false"
          role="alert"
        />
        <ElForm label-position="top" @submit.prevent="submit"
          ><ElFormItem label="处理原因（必填）"
            ><ElInput
              v-model="reason"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              :disabled="busy || uncertain"
              placeholder="只填写必要说明，勿填隐私或凭据"
          /></ElFormItem>
          <div class="actions">
            <ElButton
              :disabled="busy"
              @click="
                () => {
                  close()
                  load()
                }
              "
              >{{ uncertain ? '关闭并刷新核对' : '取消' }}</ElButton
            ><ElButton
              type="primary"
              native-type="submit"
              :disabled="uncertain || (!!selectedImage && !!selection?.approve && !previewReady)"
              :loading="busy"
              >确认{{ title }}</ElButton
            >
          </div></ElForm
        >
      </template>
    </ElDialog>
  </div>
</template>
<style scoped>
.review-page {
  max-width: 1120px;
  margin: 0 auto;
  padding: 40px 24px 80px;
}
.back,
header span,
section > p,
.copy small {
  color: var(--ph-muted);
}
header {
  margin: 28px 0;
}
header > p {
  letter-spacing: 0.16em;
  font-size: 11px;
}
header h1 {
  font-size: 36px;
  margin: 12px 0;
}
section {
  padding: 28px;
  border: 1px solid var(--ph-line);
  border-radius: 20px;
  background: white;
  margin: 24px 0;
}
h2 {
  font-size: 22px;
  margin: 0;
}
section > p {
  font-size: 14px;
  line-height: 1.8;
}
.section-head,
.pager,
.filters {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.filter {
  max-width: 240px;
}
.filters {
  justify-content: flex-start;
  margin: 20px 0;
}
.filters .el-select {
  width: 200px;
}
.review-list {
  list-style: none;
  padding: 0;
  margin: 20px 0;
}
.review-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 20px 0;
  border-bottom: 1px solid var(--ph-line);
}
.copy {
  display: grid;
  gap: 10px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.copy span,
.copy small {
  font-size: 13px;
}
.actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.actions .el-button + .el-button {
  margin-left: 0;
}
.pager {
  font-size: 13px;
}
.proposed {
  overflow-wrap: anywhere;
}
.review-page :deep(.el-alert) {
  margin: 14px 0;
}
@media (max-width: 600px) {
  .review-page {
    padding: 28px 16px 56px;
  }
  section {
    padding: 20px 16px;
  }
  .review-list li {
    align-items: flex-start;
    flex-direction: column;
  }
  .actions {
    width: 100%;
  }
}
</style>

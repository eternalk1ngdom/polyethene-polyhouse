<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElRadio, ElRadioGroup } from 'element-plus'
import { useAccountProvisioning } from '../composables/useAccountProvisioning'
import { useUserStore } from '../stores/useUserStore'
import { useAccountUiStore } from '../stores/useAccountUiStore'

const store = useUserStore()
const accountUi = useAccountUiStore()
const {
  allowed,
  role,
  ownerPassword,
  busy,
  attempted,
  result,
  message,
  error,
  create,
  confirm,
  acknowledge,
  copyCredentials,
} = useAccountProvisioning()
// 深链接也核对服务端资料，不按本地缓存角色直接显示屋主表单。
onMounted(() => {
  void store.restoreSession()
})
</script>

<template>
  <div class="provisioning-page">
    <RouterLink class="back-link" to="/manage">← 返回账号管理</RouterLink>
    <header class="page-heading">
      <p>OWNER WORKSPACE</p>
      <h1>给朋友，留一个位置。</h1>
      <span>受控内测 · 账号发放</span>
    </header>
    <section v-if="allowed" class="creation-panel" aria-labelledby="creation-title">
      <h2 id="creation-title">{{ result ? '本次创建结果' : '创建账号' }}</h2>
      <p class="intro">账号和昵称由系统统一生成。新账号首次登录必须修改初始密码。</p>
      <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" role="alert" />
      <ElAlert
        v-if="message"
        :title="message"
        type="info"
        show-icon
        :closable="false"
        role="status"
      />
      <template v-if="result">
        <dl class="credential-summary">
          <div>
            <dt>登录账号</dt>
            <dd>{{ result.username }}</dd>
          </div>
          <div>
            <dt>初始昵称</dt>
            <dd>{{ result.nickname }}</dd>
          </div>
          <div>
            <dt>创建身份</dt>
            <dd>{{ result.role === 'ADMIN' ? '管理员' : '普通用户' }}</dd>
          </div>
        </dl>
        <ElForm label-position="top" @submit.prevent>
          <ElFormItem v-if="result.firstDelivery" label="初始密码 · 仅本次展示">
            <ElInput
              :model-value="result.initialPassword ?? ''"
              readonly
              type="password"
              show-password
              autocomplete="off"
            />
          </ElFormItem>
        </ElForm>
        <p v-if="result.firstDelivery" class="delivery-note">
          离开、刷新或确认后无法找回。请保存并私密交付，勿分享截图或公开粘贴。
        </p>
        <div class="actions">
          <ElButton v-if="result.firstDelivery" @click="copyCredentials">复制账号与密码</ElButton>
          <ElButton type="primary" @click="acknowledge">{{
            result.firstDelivery ? '已保存，清除本次凭据' : '已核对，结束本次操作'
          }}</ElButton>
        </div>
      </template>
      <ElForm v-else label-position="top" :disabled="busy" @submit.prevent="create">
        <ElFormItem label="账号身份">
          <ElRadioGroup v-model="role" :disabled="attempted" aria-label="账号身份">
            <ElRadio value="USER">普通用户</ElRadio><ElRadio value="ADMIN">管理员</ElRadio>
          </ElRadioGroup>
        </ElFormItem>
        <p class="format-note">
          账号示例 ph000001；初始昵称示例 小屋住户000001。实际序号由服务端分配，不保证连续。
        </p>
        <ElFormItem v-if="role === 'ADMIN'" label="屋主当前密码（再次验证）">
          <ElInput
            v-model="ownerPassword"
            type="password"
            show-password
            autocomplete="current-password"
            :maxlength="72"
            placeholder="确认由你本人发放管理权限"
          />
        </ElFormItem>
        <p v-if="attempted" class="delivery-note">
          已保留本次创建请求。重试沿用同一编号，不会因此创建第二个账号；请勿刷新后另起一次创建。
        </p>
        <div class="actions">
          <ElButton type="primary" native-type="submit" :loading="busy">{{
            attempted ? '使用同一请求重试' : '创建并交付账号'
          }}</ElButton>
          <ElButton v-if="attempted" :disabled="busy" @click="confirm">核对本次创建结果</ElButton>
        </div>
      </ElForm>
    </section>
    <section v-else class="creation-panel" role="status">
      <h2>{{ store.status === 'checking' ? '正在核对身份' : '仅屋主可创建账号' }}</h2>
      <p class="intro">公共注册已关闭。已有账号请登录；账号管理和资源审核将按计划分阶段开放。</p>
      <ElButton
        v-if="!store.isLoggedIn && store.status !== 'checking'"
        type="primary"
        @click="accountUi.openLogin"
        >登录已有账号</ElButton
      >
      <ElButton v-if="store.status === 'unavailable'" @click="store.restoreSession()"
        >重试连接</ElButton
      >
    </section>
  </div>
</template>

<style scoped>
.provisioning-page {
  width: min(1040px, calc(100% - 80px));
  margin: 32px auto 100px;
  color: var(--ph-ink);
}
.back-link {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  color: var(--ph-muted);
  font-size: 13px;
}
.page-heading {
  margin: 36px 0;
}
.page-heading p {
  letter-spacing: 2px;
  font-size: 11px;
  color: var(--ph-muted);
}
.page-heading h1 {
  font-size: clamp(28px, 4vw, 44px);
  margin: 16px 0;
  line-height: 1.3;
}
.page-heading span {
  color: var(--ph-muted);
  font-size: 14px;
}
.creation-panel {
  border: 1px solid var(--ph-line);
  border-radius: 20px;
  padding: 32px;
  max-width: 760px;
  background: var(--ph-surface);
}
.creation-panel h2 {
  margin-top: 0;
  font-size: 22px;
}
.intro,
.format-note,
.delivery-note {
  line-height: 1.8;
  color: var(--ph-muted);
  font-size: 14px;
}
.format-note {
  margin: 0 0 24px;
}
.delivery-note {
  padding: 16px;
  background: var(--ph-soft);
  border-radius: 12px;
}
.creation-panel :deep(.el-alert) {
  margin: 18px 0;
  overflow-wrap: anywhere;
}
.creation-panel :deep(.el-input__wrapper) {
  min-height: 44px;
}
.creation-panel :deep(.el-radio) {
  min-height: 44px;
  margin-right: 24px;
}
.credential-summary {
  display: grid;
  gap: 16px;
  margin: 24px 0;
}
.credential-summary > div {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 16px;
}
.credential-summary dt {
  color: var(--ph-muted);
}
.credential-summary dd {
  margin: 0;
  overflow-wrap: anywhere;
  font-weight: 600;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 24px;
}
.actions :deep(.el-button) {
  min-height: 44px;
  margin: 0;
  white-space: normal;
  height: auto;
}
@media (max-width: 600px) {
  .provisioning-page {
    width: calc(100% - 32px);
    margin-top: 20px;
  }
  .creation-panel {
    padding: 24px 18px;
  }
  .actions {
    flex-direction: column;
    align-items: stretch;
  }
  .credential-summary > div {
    grid-template-columns: 80px minmax(0, 1fr);
    gap: 8px;
  }
}
</style>

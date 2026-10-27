<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElDialog, ElInput } from 'element-plus'
import { useAccountUiStore } from '../stores/useAccountUiStore'

const accountUi = useAccountUiStore()
const confirmation = ref('')
const acknowledged = ref(false)
const canSubmit = computed(
  () => confirmation.value === '注销账号' && acknowledged.value && !accountUi.pending,
)

function closeDialog(): void {
  accountUi.closeCancellation()
}

async function submit(): Promise<void> {
  if (canSubmit.value) await accountUi.deregister(confirmation.value, acknowledged.value)
}

// 重新打开或切换身份都清空用户同意，不能复用上一次确认。
watch(
  () => accountUi.cancellationVisible,
  () => {
    confirmation.value = ''
    acknowledged.value = false
  },
)
</script>

<template>
  <div class="cancellation-host">
    <ElDialog
      :model-value="accountUi.cancellationVisible"
      title="确认注销账号"
      width="480px"
      class="cancellation-dialog"
      align-center
      destroy-on-close
      :close-on-click-modal="!accountUi.pending"
      :close-on-press-escape="!accountUi.pending"
      :show-close="!accountUi.pending"
      :before-close="closeDialog"
      @update:model-value="closeDialog"
    >
      <p class="account-subject">
        当前账号 <strong>{{ accountUi.cancellationUsername }}</strong>
      </p>
      <p class="lead">这会停用你的账号，请先确认以下影响。</p>
      <ul class="impact-list">
        <li>账号将无法再次登录，所有终端都会退出。</li>
        <li>原账号名仍被占用，不能用它重新注册。</li>
        <li>已有资料和历史记录仍会保留，不代表永久删除个人数据。</li>
      </ul>
      <ElAlert
        v-if="accountUi.actionError"
        :title="accountUi.actionError"
        description="未收到成功确认，请核实账号状态后再决定是否重试。"
        type="error"
        :closable="false"
        show-icon
      />
      <form @submit.prevent="submit">
        <ElCheckbox v-model="acknowledged" :disabled="!!accountUi.pending" class="acknowledgement"
          >我已了解上述影响，仍要注销此账号</ElCheckbox
        >
        <label class="confirmation-label" for="account-cancellation-confirmation"
          >请输入“注销账号”以确认</label
        >
        <ElInput
          id="account-cancellation-confirmation"
          v-model="confirmation"
          name="account-cancellation-confirmation"
          placeholder="注销账号"
          autocomplete="off"
          :disabled="!!accountUi.pending"
          :maxlength="12"
        />
        <div class="dialog-actions">
          <ElButton :disabled="!!accountUi.pending" @click="closeDialog">暂不注销</ElButton
          ><ElButton
            type="danger"
            native-type="submit"
            :disabled="!canSubmit"
            :loading="accountUi.pending === 'deregister'"
            >确认注销此账号</ElButton
          >
        </div>
      </form>
    </ElDialog>
  </div>
</template>

<style scoped>
.cancellation-host :deep(.cancellation-dialog) {
  max-width: calc(100vw - 32px);
  max-height: calc(100dvh - 64px);
  overflow-y: auto;
  padding: 28px;
  border-radius: 18px;
  background: #fffefb;
}
.cancellation-host :deep(.el-dialog__title) {
  font-size: 21px;
  font-weight: 650;
  color: var(--ph-ink);
}
.account-subject {
  margin-top: 4px;
  color: var(--ph-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.account-subject strong {
  margin-left: 8px;
  color: var(--ph-ink);
  font-weight: 500;
}
.lead {
  margin-top: 24px;
  font-size: 14px;
  color: #46554b;
}
.impact-list {
  padding: 16px 18px 16px 34px;
  margin: 18px 0;
  background: #faf1e9;
  color: #7e5e43;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.9;
}
.impact-list li + li {
  margin-top: 7px;
}
.acknowledgement {
  margin: 12px 0 18px;
  height: auto;
  align-items: flex-start;
}
.acknowledgement :deep(.el-checkbox__label) {
  white-space: normal;
  line-height: 1.8;
  font-size: 12px;
}
.acknowledgement :deep(.el-checkbox__input) {
  margin-top: 4px;
}
.confirmation-label {
  display: block;
  margin-bottom: 10px;
  font-size: 12px;
  color: #46554b;
}
.cancellation-host :deep(.el-input__wrapper) {
  min-height: 42px;
}
.cancellation-host :deep(.el-input__inner:focus-visible) {
  outline: none;
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}
.dialog-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
@media (max-width: 480px) {
  .cancellation-host :deep(.cancellation-dialog) {
    padding: 24px 20px;
  }
  .dialog-actions {
    flex-wrap: wrap;
  }
}
</style>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import {
  ElAlert,
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  type FormInstance,
  type FormItemRule,
  type FormRules,
  type InputInstance,
} from 'element-plus'
import { ApiError } from '../api'
import { useUserStore } from '../stores/useUserStore'
import { useAccountUiStore } from '../stores/useAccountUiStore'
import { passwordError } from '../utils/authValidation'

/** 普通改密由个人页打开；服务端标记临时凭据时全站自动进入同一表单。 */
interface Props {
  /** 不携带密码或目标账号，只控制普通改密入口。 */
  modelValue: boolean
}

/** 明文凭据不发送给父组件，不写入全局状态或本地缓存。 */
interface Emits {
  (event: 'update:modelValue', value: boolean): void
}

/** 三个密码字段只保存在当前表单，结束、关闭或换号后立即清除。 */
interface PasswordForm {
  /** 用于再次验证操作者身份。 */
  currentPassword: string
  /** 提交服务端的新原始密码。 */
  newPassword: string
  /** 二次确认只在前端比较。 */
  confirmation: string
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const userStore = useUserStore()
const accountUi = useAccountUiStore()
const visible = computed(
  () => userStore.isLoggedIn && (props.modelValue || userStore.requiresPasswordChange),
)
const form = reactive<PasswordForm>({ currentPassword: '', newPassword: '', confirmation: '' })
const formRef = ref<FormInstance>()
const currentInput = ref<InputInstance>()
const errorSummary = ref<HTMLElement>()
const saving = ref(false)
const error = ref('')
let generation = 0

function rule(check: (value: string) => string | null): FormItemRule {
  return {
    trigger: 'blur',
    validator: (_rule, value: unknown, callback) => {
      const message = typeof value === 'string' ? check(value) : '请填写此项'
      callback(message ? new Error(message) : undefined)
    },
  }
}

const rules: FormRules<PasswordForm> = {
  currentPassword: [rule(passwordError)],
  newPassword: [
    rule(
      (value) =>
        passwordError(value) ??
        (value === form.currentPassword ? '新密码不能与当前密码相同' : null),
    ),
  ],
  confirmation: [
    rule((value) => (value && value === form.newPassword ? null : '两次输入的新密码不一致')),
  ],
}

function clearPasswords(): void {
  form.currentPassword = ''
  form.newPassword = ''
  form.confirmation = ''
}

function reset(): void {
  generation += 1
  clearPasswords()
  error.value = ''
  saving.value = false
  formRef.value?.clearValidate()
}

function close(): void {
  if (saving.value || userStore.requiresPasswordChange) return
  emit('update:modelValue', false)
}

async function showError(message: string): Promise<void> {
  error.value = message
  await nextTick()
  errorSummary.value?.focus()
}

async function save(): Promise<void> {
  if (saving.value || accountUi.pending || !visible.value || !formRef.value) return
  const submittedGeneration = generation
  const submittedToken = userStore.token
  const valid = await formRef.value.validate().catch(() => false)
  if (
    generation !== submittedGeneration ||
    userStore.token !== submittedToken ||
    !visible.value ||
    saving.value
  )
    return
  if (!valid) {
    await showError('请检查密码格式和两次新密码是否一致。')
    return
  }
  saving.value = true
  error.value = ''
  try {
    const changed = await userStore.changePassword({
      currentPassword: form.currentPassword,
      newPassword: form.newPassword,
    })
    if (changed) emit('update:modelValue', false)
  } catch (cause: unknown) {
    if (generation !== submittedGeneration || userStore.token !== submittedToken || !visible.value)
      return
    const uncertain = !(cause instanceof ApiError) || cause.status === 0 || cause.status >= 500
    await showError(
      uncertain
        ? '改密结果尚未确认，请退出后尝试用新密码登录核对，不要自动重复提交。'
        : cause instanceof ApiError && cause.status === 400
          ? '无法修改，请核对当前密码及新密码格式。'
          : cause instanceof ApiError
            ? cause.message
            : '暂时无法修改密码',
    )
  } finally {
    if (generation === submittedGeneration) {
      clearPasswords()
      saving.value = false
    }
  }
}

// 与服务端请求不同，表单生命周期可立即作废；旧请求结束不能修改新弹窗或新账号。
watch(
  [visible, () => userStore.token],
  () => {
    reset()
    if (!userStore.isLoggedIn) emit('update:modelValue', false)
  },
  { flush: 'sync' },
)
onBeforeUnmount(reset)
</script>

<template>
  <div class="password-host">
    <ElDialog
      :model-value="visible"
      title="修改密码"
      width="460px"
      align-center
      destroy-on-close
      :close-on-click-modal="!saving && !userStore.requiresPasswordChange"
      :close-on-press-escape="!saving && !userStore.requiresPasswordChange"
      :show-close="!saving && !userStore.requiresPasswordChange"
      :before-close="close"
      @opened="currentInput?.focus()"
      @update:model-value="
        (open: boolean) => {
          if (!open) close()
        }
      "
    >
      <p class="intro">
        {{
          userStore.requiresPasswordChange
            ? '你正在使用初始或重置密码，请先设置自己的密码。'
            : '修改成功后，所有终端都会退出，请使用新密码重新登录。'
        }}
      </p>
      <div v-if="error" ref="errorSummary" class="error-summary" role="alert" tabindex="-1">
        <ElAlert :title="error" type="error" :closable="false" show-icon />
      </div>
      <ElForm
        ref="formRef"
        :model="form"
        :rules="rules"
        :disabled="saving"
        label-position="top"
        @submit.prevent="save"
      >
        <ElFormItem label="当前密码" prop="currentPassword">
          <ElInput
            ref="currentInput"
            v-model="form.currentPassword"
            type="password"
            autocomplete="current-password"
            name="current-password"
            show-password
            :maxlength="72"
          />
        </ElFormItem>
        <ElFormItem label="新密码" prop="newPassword">
          <ElInput
            v-model="form.newPassword"
            type="password"
            autocomplete="new-password"
            name="new-password"
            placeholder="8～72 个字符，UTF-8 不超过 72 字节"
            show-password
            :maxlength="72"
          />
        </ElFormItem>
        <ElFormItem label="确认新密码" prop="confirmation">
          <ElInput
            v-model="form.confirmation"
            type="password"
            autocomplete="new-password"
            name="confirm-new-password"
            show-password
            :maxlength="72"
          />
        </ElFormItem>
        <ElButton
          type="primary"
          native-type="submit"
          :loading="saving"
          :disabled="!!accountUi.pending"
          class="save-button"
          >保存并重新登录</ElButton
        >
      </ElForm>
      <ElButton
        v-if="userStore.requiresPasswordChange"
        text
        :disabled="saving"
        :loading="accountUi.pending === 'logout'"
        @click="accountUi.logout()"
        >退出当前登录</ElButton
      >
      <p v-if="accountUi.actionError" role="alert" class="logout-error">
        {{ accountUi.actionError }}
      </p>
    </ElDialog>
  </div>
</template>

<style scoped>
.password-host :deep(.el-dialog) {
  max-width: calc(100vw - 32px);
  max-height: calc(100dvh - 48px);
  overflow-y: auto;
  padding: 28px;
}
.password-host :deep(.el-dialog__title) {
  font-size: 23px;
  font-weight: 650;
}
.password-host :deep(.el-input__wrapper) {
  min-height: 44px;
}
.password-host :deep(.el-input__inner:focus-visible) {
  outline: none;
}
.intro {
  color: var(--ph-muted);
  font-size: 14px;
  line-height: 1.8;
  margin: 10px 0 22px;
}
.error-summary {
  margin-bottom: 20px;
}
.save-button {
  width: 100%;
  margin-top: 8px;
}
.logout-error {
  color: #b42335;
  font-size: 13px;
}
@media (max-width: 480px) {
  .password-host :deep(.el-dialog) {
    padding: 24px 20px;
  }
}
</style>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
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
import { register } from '../api/auth'
import { useUserStore } from '../stores/useUserStore'
import { nicknameError, passwordError, usernameError } from '../utils/authValidation'

/** 弹窗仅接收显示状态，不接收或向父组件传递密码。 */
interface Props {
  /** 由导航决定是否打开，切换路由不强制跳走当前页面。 */
  modelValue: boolean
}

/** 关闭只更新显示状态，身份变化由全局会话管理负责。 */
interface Emits {
  (event: 'update:modelValue', value: boolean): void
}

/** 表单字段只存在组件内存；关闭、切换及提交结束时清除密码。 */
interface AuthFormValues {
  /** 原始账号，不自动裁剪。 */
  username: string
  /** 原始密码，不做归一化。 */
  password: string
  /** 注册时的展示昵称。 */
  nickname: string
  /** 二次确认仅供本地比较，不发送到服务端。 */
  confirmation: string
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const userStore = useUserStore()
const mode = ref<'login' | 'register'>('login')
const submitting = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const formRef = ref<FormInstance>()
const usernameInput = ref<InputInstance>()
const form = reactive<AuthFormValues>({
  username: '',
  password: '',
  nickname: '',
  confirmation: '',
})
const title = computed(() => (mode.value === 'login' ? '欢迎回到聚妙屋' : '开启你的个人空间'))

function fieldRule(validate: (value: string) => string | null): FormItemRule {
  return {
    trigger: 'blur',
    validator: (_rule, value: unknown, callback) => {
      const message = typeof value === 'string' ? validate(value) : '请填写此项'
      callback(message ? new Error(message) : undefined)
    },
  }
}

const rules = computed<FormRules<AuthFormValues>>(() => ({
  username: [fieldRule(usernameError)],
  password: [fieldRule(passwordError)],
  nickname: mode.value === 'register' ? [fieldRule(nicknameError)] : [],
  confirmation:
    mode.value === 'register'
      ? [fieldRule((value) => (value && value === form.password ? null : '两次输入的密码不一致'))]
      : [],
}))

function clearPasswords(): void {
  form.password = ''
  form.confirmation = ''
}

function changeMode(next: 'login' | 'register'): void {
  if (submitting.value || next === mode.value) return
  mode.value = next
  errorMessage.value = ''
  successMessage.value = ''
  clearPasswords()
  formRef.value?.clearValidate()
}

function closeDialog(): void {
  if (!submitting.value) emit('update:modelValue', false)
}

async function submit(): Promise<void> {
  if (submitting.value || !formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid || submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    if (mode.value === 'register') {
      await register({ username: form.username, password: form.password, nickname: form.nickname })
      mode.value = 'login'
      successMessage.value = '注册成功，现在可以登录了。'
      formRef.value.clearValidate()
    } else if (await userStore.login({ username: form.username, password: form.password })) {
      emit('update:modelValue', false)
    } else {
      errorMessage.value = '登录状态已变化，请重新操作'
    }
  } catch (error: unknown) {
    errorMessage.value = error instanceof ApiError ? error.message : '暂时无法完成操作，请稍后重试'
  } finally {
    clearPasswords()
    submitting.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    clearPasswords()
    errorMessage.value = ''
    successMessage.value = ''
    formRef.value?.clearValidate()
    if (open) mode.value = 'login'
    else {
      form.username = ''
      form.nickname = ''
    }
  },
)
</script>

<template>
  <!-- 弹层的实际根节点由 Element Plus 创建，通过本组件宿主限定 deep 样式，避免污染其他弹窗。 -->
  <div class="auth-host">
    <ElDialog
      :model-value="modelValue"
      :title="title"
      width="440px"
      class="auth-dialog"
      align-center
      destroy-on-close
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      :before-close="closeDialog"
      @opened="usernameInput?.focus()"
      @update:model-value="emit('update:modelValue', $event)"
    >
      <div class="auth-content">
        <p class="auth-eyebrow">POLYHOUSE / 聚妙屋</p>
        <p class="auth-intro">
          {{
            mode === 'login'
              ? '登录你的账号，继续探索日常里的新可能。'
              : '一个账号，查看个人资料，记录每天的成长。'
          }}
        </p>
        <div class="auth-tabs" role="group" aria-label="选择登录或注册">
          <button
            type="button"
            :class="{ active: mode === 'login' }"
            :aria-pressed="mode === 'login'"
            :disabled="submitting"
            @click="changeMode('login')"
          >
            登录
          </button>
          <button
            type="button"
            :class="{ active: mode === 'register' }"
            :aria-pressed="mode === 'register'"
            :disabled="submitting"
            @click="changeMode('register')"
          >
            注册
          </button>
        </div>
        <ElAlert
          v-if="errorMessage"
          :title="errorMessage"
          type="error"
          :closable="false"
          show-icon
          class="form-notice"
        />
        <ElAlert
          v-if="successMessage"
          :title="successMessage"
          type="success"
          :closable="false"
          show-icon
          class="form-notice"
        />
        <ElForm
          ref="formRef"
          :model="form"
          :rules="rules"
          :disabled="submitting"
          :validate-on-rule-change="false"
          label-position="top"
          @submit.prevent="submit"
        >
          <ElFormItem label="账号" prop="username"
            ><ElInput
              ref="usernameInput"
              v-model="form.username"
              name="username"
              autocomplete="username"
              placeholder="4～32 位字母、数字或下划线"
              :maxlength="32"
          /></ElFormItem>
          <ElFormItem v-if="mode === 'register'" label="昵称" prop="nickname"
            ><ElInput
              v-model="form.nickname"
              name="nickname"
              autocomplete="nickname"
              placeholder="希望我们怎么称呼你？"
              :maxlength="32"
          /></ElFormItem>
          <ElFormItem label="密码" prop="password"
            ><ElInput
              v-model="form.password"
              name="password"
              type="password"
              :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
              placeholder="至少 8 个字符"
              :maxlength="72"
              show-password
          /></ElFormItem>
          <ElFormItem v-if="mode === 'register'" label="确认密码" prop="confirmation"
            ><ElInput
              v-model="form.confirmation"
              name="confirmation"
              type="password"
              autocomplete="new-password"
              placeholder="再输入一次密码"
              :maxlength="72"
              show-password
          /></ElFormItem>
          <ElButton
            class="submit-button"
            type="primary"
            native-type="submit"
            :loading="submitting"
            >{{ mode === 'login' ? '登录，回到小屋' : '创建账号' }}</ElButton
          >
        </ElForm>
        <p class="auth-footnote">
          {{ mode === 'login' ? '密码输错可以直接重试。' : '注册成功后，使用账号和密码登录。' }}
        </p>
      </div>
    </ElDialog>
  </div>
</template>

<style scoped>
.auth-host :deep(.auth-dialog) {
  max-width: calc(100vw - 32px);
  max-height: calc(100dvh - 64px);
  overflow-y: auto;
  border-radius: 18px;
  padding: 28px;
  background: #ffffff;
}
.auth-host :deep(.el-dialog__title) {
  font-size: 23px;
  font-weight: 650;
  color: var(--ph-ink);
}
.auth-host :deep(.el-dialog__header) {
  padding-bottom: 0;
}
.auth-host :deep(.el-input__wrapper) {
  min-height: 44px;
  background: white;
}
/* 输入框沿用 Element Plus 外框的聚焦高亮，避免全站 tabindex 轮廓在内部再画一层。 */
.auth-host :deep(.el-input__inner:focus-visible) {
  outline: none;
}
.auth-host :deep(.el-form-item__label) {
  color: var(--ph-ink);
  font-size: 13px;
}
.auth-eyebrow {
  font-size: 9px;
  letter-spacing: 1.5px;
  color: var(--ph-muted);
  margin: 8px 0 14px;
}
.auth-intro {
  color: var(--ph-muted);
  font-size: 13px;
  line-height: 1.8;
  margin: 0 0 24px;
}
.auth-tabs {
  display: flex;
  gap: 26px;
  border-bottom: 1px solid var(--ph-line);
  margin-bottom: 24px;
}
.auth-tabs button {
  padding: 0 2px 12px;
  color: var(--ph-muted);
  font-size: 14px;
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
  cursor: pointer;
}
.auth-tabs button.active {
  border-color: var(--ph-brand);
  color: var(--ph-brand);
  font-weight: 600;
}
.auth-tabs button:disabled {
  cursor: wait;
}
.form-notice {
  margin-bottom: 20px;
}
.submit-button {
  width: 100%;
  min-height: 46px;
  margin-top: 6px;
}
.auth-footnote {
  font-size: 11px;
  text-align: center;
  color: var(--ph-muted);
  margin: 18px 0 0;
}
@media (max-width: 480px) {
  .auth-host :deep(.auth-dialog) {
    padding: 24px 20px;
  }
  .auth-host :deep(.el-dialog__title) {
    font-size: 21px;
  }
}
</style>

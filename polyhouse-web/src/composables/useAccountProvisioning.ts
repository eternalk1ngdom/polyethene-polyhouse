import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiError } from '../api'
import {
  confirmAccountCreation,
  createAccount,
  type AccountCreation,
  type ProvisionedRole,
} from '../api/accountProvisioning'
import { useUserStore } from '../stores/useUserStore'

/** 仅保留创建意图，不保存 Token、屋主密码或初始密码；刷新后必须先核对再创建。 */
interface CreationIntent {
  /** 防止另一账号继承本次操作。 */
  actorId: string
  /** 同一次创建请求的 UUID。 */
  requestId: string
  /** 一旦发送即冻结角色，避免同一请求被改成另一用途。 */
  role: ProvisionedRole
}

const INTENT_KEY = 'polyhouse_account_creation_intent'

/** 独立管理一次性凭据的生命周期，不把管理写操作与普通登录/成长状态混在一起。 */
export function useAccountProvisioning() {
  const store = useUserStore()
  const allowed = computed(
    () => store.isLoggedIn && store.user?.role === 'OWNER' && !store.requiresPasswordChange,
  )
  const role = ref<ProvisionedRole>('USER')
  const ownerPassword = ref('')
  const busy = ref(false)
  const attempted = ref(false)
  const result = ref<AccountCreation | null>(null)
  const message = ref('')
  const error = ref('')
  const intent = ref<CreationIntent | null>(null)
  let revision = 0

  function clearSecrets(): void {
    ownerPassword.value = ''
    result.value = null
  }

  function retainIntent(): void {
    try {
      sessionStorage.setItem(INTENT_KEY, JSON.stringify(intent.value))
    } catch {
      message.value = '浏览器无法保存创建请求编号，关闭或刷新页面前请先完成结果核对。'
    }
  }

  function loadIntent(): void {
    intent.value = null
    attempted.value = false
    try {
      const raw: unknown = JSON.parse(sessionStorage.getItem(INTENT_KEY) ?? 'null')
      if (typeof raw !== 'object' || raw === null) return
      const entry = raw as Record<string, unknown>
      if (
        typeof entry.actorId === 'string' &&
        entry.actorId === store.user?.id &&
        typeof entry.requestId === 'string' &&
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(entry.requestId) &&
        (entry.role === 'USER' || entry.role === 'ADMIN')
      ) {
        intent.value = { actorId: entry.actorId, requestId: entry.requestId, role: entry.role }
        role.value = entry.role
        attempted.value = true
        message.value = '有一笔尚未确认交付的创建请求，请先核对结果；初始密码不会在刷新后恢复。'
      }
    } catch {
      message.value = '无法读取本次创建意图，请确认上一次账号交付情况后再操作。'
    }
  }

  // 换号、退出、权限变化和卸载均清除明文；旧响应不得把凭据交付给新的浏览者。
  watch(
    () => [store.token, allowed.value, store.user?.id] as const,
    () => {
      revision += 1
      busy.value = false
      clearSecrets()
      error.value = ''
      message.value = ''
      if (allowed.value) loadIntent()
      else {
        intent.value = null
        attempted.value = false
      }
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    revision += 1
    clearSecrets()
  })

  function newRequestId(): string {
    // getRandomValues 可在本机调试使用，不依赖仅安全上下文提供的 randomUUID 快捷方法。
    const bytes = crypto.getRandomValues(new Uint8Array(16))
    bytes[6] = (bytes[6]! & 15) | 64
    bytes[8] = (bytes[8]! & 63) | 128
    const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }

  async function run(confirmOnly: boolean): Promise<void> {
    if (!allowed.value || busy.value || result.value) return
    if (confirmOnly && !intent.value) return
    if (!confirmOnly && role.value === 'ADMIN' && !ownerPassword.value) {
      error.value = '创建管理员需要填写屋主当前密码'
      return
    }
    const current = revision
    const token = store.token
    busy.value = true
    error.value = ''
    message.value = ''
    try {
      if (!intent.value)
        intent.value = { actorId: store.user!.id, requestId: newRequestId(), role: role.value }
      retainIntent()
      attempted.value = true
      const request = {
        requestId: intent.value.requestId,
        role: intent.value.role,
        ...(intent.value.role === 'ADMIN' ? { ownerPassword: ownerPassword.value } : {}),
      }
      const response = confirmOnly
        ? await confirmAccountCreation(intent.value.requestId, token)
        : await createAccount(request, token)
      if (current !== revision || !allowed.value) return
      result.value = response
      if (!response.firstDelivery)
        message.value =
          '账号已创建，初始密码不能再次查询。若未保存，请待屋主密码重置功能开放后重新发放，勿重复创建。'
    } catch (failure: unknown) {
      if (current !== revision) return
      // 本接口的 400 表示校验/当前密码拒绝，事务未创建账号；允许修正角色而不困在错误意图中。
      if (!confirmOnly && failure instanceof ApiError && failure.status === 400) {
        intent.value = null
        attempted.value = false
        try {
          sessionStorage.removeItem(INTENT_KEY)
        } catch {
          message.value = '请求已被拒绝，浏览器未能清除旧意图；刷新后请先核对。'
        }
      }
      error.value =
        failure instanceof ApiError && failure.status === 404
          ? '尚未查到成功记录，正在执行的请求仍可能提交。请稍后核对，或使用同一请求重试。'
          : failure instanceof ApiError
            ? failure.message
            : '操作未能确认，请核对本次创建结果'
    } finally {
      if (current === revision) {
        ownerPassword.value = ''
        busy.value = false
      }
    }
  }

  function acknowledge(): void {
    if (busy.value || !result.value) return
    clearSecrets()
    intent.value = null
    attempted.value = false
    error.value = ''
    message.value = ''
    role.value = 'USER'
    try {
      sessionStorage.removeItem(INTENT_KEY)
    } catch {
      message.value = '交付内容已从内存清除；浏览器暂时无法清除创建意图，刷新后可能仍需核对。'
    }
  }

  async function copyCredentials(): Promise<void> {
    if (!result.value?.firstDelivery || !result.value.initialPassword) return
    const current = revision
    try {
      await navigator.clipboard.writeText(
        `账号：${result.value.username}\n初始密码：${result.value.initialPassword}\n首次登录请修改密码。`,
      )
      if (current === revision) message.value = '已复制，请通过私密渠道交付；此内容包含临时密码。'
    } catch {
      if (current === revision) message.value = '浏览器不允许自动复制，请手动选择并复制账号和密码。'
    }
  }

  return {
    allowed,
    role,
    ownerPassword,
    busy,
    attempted,
    result,
    message,
    error,
    create: () => run(false),
    confirm: () => run(true),
    acknowledge,
    copyCredentials,
  }
}

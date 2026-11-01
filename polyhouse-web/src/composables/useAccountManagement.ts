import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ApiError } from '../api'
import {
  listManagedAccounts,
  manageAccount,
  confirmManagementOperation,
  type ManagedAccount,
  type ManagementAction,
  type ManagementResult,
} from '../api/accountManagement'
import { useUserStore } from '../stores/useUserStore'
import { passwordError } from '../utils/authValidation'

/** 筛选仅作用于查询，不能据此推导或扩大账号管理权限。 */
interface Filters {
  /** 登录名的字面前缀。 */
  username: string
  /** 空字符串表示不筛选。 */
  role: ManagedAccount['role'] | ''
  /** 空字符串表示全部状态。 */
  status: 0 | 1 | ''
}
/** 刷新只保留核对所需摘要；原因、密码和完整请求体都不写浏览器存储。 */
interface PendingOperation {
  /** 防止换号接管旧请求。 */
  actorId: string
  /** 已发送的创建意图编号。 */
  requestId: string
  /** 精确目标编号。 */
  targetId: string
  /** 仅用于核对对象。 */
  username: string
  /** 仅用于核对动作。 */
  action: ManagementAction
}
const STORAGE_KEY = 'polyhouse_management_pending'

/** 独立维护分页、确认和一次性密码；任何旧响应不得覆盖换号后的页面。 */
export function useAccountManagement() {
  const store = useUserStore()
  const allowed = computed(
    () =>
      store.isLoggedIn &&
      !store.requiresPasswordChange &&
      (store.user?.role === 'OWNER' || store.user?.role === 'ADMIN'),
  )
  const owner = computed(() => allowed.value && store.user?.role === 'OWNER')
  const filters = reactive<Filters>({ username: '', role: '', status: '' })
  const items = ref<ManagedAccount[]>([]),
    total = ref('0'),
    page = ref(1)
  const loading = ref(false),
    listError = ref(''),
    notice = ref('')
  const dialogOpen = ref(false),
    target = ref<ManagedAccount | null>(null)
  const action = ref<ManagementAction>('BAN'),
    reason = ref(''),
    ownerPassword = ref('')
  const saving = ref(false),
    error = ref(''),
    result = ref<ManagementResult | null>(null)
  const pending = ref<PendingOperation | null>(null),
    stale = ref(false)
  const needsPassword = computed(
    () =>
      action.value === 'PASSWORD_RESET' ||
      (action.value === 'BAN' && target.value?.role === 'ADMIN'),
  )
  const hasNext = computed(
    () => BigInt(page.value * 20) < BigInt(total.value) && page.value < 10000,
  )
  let revision = 0,
    queryId = 0

  function clearSecrets(): void {
    ownerPassword.value = ''
    result.value = null
  }
  function savePending(): void {
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(pending.value))
    } catch {
      notice.value = '无法保存操作编号；离开前请先核对本次结果。'
    }
  }
  function removePending(): void {
    pending.value = null
    try {
      sessionStorage.removeItem(STORAGE_KEY)
    } catch {
      notice.value = '浏览器未能清除旧操作摘要，刷新后请重新核对。'
    }
  }
  function restorePending(): void {
    try {
      const raw: unknown = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? 'null')
      if (typeof raw !== 'object' || raw === null) return
      const record = raw as Record<string, unknown>
      if (
        record.actorId !== store.user?.id ||
        typeof record.actorId !== 'string' ||
        typeof record.requestId !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(record.requestId) ||
        typeof record.targetId !== 'string' ||
        !/^[1-9]\d*$/.test(record.targetId) ||
        typeof record.username !== 'string' ||
        !['BAN', 'UNBAN', 'PASSWORD_RESET'].includes(String(record.action))
      )
        return
      pending.value = {
        actorId: record.actorId,
        requestId: record.requestId,
        targetId: record.targetId,
        username: record.username,
        action: record.action as ManagementAction,
      }
      notice.value = '有一笔待核对操作；刷新不会自动重发，临时密码不会恢复。'
    } catch {
      notice.value = '无法读取待核对操作，请先核实上一次处理结果。'
    }
  }

  async function load(resetPage = false): Promise<void> {
    if (!allowed.value) return
    if (resetPage) page.value = 1
    if (!/^[A-Za-z0-9_]{0,32}$/.test(filters.username)) {
      listError.value = '账号前缀仅限字母、数字或下划线，最多 32 位'
      return
    }
    const generation = revision,
      currentQuery = ++queryId
    loading.value = true
    listError.value = ''
    try {
      const response = await listManagedAccounts(
        {
          page: page.value,
          pageSize: 20,
          username: filters.username || undefined,
          role: owner.value ? filters.role || undefined : undefined,
          status: filters.status === '' ? undefined : filters.status,
        },
        store.token,
      )
      if (generation !== revision || currentQuery !== queryId || !allowed.value) return
      items.value = response.items
      total.value = response.total
    } catch (failure: unknown) {
      if (generation === revision && currentQuery === queryId) {
        items.value = []
        total.value = '0'
        listError.value = failure instanceof ApiError ? failure.message : '列表加载失败，请重试'
      }
    } finally {
      if (generation === revision && currentQuery === queryId) loading.value = false
    }
  }

  function open(account: ManagedAccount, nextAction: ManagementAction): void {
    if (
      !allowed.value ||
      saving.value ||
      pending.value ||
      account.readOnly ||
      (!owner.value && (account.role !== 'USER' || nextAction !== 'BAN'))
    )
      return
    target.value = { ...account }
    action.value = nextAction
    reason.value = ''
    clearSecrets()
    error.value = ''
    stale.value = false
    dialogOpen.value = true
  }
  function requestId(): string {
    // getRandomValues 不依赖仅安全上下文提供的 randomUUID；固定 RFC 4122 版本/变体位。
    const bytes = crypto.getRandomValues(new Uint8Array(16))
    bytes[6] = (bytes[6]! & 15) | 64
    bytes[8] = (bytes[8]! & 63) | 128
    const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  function accept(response: ManagementResult, expected: PendingOperation): void {
    if (
      response.requestId !== expected.requestId ||
      response.targetId !== expected.targetId ||
      response.action !== expected.action
    )
      throw new ApiError('响应不属于当前操作，请核对原请求', 502)
    result.value = response
    if (response.action === 'PASSWORD_RESET' && !response.firstDelivery)
      notice.value = '原重置已成功，但明文无法恢复。若未保存，请核对最新列表后显式重新重置。'
  }
  async function submit(): Promise<void> {
    if (!allowed.value || saving.value || pending.value || stale.value || !target.value) return
    if (!reason.value.trim() || reason.value.length > 200 || /[\p{Cc}\p{Cf}]/u.test(reason.value)) {
      error.value = '请填写 1～200 字原因，不含换行/隐藏控制字符'
      return
    }
    if (needsPassword.value && passwordError(ownerPassword.value)) {
      error.value = '请填写有效的屋主当前密码'
      return
    }
    const current = revision,
      selected = target.value
    const intent: PendingOperation = {
      actorId: store.user!.id,
      requestId: requestId(),
      targetId: selected.id,
      username: selected.username,
      action: action.value,
    }
    pending.value = intent
    savePending()
    saving.value = true
    error.value = ''
    try {
      const response = await manageAccount(
        selected.id,
        action.value,
        {
          requestId: intent.requestId,
          expectedVersion: selected.version,
          expectedRole: selected.role,
          expectedStatus: selected.status,
          reason: reason.value,
          ...(needsPassword.value ? { ownerPassword: ownerPassword.value } : {}),
        },
        store.token,
      )
      if (current !== revision || !allowed.value) return
      accept(response, intent)
      await load()
    } catch (failure: unknown) {
      if (current !== revision) return
      if (failure instanceof ApiError && [400, 403, 404, 409].includes(failure.status)) {
        removePending()
        if (failure.status === 409) {
          stale.value = true
          await load()
        }
      }
      error.value = failure instanceof ApiError ? failure.message : '提交结果未知，请核对原请求'
    } finally {
      if (current === revision) {
        ownerPassword.value = ''
        saving.value = false
      }
    }
  }
  async function confirm(): Promise<void> {
    if (!allowed.value || saving.value || !pending.value) return
    const current = revision,
      intent = pending.value
    dialogOpen.value = true
    saving.value = true
    error.value = ''
    try {
      const response = await confirmManagementOperation(intent.requestId, store.token)
      if (current !== revision || !allowed.value) return
      accept(response, intent)
      await load()
    } catch (failure: unknown) {
      if (current === revision)
        error.value =
          failure instanceof ApiError && failure.status === 404
            ? '尚无成功记录，原请求仍可能执行。请稍后核对；结束核对不会重发操作。'
            : failure instanceof ApiError
              ? failure.message
              : '核对失败，请稍后重试'
    } finally {
      if (current === revision) {
        ownerPassword.value = ''
        saving.value = false
      }
    }
  }
  function close(): void {
    if (saving.value) return
    clearSecrets()
    reason.value = ''
    target.value = null
    dialogOpen.value = false
    error.value = ''
  }
  function finish(): void {
    if (saving.value) return
    removePending()
    close()
    notice.value = '已结束本次核对；列表按服务端最新状态重新加载。'
    void load()
  }
  async function copyPassword(): Promise<void> {
    if (!result.value?.temporaryPassword) return
    const current = revision
    try {
      await navigator.clipboard.writeText(
        `账号：${result.value.username}\n临时密码：${result.value.temporaryPassword}`,
      )
    } catch {
      if (current === revision) error.value = '无法自动复制，请显示密码后手动复制并私密交付。'
    }
  }
  function nextPage(delta: number): void {
    if (loading.value || page.value + delta < 1 || (delta > 0 && !hasNext.value)) return
    page.value += delta
    void load()
  }
  watch(
    [
      () => store.token,
      () => store.user?.id,
      () => store.user?.role,
      () => store.isLoggedIn,
      () => store.requiresPasswordChange,
    ],
    () => {
      revision++
      queryId++
      clearSecrets()
      reason.value = ''
      target.value = null
      pending.value = null
      dialogOpen.value = false
      saving.value = false
      loading.value = false
      items.value = []
      total.value = '0'
      error.value = ''
      listError.value = ''
      notice.value = ''
      page.value = 1
      filters.username = ''
      filters.role = ''
      filters.status = ''
      if (allowed.value) {
        restorePending()
        void load()
      }
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    revision++
    queryId++
    clearSecrets()
    reason.value = ''
  })
  return {
    allowed,
    owner,
    filters,
    items,
    total,
    page,
    loading,
    listError,
    notice,
    dialogOpen,
    target,
    action,
    reason,
    ownerPassword,
    saving,
    error,
    result,
    pending,
    stale,
    needsPassword,
    hasNext,
    load,
    open,
    submit,
    confirm,
    close,
    finish,
    copyPassword,
    nextPage,
  }
}

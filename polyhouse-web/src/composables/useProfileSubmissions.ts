import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiError } from '../api'
import { newProfileRequestId } from '../api/auth'
import { getProfileStatus, submitNickname, type ProfileStatus } from '../api/profileModeration'
import { useUserStore } from '../stores/useUserStore'
/** 本人申请状态与公开资料分离；旧响应、换号和卸载不能回填内容。 */
export function useProfileSubmissions() {
  const store = useUserStore(),
    status = ref<ProfileStatus | null>(null),
    nickname = ref('')
  const loading = ref(false),
    saving = ref(false),
    error = ref(''),
    notice = ref('')
  const allowed = computed(() => store.isLoggedIn && !store.requiresPasswordChange)
  let generation = 0,
    readId = 0,
    requestId = '',
    submittedText = ''
  async function load(): Promise<void> {
    if (!allowed.value) return
    const current = generation,
      request = ++readId
    loading.value = true
    error.value = ''
    try {
      const response = await getProfileStatus(store.token)
      if (current === generation && request === readId) status.value = response
    } catch (e: unknown) {
      if (current === generation && request === readId) {
        status.value = null
        error.value = e instanceof ApiError ? e.message : '申请状态加载失败'
      }
    } finally {
      if (current === generation && request === readId) loading.value = false
    }
  }
  async function submit(): Promise<void> {
    if (!allowed.value || saving.value) return
    if (
      !nickname.value.trim() ||
      nickname.value.length > 32 ||
      /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u.test(nickname.value)
    ) {
      error.value = '昵称须为 1～32 字符，不包含换行或隐藏字符'
      return
    }
    if (submittedText !== nickname.value || !requestId) {
      requestId = newProfileRequestId()
      submittedText = nickname.value
    }
    const current = generation,
      expected = requestId
    saving.value = true
    error.value = ''
    notice.value = ''
    try {
      const result = await submitNickname(expected, submittedText, store.token)
      if (current !== generation) return
      if (result.requestId !== expected || result.kind !== 'NICKNAME')
        throw new ApiError('响应不属于当前申请，请刷新核对', 502)
      notice.value =
        result.status === 'PENDING'
          ? '昵称已提交，审核通过后才会公开。'
          : '已核对原申请，请查看最新状态。'
      requestId = ''
      nickname.value = ''
      await load()
    } catch (e: unknown) {
      if (current === generation)
        error.value =
          e instanceof ApiError && e.status >= 400 && e.status < 500
            ? e.message
            : '结果暂不确定，请先刷新申请状态；重试原内容会核对同一请求。'
    } finally {
      if (current === generation) saving.value = false
    }
  }
  watch(
    [() => store.token, () => store.isLoggedIn, () => store.requiresPasswordChange],
    () => {
      generation++
      readId++
      status.value = null
      nickname.value = ''
      requestId = ''
      submittedText = ''
      saving.value = false
      loading.value = false
      error.value = ''
      notice.value = ''
      if (allowed.value) void load()
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    generation++
    readId++
    status.value = null
    nickname.value = ''
    submittedText = ''
    requestId = ''
  })
  return { allowed, status, nickname, loading, saving, error, notice, load, submit }
}

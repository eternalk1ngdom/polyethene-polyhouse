import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiError } from '../api'
import { useUserStore } from '../stores/useUserStore'
import {
  getReviews,
  getPublished,
  decideReview,
  changePublished,
  type ProfileField,
  type ProfileSubmission,
  type PublishedProfile,
} from '../api/profileModeration'
/** 同一弹窗只处理一个明确目标，界面不能任意混合字段、版本或动作。 */
interface Selection {
  /** 待审或已公开两种目标。 */ type: 'decision' | 'public'
  /** 待审目标。 */ submission?: ProfileSubmission
  /** 公开目标。 */ published?: PublishedProfile
  /** 审核通过/驳回。 */ approve?: boolean
  /** 公开内容保留/下架。 */ action?: 'KEEP' | 'TAKEDOWN'
}
/** 只请求当前可见的审核任务，历史清点不随待审刷新一起加载。 */
export type ReviewSection = 'pending' | 'published'
/** 审核状态与账号管理独立，响应未知只刷新核对，不自动重发危险写操作。 */
export function useProfileReview(section: () => ReviewSection = () => 'pending') {
  const store = useUserStore(),
    allowed = computed(
      () => store.isLoggedIn && !store.requiresPasswordChange && store.user?.role === 'OWNER',
    )
  const kind = ref<ProfileField | 'ALL'>('ALL'),
    publicKind = ref<ProfileField>('AVATAR'),
    unverified = ref(true)
  const page = ref(1),
    publicPage = ref(1),
    items = ref<ProfileSubmission[]>([]),
    published = ref<PublishedProfile[]>([]),
    total = ref('0'),
    publicTotal = ref('0')
  const loading = ref(false),
    busy = ref(false),
    error = ref(''),
    notice = ref(''),
    dialogError = ref(''),
    reason = ref(''),
    selection = ref<Selection | null>(null),
    uncertain = ref(false)
  const hasNext = computed(() => BigInt(page.value * 20) < BigInt(total.value)),
    publicHasNext = computed(() => BigInt(publicPage.value * 20) < BigInt(publicTotal.value))
  let generation = 0,
    readId = 0
  async function load(): Promise<void> {
    if (!allowed.value) return
    const current = generation,
      id = ++readId
    loading.value = true
    error.value = ''
    try {
      // 一个读请求只服务一个可见分区；切换会递增 readId，旧分区响应不会覆盖新列表。
      const currentSection = section()
      const result =
        currentSection === 'pending'
          ? {
              section: 'pending' as const,
              data: await getReviews(
                page.value,
                kind.value === 'ALL' ? '' : kind.value,
                store.token,
              ),
            }
          : {
              section: 'published' as const,
              data: await getPublished(
                publicPage.value,
                publicKind.value,
                unverified.value,
                store.token,
              ),
            }
      if (current !== generation || id !== readId) return
      if (result.section === 'pending') {
        items.value = result.data.items
        total.value = result.data.total
      } else {
        published.value = result.data.items
        publicTotal.value = result.data.total
      }
    } catch (e: unknown) {
      if (current === generation && id === readId) {
        items.value = []
        published.value = []
        total.value = '0'
        publicTotal.value = '0'
        error.value = e instanceof ApiError ? e.message : '审核列表加载失败'
      }
    } finally {
      if (current === generation && id === readId) loading.value = false
    }
  }
  function openDecision(item: ProfileSubmission, approve: boolean): void {
    if (!allowed.value || busy.value) return
    selection.value = { type: 'decision', submission: { ...item }, approve }
    reason.value = ''
    dialogError.value = ''
    uncertain.value = false
  }
  function openPublic(item: PublishedProfile, action: 'KEEP' | 'TAKEDOWN'): void {
    if (!allowed.value || busy.value) return
    selection.value = { type: 'public', published: { ...item }, action }
    reason.value = ''
    dialogError.value = ''
    uncertain.value = false
  }
  function close(): void {
    if (!busy.value) {
      selection.value = null
      reason.value = ''
      dialogError.value = ''
      uncertain.value = false
    }
  }
  async function submit(): Promise<void> {
    if (!allowed.value || busy.value || !selection.value || uncertain.value) return
    if (
      !reason.value.trim() ||
      reason.value.length > 200 ||
      /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u.test(reason.value)
    ) {
      dialogError.value = '请填写 1～200 字原因，不含换行或隐藏字符'
      return
    }
    const current = generation,
      s = selection.value
    busy.value = true
    dialogError.value = ''
    try {
      if (s.type === 'decision' && s.submission && s.approve !== undefined)
        await decideReview(s.submission, s.approve, reason.value, store.token)
      else if (s.type === 'public' && s.published && s.action)
        await changePublished(s.published, s.action, reason.value, store.token)
      else throw new ApiError('操作目标不完整，请重新选择', 400)
      if (current !== generation) return
      selection.value = null
      reason.value = ''
      notice.value = '服务端已确认处理完成，列表已刷新。'
      await load()
    } catch (e: unknown) {
      if (current === generation) {
        uncertain.value = true
        dialogError.value =
          e instanceof ApiError && e.status === 409
            ? '目标已变化或已处理，请关闭并刷新后重新选择。'
            : e instanceof ApiError && e.status >= 400 && e.status < 500
              ? e.message
              : '结果暂不确定，请关闭并刷新核对；不会自动重发本次操作。'
      }
    } finally {
      if (current === generation) busy.value = false
    }
  }
  function next(delta: number, publicList = false): void {
    if (loading.value || busy.value) return
    const p = publicList ? publicPage : page,
      has = publicList ? publicHasNext : hasNext
    if (p.value + delta < 1 || p.value + delta > 10000 || (delta > 0 && !has.value)) return
    p.value += delta
    void load()
  }
  watch(kind, () => {
    page.value = 1
    if (section() === 'pending') void load()
  })
  watch([publicKind, unverified], () => {
    publicPage.value = 1
    if (section() === 'published') void load()
  })
  watch(section, () => {
    // 隐藏列表立即释放；各自筛选/页码保留，返回时重新核对最新服务端事实。
    items.value = []
    published.value = []
    total.value = '0'
    publicTotal.value = '0'
    void load()
  })
  watch(
    [
      () => store.token,
      () => store.user?.id,
      () => store.user?.role,
      () => store.isLoggedIn,
      () => store.requiresPasswordChange,
    ],
    () => {
      generation++
      readId++
      items.value = []
      published.value = []
      selection.value = null
      reason.value = ''
      error.value = ''
      notice.value = ''
      dialogError.value = ''
      busy.value = false
      loading.value = false
      total.value = '0'
      publicTotal.value = '0'
      page.value = 1
      publicPage.value = 1
      uncertain.value = false
    },
    { immediate: true, flush: 'sync' },
  )
  // 身份的私有状态同步清除；读取等待同一轮身份变更完成，避免用新 Token 查询旧身份。
  watch(
    [() => store.token, () => store.user?.id, allowed],
    () => {
      void load()
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    generation++
    readId++
    selection.value = null
    reason.value = ''
    items.value = []
    published.value = []
  })
  return {
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
  }
}

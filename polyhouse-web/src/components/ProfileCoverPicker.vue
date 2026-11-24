<script setup lang="ts">
import { computed, nextTick, ref, shallowRef, watch, onBeforeUnmount, onMounted } from 'vue'
import { Picture } from '@element-plus/icons-vue'
import { ElButton, ElDialog, ElIcon } from 'element-plus'
import {
  coverNames,
  readCovers,
  changeCover,
  type CoverCatalog,
  type CoverKey,
  type CoverItem,
} from '../api/profileCovers'
import { useUserStore } from '../stores/useUserStore'
import { useUserGrowthStore } from '../stores/useUserGrowthStore'
import LocalCoverEditor from './LocalCoverEditor.vue'
import {
  deleteLocalCover,
  enableLocalCover,
  localCoverChannel,
  readLocalCover,
  saveLocalCover,
  type LocalProfileCover,
} from '../utils/localProfileCover'

interface Props {
  accountId: string
}
interface Emits {
  applied: [key: CoverKey]
  local: [images: { desktop: string; mobile: string } | null]
}
const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const user = useUserStore()
const growth = useUserGrowthStore()
const catalog = ref<CoverCatalog>()
const visible = ref(false)
const picked = ref<CoverKey>('window')
const item = computed(() => catalog.value?.covers.find((entry) => entry.key === picked.value))
const confirm = ref(false)
const busy = ref(false)
const ready = ref(false)
const error = ref('')
const localCover = shallowRef<LocalProfileCover | null>(null)
const localError = ref('')
const localBusy = ref(false)
const editing = ref(false)
const editorBusy = ref(false)
const confirmDelete = ref(false)
const editButton = ref<HTMLDivElement>()
const localEnabled = computed(() => localCover.value?.enabled === true)
const difference = computed(
  () => BigInt(catalog.value?.coins ?? '0') - BigInt(item.value?.price ?? 0),
)
const thumbs = import.meta.glob<string>('../assets/profile-*-closeup.webp', {
  eager: true,
  import: 'default',
})
let sequence = 0
let active = true
let localSequence = 0
let channel: BroadcastChannel | undefined
let imageUrls: { desktop: string; mobile: string } | null = null
const accountId = props.accountId
const accountToken = user.token

function isCurrentAccount() {
  return (
    active &&
    user.isLoggedIn &&
    !user.requiresPasswordChange &&
    user.user?.id === accountId &&
    user.token === accountToken
  )
}

function clearImageUrls() {
  if (!imageUrls) return
  URL.revokeObjectURL(imageUrls.desktop)
  URL.revokeObjectURL(imageUrls.mobile)
  imageUrls = null
}

async function loadLocal() {
  const request = ++localSequence
  try {
    const saved = await readLocalCover(accountId)
    if (!isCurrentAccount() || request !== localSequence) return
    clearImageUrls()
    localCover.value = saved
    if (saved?.enabled)
      imageUrls = {
        desktop: URL.createObjectURL(saved.desktop),
        mobile: URL.createObjectURL(saved.mobile),
      }
    emit('local', imageUrls)
    localError.value = ''
  } catch (reason) {
    if (!isCurrentAccount() || request !== localSequence) return
    clearImageUrls()
    localCover.value = null
    emit('local', null)
    localError.value =
      reason instanceof Error && reason.message.startsWith('请关闭')
        ? reason.message
        : '暂时无法读取此浏览器保存的本地图片，请重新打开卡面设置。'
  }
}

function refreshOnFocus() {
  if (!confirm.value) void load()
  void loadLocal()
}
onMounted(() => {
  window.addEventListener('focus', refreshOnFocus)
  if (typeof BroadcastChannel !== 'undefined') {
    channel = new BroadcastChannel(localCoverChannel)
    channel.onmessage = () => {
      void loadLocal()
    }
  }
  void loadLocal()
})
onBeforeUnmount(() => {
  active = false
  sequence++
  channel?.close()
  clearImageUrls()
  window.removeEventListener('focus', refreshOnFocus)
})

watch(visible, (open) => {
  if (!open) {
    editing.value = false
    confirmDelete.value = false
  }
})

function endEditing() {
  editing.value = false
  editorBusy.value = false
  void nextTick(() => editButton.value?.querySelector('button')?.focus())
}

async function saveCustom(cover: LocalProfileCover) {
  if (!isCurrentAccount()) return
  try {
    await saveLocalCover(accountId, cover)
  } catch {
    throw new Error('无法保存本地图片，请检查浏览器的存储空间和权限后重试。')
  }
  if (!isCurrentAccount()) return
  await loadLocal()
  endEditing()
}

async function changeLocal(action: 'toggle' | 'delete') {
  if (localBusy.value || !isCurrentAccount()) return
  localBusy.value = true
  try {
    if (action === 'delete') await deleteLocalCover(accountId)
    else await enableLocalCover(accountId, !localEnabled.value)
    await loadLocal()
    confirmDelete.value = false
    if (action === 'delete')
      await nextTick(() => editButton.value?.querySelector('button')?.focus())
  } catch {
    localError.value =
      action === 'delete'
        ? '无法删除本地图片，请检查浏览器的存储权限后重试。'
        : '无法切换卡面，请检查浏览器的存储权限后重试。'
  } finally {
    localBusy.value = false
  }
}
function accept(data: CoverCatalog) {
  if (data.userId !== user.user?.id) throw new Error('卡面与当前账号不匹配')
  catalog.value = data
  emit('applied', data.equipped)
  ready.value = true
}
async function load(selectCurrent = false) {
  if (busy.value) return
  const seq = ++sequence,
    token = user.token
  ready.value = false
  confirm.value = false
  error.value = ''
  try {
    const data = await readCovers(token)
    if (!active || seq !== sequence || token !== user.token) return
    accept(data)
    if (selectCurrent) picked.value = data.equipped
  } catch {
    if (active && seq === sequence) error.value = '卡面暂时无法读取，请重试'
  }
}
watch(
  () => [user.user?.role, growth.summary?.rewardDate, growth.summary?.level, user.user?.level],
  () => void load(),
  { immediate: true },
)
function open() {
  visible.value = true
  picked.value = catalog.value?.equipped ?? 'window'
  void load(true)
  void loadLocal()
}
function pick(key: CoverKey) {
  picked.value = key
  confirm.value = false
}
function label(entry: CoverItem) {
  if (entry.key === catalog.value?.equipped) return localEnabled.value ? '账号官方卡面' : '使用中'
  if (entry.reason === 'coins') return entry.price + ' 枚硬币解锁'
  if (entry.reason === 'levelRequired') return 'Lv.3 解锁'
  return '可使用'
}
async function submit() {
  const data = catalog.value,
    choice = item.value
  if (!data || !choice || busy.value || !ready.value) return
  const purchase = choice.reason === 'coins'
  if (purchase && !confirm.value) {
    confirm.value = true
    return
  }
  const token = user.token
  busy.value = true
  sequence++
  let reload = false
  try {
    const result = await changeCover(token, choice.key, purchase ? data.quoteVersion : undefined)
    if (!active || user.token !== token) return
    accept(result)
    confirm.value = false
    if (purchase) {
      user.applyCoverBalance(token, result.userId, result.coins)
      await growth.refreshAfterPurchase()
    } else visible.value = false
  } catch {
    if (active && user.token === token) reload = true
  } finally {
    busy.value = false
    if (reload) {
      await load()
      if (active && user.token === token) {
        if (ready.value && catalog.value) {
          user.applyCoverBalance(token, catalog.value.userId, catalog.value.coins)
          await growth.refreshAfterPurchase()
        }
        if (active && user.token === token && ready.value)
          error.value = '请核对最新卡面和余额后再操作'
      }
    }
  }
}
</script>

<template>
  <ElButton class="cover-trigger" @click="open"
    ><ElIcon><Picture /></ElIcon>更换卡面</ElButton
  >
  <ElDialog
    v-model="visible"
    title="更换卡面"
    class="profile-cover-dialog"
    width="min(560px, 92vw)"
    append-to-body
    :close-on-click-modal="!busy && !editorBusy"
    :close-on-press-escape="!busy && !editorBusy"
    :show-close="!busy && !editorBusy"
  >
    <LocalCoverEditor
      v-if="visible && editing"
      :save="saveCustom"
      @cancel="endEditing"
      @busy="editorBusy = $event"
    >
      <template #avatar><slot name="avatar" /></template>
    </LocalCoverEditor>
    <template v-else>
      <slot name="preview" :cover="picked" />
      <div class="choices">
        <button
          v-for="entry in catalog?.covers"
          :key="entry.key"
          type="button"
          :aria-pressed="picked === entry.key"
          :disabled="busy"
          @click="pick(entry.key)"
        >
          <img :src="thumbs['../assets/profile-' + entry.key + '-closeup.webp']" alt="" />
          <strong>{{ coverNames[entry.key] }}</strong
          ><span>{{ label(entry) }}</span>
        </button>
      </div>
      <p v-if="error" role="status">
        {{ error }} <ElButton size="small" :disabled="busy" @click="load()">重试</ElButton>
      </p>
      <template v-if="catalog && item">
        <p v-if="item.reason === 'levelRequired'">
          距离 Lv.3 还差 {{ item.missingExperience }} 经验
        </p>
        <p v-else-if="item.reason === 'coins'">
          余额 {{ catalog.coins }} 枚<span v-if="difference < 0n">，还差 {{ -difference }} 枚</span
          ><span v-else-if="confirm"
            >，本次花费 {{ item.price }} 枚，支付后剩 {{ difference }} 枚</span
          >
        </p>
        <ElButton
          type="primary"
          :loading="busy"
          :disabled="
            !ready ||
            picked === catalog.equipped ||
            item.reason === 'levelRequired' ||
            (item.reason === 'coins' && difference < 0n)
          "
          @click="submit"
        >
          {{
            picked === catalog.equipped
              ? localEnabled
                ? '账号官方卡面'
                : '使用中'
              : item.reason === 'coins'
                ? confirm
                  ? '确认支付'
                  : '解锁这张'
                : localEnabled
                  ? '设为账号官方卡面'
                  : '使用这张'
          }}
        </ElButton>
      </template>
      <section class="local-cover-settings" aria-labelledby="local-cover-heading">
        <h3 id="local-cover-heading">自定义卡面</h3>
        <p>本地图片只在当前浏览器中对你可见。清除网站数据后需重新选择。</p>
        <p v-if="localCover">当前浏览器显示：{{ localEnabled ? '本地图片' : '账号官方卡面' }}</p>
        <p v-if="localError" role="status">{{ localError }}</p>
        <div class="local-actions">
          <div ref="editButton">
            <ElButton :disabled="busy || localBusy" @click="editing = true">{{
              localCover ? '更换本地图片' : '选择本地图片'
            }}</ElButton>
          </div>
          <template v-if="localCover">
            <ElButton :disabled="localBusy" @click="changeLocal('toggle')">{{
              localEnabled ? '切换到官方卡面' : '使用本地图片'
            }}</ElButton>
            <ElButton :disabled="localBusy" @click="confirmDelete = true">删除本地图片</ElButton>
          </template>
        </div>
        <div v-if="confirmDelete && localCover" class="local-confirm">
          <p>删除此浏览器保存的卡面后，将显示账号官方卡面。设备上的原图会保留。</p>
          <div class="local-actions">
            <ElButton :disabled="localBusy" @click="confirmDelete = false">保留图片</ElButton>
            <ElButton type="danger" :loading="localBusy" @click="changeLocal('delete')"
              >确认删除</ElButton
            >
          </div>
        </div>
      </section>
    </template>
  </ElDialog>
</template>

<style scoped>
:global(.profile-cover-dialog) {
  --el-dialog-margin-top: 5vh;
  max-height: 90dvh;
  overflow: auto;
}
.local-cover-settings {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-light);
}
.local-cover-settings h3 {
  margin: 0;
  font-size: 15px;
}
.local-cover-settings p {
  margin: 8px 0;
  line-height: 1.5;
}
.local-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.local-actions :deep(.el-button) {
  margin: 0;
}
.local-confirm {
  margin-top: 12px;
}
.cover-trigger .el-icon {
  margin-right: 7px;
}
.choices {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 22px 0 16px;
}
.choices button {
  background: white;
  color: var(--ph-ink);
  border: 1px solid #ccc;
  border-radius: 9px;
  padding: 4px;
  cursor: pointer;
}
.choices button:hover {
  background: #f4f1fa;
}
.choices button[aria-pressed='true'] {
  border: 2px solid #554279;
  padding: 3px;
}
.choices img {
  width: 100%;
  height: 36px;
  object-fit: cover;
  border-radius: 5px;
}
.choices strong,
.choices span {
  display: block;
  margin: 4px 0;
}
</style>

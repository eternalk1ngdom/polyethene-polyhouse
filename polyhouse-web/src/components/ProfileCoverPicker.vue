<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount, onMounted } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
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
interface Emits {
  applied: [key: CoverKey]
}
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
const difference = computed(
  () => BigInt(catalog.value?.coins ?? '0') - BigInt(item.value?.price ?? 0),
)
const thumbs = import.meta.glob<string>('../assets/profile-*-closeup.webp', {
  eager: true,
  import: 'default',
})
let sequence = 0
let active = true
function refreshOnFocus() {
  if (!confirm.value) void load()
}
onMounted(() => window.addEventListener('focus', refreshOnFocus))
onBeforeUnmount(() => {
  active = false
  sequence++
  window.removeEventListener('focus', refreshOnFocus)
})
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
}
function pick(key: CoverKey) {
  picked.value = key
  confirm.value = false
}
function label(entry: CoverItem) {
  if (entry.key === catalog.value?.equipped) return '使用中'
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
        if (active && user.token === token && ready.value) error.value = '请核对最新卡面和余额后再操作'
      }
    }
  }
}
</script>

<template>
  <ElButton class="cover-trigger" round size="small" @click="open">更换卡面</ElButton>
  <ElDialog
    v-model="visible"
    title="更换卡面"
    width="min(560px, 92vw)"
    append-to-body
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    :show-close="!busy"
  >
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
      <p v-if="item.reason === 'levelRequired'">距离 Lv.3 还差 {{ item.missingExperience }} 经验</p>
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
            ? '使用中'
            : item.reason === 'coins'
              ? confirm
                ? '确认支付'
                : '解锁这张'
              : '使用这张'
        }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.cover-trigger {
  position: absolute;
  top: 12px;
  right: 12px;
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

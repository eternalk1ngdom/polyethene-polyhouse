<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { ElButton, ElIcon } from 'element-plus'
import { Back, Calendar, TopRight } from '@element-plus/icons-vue'
import observatory from '../assets/startide-observatory.webp'
import { isTrendDate } from '../api/trend'
import { starTide } from '../config/starTide'
import { useUserStore } from '../stores/useUserStore'
import { trendTime } from '../features/trend/presentation'
import { useTrendEdition } from '../features/trend/useTrendEdition'
import TrendProjectEntry from '../features/trend/TrendProjectEntry.vue'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const owner = computed(
  () => user.isLoggedIn && user.user?.role === 'OWNER' && !user.requiresPasswordChange,
)
const directory = computed(() => route.name === starTide.name)
const edition = computed(() => (directory.value ? route.query.date : route.params.date))
const {
  report,
  reportError,
  loadingReport,
  noReport,
  loadReport,
  dates,
  cursor,
  dateError,
  loadingDates,
  loadDates,
} = useTrendEdition(edition, directory)
const selectedDate = computed(() => (isTrendDate(edition.value) ? edition.value : ''))
const dateOptions = computed(() =>
  [...new Set([selectedDate.value, ...dates.value].filter(Boolean))].sort().reverse(),
)
const language = computed(() => (typeof route.query.lang === 'string' ? route.query.lang : ''))
const languages = computed(() => {
  const counts = new Map<string, number>()
  for (const item of report.value?.items ?? []) {
    if (item.primaryLanguage)
      counts.set(item.primaryLanguage, (counts.get(item.primaryLanguage) ?? 0) + 1)
  }
  return [...counts].sort(([a], [b]) => a.localeCompare(b))
})
const entries = computed(
  () =>
    report.value?.items.filter(
      (item) => !language.value || item.primaryLanguage === language.value,
    ) ?? [],
)
const repoName = computed(() => `${route.params.owner}/${route.params.repo}`)
const project = computed(() =>
  report.value?.items.find((item) => item.fullName.toLowerCase() === repoName.value.toLowerCase()),
)
const listHeading = ref<HTMLElement>()
const detailPanel = ref<HTMLElement>()

function directoryLocation(date: string, lang: string, at?: string) {
  return { name: starTide.name, query: { date: date || undefined, lang: lang || undefined, at } }
}
const returnTo = computed(() =>
  directoryLocation(selectedDate.value, language.value, project.value?.fullName ?? repoName.value),
)
function detailLocation(name: string) {
  const [owner, repo] = name.split('/')
  return {
    name: 'star-tide-project',
    params: { date: report.value?.periodKey, owner, repo },
    query: { lang: language.value || undefined },
  }
}
function ordinaryClick(event: MouseEvent) {
  return event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey
}
async function openProject(event: MouseEvent, name: string) {
  if (!ordinaryClick(event) || !report.value) return
  event.preventDefault()
  const destination = detailLocation(name)
  // 先固定实际期号，跨午夜或刷新详情后仍可后退到同一期与原位置。
  const origin = directoryLocation(report.value.periodKey, language.value, name)
  await router.replace({ ...origin, state: { tideScroll: window.scrollY }, force: true })
  await router.push({ ...destination, state: { tideOrigin: router.currentRoute.value.fullPath } })
}
function goBack(event: MouseEvent) {
  if (!ordinaryClick(event)) return
  event.preventDefault()
  const origin = window.history.state?.tideOrigin
  if (
    project.value &&
    origin === router.resolve(returnTo.value).fullPath &&
    origin === window.history.state?.back
  )
    router.back()
  else void router.replace(returnTo.value)
}
function selectDate(event: Event) {
  void router.push(directoryLocation((event.target as HTMLSelectElement).value, ''))
}
function selectLanguage(event: Event) {
  void router.replace(
    directoryLocation(selectedDate.value, (event.target as HTMLSelectElement).value),
  )
}

// 仅在页面或日报就绪时恢复焦点，筛选和更早日期加载不抢占键盘操作。
watch(
  [directory, report, repoName],
  async () => {
    await nextTick()
    if (!report.value) return
    if (!directory.value) {
      detailPanel.value?.querySelector('h1')?.focus({ preventScroll: true })
      return
    }
    if (typeof route.query.at !== 'string') return
    const target = document.getElementById('repo-' + route.query.at)
    const element = target ?? listHeading.value
    element?.focus({ preventScroll: true })
    const offset = window.history.state?.tideScroll
    if (target && typeof offset === 'number') window.scrollTo(0, offset)
    else element?.scrollIntoView({ block: 'center' })
  },
  { immediate: true, flush: 'post' },
)
</script>

<template>
  <div class="reader">
    <div class="sheet">
      <template v-if="directory">
        <header class="cover">
          <div class="cover-copy">
            <p class="signature">StarTide <span>／ 开源项目日报</span></p>
            <h1>星潮</h1>
            <p class="issue-meta" v-if="report">
              <strong>{{ report.periodKey }}</strong
              ><span>本期 {{ report.items.length }} 个项目</span>
            </p>
          </div>
          <img :src="observatory" alt="" width="1440" height="480" class="observatory" />
        </header>
        <div class="toolbar">
          <label class="control"
            ><ElIcon aria-hidden="true"><Calendar /></ElIcon>日报日期
            <select id="trend-date" :value="selectedDate" @change="selectDate">
              <option value="">最新一期</option>
              <option v-for="day in dateOptions" :key="day" :value="day">{{ day }}</option>
            </select>
          </label>
          <ElButton v-if="cursor" :loading="loadingDates" @click="loadDates(true)"
            >更早日期</ElButton
          >
          <label class="control" v-if="languages.length || language"
            >编程语言
            <select id="trend-language" :value="language" @change="selectLanguage">
              <option value="">全部语言</option>
              <option
                v-if="language && !languages.some(([name]) => name === language)"
                :value="language"
              >
                {{ language }} · 0
              </option>
              <option v-for="[name, count] in languages" :key="name" :value="name">
                {{ name }} · {{ count }}
              </option>
            </select>
          </label>
          <RouterLink v-if="owner" :to="starTide.ownerRoute" class="utility owner-link"
            >星潮发布台</RouterLink
          >
        </div>
        <p v-if="dateError" class="notice" role="status">
          历史日期暂时无法读取。<ElButton text @click="loadDates()">重试历史</ElButton>
        </p>
      </template>
      <a v-else class="utility back" :href="router.resolve(returnTo).href" @click="goBack"
        ><ElIcon aria-hidden="true"><Back /></ElIcon> 返回当期榜单</a
      >
      <div v-if="loadingReport" class="state" role="status">正在读取日报…</div>
      <div v-else-if="reportError" class="state" role="alert">
        <p>{{ reportError }}</p>
        <ElButton @click="loadReport">重新读取</ElButton>
        <RouterLink :to="starTide.route">查看最新一期</RouterLink>
      </div>
      <div v-else-if="noReport" class="state" role="status">
        <h2>{{ selectedDate ? '该日没有已发布的日报' : '暂无已发布的日报' }}</h2>
        <RouterLink v-if="selectedDate" :to="starTide.route">返回最新一期</RouterLink>
      </div>
      <template v-else-if="report && directory">
        <div class="section-head">
          <div>
            <h2 ref="listHeading" tabindex="-1">项目日榜</h2>
            <p class="meta">采集于 {{ trendTime(report.capturedAt) }}（北京时间）</p>
          </div>
          <a class="utility" :href="report.sourceUrl" target="_blank" rel="noopener noreferrer"
            >GitHub 日榜 <ElIcon aria-hidden="true"><TopRight /></ElIcon
          ></a>
        </div>
        <p v-if="!report.insightComplete" class="notice">
          部分解读暂缺或沿用旧版，榜单资料仍可阅读。
        </p>
        <div class="columns">
          <span role="status">{{
            language
              ? `筛选后 ${entries.length} / ${report.items.length} 条 · 保留来源排名`
              : '排名 ／ 项目'
          }}</span
          ><span>本期新增星标</span>
        </div>
        <TrendProjectEntry
          v-for="item in entries"
          :key="item.fullName"
          :project="item"
          :href="router.resolve(detailLocation(item.fullName)).href"
          @open="openProject"
        />
        <p v-if="!entries.length" class="state">
          本期没有符合条件的项目。
          <RouterLink :to="directoryLocation(selectedDate, '')">查看全部</RouterLink>
        </p>
      </template>
      <section v-else-if="report" ref="detailPanel" aria-label="当期项目详情">
        <RouterView v-if="project" :project="project" :report="report" />
        <div v-else class="state">
          <h1 tabindex="-1">该项目未收录在这期当前版本</h1>
          <p>可返回当期榜单查看已发布的项目。</p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.reader {
  background: #fbfaf6;
  color: #192743;
  min-height: 65vh;
  padding: 24px 0 64px;
}
.sheet {
  width: min(1110px, calc(100% - 48px));
  margin: auto;
}
.cover {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  border-radius: 18px;
  background: linear-gradient(110deg, #deebf2 28%, #d8eeed 73%, #f9e8cb);
  min-height: 175px;
  display: flex;
  align-items: center;
}
.cover-copy {
  padding: 20px clamp(20px, 4vw, 52px);
}
.signature {
  font:
    italic 20px Georgia,
    serif;
  margin: 0;
}
.signature span {
  font: 600 12px var(--ph-font);
  letter-spacing: 0.08em;
}
.cover h1 {
  font:
    700 clamp(46px, 6vw, 70px)/1 Georgia,
    'Songti SC',
    SimSun,
    serif;
  letter-spacing: 0.04em;
  margin: 9px 0 14px;
}
.observatory {
  position: absolute;
  z-index: -1;
  right: 0;
  bottom: 0;
  width: 65%;
  height: 100%;
  object-fit: contain;
  object-position: right bottom;
}
.issue-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 5px 14px;
  font-size: 13px;
  margin: 0;
}
.toolbar,
.control,
.utility,
.section-head,
.columns {
  display: flex;
  align-items: center;
}
.toolbar {
  gap: 8px 18px;
  flex-wrap: wrap;
  padding: 14px 0;
  border-bottom: 1px solid #c8d5db;
}
.control {
  gap: 8px;
  font-size: 13px;
  font-weight: 650;
  max-width: 100%;
}
select {
  min-height: 44px;
  max-width: 100%;
  min-width: 0;
  padding: 6px 9px;
  border: 1px solid #9daeb8;
  background: white;
  color: #213951;
  border-radius: 9px;
}
select:focus-visible {
  outline: none;
  border-color: #154e78;
  box-shadow: inset 0 0 0 1px #154e78;
}
.utility {
  width: fit-content;
  gap: 5px;
  min-height: 44px;
  color: #28566f;
  font-size: 13px;
  font-weight: 650;
}
.utility:hover,
.state a {
  text-decoration: underline;
  text-underline-offset: 4px;
}
.owner-link {
  margin-left: auto;
}
.section-head,
.columns {
  justify-content: space-between;
  gap: 14px;
}
.section-head {
  margin: 22px 0 9px;
}
.section-head h2 {
  margin: 0;
  font-size: 25px;
  letter-spacing: -0.04em;
}
.meta,
.notice,
.columns {
  font-size: 12px;
  color: #475c6b;
}
.meta {
  margin: 4px 0 0;
}
.notice {
  margin: 6px 0 12px;
}
.columns {
  border-bottom: 2px solid #273b50;
  padding: 8px 0;
  font-weight: 650;
}
.state {
  padding: 28px 0;
}
.state h1 {
  font-size: 24px;
}
@media (max-width: 620px) {
  .reader {
    padding-top: 12px;
  }
  .sheet {
    width: calc(100% - 28px);
  }
  .cover {
    min-height: 148px;
  }
  .cover-copy {
    padding: 16px;
  }
  .observatory {
    /* 窄屏裁去左侧星轨，保住圆顶和镜筒的比例，给左侧期号留出空间。 */
    width: 50%;
    height: min(75%, 25vw);
    object-fit: cover;
    mask-image: linear-gradient(to right, transparent, black 8%);
  }
  .issue-meta {
    flex-direction: column;
    gap: 2px;
  }
  .toolbar {
    gap: 6px 12px;
  }
  .section-head {
    margin-top: 16px;
  }
}
</style>

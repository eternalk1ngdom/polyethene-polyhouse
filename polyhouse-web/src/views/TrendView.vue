<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { ElButton, ElIcon } from 'element-plus'
import { Back, Calendar, TopRight } from '@element-plus/icons-vue'
import tideAtlas from '../assets/startide-tide-atlas.webp'
import { isTrendDate } from '../api/trend'
import { starTide } from '../config/starTide'
import { useUserStore } from '../stores/useUserStore'
import {
  categoryCounts,
  categoryLabel,
  isTrendCategoryFilter,
  projectCategory,
  siblingProjects,
  trendCategoryKeys,
  trendTime,
  UNCATEGORIZED,
  type TrendCategoryFilter,
} from '../features/trend/presentation'
import { useTrendEdition } from '../features/trend/useTrendEdition'
import TrendProjectEntry from '../features/trend/TrendProjectEntry.vue'
import TrendSiblingNav from '../features/trend/TrendSiblingNav.vue'
import TrendSkeleton from '../features/trend/TrendSkeleton.vue'
import TrendWindowOverview from '../features/trend/TrendWindowOverview.vue'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const owner = computed(
  () => user.isLoggedIn && user.user?.role === 'OWNER' && !user.requiresPasswordChange,
)
const directory = computed(() => route.name === starTide.name)
const isWindowView = computed(() => directory.value && route.query.view === 'window')
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
  windowStats,
  windowBriefing,
  windowError,
  loadingWindow,
  loadWindow,
} = useTrendEdition(edition, directory, isWindowView)
const selectedDate = computed(() => (isTrendDate(edition.value) ? edition.value : ''))
const dateOptions = computed(() =>
  [...new Set([selectedDate.value, ...dates.value].filter(Boolean))].sort().reverse(),
)
const language = computed(() => (typeof route.query.lang === 'string' ? route.query.lang : ''))
const category = computed<TrendCategoryFilter | ''>(() =>
  isTrendCategoryFilter(route.query.cat) ? route.query.cat : '',
)
const languages = computed(() => {
  const counts = new Map<string, number>()
  for (const item of report.value?.items ?? []) {
    if (item.primaryLanguage)
      counts.set(item.primaryLanguage, (counts.get(item.primaryLanguage) ?? 0) + 1)
  }
  return [...counts].sort(([a], [b]) => a.localeCompare(b))
})
const categoryOptions = computed(() => {
  const counts = categoryCounts((report.value?.items ?? []).map((item) => item.insight?.category))
  const list: { key: TrendCategoryFilter; label: string; count: number }[] = trendCategoryKeys.map(
    (key) => ({
      key,
      label: categoryLabel(key),
      count: counts.get(key) ?? 0,
    }),
  )
  const uncategorizedCount = counts.get(UNCATEGORIZED) ?? 0
  // 只有存在无解读项目或网址显式指定未分类时才列出该项，避免常规完整期号多出无用选项。
  if (uncategorizedCount > 0 || category.value === UNCATEGORIZED) {
    list.push({
      key: UNCATEGORIZED,
      label: categoryLabel(null),
      count: uncategorizedCount,
    })
  }
  return list
})
const hasFilter = computed(() => Boolean(language.value || category.value))
const entries = computed(
  () =>
    report.value?.items.filter(
      (item) =>
        (!language.value || item.primaryLanguage === language.value) &&
        (!category.value || projectCategory(item.insight?.category) === category.value),
    ) ?? [],
)
const repoName = computed(() => `${route.params.owner}/${route.params.repo}`)
const project = computed(() =>
  report.value?.items.find((item) => item.fullName.toLowerCase() === repoName.value.toLowerCase()),
)
const listHeading = ref<HTMLElement>()
const detailPanel = ref<HTMLElement>()

function directoryLocation(date: string, lang: string, cat = '', at?: string, view?: 'window') {
  return {
    name: starTide.name,
    query: {
      date: date || undefined,
      lang: view === 'window' ? undefined : lang || undefined,
      cat: view === 'window' ? undefined : cat || undefined,
      view: view === 'window' ? 'window' : undefined,
      at,
    },
  }
}
const returnView = computed<'window' | undefined>(() =>
  route.query.view === 'window' ? 'window' : undefined,
)
const inWindowContext = computed(() => isWindowView.value || returnView.value === 'window')
const returnTo = computed(() =>
  directoryLocation(
    selectedDate.value,
    language.value,
    category.value,
    project.value?.fullName ?? repoName.value,
    returnView.value,
  ),
)
const neighbors = computed(() => {
  if (!report.value || !project.value) return null
  // 从近 7 日风向进入时沿整期排名连读；从日榜进入时沿当前筛选结果连读，不在结果内则自动退回整期。
  const list = inWindowContext.value ? report.value.items : entries.value
  return siblingProjects(list, report.value.items, project.value.fullName)
})
const neighborScope = computed(() => {
  if (!neighbors.value?.filtered) return '按本期排名'
  const parts: string[] = []
  if (language.value) parts.push(language.value)
  if (category.value) {
    parts.push(categoryLabel(category.value === UNCATEGORIZED ? null : category.value))
  }
  return `按 ${parts.join(' · ')} 筛选`
})
function detailLocation(name: string) {
  const [owner, repo] = name.split('/')
  return {
    name: 'star-tide-project',
    params: { date: report.value?.periodKey, owner, repo },
    query: {
      lang: inWindowContext.value ? undefined : language.value || undefined,
      cat: inWindowContext.value ? undefined : category.value || undefined,
      view: inWindowContext.value ? 'window' : undefined,
    },
  }
}
function ordinaryClick(event: MouseEvent) {
  return event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey
}
async function openProject(event: MouseEvent, name: string) {
  if (!ordinaryClick(event) || !report.value) return
  event.preventDefault()
  const destination = detailLocation(name)
  // 先固定实际期号与当前视图，跨午夜或刷新详情后仍可后退到同一期与原位置。
  const origin = directoryLocation(
    report.value.periodKey,
    language.value,
    category.value,
    name,
    isWindowView.value ? 'window' : undefined,
  )
  await router.replace({ ...origin, state: { tideScroll: window.scrollY }, force: true })
  await router.push({ ...destination, state: { tideOrigin: router.currentRoute.value.fullPath } })
}
/** 详情页连续阅读采用 replace，不堆叠多条历史；点“返回当期榜单”会定位到最后读的那一篇。 */
async function openSibling(event: MouseEvent, name: string) {
  if (!ordinaryClick(event) || !report.value) return
  event.preventDefault()
  await router.replace(detailLocation(name))
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
function selectView(view: 'daily' | 'window') {
  if ((view === 'window') === isWindowView.value) return
  void router.replace(
    directoryLocation(
      selectedDate.value,
      language.value,
      category.value,
      undefined,
      view === 'window' ? 'window' : undefined,
    ),
  )
}
function selectDate(event: Event) {
  void router.push(
    directoryLocation(
      (event.target as HTMLSelectElement).value,
      '',
      '',
      undefined,
      isWindowView.value ? 'window' : undefined,
    ),
  )
}
function selectLanguage(event: Event) {
  void router.replace(
    directoryLocation(
      selectedDate.value,
      (event.target as HTMLSelectElement).value,
      category.value,
    ),
  )
}
function selectCategory(event: Event) {
  void router.replace(
    directoryLocation(
      selectedDate.value,
      language.value,
      (event.target as HTMLSelectElement).value,
    ),
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
          <img :src="tideAtlas" alt="" width="2800" height="560" />
          <h1>星潮 <em lang="en">StarTide</em></h1>
        </header>
        <div class="toolbar">
          <div class="view-switch" role="group" aria-label="星潮阅读视图">
            <button
              type="button"
              class="view-tab"
              :class="{ active: !isWindowView }"
              :aria-pressed="!isWindowView"
              @click="selectView('daily')"
            >
              开源日报
            </button>
            <button
              type="button"
              class="view-tab"
              :class="{ active: isWindowView }"
              :aria-pressed="isWindowView"
              @click="selectView('window')"
            >
              近 7 日风向
            </button>
          </div>
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
          <label class="control" v-if="!isWindowView && (languages.length || language)"
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
          <label class="control" v-if="!isWindowView && (report?.items.length || category)"
            >技术分类
            <select id="trend-category" :value="category" @change="selectCategory">
              <option value="">全部分类</option>
              <option
                v-for="item in categoryOptions"
                :key="item.key"
                :value="item.key"
                :disabled="item.count === 0 && item.key !== category"
              >
                {{ item.label }} · {{ item.count }}
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
        ><ElIcon aria-hidden="true"><Back /></ElIcon>
        {{ returnView === 'window' ? '返回近 7 日风向' : '返回当期榜单' }}</a
      >
      <TrendSkeleton v-if="loadingReport" :variant="directory ? 'list' : 'detail'" />
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
        <TrendWindowOverview
          v-if="isWindowView"
          :stats="windowStats"
          :briefing="windowBriefing"
          :loading="loadingWindow"
          :error="windowError"
          :resolve-detail-href="(name) => router.resolve(detailLocation(name)).href"
          @retry="loadWindow()"
          @open-project="openProject"
        />
        <template v-else>
          <div class="section-head">
            <h2 ref="listHeading" tabindex="-1">开源项目日报</h2>
            <p class="meta">
              <time :datetime="report.periodKey">{{ report.periodKey }}</time>
              <span>本期 {{ report.items.length }} 个项目</span>
              <span>采集于 {{ trendTime(report.capturedAt) }}（北京时间）</span>
            </p>
            <a class="utility" :href="report.sourceUrl" target="_blank" rel="noopener noreferrer"
              >GitHub 日榜 <ElIcon aria-hidden="true"><TopRight /></ElIcon
            ></a>
          </div>
          <p v-if="!report.insightComplete" class="notice">
            部分解读暂缺或沿用旧版，榜单资料仍可阅读。
          </p>
          <div class="columns">
            <span role="status">{{
              hasFilter
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
            <RouterLink :to="directoryLocation(selectedDate, '', '')">查看全部</RouterLink>
          </p>
        </template>
      </template>
      <section v-else-if="report" ref="detailPanel" aria-label="当期项目详情">
        <template v-if="project">
          <RouterView :project="project" :report="report" />
          <TrendSiblingNav
            v-if="neighbors"
            :previous="
              neighbors.previous
                ? {
                    project: neighbors.previous,
                    href: router.resolve(detailLocation(neighbors.previous.fullName)).href,
                  }
                : null
            "
            :next="
              neighbors.next
                ? {
                    project: neighbors.next,
                    href: router.resolve(detailLocation(neighbors.next.fullName)).href,
                  }
                : null
            "
            :position="neighbors.position"
            :total="neighbors.total"
            :scope="neighborScope"
            @open="openSibling"
          />
        </template>
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
  background: var(--st-surface);
  color: var(--st-ink);
  min-height: 65vh;
  padding: 24px 0 64px;
}
.sheet {
  width: min(1110px, calc(100% - 48px));
  margin: auto;
}
.cover {
  --scene-height: clamp(92px, min(20vw, 24vh), 224px);
  position: relative;
  isolation: isolate;
  display: grid;
  align-items: center;
  width: 100%;
  min-height: var(--scene-height);
  overflow: hidden;
  border-radius: 14px;
  background: var(--st-sky);
}
.cover img {
  position: absolute;
  inset: 0;
  z-index: -1;
  width: 100%;
  height: 100%;
  /* 5:1 全景原画锚定右下角：宽屏无缝铺满，窄屏自然收起左侧余白而完整保留右侧星月山海。 */
  object-fit: cover;
  object-position: right bottom;
}
.cover h1 {
  margin: 0;
  padding-inline-start: clamp(16px, 3.5vw, 38px);
  display: flex;
  align-items: baseline;
  gap: 4px 16px;
  color: #142f46;
  font:
    700 clamp(36px, 5vw, 62px)/1.1 'Songti SC',
    'STSong',
    'Noto Serif SC',
    Georgia,
    serif;
}
.cover h1 em {
  font:
    italic 400 clamp(18px, 2.3vw, 28px)/1 Georgia,
    serif;
}
.toolbar,
.control,
.utility,
.columns {
  display: flex;
  align-items: center;
}
.toolbar {
  gap: 10px 18px;
  flex-wrap: wrap;
  padding: 16px 0;
  border-bottom: 1px solid #dce6ea;
}
.view-switch {
  display: inline-flex;
  align-items: center;
  padding: 3px;
  gap: 2px;
  border: 1px solid #cad8de;
  border-radius: 12px;
  background: #f0f5f7;
  max-width: 100%;
}
.view-tab {
  min-height: 44px;
  padding: 0 15px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: #3a5261;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
}
.view-tab:hover {
  background: #e4eef1;
}
.view-tab:active {
  border-color: #154e78;
}
.view-tab.active {
  background: #ffffff;
  border-color: #c2d2da;
  color: #142f46;
  box-shadow: 0 1px 3px rgba(20, 47, 70, 0.08);
}
.view-tab:focus-visible {
  outline: none;
  border-color: #154e78;
  box-shadow: inset 0 0 0 1px #154e78;
}
.owner-link {
  margin-inline-start: auto;
  padding: 0 14px;
  border: 1px solid #cddbe0;
  border-radius: 999px;
  background: #f8fbfc;
}
.owner-link:hover {
  background: #edf5f7;
  border-color: #9cbccf;
  text-decoration: none;
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
  padding: 6px 10px;
  border: 1px solid #cad8de;
  background: white;
  color: #213951;
  border-radius: 10px;
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
  color: var(--st-link);
  font-size: 13px;
  font-weight: 650;
}
.utility:hover,
.state a {
  text-decoration: underline;
  text-underline-offset: 4px;
}
.columns {
  justify-content: space-between;
  gap: 14px;
}
.section-head {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  column-gap: 12px;
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
  color: var(--st-muted);
}
.meta {
  grid-column: 1 / -1;
  display: flex;
  flex-wrap: wrap;
  gap: 3px 14px;
  margin: 0;
  font-variant-numeric: tabular-nums;
}
.meta time {
  font-weight: 650;
}
.section-head > .utility {
  grid-area: 1 / 2;
}
.notice {
  margin: 6px 0 12px;
}
.columns {
  border-bottom: 1px solid #cfdde2;
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
  .cover h1 {
    flex-direction: column;
    gap: 1px;
    padding-bottom: 6px;
  }
  .toolbar {
    gap: 6px 12px;
  }
  .section-head {
    margin-top: 16px;
  }
}
</style>

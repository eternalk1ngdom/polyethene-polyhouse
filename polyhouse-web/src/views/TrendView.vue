<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElButton, ElIcon } from 'element-plus'
import { TopRight } from '@element-plus/icons-vue'
import { ApiError } from '../api/index'
import { getTrendPeriods, getTrendReport, isTrendDate, type TrendReport } from '../api/trend'
import { starTide } from '../config/starTide'
import TrendProjectEntry from '../features/trend/TrendProjectEntry.vue'
import { trendTime } from '../features/trend/presentation'
import { useUserStore } from '../stores/useUserStore'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const owner = computed(
  () => user.isLoggedIn && user.user?.role === 'OWNER' && !user.requiresPasswordChange,
)
const report = ref<TrendReport | null>(null)
const dates = ref<string[]>([])
const cursor = ref<string | null>(null)
const language = ref('')
const loadingReport = ref(false)
const loadingDates = ref(false)
const dateError = ref(false)
const reportError = ref('')
const noReport = ref(false)
const reportBlockedUntil = ref(0)
const dateBlockedUntil = ref(0)
const selectedDate = computed(() => (isTrendDate(route.query.date) ? route.query.date : ''))
const dateOptions = computed(() =>
  [...new Set([...(selectedDate.value ? [selectedDate.value] : []), ...dates.value])]
    .sort()
    .reverse(),
)
const languages = computed(() =>
  [
    ...new Set(
      report.value?.items.flatMap((item) => (item.primaryLanguage ? [item.primaryLanguage] : [])) ??
        [],
    ),
  ].sort(),
)
const entries = computed(
  () =>
    report.value?.items.filter(
      (item) => !language.value || item.primaryLanguage === language.value,
    ) ?? [],
)
let reportRequest: AbortController | undefined
let datesRequest: AbortController | undefined

function selectDate(event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  void router.push({ name: starTide.name, query: value ? { date: value } : {} })
}

async function loadReport(): Promise<void> {
  reportRequest?.abort()
  const request = new AbortController()
  reportRequest = request
  const raw = route.query.date
  report.value = null
  reportError.value = ''
  noReport.value = false
  language.value = ''
  loadingReport.value = false
  if (raw !== undefined && !isTrendDate(raw)) {
    reportError.value = '日期格式无效，请重新选择日报期次。'
    return
  }
  if (Date.now() < reportBlockedUntil.value) {
    reportError.value = '请求过于频繁，请稍后重试。'
    return
  }
  loadingReport.value = true
  try {
    const result = await getTrendReport(selectedDate.value || undefined, request.signal)
    if (!request.signal.aborted) report.value = result
  } catch (failure: unknown) {
    if (request.signal.aborted) return
    if (failure instanceof ApiError && failure.status === 404) noReport.value = true
    else if (failure instanceof ApiError && failure.status === 429) {
      reportBlockedUntil.value = Date.now() + (failure.retryAfterSeconds ?? 2) * 1000
      reportError.value = '请求过于频繁，请稍后重试。'
    } else reportError.value = '暂时无法读取日报，请稍后重试。'
  } finally {
    if (!request.signal.aborted) loadingReport.value = false
  }
}

async function loadDates(append = false): Promise<void> {
  if (loadingDates.value || Date.now() < dateBlockedUntil.value) return
  const before = append ? cursor.value : null
  if (append && !before) return
  const request = new AbortController()
  datesRequest = request
  loadingDates.value = true
  dateError.value = false
  try {
    const result = await getTrendPeriods(before ?? undefined, request.signal)
    if (request.signal.aborted) return
    dates.value = append ? [...new Set([...dates.value, ...result.items])] : result.items
    cursor.value = result.nextBefore
  } catch (failure: unknown) {
    if (request.signal.aborted) return
    dateError.value = true
    if (failure instanceof ApiError && failure.status === 429)
      dateBlockedUntil.value = Date.now() + (failure.retryAfterSeconds ?? 2) * 1000
  } finally {
    if (!request.signal.aborted) loadingDates.value = false
  }
}

watch(
  () => route.query.date,
  () => {
    void loadReport()
  },
  { immediate: true },
)
onMounted(() => {
  void loadDates()
})
onScopeDispose(() => {
  reportRequest?.abort()
  datesRequest?.abort()
})
</script>

<template>
  <div class="trend-page">
    <header class="masthead">
      <div class="masthead-inner">
        <div>
          <p class="kicker">{{ starTide.subtitle }} <span>／</span> {{ starTide.tag }}</p>
          <h1>{{ starTide.title }}<span>.</span></h1>
          <p>{{ starTide.description }}</p>
        </div>
        <RouterLink v-if="owner" :to="starTide.ownerRoute" class="owner-link"
          >屋主工作台</RouterLink
        >
      </div>
    </header>
    <div class="issue">
      <div class="controls">
        <div class="control-field">
          <label for="trend-date">日报日期</label>
          <select id="trend-date" :value="selectedDate" @change="selectDate">
            <option value="">最新一期</option>
            <option v-for="day in dateOptions" :key="day" :value="day">{{ day }}</option>
          </select>
        </div>
        <ElButton v-if="cursor" :loading="loadingDates" @click="loadDates(true)">更早日期</ElButton>
        <div v-if="languages.length" class="control-field">
          <label for="trend-language">编程语言</label>
          <select id="trend-language" v-model="language">
            <option value="">全部语言</option>
            <option v-for="item in languages" :key="item" :value="item">{{ item }}</option>
          </select>
        </div>
      </div>
      <p v-if="dateError" class="notice" role="status">
        历史日期暂时无法读取。<ElButton text @click="loadDates()">重试历史</ElButton>
      </p>
      <div v-if="loadingReport" class="reader-state" role="status">正在读取日报…</div>
      <div v-else-if="reportError" class="reader-state" role="alert">
        <p>{{ reportError }}</p>
        <ElButton @click="loadReport">重新读取</ElButton>
      </div>
      <div v-else-if="noReport" class="reader-state" role="status">
        <h2>{{ selectedDate ? '该日没有已发布的日报' : '暂无已发布的日报' }}</h2>
        <RouterLink v-if="selectedDate" :to="starTide.route">返回最新一期</RouterLink>
      </div>
      <template v-else-if="report">
        <div class="issue-meta">
          <div>
            <strong>{{ report.periodKey }}</strong
            ><span> 采集 {{ trendTime(report.capturedAt) }}（北京时间）</span>
          </div>
          <a :href="report.sourceUrl" target="_blank" rel="noopener noreferrer"
            >GitHub 日榜来源 <ElIcon aria-hidden="true"><TopRight /></ElIcon
          ></a>
        </div>
        <p v-if="!report.insightComplete" class="notice">
          部分项目的 AI 解读暂缺或沿用旧版，GitHub 榜单资料仍可查看。
        </p>
        <p class="results" role="status">
          {{
            language
              ? `筛选后 ${entries.length} 条 · 保留来源排名`
              : `本期 ${entries.length} 个项目`
          }}
        </p>
        <div v-if="!entries.length" class="reader-state">本期没有符合条件的项目。</div>
        <TrendProjectEntry
          v-for="project in entries"
          :key="`${report.reportId}:${project.fullName}`"
          :project="project"
        />
      </template>
    </div>
  </div>
</template>

<style scoped>
.trend-page {
  color: #251e32;
  background: #faf8f6;
  min-width: 0;
}
.masthead {
  color: #fff8fc;
  background: radial-gradient(ellipse at 86% 10%, #493457, transparent 57%), #262437;
}
.masthead-inner,
.issue {
  width: min(1100px, calc(100% - 48px));
  margin: auto;
}
.masthead-inner {
  min-height: 224px;
  display: flex;
  align-items: end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 14px;
  padding-block: clamp(24px, 6vw, 46px);
}
h1,
h2,
p {
  margin: 0;
}
.masthead h1 {
  margin-block: 3px 8px;
  font:
    700 clamp(46px, 8vw, 78px)/1.05 Georgia,
    'Songti SC',
    SimSun,
    serif;
  letter-spacing: 0.06em;
}
.masthead h1 span {
  color: #f2a3c6;
}
.kicker {
  color: #efd3e2;
  font-size: 13px;
  letter-spacing: 0.13em;
}
.kicker span {
  margin-inline: 4px;
}
.owner-link {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  padding: 6px 14px;
  border: 1px solid #c9a8c1;
  border-radius: 8px;
  color: #fff8fb;
}
.owner-link:hover,
.owner-link:focus-visible {
  background: #493754;
}
.issue {
  padding-block: 20px 42px;
}
.controls,
.issue-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
}
.controls label {
  font-size: 13px;
  font-weight: 650;
  white-space: nowrap;
}
.control-field {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  max-width: 100%;
}
.controls select {
  color: #2b2336;
  min-height: 44px;
  max-width: 100%;
  min-width: 0;
  border: 1px solid #b9afc1;
  border-radius: 8px;
  background: #fff;
  padding: 7px 10px;
  font: inherit;
}
.controls select:focus-visible {
  border-color: #85345d;
  outline: 2px solid #85345d;
  outline-offset: 2px;
}
.issue-meta {
  margin-top: 13px;
  justify-content: space-between;
  font-size: 13px;
}
.issue-meta strong {
  font-variant-numeric: tabular-nums;
}
.issue-meta span,
.results,
.notice {
  color: #574d61;
}
.issue-meta a,
.reader-state a {
  color: #8a2955;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.notice {
  margin-top: 10px;
  font-size: 13px;
}
.results {
  margin-block: 14px 5px;
  font-size: 13px;
}
.reader-state {
  padding: 32px 0;
}
.reader-state h2 {
  font-size: 20px;
}
.reader-state .el-button {
  margin-block-start: 8px;
}
@media (max-width: 520px) {
  .masthead-inner,
  .issue {
    width: calc(100% - 30px);
  }
  .masthead-inner {
    min-height: 174px;
  }
  .controls {
    gap: 7px;
  }
}
</style>

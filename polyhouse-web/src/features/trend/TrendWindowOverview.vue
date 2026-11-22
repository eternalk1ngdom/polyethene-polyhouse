<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElIcon } from 'element-plus'
import { StarFilled, TopRight } from '@element-plus/icons-vue'
import type { TrendWindowCategoryStat, TrendWindowSampleRepo, TrendWindowStats } from '../../api/trend'
import {
  categoryLabel,
  shortTrendDate,
  trendDeltaPercent,
  trendMedianNumber,
  trendNumber,
  trendPercent,
  trendTime,
} from './presentation'

interface Props {
  /** 7 日确定性统计数据，加载中或失败时为 null。 */
  stats: TrendWindowStats | null
  /** 正在请求近 7 日统计。 */
  loading: boolean
  /** 加载失败提示。 */
  error: string
  /** 为锚点当日在榜的仓库生成站内详情链接，保持新标签页与键盘访问自然工作。 */
  resolveDetailHref: (fullName: string) => string
}

interface Emits {
  retry: []
  openProject: [event: MouseEvent, fullName: string]
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const DEFAULT_SAMPLE_LIMIT = 3
const expandedCategories = ref<Record<string, boolean>>({})

watch(
  () => props.stats?.anchorDate,
  () => {
    expandedCategories.value = {}
  },
)

const isSmallSample = computed(() => props.stats?.sampleStatus === 'INSUFFICIENT')

const sampleBadge = computed(() => {
  if (!props.stats) return ''
  if (props.stats.sampleStatus === 'INSUFFICIENT') return '样本偏少'
  if (props.stats.sampleStatus === 'LIMITED') return '观察中'
  return '样本充足'
})

const missingDatesText = computed(() =>
  props.stats ? props.stats.missingDates.map(shortTrendDate).join('、') : '',
)

function barWidth(share: number | null): string {
  if (share === null) return '0%'
  return `${Math.max(4, Math.round(share * 100))}%`
}

function isCategoryExpanded(category: string): boolean {
  return Boolean(expandedCategories.value[category])
}

function toggleCategory(category: string) {
  expandedCategories.value = {
    ...expandedCategories.value,
    [category]: !expandedCategories.value[category],
  }
}

function visibleRepos(cat: TrendWindowCategoryStat): TrendWindowSampleRepo[] {
  return isCategoryExpanded(cat.category)
    ? cat.sampleRepos
    : cat.sampleRepos.slice(0, DEFAULT_SAMPLE_LIMIT)
}
</script>

<template>
  <section class="window-view" aria-label="近 7 日榜内风向观察">
    <div v-if="loading && !stats" class="state" role="status">正在汇总近 7 日榜内统计…</div>
    <div v-else-if="error && !stats" class="state" role="alert">
      <p>{{ error }}</p>
      <button type="button" class="retry-btn" @click="emit('retry')">重试统计</button>
    </div>
    <template v-else-if="stats">
      <header class="window-head">
        <div class="head-title">
          <h2>近 7 日榜内风向观察</h2>
          <span class="status-tag" :class="stats.sampleStatus.toLowerCase()">
            覆盖 {{ stats.coveredDays }}/{{ stats.windowDays }} 天 · {{ sampleBadge }}
          </span>
        </div>
        <p class="window-meta">
          <time>{{ stats.windowStart }} ～ {{ stats.windowEnd }}</time>
          <span>零模型费用 · 基于已发布日榜当前版本去重统计</span>
          <span>非 GitHub 官方周榜</span>
        </p>
      </header>

      <div class="kpi-strip" aria-label="窗口核心覆盖指标">
        <div class="kpi-item">
          <span class="kpi-label">7 日去重仓库</span>
          <strong class="kpi-value">{{ stats.windowSummary.uniqueRepoCount }}</strong>
          <span class="kpi-sub">累计 {{ stats.windowSummary.totalAppearances }} 上榜人次</span>
        </div>
        <div class="kpi-item">
          <span class="kpi-label">已分类仓库</span>
          <strong class="kpi-value">{{ stats.windowSummary.classifiedRepoCount }}</strong>
          <span class="kpi-sub"
            >占比分母 · 资料清单 {{ stats.windowSummary.docListRepoCount }} / 待解读
            {{ stats.windowSummary.missingInsightRepoCount }}</span
          >
        </div>
        <div class="kpi-item">
          <span class="kpi-label">多日上榜仓库</span>
          <strong class="kpi-value">{{ stats.recurringRepos.length }}</strong>
          <span class="kpi-sub">窗口内至少上榜 2 天</span>
        </div>
        <div class="kpi-item">
          <span class="kpi-label">有效涨星样本</span>
          <strong class="kpi-value"
            >{{ stats.windowSummary.starsKnownRepoCount }}/{{
              stats.windowSummary.uniqueRepoCount
            }}</strong
          >
          <span class="kpi-sub">缺失涨星严格不计零</span>
        </div>
      </div>

      <div class="scope-notes">
        <p v-if="isSmallSample" class="small-sample-note">
          当前窗口仅覆盖 {{ stats.coveredDays }} 天（不足 3
          天），以下仅列举基础覆盖与代表项目，不做分类占比变化或风向排名。
        </p>
        <p v-if="stats.missingDates.length">
          缺采日期：{{ missingDatesText }}（未采集日期不补猜，连续上榜遇缺口自然断开）。
        </p>
        <p>
          锚点日（{{ shortTrendDate(stats.anchorDate) }}）共 {{ stats.anchorSummary.totalItems }}
          项：已分类 {{ stats.anchorSummary.classifiedItems }}、资料清单
          {{ stats.anchorSummary.docListItems }}、待解读
          {{ stats.anchorSummary.missingInsightItems }}。
        </p>
        <p v-if="stats.windowSummary.staleClassifiedRepoCount > 0">
          旧稿归类提示：{{ stats.windowSummary.staleClassifiedRepoCount }}
          个仓库的分类来自沿用旧解读<template v-if="stats.windowSummary.oldestStaleAnalyzedAt"
            >（最早原稿生成于
            {{ trendTime(stats.windowSummary.oldestStaleAnalyzedAt) }}，北京时间）</template
          >。
        </p>
        <p v-if="stats.windowSummary.unverifiedIdentityCount > 0">
          身份核对提示：{{ stats.windowSummary.unverifiedIdentityCount }}
          个仓库在窗口内缺少稳定 GitHub 编号，暂按仓库全名去重。
        </p>
      </div>

      <section class="editorial-section" aria-labelledby="window-categories-heading">
        <div class="section-bar">
          <h3 id="window-categories-heading">分类分布</h3>
          <span class="section-denom"
            >分母：7 日已分类去重 {{ stats.windowSummary.classifiedRepoCount }} 个仓库</span
          >
        </div>
        <p v-if="!stats.categories.length" class="empty-note">
          当前窗口暂无具备有效分类的解读（资料清单 {{ stats.windowSummary.docListRepoCount }}
          个，待解读 {{ stats.windowSummary.missingInsightRepoCount }} 个）。
        </p>
        <div v-else class="category-grid">
          <article v-for="cat in stats.categories" :key="cat.category" class="category-item">
            <div class="cat-top">
              <strong class="cat-name">{{ categoryLabel(cat.category) }}</strong>
              <span class="cat-count">
                {{ cat.windowUniqueCount }} 个仓库
                <template v-if="!isSmallSample && cat.windowShare !== null">
                  · {{ trendPercent(cat.windowShare) }}
                </template>
              </span>
            </div>
            <div
              v-if="!isSmallSample && cat.windowShare !== null"
              class="share-track"
              aria-hidden="true"
            >
              <div class="share-fill" :style="{ width: barWidth(cat.windowShare) }" />
            </div>
            <dl class="cat-metrics">
              <div>
                <dt>本期上榜</dt>
                <dd>
                  {{ cat.anchorDayCount }} 个
                  <span v-if="!isSmallSample && cat.shareDelta !== null" class="muted">
                    （较前序 {{ trendDeltaPercent(cat.shareDelta) }}）
                  </span>
                </dd>
              </div>
              <div>
                <dt>涨星中位数</dt>
                <dd>
                  <template v-if="cat.medianStarsAdded !== null">
                    +{{ trendMedianNumber(cat.medianStarsAdded) }}
                    <span class="muted">
                      （样本 {{ cat.starsSampleCount }} 个<template
                        v-if="cat.maxRepoStarsAdded !== null"
                        >，最高 +{{ trendNumber(cat.maxRepoStarsAdded) }}</template
                      >）
                    </span>
                  </template>
                  <template v-else>未知（有效样本 0 个）</template>
                </dd>
              </div>
            </dl>
            <p v-if="cat.staleClassifiedCount > 0" class="stale-note">
              含 {{ cat.staleClassifiedCount }} 个沿用旧解读归类<template
                v-if="cat.oldestStaleAnalyzedAt"
              >
                · 最早 {{ trendTime(cat.oldestStaleAnalyzedAt) }}</template
              >
            </p>
            <div v-if="cat.sampleRepos.length" class="sample-repos">
              <div class="sample-head">
                <span class="sample-label">代表项目</span>
                <button
                  v-if="cat.sampleRepos.length > DEFAULT_SAMPLE_LIMIT"
                  type="button"
                  class="more-repos-btn"
                  :aria-expanded="isCategoryExpanded(cat.category)"
                  @click="toggleCategory(cat.category)"
                >
                  {{
                    isCategoryExpanded(cat.category)
                      ? '收起'
                      : `展开全部 ${cat.sampleRepos.length} 个`
                  }}
                </button>
              </div>
              <ul class="sample-chips">
                <li v-for="repo in visibleRepos(cat)" :key="repo.fullName" class="sample-chip">
                  <a
                    v-if="repo.inAnchorDay"
                    class="repo-link"
                    :href="resolveDetailHref(repo.fullName)"
                    @click="emit('openProject', $event, repo.fullName)"
                    >{{ repo.fullName }}</a
                  >
                  <a
                    v-else
                    class="repo-link"
                    :href="repo.repoUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                    >{{ repo.fullName
                    }}<ElIcon class="ext-icon" aria-hidden="true"><TopRight /></ElIcon
                  ></a>
                  <span class="chip-meta">
                    <template v-if="repo.appearanceDays >= 2">{{ repo.appearanceDays }}天</template>
                    <template v-if="repo.appearanceDays >= 2 && repo.latestStarsAdded !== null">
                      ·
                    </template>
                    <template v-if="repo.latestStarsAdded !== null"
                      >+{{ trendNumber(repo.latestStarsAdded) }}</template
                    >
                  </span>
                </li>
              </ul>
            </div>
          </article>
        </div>
      </section>

      <section class="editorial-section" aria-labelledby="window-recurring-heading">
        <div class="section-bar">
          <h3 id="window-recurring-heading">多日上榜仓库</h3>
          <span class="section-denom">窗口中位新增星标</span>
        </div>
        <p v-if="!stats.recurringRepos.length" class="empty-note">
          {{
            stats.coveredDays < 2
              ? '当前窗口仅覆盖 1 天，尚无法统计多日重复上榜。'
              : '当前窗口内暂无重复上榜的仓库。'
          }}
        </p>
        <div v-else class="recurring-list">
          <article v-for="repo in stats.recurringRepos" :key="repo.fullName" class="recurring-row">
            <div class="rec-days" :aria-label="`窗口内上榜 ${repo.appearanceDays} 天`">
              <strong>{{ String(repo.appearanceDays).padStart(2, '0') }}<span>天</span></strong>
              <small>连榜 {{ repo.consecutiveDays }} 天</small>
            </div>
            <div class="rec-body">
              <div class="rec-title-line">
                <h4>
                  <a
                    v-if="repo.inAnchorDay"
                    class="repo-link"
                    :href="resolveDetailHref(repo.fullName)"
                    @click="emit('openProject', $event, repo.fullName)"
                    ><span class="org">{{ repo.fullName.split('/')[0] }}/</span><wbr />{{
                      repo.fullName.split('/')[1]
                    }}</a
                  >
                  <a
                    v-else
                    class="repo-link"
                    :href="repo.repoUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                    ><span class="org">{{ repo.fullName.split('/')[0] }}/</span><wbr />{{
                      repo.fullName.split('/')[1]
                    }}<ElIcon class="ext-icon" aria-hidden="true"><TopRight /></ElIcon
                  ></a>
                </h4>
                <span v-if="repo.category" class="cat-pill">{{
                  categoryLabel(repo.category)
                }}</span>
                <span v-if="!repo.inAnchorDay" class="off-anchor">本期未在榜</span>
              </div>
              <p v-if="repo.definition" class="rec-definition">{{ repo.definition }}</p>
              <p class="rec-meta">
                <span v-if="repo.anchorStreakDays > 0"
                  >截至本期连榜 {{ repo.anchorStreakDays }} 天</span
                >
                <span>最高第 {{ repo.bestRank }} 名</span>
                <span>上榜日 {{ repo.appearedDates.map(shortTrendDate).join('、') }}</span>
              </p>
            </div>
            <span
              class="stars"
              role="img"
              :aria-label="`窗口中位新增星标：${trendMedianNumber(repo.medianStarsAdded)}`"
            >
              <ElIcon aria-hidden="true"><StarFilled /></ElIcon>
              {{
                repo.medianStarsAdded === null
                  ? '—'
                  : '+' + trendMedianNumber(repo.medianStarsAdded)
              }}
            </span>
          </article>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.window-view,
.window-view * {
  box-sizing: border-box;
}
.window-view {
  color: #192743;
}
.state {
  padding: 28px 0;
  font-size: 14px;
  color: #354b59;
}
.state p {
  margin: 0 0 12px;
}
.retry-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 0 16px;
  border: 1px solid #9daeb8;
  border-radius: 9px;
  background: #ffffff;
  color: #1d435b;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
}
.retry-btn:hover {
  background: #edf3f4;
}
.retry-btn:active {
  border-color: #154e78;
  background: #e4eef0;
}
.retry-btn:focus-visible {
  outline: none;
  border-color: #154e78;
  box-shadow: inset 0 0 0 1px #154e78;
}
.window-head {
  margin: 22px 0 14px;
}
.head-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
}
.window-head h2 {
  margin: 0;
  font-size: 25px;
  letter-spacing: -0.04em;
  color: #142f46;
}
.status-tag {
  font-size: 12px;
  font-weight: 650;
  padding: 3px 9px;
  border-radius: 999px;
  background: #e4f0ee;
  color: #1d4e5a;
  font-variant-numeric: tabular-nums;
}
.status-tag.insufficient {
  background: #fdf3dc;
  color: #6b460a;
}
.status-tag.limited {
  background: #e9eff6;
  color: #314b66;
}
.window-meta {
  margin: 6px 0 0;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 12px;
  color: #475c6b;
  font-variant-numeric: tabular-nums;
}
.window-meta time {
  font-weight: 650;
}
.kpi-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px 18px;
  padding: 14px 0;
  border-top: 1px solid #c8d5db;
  border-bottom: 1px solid #c8d5db;
}
.kpi-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.kpi-label {
  font-size: 12px;
  color: #4f6372;
  font-weight: 600;
}
.kpi-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: -0.02em;
  color: #142f46;
  font-variant-numeric: lining-nums tabular-nums;
}
.kpi-sub {
  font-size: 12px;
  color: #52667c;
  line-height: 1.45;
  font-variant-numeric: tabular-nums;
}
.scope-notes {
  margin: 14px 0 0;
  padding: 2px 0 2px 12px;
  border-left: 3px solid #9bb6bc;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #475c6b;
}
.scope-notes p {
  margin: 0;
}
.small-sample-note {
  color: #6b460a;
  font-weight: 650;
}
.editorial-section {
  margin-top: 26px;
}
.section-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 6px 14px;
  padding-bottom: 8px;
  border-bottom: 2px solid #273b50;
}
.section-bar h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: #142f46;
}
.section-denom {
  font-size: 12px;
  font-weight: 650;
  color: #475c6b;
  font-variant-numeric: tabular-nums;
}
.empty-note {
  margin: 14px 0 0;
  font-size: 13px;
  color: #4f6372;
}
.category-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 32px;
}
.category-item {
  padding: 15px 0;
  border-bottom: 1px solid #d7dedf;
  min-width: 0;
}
.cat-top {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 4px 10px;
}
.cat-name {
  font-size: 15px;
  color: #142f46;
}
.cat-count {
  font-size: 13px;
  font-weight: 700;
  color: #28566f;
  font-variant-numeric: tabular-nums;
}
.share-track {
  margin-top: 7px;
  height: 5px;
  border-radius: 999px;
  background: #e5eeee;
  overflow: hidden;
}
.share-fill {
  height: 100%;
  border-radius: 999px;
  background: #28566f;
}
.cat-metrics {
  margin: 9px 0 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.cat-metrics > div {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 8px;
}
.cat-metrics dt {
  color: #52667c;
  min-width: 68px;
}
.cat-metrics dd {
  margin: 0;
  color: #192743;
}
.muted {
  color: #52667c;
  font-size: 12px;
}
.stale-note {
  margin: 6px 0 0;
  font-size: 12px;
  color: #6b460a;
}
.sample-repos {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #d3dfe0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.sample-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 4px 10px;
}
.sample-label {
  font-size: 12px;
  font-weight: 600;
  color: #52667c;
}
.more-repos-btn {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  padding: 0 10px;
  margin: -10px -10px -10px 0;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: #1d4e6b;
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}
.more-repos-btn:hover {
  background: #eaf1f2;
}
.more-repos-btn:active {
  border-color: #154e78;
  background: #e1ecef;
}
.more-repos-btn:focus-visible {
  outline: none;
  border-color: #154e78;
  box-shadow: inset 0 0 0 1px #154e78;
}
.sample-chips {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.sample-chip {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  padding: 4px 9px;
  border: 1px solid #d7e2e4;
  border-radius: 7px;
  background: #f5f8f8;
  font-size: 12px;
  line-height: 1.4;
}
.chip-meta {
  color: #52667c;
  font-size: 11px;
  font-variant-numeric: lining-nums tabular-nums;
}
.repo-link {
  color: #192743;
  text-decoration: none;
  overflow-wrap: anywhere;
}
.sample-repos .repo-link {
  color: #1d4e6b;
  font-weight: 600;
}
.repo-link:hover,
.repo-link:focus-visible {
  text-decoration: underline;
  text-underline-offset: 4px;
  color: #075981;
}
.ext-icon {
  margin-left: 3px;
  vertical-align: -2px;
}
.recurring-row {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr) auto;
  align-items: start;
  gap: 16px;
  padding: 16px 0;
  border-bottom: 1px solid #d7dedf;
}
.rec-days {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: #52667c;
  font-variant-numeric: tabular-nums;
}
.rec-days strong {
  font-size: 20px;
  font-weight: 700;
  font-style: normal;
  line-height: 1.25;
  color: #28566f;
  font-variant-numeric: lining-nums tabular-nums;
}
.rec-days strong span {
  font-style: normal;
  font-size: 12px;
  margin-left: 2px;
}
.rec-days small {
  font-size: 11px;
  color: #52667c;
}
.rec-body {
  min-width: 0;
  overflow-wrap: anywhere;
}
.rec-title-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
}
.rec-title-line h4 {
  margin: 0;
  font-size: clamp(17px, 2.2vw, 22px);
  line-height: 1.32;
  letter-spacing: -0.03em;
}
.org {
  color: #4f6372;
  font-weight: 450;
}
.cat-pill,
.off-anchor {
  font-size: 11px;
  font-weight: 650;
  padding: 2px 7px;
  border-radius: 6px;
}
.cat-pill {
  background: #e4f0ee;
  color: #1d4e5a;
}
.off-anchor {
  background: #eceff1;
  color: #4f6372;
}
.rec-definition {
  margin: 6px 0 0;
  font-size: 14px;
  line-height: 1.55;
  color: #354b59;
}
.rec-meta {
  margin: 6px 0 0;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  font-size: 12px;
  color: #4f6372;
  font-variant-numeric: tabular-nums;
}
.stars {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #473215;
  background: #ffe7a2;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  font-size: 14px;
  padding: 5px 9px;
  border-radius: 7px;
}
.stars .el-icon {
  color: #875907;
}
@media (max-width: 820px) {
  .kpi-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 620px) {
  .window-head {
    margin-top: 16px;
  }
  .category-grid {
    grid-template-columns: 1fr;
  }
  .recurring-row {
    grid-template-columns: 60px minmax(0, 1fr) auto;
    gap: 8px;
    padding-block: 14px;
  }
  .rec-body {
    display: contents;
  }
  .rec-title-line {
    grid-column: 2 / -1;
  }
  .rec-definition {
    grid-column: 2 / -1;
    margin: 0;
  }
  .rec-meta {
    grid-column: 2;
    margin: 0;
    align-self: center;
  }
  .stars {
    grid-column: 3;
    font-size: 12px;
  }
}
</style>

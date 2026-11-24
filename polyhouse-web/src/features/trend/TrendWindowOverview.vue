<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElIcon } from 'element-plus'
import { StarFilled, TopRight } from '@element-plus/icons-vue'
import type {
  TrendBriefing,
  TrendWindowCategoryStat,
  TrendWindowSampleRepo,
  TrendWindowStats,
} from '../../api/trend'
import TrendWindowBriefing from './TrendWindowBriefing.vue'
import {
  categoryLabel,
  categoryTone,
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
  /** 可选 7 日 AI 风口简报；未生成（ABSENT）或加载失败时为 null，无缝保持纯净 2A 统计。 */
  briefing?: TrendBriefing | null
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
  return ''
})

const missingDatesText = computed(() =>
  props.stats ? props.stats.missingDates.map(shortTrendDate).join('、') : '',
)

const scopeSummaryHint = computed(() => {
  if (!props.stats) return ''
  const parts: string[] = [`锚点日 ${props.stats.anchorSummary.totalItems} 项`]
  if (props.stats.missingDates.length) parts.push(`缺采 ${props.stats.missingDates.length} 天`)
  if (props.stats.windowSummary.staleClassifiedRepoCount > 0) {
    parts.push(`含 ${props.stats.windowSummary.staleClassifiedRepoCount} 项旧稿归类`)
  }
  return parts.join(' · ')
})

const proportionSegments = computed(() => {
  if (!props.stats || isSmallSample.value) return []
  return props.stats.categories
    .map((cat) => ({
      category: cat.category,
      label: categoryLabel(cat.category),
      share: cat.windowShare ?? 0,
      shareText: trendPercent(cat.windowShare),
      count: cat.windowUniqueCount,
      color: categoryTone(cat.category).accent,
    }))
    .filter((seg) => seg.share > 0)
})

function barWidth(share: number | null): string {
  if (share === null) return '0%'
  return `${Math.max(4, Math.round(share * 100))}%`
}

function deltaTone(delta: number | null): 'up' | 'down' | 'flat' {
  if (delta === null || delta === 0) return 'flat'
  return delta > 0 ? 'up' : 'down'
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
            覆盖 {{ stats.coveredDays }}/{{ stats.windowDays }} 天<template v-if="sampleBadge">
              · {{ sampleBadge }}</template
            >
          </span>
        </div>
        <time class="window-range">{{ stats.windowStart }} ～ {{ stats.windowEnd }}</time>
      </header>

      <TrendWindowBriefing
        :briefing="briefing ?? null"
        :resolve-detail-href="resolveDetailHref"
        @open-project="(event, name) => emit('openProject', event, name)"
      />

      <div class="kpi-strip" aria-label="窗口核心覆盖指标">
        <div class="kpi-item tone-ocean">
          <span class="kpi-label">7 日去重仓库</span>
          <strong class="kpi-value">{{ stats.windowSummary.uniqueRepoCount }}</strong>
          <span class="kpi-sub">累计 {{ stats.windowSummary.totalAppearances }} 上榜人次</span>
        </div>
        <div class="kpi-item tone-teal">
          <span class="kpi-label">已分类仓库</span>
          <strong class="kpi-value">{{ stats.windowSummary.classifiedRepoCount }}</strong>
          <span class="kpi-sub"
            >占比分母 · 清单 {{ stats.windowSummary.docListRepoCount }} / 待解读
            {{ stats.windowSummary.missingInsightRepoCount }}</span
          >
        </div>
        <div class="kpi-item tone-gold">
          <span class="kpi-label">多日上榜仓库</span>
          <strong class="kpi-value">{{ stats.recurringRepos.length }}</strong>
          <span class="kpi-sub">窗口内至少上榜 2 天</span>
        </div>
        <div class="kpi-item tone-dusk">
          <span class="kpi-label">有效涨星样本</span>
          <strong class="kpi-value"
            >{{ stats.windowSummary.starsKnownRepoCount }}/{{
              stats.windowSummary.uniqueRepoCount
            }}</strong
          >
          <span class="kpi-sub">缺失涨星严格不计零</span>
        </div>
      </div>

      <!-- 样本不足 3 天时外露温和提醒；常规口径、缺采日与旧稿归类统一折叠收口，减少阅读噪音。 -->
      <p v-if="isSmallSample" class="small-sample-banner" role="status">
        当前窗口仅覆盖 {{ stats.coveredDays }} 天（不足 3
        天），以下仅列举基础覆盖与代表项目，不做分类占比变化或风向排名。
      </p>

      <details class="scope-disclosure">
        <summary class="scope-summary">
          <span>统计口径与样本说明</span>
          <span class="scope-hint">{{ scopeSummaryHint }}</span>
        </summary>
        <div class="scope-notes">
          <p>零模型费用 · 基于已发布日榜当前版本去重统计 · 非 GitHub 官方周榜。</p>
          <p v-if="stats.missingDates.length">
            缺采日期：{{ missingDatesText }}（未采集日期不补猜，连续上榜遇缺口自然断开）。
          </p>
          <p>
            锚点日（{{ shortTrendDate(stats.anchorDate) }}）共
            {{ stats.anchorSummary.totalItems }} 项：已分类
            {{ stats.anchorSummary.classifiedItems }}、资料清单
            {{ stats.anchorSummary.docListItems }}、待解读
            {{ stats.anchorSummary.missingInsightItems }}。
          </p>
          <p v-if="stats.windowSummary.staleClassifiedRepoCount > 0">
            旧稿归类提示：{{
              stats.windowSummary.staleClassifiedRepoCount
            }}
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
      </details>

      <section class="editorial-section" aria-labelledby="window-categories-heading">
        <div class="section-bar">
          <h3 id="window-categories-heading">分类分布</h3>
          <span class="section-denom"
            >分母：7 日已分类去重 {{ stats.windowSummary.classifiedRepoCount }} 个仓库</span
          >
        </div>
        <p v-if="!stats.categories.length" class="empty-note">
          当前窗口暂无具备有效分类的解读（资料清单
          {{ stats.windowSummary.docListRepoCount }} 个，待解读
          {{ stats.windowSummary.missingInsightRepoCount }} 个）。
        </p>
        <template v-else>
          <!-- 样本充足时展示 100% 堆叠份额总览条与文字图例，帮助读者快速感知整体赛道格局。 -->
          <div v-if="proportionSegments.length" class="proportion-overview">
            <div class="stacked-bar" aria-hidden="true">
              <span
                v-for="seg in proportionSegments"
                :key="seg.category"
                class="stacked-seg"
                :style="{ flexGrow: seg.share, backgroundColor: seg.color }"
              />
            </div>
            <ul class="proportion-legend" aria-label="分类占比总览">
              <li v-for="seg in proportionSegments" :key="seg.category">
                <span
                  class="legend-dot"
                  :style="{ backgroundColor: seg.color }"
                  aria-hidden="true"
                />
                <span class="legend-name">{{ seg.label }}</span>
                <span class="legend-share">{{ seg.shareText }}</span>
              </li>
            </ul>
          </div>

          <div class="category-grid">
            <article
              v-for="cat in stats.categories"
              :key="cat.category"
              class="category-item"
              :style="{ '--cat-accent': categoryTone(cat.category).accent }"
            >
              <div class="cat-top">
                <div class="cat-title-wrap">
                  <span v-if="!isSmallSample" class="cat-dot" aria-hidden="true" />
                  <strong class="cat-name">{{ categoryLabel(cat.category) }}</strong>
                  <span
                    v-if="cat.staleClassifiedCount > 0"
                    class="stale-dot"
                    :aria-label="`含 ${cat.staleClassifiedCount} 个沿用旧解读归类`"
                    :title="
                      cat.oldestStaleAnalyzedAt
                        ? `含 ${cat.staleClassifiedCount} 个沿用旧解读归类（最早 ${trendTime(cat.oldestStaleAnalyzedAt)}）`
                        : `含 ${cat.staleClassifiedCount} 个沿用旧解读归类`
                    "
                    >含旧稿</span
                  >
                </div>
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
              <div class="cat-metrics-row">
                <span class="metric-piece">
                  本期 <strong>{{ cat.anchorDayCount }} 个</strong>
                  <span
                    v-if="!isSmallSample && cat.shareDelta !== null"
                    class="delta-badge"
                    :class="deltaTone(cat.shareDelta)"
                  >
                    <span v-if="cat.shareDelta > 0" aria-hidden="true">↑</span>
                    <span v-else-if="cat.shareDelta < 0" aria-hidden="true">↓</span>
                    较前序 {{ trendDeltaPercent(cat.shareDelta) }}
                  </span>
                </span>
                <span class="metric-piece">
                  <template v-if="cat.medianStarsAdded !== null">
                    中位
                    <strong class="star-metric">
                      <ElIcon aria-hidden="true"><StarFilled /></ElIcon>+{{
                        trendMedianNumber(cat.medianStarsAdded)
                      }}
                    </strong>
                    <span v-if="cat.maxRepoStarsAdded !== null" class="muted">
                      最高 +{{ trendNumber(cat.maxRepoStarsAdded) }}
                    </span>
                  </template>
                  <template v-else><span class="muted">涨星样本未知</span></template>
                </span>
              </div>
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
                      <template v-if="repo.appearanceDays >= 2"
                        >{{ repo.appearanceDays }}天</template
                      >
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
        </template>
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
                <span
                  v-if="repo.category"
                  class="cat-pill"
                  :style="{
                    backgroundColor: categoryTone(repo.category).bg,
                    color: categoryTone(repo.category).ink,
                  }"
                  >{{ categoryLabel(repo.category) }}</span
                >
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
  padding: 32px 0;
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
.window-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px 16px;
  margin: 26px 0 20px;
}
.head-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
}
.window-head h2 {
  margin: 0;
  font-size: 23px;
  font-weight: 650;
  letter-spacing: -0.02em;
  color: #142f46;
}
.status-tag {
  font-size: 12px;
  font-weight: 600;
  padding: 3px 10px;
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
.window-range {
  font-size: 13px;
  font-weight: 600;
  color: #506678;
  font-variant-numeric: tabular-nums;
}
.kpi-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}
.kpi-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding: 16px 18px;
  border-radius: 14px;
  background: #f3f7f9;
}
/* 4 张总览轻底块呼应横幅的海雾蓝、潮青、弯月暖金与暮空微紫，同时与发布台账本卡保持家族感。 */
.kpi-item.tone-ocean {
  background: #eef4f8;
}
.kpi-item.tone-ocean .kpi-value {
  color: #143a54;
}
.kpi-item.tone-teal {
  background: #eaf4f1;
}
.kpi-item.tone-teal .kpi-value {
  color: #134850;
}
.kpi-item.tone-gold {
  background: #fbf4e2;
}
.kpi-item.tone-gold .kpi-value {
  color: #6d480d;
}
.kpi-item.tone-dusk {
  background: #f2f1f7;
}
.kpi-item.tone-dusk .kpi-value {
  color: #3c2d5e;
}
.kpi-label {
  font-size: 12px;
  color: #4b6173;
  font-weight: 600;
}
.kpi-value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.18;
  letter-spacing: -0.02em;
  color: #142f46;
  font-variant-numeric: lining-nums tabular-nums;
}
.kpi-sub {
  font-size: 12px;
  color: #556b7d;
  line-height: 1.45;
  font-variant-numeric: tabular-nums;
}
.small-sample-banner {
  margin: 14px 0 0;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fdf6e7;
  border: 1px solid #ecd8ad;
  color: #6b460a;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.55;
}
.scope-disclosure {
  margin-top: 14px;
  padding: 2px 14px;
  border-radius: 12px;
  background: #f6f9fb;
  border: 1px solid #e4edf0;
}
.scope-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 6px 14px;
  min-height: 44px;
  padding: 8px 0;
  cursor: pointer;
  font-size: 12.5px;
  font-weight: 650;
  color: #445c6e;
  list-style: none;
}
.scope-summary::-webkit-details-marker {
  display: none;
}
.scope-summary > span:first-child::before {
  content: '▸';
  display: inline-block;
  margin-right: 7px;
  color: #6b8496;
  transition: transform 0.16s ease;
}
.scope-disclosure[open] .scope-summary > span:first-child::before {
  transform: rotate(90deg);
}
.scope-summary:hover {
  color: #142f46;
}
.scope-hint {
  font-weight: 500;
  color: #62788a;
  font-variant-numeric: tabular-nums;
}
.scope-notes {
  padding: 6px 0 12px;
  border-top: 1px solid #e4edf0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12.5px;
  line-height: 1.62;
  color: #4b6173;
  font-variant-numeric: tabular-nums;
}
.scope-notes p {
  margin: 0;
}
.editorial-section {
  margin-top: 40px;
}
.section-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 6px 14px;
  padding-bottom: 10px;
  border-bottom: 1px solid #e1eaed;
}
.section-bar h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  letter-spacing: -0.015em;
  color: #142f46;
}
.section-denom {
  font-size: 12px;
  font-weight: 600;
  color: #5b7182;
  font-variant-numeric: tabular-nums;
}
.empty-note {
  margin: 16px 0 0;
  font-size: 13.5px;
  color: #4f6372;
}
.proportion-overview {
  margin-top: 18px;
  padding: 14px 18px;
  border-radius: 14px;
  background: #f7fafb;
  border: 1px solid #e4edf0;
}
.stacked-bar {
  display: flex;
  gap: 3px;
  height: 10px;
  border-radius: 999px;
  overflow: hidden;
  background: #e9f0f3;
}
.stacked-seg {
  min-width: 6px;
  border-radius: 3px;
}
.proportion-legend {
  list-style: none;
  margin: 11px 0 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  font-size: 12.5px;
  color: #3c5365;
  font-variant-numeric: tabular-nums;
}
.proportion-legend li {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}
.legend-name {
  font-weight: 600;
  color: #1c3448;
}
.legend-share {
  color: #5b7182;
}
.category-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 20px;
  margin-top: 16px;
}
.category-item {
  display: flex;
  flex-direction: column;
  padding: 18px 20px;
  border: 1px solid #dce6ea;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 2px 12px rgba(20, 47, 70, 0.035);
  min-width: 0;
}
.cat-top {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 6px 10px;
}
.cat-title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.cat-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--cat-accent, #1e5575);
  flex: none;
}
.cat-name {
  font-size: 15.5px;
  font-weight: 650;
  color: #142f46;
}
.stale-dot {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 7px;
  border-radius: 999px;
  background: #fbf3df;
  color: #7c5617;
}
.cat-count {
  font-size: 13px;
  font-weight: 700;
  color: #1f5570;
  font-variant-numeric: tabular-nums;
}
.share-track {
  margin-top: 10px;
  height: 6px;
  border-radius: 999px;
  background: #ecf2f4;
  overflow: hidden;
}
.share-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--cat-accent, #1e5575);
}
.cat-metrics-row {
  margin: 12px 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 14px;
  font-size: 13px;
  color: #4b6173;
  font-variant-numeric: tabular-nums;
}
.metric-piece {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.metric-piece strong {
  color: #142f46;
  font-weight: 650;
}
.star-metric {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: #7c510a;
}
.star-metric .el-icon {
  color: #c88a2b;
  font-size: 12px;
}
.delta-badge {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 600;
}
.delta-badge.up {
  background: #e7f4ed;
  color: #1c6042;
}
.delta-badge.down {
  background: #f9ebe7;
  color: #933f2e;
}
.delta-badge.flat {
  background: #eef2f4;
  color: #556b7d;
}
.muted {
  color: #62788a;
  font-size: 12px;
}
.sample-repos {
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px dashed #e0e9ec;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sample-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 6px 10px;
}
.sample-label {
  font-size: 12px;
  font-weight: 600;
  color: #5b7182;
}
.more-repos-btn {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 11px;
  border: 1px solid #dce6ea;
  border-radius: 999px;
  background: #f6fafb;
  color: #1d4e6b;
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease;
}
.more-repos-btn:hover {
  background: #eaf2f5;
  border-color: #b7ccd5;
}
.more-repos-btn:active {
  border-color: #154e78;
  background: #e1ecef;
}
.sample-chips {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.sample-chip {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 5px 11px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: #f2f6f8;
  font-size: 12.5px;
  line-height: 1.4;
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease;
}
.sample-chip:hover {
  background: #e8f1f4;
  border-color: #c7d9e0;
}
.chip-meta {
  color: #5e7486;
  font-size: 11.5px;
  font-variant-numeric: lining-nums tabular-nums;
}
.repo-link {
  color: #192743;
  text-decoration: none;
  overflow-wrap: anywhere;
}
.sample-repos .repo-link {
  color: #194b68;
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
/* 多日上榜仓库收进同款 Soft Bento 圆角白卡，与上方简报卡、分类卡保持一致的收口节奏。 */
.recurring-list {
  margin-top: 18px;
  padding: 4px 22px;
  border: 1px solid #dce6ea;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 4px 18px rgba(20, 47, 70, 0.04);
}
.recurring-row {
  display: grid;
  grid-template-columns: 78px minmax(0, 1fr) auto;
  align-items: start;
  gap: 18px;
  padding: 18px 0;
  border-bottom: 1px solid #e6eef1;
}
.recurring-row:last-child {
  border-bottom: 0;
}
.rec-days {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 8px 6px;
  border-radius: 10px;
  background: #f1f6f8;
  color: #52667c;
  font-variant-numeric: tabular-nums;
}
.rec-days strong {
  font-size: 20px;
  font-weight: 700;
  font-style: normal;
  line-height: 1.2;
  color: #1b506d;
  font-variant-numeric: lining-nums tabular-nums;
}
.rec-days strong span {
  font-style: normal;
  font-size: 12px;
  margin-left: 2px;
}
.rec-days small {
  font-size: 11px;
  color: #556b7d;
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
  font-size: clamp(17px, 2.2vw, 20px);
  font-weight: 650;
  line-height: 1.35;
  letter-spacing: -0.015em;
}
.org {
  color: #546a7d;
  font-weight: 450;
}
.cat-pill,
.off-anchor {
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 9px;
  border-radius: 999px;
}
.cat-pill {
  background: #e6f2f0;
  color: #1c525e;
}
.off-anchor {
  background: #edf1f3;
  color: #516676;
}
.rec-definition {
  margin: 7px 0 0;
  font-size: 14px;
  line-height: 1.62;
  color: #354b59;
}
.rec-meta {
  margin: 7px 0 0;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 12px;
  color: #556b7d;
  font-variant-numeric: tabular-nums;
}
.stars {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #63420a;
  background: #fdf0cb;
  border: 1px solid #efd89a;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  font-size: 14px;
  padding: 5px 10px;
  border-radius: 9px;
}
.stars .el-icon {
  color: #b7791f;
}
@media (max-width: 820px) {
  .kpi-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 680px) {
  .category-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 620px) {
  .window-head {
    margin: 18px 0 16px;
  }
  .kpi-strip {
    gap: 10px;
  }
  .kpi-item {
    padding: 14px;
  }
  .kpi-value {
    font-size: 24px;
  }
  .editorial-section {
    margin-top: 30px;
  }
  .proportion-overview {
    padding: 12px 14px;
  }
  .category-item {
    padding: 16px;
  }
  .recurring-list {
    padding: 2px 14px;
  }
  .recurring-row {
    grid-template-columns: 64px minmax(0, 1fr) auto;
    gap: 10px;
    padding-block: 16px;
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
    font-size: 12.5px;
  }
}
</style>

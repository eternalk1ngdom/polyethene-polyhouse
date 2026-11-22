<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElIcon } from 'element-plus'
import { ArrowDown, StarFilled, TopRight } from '@element-plus/icons-vue'
import type { TrendWindowStats } from '../../api/trend'
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
  /** 加载失败提示，不影响下方当期日报阅读。 */
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

const expanded = ref(false)
const panelId = 'trend-window-panel'

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
</script>

<template>
  <section class="window-overview" aria-label="近 7 日榜内观察">
    <p v-if="loading && !stats" class="overview-status" role="status">正在汇总近 7 日榜内统计…</p>
    <div v-else-if="error && !stats" class="overview-error" role="status">
      <span>{{ error }}</span>
      <button type="button" class="action-btn" @click="emit('retry')">重试统计</button>
    </div>
    <template v-else-if="stats">
      <div class="overview-bar">
        <div class="overview-head">
          <div class="title-row">
            <h3>近 7 日榜内观察</h3>
            <span class="range"
              >{{ shortTrendDate(stats.windowStart) }} ～ {{ shortTrendDate(stats.windowEnd) }}</span
            >
            <span class="status-tag" :class="stats.sampleStatus.toLowerCase()">
              覆盖 {{ stats.coveredDays }}/{{ stats.windowDays }} 天 · {{ sampleBadge }}
            </span>
          </div>
          <p class="digest">
            <span>去重 {{ stats.windowSummary.uniqueRepoCount }} 个仓库</span>
            <span>已分类 {{ stats.windowSummary.classifiedRepoCount }} 个</span>
            <span>多日上榜 {{ stats.recurringRepos.length }} 个</span>
            <span v-if="stats.missingDates.length">缺采 {{ stats.missingDates.length }} 天</span>
          </p>
        </div>
        <button
          type="button"
          class="action-btn toggle-btn"
          :aria-expanded="expanded"
          :aria-controls="panelId"
          @click="expanded = !expanded"
        >
          <span>{{ expanded ? '收起明细' : '展开明细' }}</span>
          <ElIcon class="chevron" :class="{ open: expanded }" aria-hidden="true">
            <ArrowDown />
          </ElIcon>
        </button>
      </div>

      <div v-if="expanded" :id="panelId" class="overview-body">
        <div class="scope-note">
          <p v-if="isSmallSample" class="small-sample-alert">
            当前窗口仅覆盖 {{ stats.coveredDays }} 天（不足 3
            天），以下仅列举基础覆盖与代表项目，不做分类占比变化或风向排名。
          </p>
          <p>
            基于窗口内每日当前已发布日榜去重汇总（共 {{ stats.windowSummary.totalAppearances }}
            上榜人次，零模型费用，非 GitHub 官方周榜）。锚点日共
            {{ stats.anchorSummary.totalItems }} 项（已分类
            {{ stats.anchorSummary.classifiedItems }}、资料清单
            {{ stats.anchorSummary.docListItems }}、待解读
            {{ stats.anchorSummary.missingInsightItems }}）；缺失涨星不计零（有效涨星样本
            {{ stats.windowSummary.starsKnownRepoCount }}/{{
              stats.windowSummary.uniqueRepoCount
            }}
            个仓库）。
          </p>
          <p v-if="stats.missingDates.length">
            缺采日期：{{ missingDatesText }}（未采集日期不补猜，连续上榜遇缺口自然断开）。
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

        <div class="block">
          <div class="block-head">
            <h4>分类分布</h4>
            <span class="denom">
              分母：7 日已分类去重 {{ stats.windowSummary.classifiedRepoCount }} 个仓库
            </span>
          </div>
          <p v-if="!stats.categories.length" class="empty-note">
            当前窗口暂无具备有效分类的解读（资料清单 {{ stats.windowSummary.docListRepoCount }}
            个，待解读 {{ stats.windowSummary.missingInsightRepoCount }} 个）。
          </p>
          <div v-else class="category-grid">
            <article v-for="cat in stats.categories" :key="cat.category" class="category-card">
              <div class="cat-top">
                <strong class="cat-name">{{ categoryLabel(cat.category) }}</strong>
                <span class="cat-count">
                  {{ cat.windowUniqueCount }} 个仓库
                  <template v-if="!isSmallSample && cat.windowShare !== null">
                    · {{ trendPercent(cat.windowShare) }}
                  </template>
                </span>
              </div>
              <dl class="cat-metrics">
                <div>
                  <dt>本期上榜</dt>
                  <dd>
                    {{ cat.anchorDayCount }} 个
                    <span v-if="!isSmallSample && cat.shareDelta !== null" class="delta">
                      （较前序 {{ trendDeltaPercent(cat.shareDelta) }}）
                    </span>
                  </dd>
                </div>
                <div>
                  <dt>涨星中位数</dt>
                  <dd>
                    <template v-if="cat.medianStarsAdded !== null">
                      +{{ trendMedianNumber(cat.medianStarsAdded) }}
                      <span class="sub">
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
              <div v-if="isSmallSample || cat.windowUniqueCount < 3" class="sample-repos">
                <span class="sample-label">代表项目：</span>
                <template v-for="(repo, idx) in cat.sampleRepos" :key="repo.fullName">
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
                  <span v-if="idx < cat.sampleRepos.length - 1" class="sep">、</span>
                </template>
              </div>
            </article>
          </div>
        </div>

        <div class="block">
          <div class="block-head">
            <h4>多日上榜仓库</h4>
            <span class="denom">按稳定仓库 ID 去重，至少上榜 2 天</span>
          </div>
          <p v-if="!stats.recurringRepos.length" class="empty-note">
            {{
              stats.coveredDays < 2
                ? '当前窗口仅覆盖 1 天，尚无法统计多日重复上榜。'
                : '当前窗口内暂无重复上榜的仓库。'
            }}
          </p>
          <ul v-else class="recurring-list">
            <li v-for="repo in stats.recurringRepos" :key="repo.fullName" class="recurring-item">
              <div class="rec-main">
                <a
                  v-if="repo.inAnchorDay"
                  class="repo-link rec-name"
                  :href="resolveDetailHref(repo.fullName)"
                  @click="emit('openProject', $event, repo.fullName)"
                  >{{ repo.fullName }}</a
                >
                <a
                  v-else
                  class="repo-link rec-name"
                  :href="repo.repoUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  >{{ repo.fullName
                  }}<ElIcon class="ext-icon" aria-hidden="true"><TopRight /></ElIcon
                ></a>
                <span v-if="repo.category" class="cat-pill">{{
                  categoryLabel(repo.category)
                }}</span>
                <span v-if="!repo.inAnchorDay" class="off-anchor">本期未在榜</span>
              </div>
              <div class="rec-meta">
                <span>上榜 {{ repo.appearanceDays }} 天</span>
                <span>最长连续 {{ repo.consecutiveDays }} 天</span>
                <span v-if="repo.anchorStreakDays > 0">截至本期连榜 {{ repo.anchorStreakDays }} 天</span>
                <span>最高第 {{ repo.bestRank }} 名</span>
                <span v-if="repo.medianStarsAdded !== null" class="rec-stars">
                  <ElIcon aria-hidden="true"><StarFilled /></ElIcon>
                  中位 +{{ trendMedianNumber(repo.medianStarsAdded) }}
                </span>
              </div>
            </li>
          </ul>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.window-overview,
.window-overview * {
  box-sizing: border-box;
}
.window-overview {
  margin: 14px 0 4px;
  padding: 12px 16px;
  border: 1px solid #cddbd9;
  border-radius: 12px;
  background: #ffffff;
  color: #192743;
}
.overview-status,
.overview-error {
  margin: 0;
  font-size: 13px;
  color: #475c6b;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
}
.overview-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px 16px;
  flex-wrap: wrap;
}
.overview-head {
  min-width: 0;
  flex: 1 1 260px;
}
.title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
}
h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #142f46;
}
.range {
  font-size: 12px;
  color: #475c6b;
  font-variant-numeric: tabular-nums;
}
.status-tag {
  font-size: 12px;
  font-weight: 650;
  padding: 2px 8px;
  border-radius: 999px;
  background: #e9f2f1;
  color: #1d4e5a;
  font-variant-numeric: tabular-nums;
}
.status-tag.insufficient {
  background: #fdf3dc;
  color: #6b460a;
}
.status-tag.limited {
  background: #eef2f7;
  color: #314b66;
}
.digest {
  margin: 5px 0 0;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 12px;
  color: #475c6b;
  font-variant-numeric: tabular-nums;
}
.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 44px;
  padding: 0 14px;
  border: 1px solid #9daeb8;
  border-radius: 9px;
  background: #fbfaf6;
  color: #1d435b;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
}
.action-btn:hover {
  background: #edf3f4;
}
.action-btn:active {
  border-color: #154e78;
  background: #e4eef0;
}
.action-btn:focus-visible {
  outline: none;
  border-color: #154e78;
  box-shadow: inset 0 0 0 1px #154e78;
}
.chevron {
  transition: transform 0.16s ease;
}
.chevron.open {
  transform: rotate(180deg);
}
@media (prefers-reduced-motion: reduce) {
  .chevron {
    transition: none;
  }
}
.overview-body {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #dce6e7;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.scope-note {
  padding: 10px 12px;
  border-radius: 9px;
  background: #f6f9f9;
  font-size: 12px;
  line-height: 1.6;
  color: #354b59;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.scope-note p {
  margin: 0;
}
.small-sample-alert {
  color: #6b460a;
  font-weight: 650;
}
.block {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.block-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 4px 12px;
}
h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: #142f46;
}
.denom,
.empty-note {
  margin: 0;
  font-size: 12px;
  color: #4f6372;
}
.category-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.category-card {
  padding: 11px 12px;
  border: 1px solid #dce6e7;
  border-radius: 10px;
  background: #fbfaf6;
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
  font-size: 14px;
  color: #142f46;
}
.cat-count {
  font-size: 12px;
  font-weight: 650;
  color: #28566f;
  font-variant-numeric: tabular-nums;
}
.cat-metrics {
  margin: 8px 0 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.cat-metrics > div {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 8px;
}
.cat-metrics dt {
  color: #52667c;
  min-width: 64px;
}
.cat-metrics dd {
  margin: 0;
  color: #192743;
}
.delta,
.sub {
  color: #4f6372;
}
.stale-note {
  margin: 6px 0 0;
  font-size: 12px;
  color: #6b460a;
}
.sample-repos {
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px dashed #d3dfe0;
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.sample-label,
.sep {
  color: #52667c;
}
.repo-link {
  color: #1d4e6b;
  font-weight: 600;
  text-decoration: none;
  overflow-wrap: anywhere;
}
.repo-link:hover,
.repo-link:focus-visible {
  text-decoration: underline;
  text-underline-offset: 3px;
  color: #075981;
}
.ext-icon {
  margin-left: 2px;
  vertical-align: -2px;
}
.recurring-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.recurring-item {
  padding: 9px 12px;
  border: 1px solid #dce6e7;
  border-radius: 9px;
  background: #fbfaf6;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.rec-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
  min-width: 0;
}
.rec-name {
  font-size: 14px;
}
.cat-pill,
.off-anchor {
  font-size: 11px;
  font-weight: 650;
  padding: 2px 7px;
  border-radius: 6px;
}
.cat-pill {
  background: #e9f2f1;
  color: #1d4e5a;
}
.off-anchor {
  background: #eef1f3;
  color: #4f6372;
}
.rec-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  font-size: 12px;
  color: #475c6b;
  font-variant-numeric: tabular-nums;
}
.rec-stars {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: #6b460a;
  font-weight: 650;
}
@media (max-width: 620px) {
  .window-overview {
    padding: 12px;
  }
  .toggle-btn {
    width: 100%;
  }
  .category-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<script setup lang="ts">
import { computed } from 'vue'
import { ElIcon } from 'element-plus'
import { TopRight } from '@element-plus/icons-vue'
import type { TrendBriefing, TrendBriefingSignalType } from '../../api/trend'
import { trendTime } from './presentation'

interface Props {
  /** 7 日风口简报只读视图；未生成（ABSENT）或加载失败时为 null，绝不展示空态占位噪音。 */
  briefing: TrendBriefing | null
  /** 为锚点当日在榜的证据仓库生成站内详情链接，保持中键/键盘新开标签页自然工作。 */
  resolveDetailHref: (fullName: string) => string
}

interface Emits {
  openProject: [event: MouseEvent, fullName: string]
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const ready = computed(() => props.briefing?.status === 'READY')
const isTrio = computed(() => (props.briefing?.themes.length ?? 0) === 3)

const sampleTag = computed(() => {
  if (!props.briefing) return ''
  return props.briefing.sampleStatus === 'LIMITED' ? '有限样本' : '充分样本'
})

function signalLabel(type: TrendBriefingSignalType): string {
  if (type === 'CONCENTRATION') return '多仓聚集'
  if (type === 'RECURRING') return '持续霸榜'
  return '新苗头'
}

function themeNumber(index: number): string {
  return String(index + 1).padStart(2, '0')
}
</script>

<template>
  <section v-if="ready && briefing" class="briefing-card" aria-labelledby="briefing-headline">
    <div class="briefing-meta">
      <div class="meta-pills">
        <span class="ai-pill">
          <svg
            class="spark-icon"
            viewBox="0 0 16 16"
            width="13"
            height="13"
            fill="currentColor"
            aria-hidden="true"
          >
            <path d="M8 1.2 9.7 6.3 14.8 8 9.7 9.7 8 14.8 6.3 9.7 1.2 8 6.3 6.3 8 1.2Z" />
          </svg>
          AI 跨日综合 · 基于 {{ briefing.coveredDays }} 天样本
        </span>
        <span class="sample-pill" :class="briefing.sampleStatus.toLowerCase()">{{
          sampleTag
        }}</span>
      </div>
      <span v-if="briefing.generatedAt" class="generated-at">
        生成于 {{ trendTime(briefing.generatedAt) }}（北京时间）
      </span>
    </div>

    <p v-if="briefing.stale" class="stale-banner" role="status">
      基于较早日报快照生成，最新日报变动尚未重新综合，以下仍可结合下方实时 7 日统计核对。
    </p>

    <div class="briefing-lead">
      <h3 id="briefing-headline" class="headline">{{ briefing.headline }}</h3>
      <p class="overview">{{ briefing.overview }}</p>
    </div>

    <!-- 3 条主题采用「1 条通栏主风口 + 2 条并列宽卡」报刊头条结构，2 条时自然双列舒展。 -->
    <div class="theme-grid" :class="{ 'is-trio': isTrio }">
      <article
        v-for="(theme, index) in briefing.themes"
        :key="theme.title"
        class="theme-item"
        :class="{ 'is-lead': isTrio && index === 0 }"
      >
        <div class="theme-head">
          <div class="theme-heading">
            <span class="theme-index" aria-hidden="true">{{ themeNumber(index) }}</span>
            <h4 class="theme-title">{{ theme.title }}</h4>
          </div>
          <span class="signal-tag" :class="theme.signalType.toLowerCase()">
            {{ signalLabel(theme.signalType) }}
          </span>
        </div>
        <p class="theme-summary">{{ theme.summary }}</p>
        <div class="evidence-row" aria-label="实名证据仓库">
          <span class="evidence-label">证据仓</span>
          <div class="evidence-links">
            <template v-for="repo in theme.evidenceRepos" :key="repo.fullName">
              <a
                v-if="repo.inAnchorDay"
                class="repo-link in-site"
                :href="resolveDetailHref(repo.fullName)"
                @click="emit('openProject', $event, repo.fullName)"
              >
                {{ repo.fullName }}
              </a>
              <a
                v-else
                class="repo-link external"
                :href="repo.repoUrl"
                target="_blank"
                rel="noopener noreferrer"
              >
                {{ repo.fullName }}
                <ElIcon aria-hidden="true"><TopRight /></ElIcon>
              </a>
            </template>
          </div>
        </div>
      </article>
    </div>

    <!-- 冷静边界提醒默认折叠在简报底部，保持主阅读流清爽，需要核对局限时一键展开。 -->
    <details v-if="briefing.watchouts.length" class="watchouts">
      <summary class="watchout-summary">边界提醒（{{ briefing.watchouts.length }} 条）</summary>
      <ul>
        <li v-for="line in briefing.watchouts" :key="line">{{ line }}</li>
      </ul>
    </details>
  </section>
</template>

<style scoped>
.briefing-card {
  margin: 0 0 32px;
  padding: clamp(20px, 2.8vw, 30px);
  background: linear-gradient(180deg, #fafcfd 0%, #ffffff 130px);
  border: 1px solid #cddbe0;
  border-top: 3px solid var(--st-accent);
  border-radius: 16px;
}

.briefing-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 14px;
}

.meta-pills {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.ai-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 11px;
  border-radius: 999px;
  /* 暖金轻底直接呼应横幅弯月与星光，作为整页最克制的提亮点缀。 */
  background: #fdf6e4;
  border: 1px solid #eed79f;
  color: #7a5210;
  font-size: 12px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

.spark-icon {
  color: #c88a2b;
  flex: none;
}

.sample-pill {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid var(--st-line-strong);
  color: var(--st-muted);
}

.sample-pill.limited {
  background: #fdf6e7;
  border-color: #e6cfa0;
  color: #7c5617;
}

.sample-pill.sufficient {
  background: #eef6f1;
  border-color: #c2decb;
  color: #1f5c3e;
}

.generated-at {
  font-size: 12px;
  color: var(--st-muted);
  font-variant-numeric: tabular-nums;
}

.stale-banner {
  margin: 14px 0 0;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fdf6e7;
  border: 1px solid #ecd8ad;
  color: #7c5617;
  font-size: 13px;
  line-height: 1.55;
}

.briefing-lead {
  margin-top: 16px;
}

.headline {
  margin: 0 0 10px;
  font:
    700 clamp(21px, 2.5vw, 26px)/1.34 Georgia,
    'Songti SC',
    SimSun,
    serif;
  color: var(--st-ink-deep);
  text-wrap: balance;
  overflow-wrap: anywhere;
}

.overview {
  margin: 0;
  max-width: 76ch;
  color: #2f4657;
  font-size: 15px;
  line-height: 1.76;
  overflow-wrap: anywhere;
}

.theme-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 22px;
}

.theme-item {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 20px;
  border: 1px solid #dce6e9;
  border-radius: 12px;
  background: #f9fcfd;
  min-width: 0;
}

.theme-item.is-lead {
  grid-column: 1 / -1;
  padding: 20px 22px;
  border-color: #d7e2e6;
  border-left: 3px solid #d59a38;
  background: linear-gradient(135deg, #fffdf8 0%, #f8fbfd 68%);
}

.theme-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
}

.theme-heading {
  display: flex;
  align-items: baseline;
  gap: 9px;
  min-width: 0;
}

.theme-index {
  font:
    700 13px/1 Georgia,
    serif;
  letter-spacing: 0.04em;
  color: #637f92;
  font-variant-numeric: tabular-nums;
  flex: none;
}

.theme-item.is-lead .theme-index {
  color: #b7791f;
}

.theme-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--st-ink-deep);
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.theme-item.is-lead .theme-title {
  font-size: 17px;
}

.signal-tag {
  flex: none;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 650;
}

.signal-tag.concentration {
  background: #e5eff6;
  color: #16486b;
}

.signal-tag.recurring {
  background: #e4f0ee;
  color: #1d4e5a;
}

.signal-tag.emerging {
  background: #f2ecf7;
  color: #5b3a73;
}

.theme-summary {
  margin: 0;
  color: #253b4d;
  font-size: 14px;
  line-height: 1.72;
  overflow-wrap: anywhere;
}

.theme-item.is-lead .theme-summary {
  max-width: 78ch;
}

.evidence-row {
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px dashed #d7e3e7;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 10px;
}

.evidence-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--st-muted);
  flex: none;
}

.evidence-links {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 8px;
}

.repo-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid transparent;
  background: #eaf3f6;
  color: #194c69;
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.35;
  text-decoration: none;
  overflow-wrap: anywhere;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease;
}

.repo-link:hover {
  background: #dfeef3;
  border-color: #9cbccf;
  color: var(--st-ink-deep);
}

.repo-link:active {
  border-color: var(--st-ink-deep);
}

.repo-link:focus-visible {
  outline: 2px solid var(--st-accent);
  outline-offset: 2px;
}

.watchouts {
  margin-top: 18px;
  padding-top: 8px;
  border-top: 1px solid #e3ecef;
  font-size: 13px;
  color: var(--st-muted);
}

.watchout-summary {
  width: fit-content;
  max-width: 100%;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  padding: 6px 2px;
  cursor: pointer;
  font-size: 12.5px;
  font-weight: 650;
  color: #4f6678;
  font-variant-numeric: tabular-nums;
}

.watchout-summary:hover {
  color: var(--st-ink-deep);
}

.watchouts ul {
  margin: 4px 0 6px;
  padding-left: 18px;
  line-height: 1.65;
}

.watchouts li + li {
  margin-top: 4px;
}

@media (max-width: 720px) {
  .theme-grid {
    grid-template-columns: 1fr;
  }

  .theme-item.is-lead {
    grid-column: auto;
  }
}

@media (max-width: 620px) {
  .briefing-card {
    padding: 18px 16px;
    margin-bottom: 26px;
  }

  .theme-item,
  .theme-item.is-lead {
    padding: 15px 14px;
  }
}
</style>

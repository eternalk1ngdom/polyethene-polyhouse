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
const isLimited = computed(() => props.briefing?.sampleStatus === 'LIMITED')

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
          AI 风口速览
        </span>
        <!-- 满样本天数已在页眉展示，此处仅在有限样本时补充轻提示，避免重复标签。 -->
        <span v-if="isLimited" class="sample-note">· 有限样本</span>
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

    <!-- 2～3 条风向采用完全一致的等宽单列排版，并按风口信号语义轻度着色区分性质。 -->
    <div class="theme-list">
      <article
        v-for="(theme, index) in briefing.themes"
        :key="theme.title"
        class="theme-item"
        :class="theme.signalType.toLowerCase()"
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
          <span class="evidence-label">代表仓库</span>
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

    <!-- 冷静边界提醒默认折叠在简报底部，带明确展开指示箭头，保持主阅读流清爽。 -->
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
  margin: 0 0 34px;
  padding: clamp(22px, 2.8vw, 30px);
  background: #ffffff;
  border: 1px solid #dce6ea;
  border-radius: 16px;
  box-shadow: 0 4px 18px rgba(20, 47, 70, 0.04);
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
  padding: 4px 12px;
  border-radius: 999px;
  /* 星夜靛紫微渐变呼应月夜横幅夜空色调，与海蓝页面融洽并彻底区分黄色星标。 */
  background: linear-gradient(135deg, #eef1fc 0%, #f3ecfb 100%);
  border: 1px solid #d5d7f2;
  color: #3f3d8f;
  font-size: 12px;
  font-weight: 600;
}

.spark-icon {
  color: #635cc7;
  flex: none;
}

.sample-note {
  font-size: 12.5px;
  font-weight: 600;
  color: #8b5d16;
}

.generated-at {
  font-size: 12px;
  color: #5b7182;
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
  margin-top: 18px;
}

.headline {
  margin: 0 0 10px;
  font-family: var(--ph-font);
  font-size: clamp(20px, 2.2vw, 24px);
  font-weight: 600;
  line-height: 1.42;
  letter-spacing: -0.015em;
  color: var(--st-ink-deep);
  text-wrap: balance;
  overflow-wrap: anywhere;
}

.overview {
  margin: 0;
  max-width: 82ch;
  color: #364e60;
  font-size: 14.5px;
  line-height: 1.76;
  overflow-wrap: anywhere;
}

.theme-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 22px;
}

.theme-item {
  display: flex;
  flex-direction: column;
  gap: 11px;
  padding: 18px 20px;
  border: 1px solid #e4eef2;
  border-radius: 14px;
  background: #f7fafc;
  min-width: 0;
}

/* 三类风口信号共享相同结构与间距，仅以极淡同色系微底区分持续霸榜、新苗头与多仓聚集。 */
.theme-item.recurring {
  background: #f3faf9;
  border-color: #d8ece9;
}

.theme-item.emerging {
  background: #f8f5fc;
  border-color: #e6def2;
}

.theme-item.concentration {
  background: #f4f8fc;
  border-color: #dbe7f3;
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
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.theme-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: #e7f0f4;
  color: #38586b;
  font-size: 11.5px;
  font-weight: 700;
  line-height: 1.35;
  font-variant-numeric: tabular-nums;
  flex: none;
}

.theme-item.recurring .theme-index {
  background: #dff1ee;
  color: #17575e;
}

.theme-item.emerging .theme-index {
  background: #ede4f8;
  color: #563a7a;
}

.theme-item.concentration .theme-index {
  background: #e1eef9;
  color: #184b73;
}

.theme-title {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
  color: var(--st-ink-deep);
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.signal-tag {
  flex: none;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 600;
}

.signal-tag.concentration {
  background: #e1eef9;
  color: #184b73;
}

.signal-tag.recurring {
  background: #dff1ee;
  color: #17575e;
}

.signal-tag.emerging {
  background: #ede4f8;
  color: #563a7a;
}

.theme-summary {
  margin: 0;
  color: #2b4153;
  font-size: 14px;
  line-height: 1.74;
  overflow-wrap: anywhere;
}

.evidence-row {
  padding-top: 10px;
  border-top: 1px dashed #dce7eb;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 10px;
}

.theme-item.recurring .evidence-row {
  border-top-color: #cfe6e2;
}

.theme-item.emerging .evidence-row {
  border-top-color: #dfd4ee;
}

.theme-item.concentration .evidence-row {
  border-top-color: #d0e0ef;
}

.evidence-label {
  font-size: 12px;
  font-weight: 600;
  color: #5b7182;
  flex: none;
}

.evidence-links {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.repo-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 5px 11px;
  border-radius: 999px;
  border: 1px solid transparent;
  background: #ffffff;
  box-shadow: 0 1px 2px rgba(20, 47, 70, 0.05);
  color: #194c69;
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.35;
  text-decoration: none;
  overflow-wrap: anywhere;
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease;
}

.repo-link:hover {
  background: #f1f7fa;
  border-color: #a9c5d4;
  color: var(--st-ink-deep);
}

.repo-link:active {
  border-color: var(--st-ink-deep);
}

.watchouts {
  margin-top: 18px;
  padding-top: 10px;
  border-top: 1px solid #e7eff2;
  font-size: 13px;
  color: var(--st-muted);
}

.watchout-summary {
  width: fit-content;
  max-width: 100%;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  padding: 6px 10px 6px 4px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 12.5px;
  font-weight: 650;
  color: #4f6678;
  font-variant-numeric: tabular-nums;
  list-style: none;
  transition:
    background-color 0.16s ease,
    color 0.16s ease;
}

.watchout-summary::-webkit-details-marker {
  display: none;
}

.watchout-summary::before {
  content: '▸';
  display: inline-block;
  margin-right: 7px;
  color: #6b8496;
  transition: transform 0.16s ease;
}

.watchouts[open] .watchout-summary::before {
  transform: rotate(90deg);
}

.watchout-summary:hover {
  background: #f4f8fa;
  color: var(--st-ink-deep);
}

.watchouts ul {
  margin: 6px 0 4px;
  padding-left: 20px;
  line-height: 1.68;
}

.watchouts li + li {
  margin-top: 4px;
}

@media (max-width: 620px) {
  .briefing-card {
    padding: 18px 16px;
    margin-bottom: 26px;
  }

  .theme-item {
    padding: 16px 14px;
  }
}
</style>

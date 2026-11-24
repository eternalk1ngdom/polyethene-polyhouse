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

const sampleTag = computed(() => {
  if (!props.briefing) return ''
  return props.briefing.sampleStatus === 'LIMITED' ? '有限样本' : '充分样本'
})

function signalLabel(type: TrendBriefingSignalType): string {
  if (type === 'CONCENTRATION') return '多仓聚集'
  if (type === 'RECURRING') return '持续霸榜'
  return '新苗头'
}
</script>

<template>
  <section v-if="ready && briefing" class="briefing-card" aria-labelledby="briefing-headline">
    <div class="briefing-meta">
      <div class="meta-pills">
        <span class="ai-pill">AI 跨日综合 · 基于 {{ briefing.coveredDays }} 天样本</span>
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

    <h3 id="briefing-headline" class="headline">{{ briefing.headline }}</h3>
    <p class="overview">{{ briefing.overview }}</p>

    <div class="theme-grid">
      <article v-for="theme in briefing.themes" :key="theme.title" class="theme-item">
        <div class="theme-head">
          <h4 class="theme-title">{{ theme.title }}</h4>
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

    <aside v-if="briefing.watchouts.length" class="watchouts" aria-label="冷静边界提醒">
      <span class="watchout-title">边界提醒</span>
      <ul>
        <li v-for="line in briefing.watchouts" :key="line">{{ line }}</li>
      </ul>
    </aside>
  </section>
</template>

<style scoped>
.briefing-card {
  margin: 0 0 22px;
  padding: 20px 22px;
  background: var(--st-surface);
  border: 1px solid var(--st-line-strong);
  border-top: 3px solid var(--st-accent);
  border-radius: 12px;
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
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--st-pill-bg);
  color: var(--st-pill-ink);
  font-size: 12px;
  font-weight: 650;
}

.sample-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
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
  margin: 12px 0 0;
  padding: 8px 12px;
  border-radius: 8px;
  background: #f6f8fa;
  border: 1px solid var(--st-line);
  color: var(--st-muted);
  font-size: 13px;
  line-height: 1.5;
}

.headline {
  margin: 14px 0 8px;
  font:
    700 clamp(19px, 2.2vw, 23px)/1.32 Georgia,
    'Songti SC',
    SimSun,
    serif;
  color: var(--st-ink-deep);
  overflow-wrap: anywhere;
}

.overview {
  margin: 0;
  color: var(--st-ink);
  font-size: 14.5px;
  line-height: 1.68;
  overflow-wrap: anywhere;
}

.theme-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 14px;
  margin-top: 16px;
}

.theme-item {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 15px 16px;
  border: 1px solid var(--st-line);
  border-radius: 10px;
  background: #fafcfd;
  min-width: 0;
}

.theme-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 6px 10px;
}

.theme-title {
  margin: 0;
  font-size: 15.5px;
  font-weight: 700;
  color: var(--st-ink-deep);
  line-height: 1.38;
  overflow-wrap: anywhere;
}

.signal-tag {
  flex: none;
  padding: 2px 8px;
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
  background: #f4eef8;
  color: #5b3a73;
}

.theme-summary {
  margin: 0;
  color: var(--st-ink);
  font-size: 13.8px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.evidence-row {
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px dashed var(--st-line);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.evidence-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--st-muted);
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
  padding: 4px 9px;
  border-radius: 6px;
  border: 1px solid var(--st-line-strong);
  background: var(--st-surface);
  color: var(--st-link);
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
  background: #eef4f7;
  border-color: var(--st-accent);
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
  margin-top: 16px;
  padding: 10px 14px;
  border-left: 3px solid var(--st-line-strong);
  background: #f8fafb;
  border-radius: 0 8px 8px 0;
}

.watchout-title {
  display: block;
  font-size: 12px;
  font-weight: 700;
  color: var(--st-muted);
  margin-bottom: 4px;
}

.watchouts ul {
  margin: 0;
  padding-left: 18px;
  color: var(--st-muted);
  font-size: 13px;
  line-height: 1.58;
}

.watchouts li + li {
  margin-top: 3px;
}

@media (max-width: 640px) {
  .briefing-card {
    padding: 16px 14px;
  }

  .theme-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

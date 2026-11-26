<script setup lang="ts">
import { computed } from 'vue'
import { ElIcon } from 'element-plus'
import { StarFilled } from '@element-plus/icons-vue'
import type { TrendProject } from '../../api/trend'
import { languageColor } from './languageColors'
import { categoryLabel, categoryTone, presenceBadge, trendNumber } from './presentation'

interface Props {
  /** 排名和星标取自同一期，筛选后仍可与来源逐项核对。 */
  project: TrendProject
  /** 保留真实地址，让新标签页、复制链接与键盘访问自然工作。 */
  href: string
}
interface Emits {
  open: [event: MouseEvent, fullName: string]
}
const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const badge = computed(() => presenceBadge(props.project.presence))
</script>
<template>
  <article class="entry">
    <span class="rank" :aria-label="`来源排名 ${project.rankNum}`">{{
      String(project.rankNum).padStart(2, '0')
    }}</span>
    <div class="summary">
      <h2>
        <a
          :id="'repo-' + project.fullName"
          :href="href"
          @click="emit('open', $event, project.fullName)"
          ><span class="org">{{ project.fullName.split('/')[0] }}/</span><wbr />{{
            project.fullName.split('/')[1]
          }}</a
        >
      </h2>
      <p v-if="project.insight || project.description" class="excerpt">
        <span class="origin">{{
          project.insight
            ? project.insightStatus === 'STALE'
              ? '沿用旧解读'
              : 'AI 解读'
            : 'GitHub 简介'
        }}</span>
        {{ project.insight?.definition ?? project.description }}
      </p>
      <div class="meta-tags">
        <span v-if="badge" class="presence-badge" :class="`presence-${badge.tone}`">
          {{ badge.label }}
          <span
            v-if="badge.rankRise"
            class="rank-rise"
            :aria-label="`较上期排名上升 ${badge.rankRise.slice(1)} 位`"
            >{{ badge.rankRise }}</span
          >
        </span>
        <span
          class="language"
          :style="{ '--lang-color': languageColor(project.primaryLanguage) }"
          >{{ project.primaryLanguage ?? '语言未知' }}</span
        >
        <span
          v-if="project.insight"
          class="category"
          :style="{
            backgroundColor: categoryTone(project.insight.category).bg,
            color: categoryTone(project.insight.category).ink,
          }"
          >{{ categoryLabel(project.insight.category) }}</span
        >
      </div>
    </div>
    <span class="stars" role="img" :aria-label="`本期新增星标：${trendNumber(project.starsAdded)}`"
      ><ElIcon aria-hidden="true"><StarFilled /></ElIcon
      >{{ project.starsAdded === null ? '—' : '+' + trendNumber(project.starsAdded) }}</span
    >
  </article>
</template>
<style scoped>
.entry {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: start;
  gap: 16px;
  padding: 17px 0;
  border-bottom: 1px solid #d7dedf;
}
.rank {
  color: #52667c;
  font:
    italic 21px/1.5 Georgia,
    serif;
}
.summary {
  min-width: 0;
  overflow-wrap: anywhere;
}
h2 {
  font-size: clamp(18px, 2.4vw, 24px);
  line-height: 1.32;
  margin: 0;
  letter-spacing: -0.035em;
}
.org {
  color: #4f6372;
  font-weight: 450;
}
h2 a:hover,
h2 a:focus-visible {
  text-decoration: underline;
  text-underline-offset: 4px;
  color: #075981;
}
.excerpt {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  color: #354b59;
  margin: 6px 0 0;
  line-height: 1.55;
  font-size: 14px;
}
.origin {
  color: #275a63;
  font-size: 11px;
  font-weight: 700;
  margin-right: 4px;
}
.meta-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
  margin-top: 6px;
}
.language {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #41576a;
}
.category {
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--st-pill-bg);
  color: var(--st-pill-ink);
}
.presence-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  font-weight: 650;
  line-height: 1.3;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid transparent;
  font-variant-numeric: tabular-nums;
}
.presence-new-top {
  background: #fdece7;
  border-color: #f0bfb2;
  color: #8f2d1b;
}
.presence-new {
  background: #e2f5f4;
  border-color: #a9dedb;
  color: #0e5a63;
}
.presence-returning {
  background: #efeaf8;
  border-color: #d3c6eb;
  color: #4b3d7a;
}
.presence-streak {
  background: #f1f5f7;
  border-color: #d9e3e8;
  color: #566a78;
  font-weight: 550;
}
.rank-rise {
  color: #1f6e58;
  font-weight: 700;
}
.language::before {
  content: '';
  width: 8px;
  height: 8px;
  background: var(--lang-color, #6e7f8d);
  box-shadow: inset 0 0 0 1px rgba(20, 47, 70, 0.16);
  border-radius: 50%;
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
@media (max-width: 620px) {
  .entry {
    grid-template-columns: 26px minmax(0, 1fr) auto;
    gap: 8px;
    padding-block: 14px;
  }
  .rank {
    font-size: 17px;
  }
  .summary {
    display: contents;
  }
  h2 {
    grid-column: 2 / -1;
  }
  .excerpt {
    grid-column: 2 / -1;
    margin: 0;
  }
  .meta-tags {
    grid-column: 2;
    align-self: center;
    margin: 0;
  }
  .stars {
    grid-column: 3;
    font-size: 12px;
  }
}
</style>

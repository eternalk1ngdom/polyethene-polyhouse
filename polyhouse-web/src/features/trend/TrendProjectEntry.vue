<script setup lang="ts">
import { ElIcon } from 'element-plus'
import { TopRight } from '@element-plus/icons-vue'
import type { TrendProject } from '../../api/trend'
import { insightLabel, trendNumber, trendTime } from './presentation'

interface Props {
  /** 绑定本期快照，列表中的文字只按普通文本显示。 */
  project: TrendProject
}
defineProps<Props>()
</script>

<template>
  <article class="entry">
    <span class="rank" :aria-label="`来源排名 ${project.rankNum}`">{{
      String(project.rankNum).padStart(2, '0')
    }}</span>
    <div class="entry-body">
      <div class="headline">
        <h2>
          <a :href="project.repoUrl" target="_blank" rel="noopener noreferrer"
            >{{ project.fullName }}<ElIcon aria-hidden="true"><TopRight /></ElIcon
          ></a>
        </h2>
        <span class="stars"
          ><strong>{{
            project.starsAdded === null ? '未知' : `+${trendNumber(project.starsAdded)}`
          }}</strong>
          本期新增星标</span
        >
      </div>
      <div class="facts">
        <span>{{ project.primaryLanguage ?? '语言未知' }}</span>
        <span>总星标 {{ trendNumber(project.totalStars) }}</span>
        <span>Fork {{ trendNumber(project.forksCount) }}</span>
        <span>{{ project.license ?? '许可证未识别' }}</span>
        <span v-if="project.archived">已归档</span>
      </div>
      <p v-if="project.description" class="source">
        <strong>GitHub 简介</strong> {{ project.description }}
      </p>
      <details v-if="project.insight" class="insight">
        <summary>
          <span class="insight-status">{{ insightLabel(project.insightStatus) }}</span>
          {{ project.insight.definition }}
        </summary>
        <div class="detail">
          <section>
            <h3>解决什么问题</h3>
            <p>{{ project.insight.painPoint }}</p>
          </section>
          <section v-if="project.insight.highlights.length">
            <h3>技术亮点</h3>
            <ul>
              <li v-for="(line, i) in project.insight.highlights" :key="i">{{ line }}</li>
            </ul>
          </section>
          <section v-if="project.insight.comparison">
            <h3>同类差异</h3>
            <p>{{ project.insight.comparison }}</p>
          </section>
          <section v-if="project.insight.documentedLimitations.length">
            <h3>项目文档明确的限制</h3>
            <ul>
              <li v-for="(line, i) in project.insight.documentedLimitations" :key="i">
                {{ line }}
              </li>
            </ul>
          </section>
          <section v-if="project.insight.inferredRisks.length">
            <h3>AI 推测的风险</h3>
            <ul>
              <li v-for="(line, i) in project.insight.inferredRisks" :key="i">{{ line }}</li>
            </ul>
          </section>
          <small
            >AI 解读 · {{ project.insight.model }} ·
            {{ trendTime(project.insight.analyzedAt) }}（北京时间）</small
          >
        </div>
      </details>
      <p v-else class="source">{{ insightLabel(project.insightStatus) }}</p>
    </div>
  </article>
</template>

<style scoped>
.entry {
  display: grid;
  grid-template-columns: 2.2rem minmax(0, 1fr);
  gap: clamp(8px, 2vw, 22px);
  border-top: 1px solid #ded9e2;
  padding: 20px 0;
}
.rank {
  color: #96395f;
  font:
    22px/1.2 Georgia,
    serif;
  font-variant-numeric: tabular-nums;
}
.entry-body {
  min-width: 0;
  overflow-wrap: anywhere;
}
.headline,
.facts {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 5px 16px;
}
.headline {
  justify-content: space-between;
}
h2,
p {
  margin: 0;
}
h2 {
  min-width: 0;
  font-size: clamp(18px, 2.7vw, 25px);
  line-height: 1.35;
}
h2 a {
  color: inherit;
}
h2 a:hover {
  text-decoration: underline;
  text-underline-offset: 4px;
}
h2 .el-icon {
  margin-inline-start: 5px;
  font-size: 0.64em;
  vertical-align: baseline;
  color: #96395f;
}
.stars {
  color: #5f5364;
  font-size: 13px;
}
.stars strong {
  color: #96395f;
  font-size: 15px;
  font-variant-numeric: tabular-nums;
}
.facts,
.source {
  color: #574d61;
  font-size: 13px;
  line-height: 1.65;
}
.facts {
  margin-top: 6px;
}
.source {
  margin-top: 8px;
}
.source strong {
  margin-inline-end: 5px;
}
.insight {
  margin-top: 9px;
}
summary {
  cursor: pointer;
  padding-block: 8px;
  line-height: 1.55;
}
summary:hover .insight-status {
  text-decoration: underline;
}
.insight-status {
  color: #8a2955;
  font-weight: 700;
  margin-inline-end: 6px;
}
.detail {
  display: grid;
  gap: 12px;
  margin-top: 4px;
  padding: 13px 16px;
  border-inline-start: 2px solid #ad577a;
  background: #f4edf2;
}
.detail h3,
.detail ul {
  margin: 0;
}
.detail h3 {
  font-size: 14px;
}
.detail ul {
  padding-inline-start: 20px;
}
.detail small {
  color: #624f65;
}
@media (max-width: 520px) {
  .entry {
    grid-template-columns: 1.7rem minmax(0, 1fr);
  }
  .headline {
    align-items: flex-start;
    flex-direction: column;
  }
  .detail {
    padding-inline: 10px;
  }
}
</style>

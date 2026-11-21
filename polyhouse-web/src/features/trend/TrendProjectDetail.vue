<script setup lang="ts">
import { computed } from 'vue'
import { ElIcon } from 'element-plus'
import { TopRight, StarFilled } from '@element-plus/icons-vue'
import voyage from '../../assets/startide-voyage.webp'
import type { TrendProject, TrendReport } from '../../api/trend'
import { insightLabel, insightReasonLabel, trendNumber, trendTime } from './presentation'

interface Props {
  /** 日报内的仓库快照，同名仓库在不同期次可有不同解读。 */
  project: TrendProject
  /** 期号和采集时间随快照展示，避免误读为实时数据。 */
  report: TrendReport
}
const props = defineProps<Props>()
const insight = computed(() => props.project.insight)
const readmeLabels: Record<string, string> = {
  AVAILABLE: '本期已取得',
  MISSING: '仓库未提供',
  UNAVAILABLE: '本期未取得',
}
const facts = computed(() => [
  ['总星标', trendNumber(props.project.totalStars)],
  ['Fork', trendNumber(props.project.forksCount)],
  ['主语言', props.project.primaryLanguage ?? '未知'],
  ['许可证', props.project.license ?? '未识别'],
  ['README', readmeLabels[props.project.readmeStatus] ?? '未知'],
  [
    '归档状态',
    props.project.archived === null ? '未知' : props.project.archived ? '已归档' : '未归档',
  ],
])
</script>
<template>
  <article class="story" :key="project.fullName">
    <header class="story-head">
      <p class="dateline"><em>StarTide</em> {{ report.periodKey }}</p>
      <h1 tabindex="-1">
        <small>{{ project.fullName.split('/')[0] }} /</small>{{ project.fullName.split('/')[1] }}
      </h1>
      <a class="repo" :href="project.repoUrl" target="_blank" rel="noopener noreferrer"
        >在 GitHub 查看 <ElIcon aria-hidden="true"><TopRight /></ElIcon
      ></a>
      <div class="edition-stamp">
        <img :src="voyage" alt="" />
        <span>日榜第 {{ project.rankNum }} 名</span>
      </div>
    </header>
    <div class="spread">
      <div class="copy">
        <template v-if="insight">
          <header class="lede">
            <strong>{{
              project.insightStatus === 'ANALYZED'
                ? '本期 AI 解读'
                : 'AI · ' + insightLabel(project.insightStatus)
            }}</strong>
            <small v-if="project.insightStatus === 'REUSED'">
              本期于 {{ trendTime(report.capturedAt) }} 核对资料，原稿生成于
              {{ trendTime(insight.analyzedAt) }}（北京时间）
            </small>
            <small v-else-if="project.insightStatus === 'STALE'"
              >原生成于 {{ trendTime(insight.analyzedAt) }}（北京时间）</small
            >
            <small v-if="project.insightStatus === 'STALE' && project.insightReason">
              {{ insightReasonLabel(project.insightReason) }}
            </small>
            <p>{{ insight.definition }}</p>
          </header>
          <section>
            <h2>解决什么问题</h2>
            <p>{{ insight.painPoint }}</p>
          </section>
          <section v-if="insight.highlights.length">
            <h2>技术亮点</h2>
            <ul>
              <li v-for="(line, i) in insight.highlights" :key="i">{{ line }}</li>
            </ul>
          </section>
          <section v-if="insight.comparison">
            <h2>同类差异</h2>
            <p>{{ insight.comparison }}</p>
          </section>
          <section v-if="insight.documentedLimitations.length">
            <h2>项目文档明确的限制</h2>
            <ul>
              <li v-for="(line, i) in insight.documentedLimitations" :key="i">{{ line }}</li>
            </ul>
          </section>
          <section v-if="insight.inferredRisks.length">
            <h2>AI 推测的风险</h2>
            <ul>
              <li v-for="(line, i) in insight.inferredRisks" :key="i">{{ line }}</li>
            </ul>
          </section>
          <details>
            <summary>解读依据</summary>
            <dl>
              <dt>模型</dt>
              <dd>{{ insight.model }}</dd>
              <dt>生成时间</dt>
              <dd>{{ trendTime(insight.analyzedAt) }}（北京时间）</dd>
              <dt>资料版本</dt>
              <dd>{{ insight.readmeVersion }}</dd>
              <dt>解读规则</dt>
              <dd>{{ insight.ruleVersion }}</dd>
            </dl>
          </details>
        </template>
        <section class="original">
          <h2>GitHub 原始简介</h2>
          <p>{{ project.description ?? '仓库未提供简介。' }}</p>
        </section>
        <p v-if="!insight" class="note">
          {{
            project.insightReason
              ? insightReasonLabel(project.insightReason)
              : insightLabel(project.insightStatus)
          }}
        </p>
      </div>
      <aside aria-label="本期仓库资料">
        <h2>仓库快照</h2>
        <div class="gain">
          <span>本期新增星标</span
          ><strong
            ><ElIcon aria-hidden="true"><StarFilled /></ElIcon
            >{{
              project.starsAdded === null ? '未知' : '+' + trendNumber(project.starsAdded)
            }}</strong
          >
        </div>
        <dl>
          <template v-for="[label, value] in facts" :key="label"
            ><dt>{{ label }}</dt>
            <dd>{{ value }}</dd></template
          >
        </dl>
        <p class="note">采集于 {{ trendTime(report.capturedAt) }}（北京时间）</p>
      </aside>
    </div>
  </article>
</template>
<style scoped>
.story {
  overflow-wrap: anywhere;
}
.story-head {
  position: relative;
  border-top: 2px solid #223e55;
  border-bottom: 1px solid #afc3ce;
  padding: clamp(18px, 4vw, 42px);
  background: #e3f0f4;
}
.dateline {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 12px;
  font-size: 13px;
  color: #284d68;
}
.dateline em {
  color: #19314c;
  font:
    italic 20px Georgia,
    serif;
}
.edition-stamp {
  position: absolute;
  top: 50%;
  right: 6%;
  transform: translateY(-50%) rotate(6deg);
  width: clamp(94px, 15vw, 145px);
  padding: 6px;
  background: #fff9e4;
  border: 3px dotted #afbfbd;
  text-align: center;
  box-shadow: 3px 4px 0 #bed1d5;
}
.edition-stamp img {
  display: block;
  width: 100%;
  background: #d8eaf0;
}
.edition-stamp span {
  display: block;
  margin-top: 5px;
  font-size: 12px;
  font-weight: 700;
}
.story .story-head {
  padding-inline-end: clamp(150px, 27%, 290px);
}
@media (max-width: 520px) {
  .story .story-head {
    padding-inline-end: 18px;
  }
  .edition-stamp {
    position: static;
    transform: none;
    width: auto;
    padding: 0;
    border: 0;
    background: transparent;
    box-shadow: none;
    text-align: start;
  }
  .edition-stamp img {
    display: none;
  }
}
.story-head h1 {
  width: fit-content;
  max-width: 100%;
  font-size: clamp(30px, 5vw, 58px);
  letter-spacing: -0.04em;
  line-height: 1.1;
  margin: 20px 0 10px;
}
h1 small {
  display: block;
  font-size: 0.43em;
  margin-bottom: 6px;
  font-weight: 500;
  color: #415e74;
  letter-spacing: 0;
}
.repo {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #194c68;
  font-weight: 650;
  min-height: 44px;
  text-decoration: underline;
  text-underline-offset: 4px;
}
.spread {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(215px, 28%);
  gap: clamp(20px, 6vw, 80px);
  margin-top: 28px;
}
.copy {
  min-width: 0;
}
.copy p,
li {
  line-height: 1.8;
  margin: 5px 0;
}
.copy h2,
aside h2 {
  font-size: 20px;
  margin: 0 0 8px;
}
.copy section,
details {
  margin-bottom: 24px;
}
.lede {
  padding: 14px 20px;
  border-inline-start: 4px solid #179983;
  background: #e3f4ed;
  margin-bottom: 25px;
}
.lede strong,
.note,
.lede small {
  color: #355864;
  font-size: 13px;
}
.lede small {
  display: block;
}
.lede p {
  font-size: clamp(18px, 2.2vw, 23px);
  font-weight: 600;
  line-height: 1.55;
}
ul {
  padding-left: 1.4em;
  margin: 0;
}
li::marker {
  color: #28788b;
}
summary {
  cursor: pointer;
  padding: 12px 0;
  font-weight: 650;
}
details,
.original {
  border-top: 1px solid #cad7d9;
}
.original {
  padding-top: 16px;
}
aside {
  align-self: start;
  background: #fff;
  border: 1px solid #d5dedf;
  border-radius: 14px;
  padding: 20px;
}
.gain {
  background: #ffebaa;
  border-radius: 8px;
  padding: 12px;
  margin: 14px 0;
  display: grid;
  gap: 3px;
  color: #563817;
}
.gain span {
  font-size: 12px;
}
.gain strong {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 25px;
}
dl {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 8px 12px;
  margin: 8px 0;
  font-size: 14px;
}
dt {
  color: #4e606a;
}
dd {
  margin: 0;
  font-weight: 600;
}
aside dd {
  text-align: right;
}
.note {
  margin: 16px 0 0;
}
@media (max-width: 700px) {
  .spread {
    display: flex;
    flex-direction: column;
    gap: 17px;
    margin-top: 18px;
  }
  aside {
    width: 100%;
  }
  .copy {
    display: contents;
  }
  .copy > * {
    order: 2;
  }
  .copy > :first-child {
    order: 0;
  }
  aside {
    order: 1;
  }
}
</style>

<script setup lang="ts">
import { computed } from 'vue'
import { ElIcon } from 'element-plus'
import { TopRight, StarFilled } from '@element-plus/icons-vue'
import voyage from '../../assets/startide-voyage.webp'
import type { TrendProject, TrendReport } from '../../api/trend'
import { languageColor } from './languageColors'
import {
  insightLabel,
  insightReasonLabel,
  trendNumber,
  trendParagraphs,
  trendTime,
} from './presentation'

interface Props {
  /** 日报内的仓库快照，同名仓库在不同期次可有不同解读。 */
  project: TrendProject
  /** 期号和采集时间随快照展示，避免误读为实时数据。 */
  report: TrendReport
}
const props = defineProps<Props>()
const insight = computed(() => props.project.insight)
const usageParagraphs = computed(() =>
  insight.value ? trendParagraphs(insight.value.painPoint) : [],
)
/** 仅当存在原始简介且译文与原文不同时展示双语参考译文，避免中文原简介重复显示。 */
const descriptionZh = computed(() => {
  const raw = props.project.description?.trim()
  const translated = insight.value?.descriptionZh?.trim()
  if (!raw || !translated || translated === raw) return null
  return translated
})
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
        <small>{{ project.fullName.split('/')[0] }}/</small><wbr />{{ project.fullName.split('/')[1] }}
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
          <section class="usage-body">
            <h2>它能帮你做什么</h2>
            <p v-for="(paragraph, i) in usageParagraphs" :key="i">{{ paragraph }}</p>
          </section>
          <section v-if="insight.highlights.length">
            <h2>技术亮点</h2>
            <ul>
              <li v-for="(line, i) in insight.highlights" :key="i">{{ line }}</li>
            </ul>
          </section>
          <section v-if="insight.trendSignal" class="trend-signal">
            <div class="signal-head">
              <h2>技术风向观察</h2>
              <span class="signal-badge">AI 趋势解读</span>
            </div>
            <p>{{ insight.trendSignal }}</p>
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
          <template v-if="project.description">
            <p class="original-raw">{{ project.description }}</p>
            <p v-if="descriptionZh" class="original-zh">
              <span class="original-zh-tag">参考译文</span>{{ descriptionZh }}
            </p>
          </template>
          <p v-else>仓库未提供简介。</p>
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
            <dd>
              <span
                v-if="label === '主语言'"
                class="lang-dot"
                aria-hidden="true"
                :style="{ backgroundColor: languageColor(project.primaryLanguage) }"
              />{{ value }}
            </dd></template
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
  padding: clamp(14px, 2.6vw, 24px) clamp(16px, 3vw, 28px);
  background: #e3f0f4;
}
.dateline {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 10px;
  font-size: 12.5px;
  color: #284d68;
}
.dateline em {
  color: #19314c;
  font:
    italic 17px Georgia,
    serif;
}
.edition-stamp {
  position: absolute;
  top: 50%;
  right: 4.5%;
  transform: translateY(-50%) rotate(5deg);
  width: clamp(78px, 11vw, 108px);
  padding: 5px;
  background: #fff9e4;
  border: 2px dotted #afbfbd;
  text-align: center;
  box-shadow: 2px 3px 0 #bed1d5;
}
.edition-stamp img {
  display: block;
  width: 100%;
  background: #d8eaf0;
}
.edition-stamp span {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.25;
}
.story .story-head {
  padding-inline-end: clamp(124px, 21%, 195px);
}
@media (max-width: 520px) {
  .story .story-head {
    padding-inline-end: 16px;
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
  font-size: clamp(24px, 3vw, 34px);
  letter-spacing: -0.025em;
  line-height: 1.2;
  margin: 10px 0 4px;
}
h1 small {
  display: inline;
  font-size: 0.72em;
  margin-inline-end: 2px;
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
  margin-top: 22px;
}
.copy {
  min-width: 0;
}
.copy p,
li {
  line-height: 1.8;
  margin: 5px 0;
}
.usage-body p + p {
  margin-top: 12px;
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
.trend-signal {
  padding: 16px 0;
  border-top: 1px solid #cad7d9;
  border-bottom: 1px solid #cad7d9;
  background: transparent;
}
.trend-signal + details {
  border-top: 0;
  margin-top: -16px;
}
.signal-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 10px;
  margin-bottom: 8px;
}
.signal-head h2 {
  margin: 0;
}
.signal-badge {
  display: inline-flex;
  align-items: center;
  padding: 1px 7px;
  border: 1px solid #c8d5db;
  border-radius: 999px;
  background: transparent;
  color: #475c6b;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
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
.original-raw {
  color: #364a59;
}
.original-zh {
  margin-top: 8px;
  padding-left: 10px;
  border-left: 2px solid #cbd6d8;
  color: #192743;
}
.original-zh-tag {
  display: inline-block;
  margin-right: 8px;
  font-size: 12px;
  font-weight: 600;
  color: #475c6b;
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
.lang-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 6px;
  border-radius: 50%;
  vertical-align: middle;
  box-shadow: inset 0 0 0 1px rgba(20, 47, 70, 0.16);
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

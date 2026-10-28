<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ElIcon } from 'element-plus'
import { ArrowDown, ArrowRight, Document, Picture, Right } from '@element-plus/icons-vue'
import { toolRegistry } from '../config/toolRegistry'

// 字符串元数据在展示层转换成图标组件，注册表保持与 UI 解耦。
const toolIcons = { Document, Picture }
</script>

<template>
  <div class="home-page">
    <section class="hero" aria-labelledby="hero-title">
      <div class="hero-copy">
        <p class="eyebrow"><span />日常小事，也值得顺手一点</p>
        <h1 id="hero-title">少一点繁琐，<br />多一点<span class="highlight">从容。</span></h1>
        <p class="hero-description">把重复的工作交给工具，<br />把时间留给更喜欢的事。</p>
        <div class="hero-actions">
          <RouterLink class="primary-action" :to="{ path: '/', hash: '#tools' }"
            >发现小工具<ElIcon><ArrowDown /></ElIcon
          ></RouterLink>
          <RouterLink class="secondary-action" :to="{ path: '/', hash: '#about' }"
            >认识聚妙屋<ElIcon><ArrowRight /></ElIcon
          ></RouterLink>
        </div>
        <p class="hero-note">
          <span class="tiny-cross" aria-hidden="true">✳</span> 简单一点，刚刚好。
        </p>
      </div>

      <!-- 装饰示意只表达工具用途；没有上传入口，也不模拟真实处理结果。 -->
      <div class="hero-art" role="img" aria-label="文本替换与图片大小调整的工具概念插画">
        <span class="art-grid" />
        <span class="art-orbit" />
        <span class="art-spark spark-one">✳</span>
        <div class="demo-paper">
          <div class="paper-top">
            <span class="paper-dot" /><span class="paper-dot" /><span class="paper-dot" /><span
              class="paper-label"
              >一点小改变</span
            >
          </div>
          <div class="paper-content">
            <span class="demo-label">让重复，变轻松</span>
            <div class="word-before">繁琐的日常</div>
            <ElIcon class="paper-arrow"><Right /></ElIcon>
            <div class="word-after">顺手的日常<span class="text-cursor" /></div>
          </div>
          <div class="paper-bottom"><span>PolyReplace</span><span>文本替换 · 概念示意</span></div>
        </div>
        <div class="size-chip">
          <span class="chip-icon"
            ><ElIcon><Picture /></ElIcon></span
          ><span><strong>刚刚好的大小</strong><small>TargetKB · 图片处理</small></span
          ><span class="chip-dots" aria-hidden="true">···</span>
        </div>
        <span class="art-caption">SMALL TOOLS. MORE POSSIBILITIES.</span>
      </div>
    </section>

    <section id="tools" class="tools-section" aria-labelledby="tools-title">
      <div class="section-heading">
        <div>
          <p class="section-kicker">THE TOOLBOX</p>
          <h2 id="tools-title">你的日常工具箱<span>一点点，变好用。</span></h2>
        </div>
        <span class="section-aside">从小事出发，认真打磨</span>
      </div>
      <div class="tool-grid">
        <RouterLink
          v-for="(tool, index) in toolRegistry"
          :key="tool.code"
          :to="tool.route"
          class="tool-card"
          :class="tool.accent"
        >
          <div class="card-top">
            <span class="tool-icon"
              ><ElIcon><component :is="toolIcons[tool.icon]" /></ElIcon></span
            ><span class="tool-status">{{ tool.status === 'planned' ? '开发中' : '可使用' }}</span>
          </div>
          <div class="tool-title">
            <h3>{{ tool.title }}</h3>
            <span>{{ tool.subtitle }}</span>
          </div>
          <p class="tool-description">{{ tool.description }}</p>
          <div class="card-bottom">
            <span class="tool-category"
              ><span class="tool-number">0{{ index + 1 }}</span
              >{{ tool.tag }}</span
            ><span class="tool-open"
              >{{ tool.status === 'planned' ? '了解工具' : '打开工具'
              }}<ElIcon><ArrowRight /></ElIcon
            ></span>
          </div>
        </RouterLink>
      </div>
    </section>

    <section id="about" class="about-section" aria-labelledby="about-title">
      <div class="about-symbol" aria-hidden="true">✳</div>
      <div>
        <p class="section-kicker">A LITTLE HOUSE FOR USEFUL THINGS</p>
        <h2 id="about-title">不必复杂，好用就好。</h2>
        <p class="about-description">聚妙屋，收集日常里的小麻烦，慢慢做成顺手的小工具。</p>
      </div>
      <p class="about-note">从文字，到图片。<br />一间小屋，慢慢添置。</p>
    </section>
  </div>
</template>

<style scoped>
.home-page {
  width: min(1200px, calc(100% - 96px));
  margin: auto;
}
.hero {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  align-items: center;
  gap: 28px;
  min-height: 560px;
  padding: 64px 0 54px;
}
.eyebrow {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0 0 26px;
  color: #7e5765;
  font-size: 13px;
  letter-spacing: 1px;
}
.eyebrow > span {
  width: 7px;
  height: 7px;
  background: #ab788a;
  border-radius: 50%;
}
h1 {
  font-size: clamp(42px, 4.5vw, 64px);
  line-height: 1.3;
  letter-spacing: -2px;
  margin: 0;
  font-weight: 750;
}
.highlight {
  position: relative;
  z-index: 0;
  white-space: nowrap;
}
.highlight::after {
  content: '';
  position: absolute;
  z-index: -1;
  background: #faedf2;
  height: 17px;
  left: 0;
  right: 7px;
  bottom: 6px;
  border-radius: 2px;
  transform: rotate(-2deg);
}
.hero-description {
  font-size: 16px;
  line-height: 1.95;
  color: var(--ph-muted);
  margin: 22px 0 28px;
}
.hero-actions {
  display: flex;
  align-items: center;
  gap: 26px;
}
.primary-action,
.secondary-action {
  display: inline-flex;
  align-items: center;
  gap: 22px;
  font-size: 14px;
}
.primary-action {
  padding: 15px 21px;
  background: #f7e8ee;
  color: #613e4d;
  border: 1px solid #eed9e2;
  border-radius: 9px;
  transition: background 0.18s;
}
.primary-action:hover {
  background: #f0dbe4;
}
.secondary-action {
  color: #775662;
  gap: 9px;
  padding: 12px 0;
}
.secondary-action:hover {
  color: var(--ph-green);
  text-decoration: underline;
  text-underline-offset: 5px;
}
.hero-note {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: #80636d;
  margin: 28px 0 0;
}
.tiny-cross {
  font-size: 20px;
  color: #9e7b87;
}
.hero-art {
  height: 390px;
  position: relative;
  display: grid;
  place-items: center;
  isolation: isolate;
}
.art-grid {
  position: absolute;
  z-index: -2;
  inset: 20px 0 32px;
  background-image: radial-gradient(#e6cbd5 1px, transparent 1px);
  background-size: 19px 19px;
  mask-image: radial-gradient(ellipse, black 35%, transparent 72%);
}
.art-orbit {
  position: absolute;
  z-index: -1;
  width: 300px;
  height: 300px;
  top: 25px;
  right: 35px;
  background: #fcf4f8;
  border-radius: 50%;
}
.demo-paper {
  width: 350px;
  background: #ffffff;
  border: 1px solid #e6cfd7;
  border-radius: 14px;
  transform: rotate(-6deg) translate(-8px, -8px);
  box-shadow: 8px 12px 0 #f2dee5;
}
.paper-top {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 17px 18px;
  border-bottom: 1px solid #f0e3e8;
}
.paper-dot {
  width: 6px;
  height: 6px;
  border: 1px solid #dac2cb;
  border-radius: 50%;
}
.paper-label {
  font-size: 10px;
  color: #8e6c78;
  margin-left: auto;
}
.paper-content {
  padding: 26px 30px 30px;
}
.demo-label {
  font-size: 11px;
  color: #8e6c78;
}
.word-before {
  font-size: 25px;
  color: #ae949d;
  text-decoration: line-through;
  text-decoration-thickness: 1px;
  margin-top: 18px;
}
.paper-arrow {
  margin: 12px 0 8px;
  font-size: 23px;
  color: #a17484;
}
.word-after {
  font-size: 29px;
  font-weight: 650;
  color: #80546d;
  white-space: nowrap;
}
.text-cursor {
  display: inline-block;
  width: 2px;
  height: 25px;
  background: #9a6a7b;
  margin-left: 6px;
  vertical-align: -2px;
}
.paper-bottom {
  padding: 12px 18px;
  border-top: 1px solid #f0e3e8;
  display: flex;
  justify-content: space-between;
  gap: 8px;
  color: #8e6c78;
  font-size: 9px;
}
.size-chip {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 12px;
  right: 2px;
  bottom: 24px;
  width: 255px;
  padding: 18px;
  border: 1px solid #e8e2f1;
  background: #f8f5fc;
  border-radius: 12px;
  transform: rotate(5deg);
  box-shadow: 0 6px 18px #55446b10;
}
.chip-icon {
  width: 38px;
  height: 42px;
  background: #faf8ff;
  border: 1px solid #eee9f6;
  border-radius: 7px;
  display: grid;
  place-items: center;
  color: #776989;
  font-size: 21px;
}
.size-chip strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: #504858;
}
.size-chip small {
  display: block;
  margin-top: 6px;
  font-size: 9px;
  color: #756b7e;
}
.chip-dots {
  margin-left: auto;
  align-self: flex-start;
  color: #756b7e;
}
.art-spark {
  position: absolute;
  font-size: 62px;
  color: #80546d;
}
.spark-one {
  top: 2px;
  right: 7px;
  transform: rotate(14deg);
}
.art-caption {
  position: absolute;
  bottom: -10px;
  left: 22px;
  font-size: 8px;
  letter-spacing: 2px;
  color: #91707c;
}
.tools-section {
  padding-top: 24px;
  scroll-margin-top: 24px;
}
.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 26px;
  gap: 20px;
}
.section-kicker {
  font-size: 9px;
  letter-spacing: 2px;
  font-weight: 600;
  color: #8b6874;
  margin: 0 0 12px;
}
.section-heading h2 {
  margin: 0;
  font-size: 26px;
  letter-spacing: -0.6px;
}
.section-heading h2 > span {
  font-size: 12px;
  margin-left: 18px;
  color: var(--ph-muted);
  font-weight: 400;
  letter-spacing: 0;
}
.section-aside {
  font-size: 11px;
  color: var(--ph-muted);
  padding-bottom: 3px;
}
.tool-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
}
.tool-card {
  display: block;
  border: 1px solid var(--ph-line);
  background: #fff;
  padding: 30px;
  border-radius: 15px;
  transition:
    border-color 0.2s,
    transform 0.2s,
    box-shadow 0.2s;
}
.tool-card:hover {
  border-color: #dab0bf;
  transform: translateY(-3px);
  box-shadow: 0 10px 24px #3e2e3808;
}
.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.tool-icon {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  background: #fdf7f9;
  color: #8f4a63;
  font-size: 24px;
}
.apricot .tool-icon {
  background: #f8f5fc;
  color: #776989;
}
.tool-status {
  background: #f7f0f2;
  color: #846470;
  font-size: 10px;
  border-radius: 5px;
  padding: 5px 9px;
}
.tool-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-top: 24px;
}
.tool-title h3 {
  margin: 0;
  font-size: 25px;
  font-weight: 650;
}
.tool-title > span {
  font-size: 12px;
  color: #8b6874;
}
.tool-description {
  color: var(--ph-muted);
  font-size: 13px;
  line-height: 1.9;
  margin: 12px 0 26px;
  max-width: 360px;
}
.card-bottom {
  border-top: 1px solid #f4ebee;
  padding-top: 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.tool-category {
  font-size: 10px;
  color: #8b6874;
  display: flex;
  align-items: center;
  gap: 10px;
}
.tool-number {
  color: #977581;
  font-size: 10px;
}
.tool-open {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: var(--ph-green);
}
.about-section {
  border-top: 1px solid var(--ph-line);
  margin-top: 58px;
  padding-top: 34px;
  display: flex;
  align-items: center;
  gap: 24px;
  scroll-margin-top: 24px;
}
.about-symbol {
  font-size: 54px;
  color: #9a7683;
}
.about-section .section-kicker {
  font-size: 8px;
  margin-bottom: 9px;
}
.about-section h2 {
  margin: 0;
  font-size: 21px;
  font-weight: 600;
}
.about-description {
  color: var(--ph-muted);
  font-size: 12px;
  line-height: 1.8;
  margin-bottom: 0;
}
.about-note {
  color: #8b6874;
  font-size: 11px;
  line-height: 1.9;
  margin-left: auto;
  flex-shrink: 0;
}
@media (max-width: 960px) {
  .hero {
    grid-template-columns: 1fr 1fr;
    gap: 12px;
    min-height: 510px;
  }
  h1 {
    font-size: 44px;
  }
  .hero-art {
    transform: scale(0.88);
    transform-origin: center;
  }
  .demo-paper {
    width: 300px;
  }
  .hero-actions {
    gap: 18px;
  }
  .section-aside {
    display: none;
  }
  .tool-card {
    padding: 24px;
  }
}
@media (max-width: 700px) {
  .hero {
    grid-template-columns: 1fr;
    padding-top: 44px;
    gap: 16px;
  }
  h1 {
    font-size: clamp(40px, 8.5vw, 56px);
  }
  .hero-art {
    width: min(100%, 420px);
    height: 355px;
    justify-self: center;
    transform: none;
  }
  .hero-note {
    margin-top: 20px;
  }
  .tool-grid {
    grid-template-columns: 1fr;
    gap: 16px;
  }
  .section-heading h2 > span {
    display: block;
    margin: 10px 0 0;
  }
  .about-note {
    display: none;
  }
}
@media (max-width: 640px) {
  .home-page {
    width: calc(100% - 40px);
  }
  .eyebrow {
    font-size: 11px;
  }
  .hero-description {
    font-size: 14px;
  }
  .hero-art {
    height: 330px;
  }
  .demo-paper {
    width: min(290px, calc(100% - 20px));
  }
  .paper-content {
    padding: 20px 24px;
  }
  .word-after {
    font-size: 25px;
  }
  .art-orbit {
    width: 250px;
    height: 250px;
    right: 15px;
  }
  .art-spark {
    font-size: 42px;
  }
  .size-chip {
    width: 224px;
    padding: 13px;
    right: 0;
    bottom: 13px;
  }
  .art-caption {
    left: 0;
    bottom: -14px;
    font-size: 7px;
    letter-spacing: 1.4px;
  }
  .hero-actions {
    gap: 20px;
  }
  .primary-action {
    padding: 14px 18px;
    gap: 14px;
  }
  .secondary-action {
    font-size: 12px;
  }
  .tools-section {
    padding-top: 18px;
  }
  .section-heading h2 {
    font-size: 24px;
  }
  .about-section {
    gap: 16px;
    margin-top: 40px;
  }
  .about-symbol {
    font-size: 36px;
  }
  .about-section .section-kicker {
    letter-spacing: 1px;
    font-size: 7px;
  }
  .about-section h2 {
    font-size: 19px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .tool-card,
  .primary-action {
    transition: none;
  }
  .tool-card:hover {
    transform: none;
  }
}
</style>

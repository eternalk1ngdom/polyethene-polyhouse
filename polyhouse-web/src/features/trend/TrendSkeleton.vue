<script setup lang="ts">
interface Props {
  /** list 仿日榜条目结构，detail 仿详情报头与正文双栏，减少数据到达时的版面跳动。 */
  variant: 'list' | 'detail'
}
defineProps<Props>()
/** 榜单占位行数：约一屏高度，足够表达“列表正在到来”，不伪造真实条数。 */
const rows = 6
</script>

<template>
  <div class="skeleton" :class="variant" role="status" aria-busy="true">
    <span class="visually-hidden">正在读取日报…</span>
    <div aria-hidden="true">
      <template v-if="variant === 'list'">
        <div class="head">
          <span class="bar title"></span>
          <span class="bar meta"></span>
        </div>
        <div v-for="row in rows" :key="row" class="row">
          <span class="bar rank"></span>
          <div class="lines">
            <span class="bar name"></span>
            <span class="bar line"></span>
            <span class="bar line short"></span>
            <span class="bar tag"></span>
          </div>
          <span class="bar stars"></span>
        </div>
      </template>
      <template v-else>
        <div class="story-head">
          <span class="bar meta"></span>
          <span class="bar title"></span>
          <span class="bar link"></span>
        </div>
        <div class="spread">
          <div class="lines">
            <span class="bar lede"></span>
            <span
              v-for="row in rows"
              :key="row"
              class="bar line"
              :class="{ short: row % 3 === 0 }"
            ></span>
          </div>
          <div class="aside">
            <span class="bar gain"></span>
            <span v-for="row in 4" :key="row" class="bar line"></span>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.skeleton {
  /* 延迟淡入：接口很快返回时不闪一下占位，纯 CSS 实现不引入计时器。 */
  animation: reveal 0.2s ease-out 0.12s both;
  padding: 18px 0 8px;
}
.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.bar {
  display: block;
  height: 14px;
  border-radius: 6px;
  /* 浅雾灰蓝骨架条，与纯白页底和月夜横幅保持同一清冷色系。 */
  background: linear-gradient(90deg, #e8eff3 0%, #f3f7f9 40%, #e8eff3 80%) 0 0 / 300% 100%;
  animation: sweep 1.4s linear infinite;
}
.head {
  display: grid;
  gap: 10px;
  padding-bottom: 14px;
  border-bottom: 2px solid var(--st-line-strong);
}
.head .title {
  width: 210px;
  max-width: 60%;
  height: 26px;
}
.head .meta {
  width: 340px;
  max-width: 90%;
}
.row {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) 72px;
  align-items: start;
  gap: 16px;
  padding: 17px 0;
  border-bottom: 1px solid var(--st-line);
}
.rank {
  width: 28px;
  height: 22px;
}
.lines {
  display: grid;
  gap: 9px;
  min-width: 0;
}
.name {
  width: 55%;
  height: 22px;
}
.line {
  width: 90%;
}
.line.short {
  width: 70%;
}
.tag {
  width: 120px;
  height: 12px;
}
.stars {
  height: 28px;
}
.story-head {
  display: grid;
  gap: 12px;
  padding: 22px 24px;
  border-top: 2px solid var(--st-line-strong);
  background: #eff4f8;
}
.story-head .meta {
  width: 140px;
}
.story-head .title {
  width: 46%;
  height: 32px;
}
.story-head .link {
  width: 110px;
}
.spread {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(215px, 28%);
  gap: clamp(20px, 6vw, 80px);
  margin-top: 22px;
}
.lede {
  height: 64px;
  margin-bottom: 12px;
}
.aside {
  display: grid;
  align-content: start;
  gap: 12px;
  padding: 20px;
  border: 1px solid var(--st-line);
  border-radius: 14px;
  background: var(--st-surface);
}
.gain {
  height: 54px;
}
@media (max-width: 700px) {
  .spread {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 620px) {
  .row {
    grid-template-columns: 26px minmax(0, 1fr) 56px;
    gap: 8px;
    padding-block: 14px;
  }
  .rank {
    width: 20px;
  }
  .name {
    width: 80%;
  }
}
@keyframes sweep {
  from {
    background-position: 100% 0;
  }
  to {
    background-position: 0 0;
  }
}
@keyframes reveal {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
/* 系统要求减少动态时只保留静态色条，延迟淡入也一并取消。 */
@media (prefers-reduced-motion: reduce) {
  .skeleton,
  .bar {
    animation: none;
  }
}
</style>

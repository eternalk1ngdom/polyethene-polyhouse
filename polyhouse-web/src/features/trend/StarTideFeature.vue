<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ElIcon } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import { starTide } from '../../config/starTide'
</script>

<template>
  <article class="spotlight">
    <div class="rings" aria-hidden="true"><span></span><span></span><span></span></div>
    <p>{{ starTide.subtitle }} <span>／</span> {{ starTide.tag }}</p>
    <h3>{{ starTide.title }}</h3>
    <p class="description">{{ starTide.description }}</p>
    <RouterLink :to="starTide.route" class="read-link">
      阅读星潮<ElIcon aria-hidden="true"><ArrowRight /></ElIcon>
    </RouterLink>
  </article>
</template>

<style scoped>
.spotlight {
  position: relative;
  overflow: hidden;
  isolation: isolate;
  border-radius: 18px;
  padding: clamp(24px, 5vw, 54px);
  color: #fff8fc;
  background: radial-gradient(ellipse at 90% 5%, #563855, transparent 62%), #262437;
}
p {
  margin: 0;
  color: #f1d5e4;
  font-size: 13px;
  letter-spacing: 0.07em;
}
p span {
  margin-inline: 4px;
}
h3 {
  font:
    700 clamp(42px, 7vw, 66px)/1.12 Georgia,
    'Songti SC',
    SimSun,
    serif;
  margin: 10px 0;
}
.description {
  font-size: 15px;
  color: #fff2f9;
  letter-spacing: 0;
}
.read-link {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  margin-top: 18px;
  min-height: 44px;
  padding-inline: 16px;
  border: 1px solid #e3cbdc;
  border-radius: 99px;
  color: #fff8fc;
  font-size: 14px;
}
.read-link:hover,
.read-link:focus-visible {
  background: #5c4666;
}
.rings {
  position: absolute;
  z-index: -1;
  pointer-events: none;
  width: 60%;
  aspect-ratio: 1;
  right: -7%;
  top: 48%;
  transform: translateY(-50%) rotate(-24deg);
}
.rings span {
  position: absolute;
  inset: 23% -6%;
  border: 1px solid #e6addc5e;
  border-radius: 50%;
}
.rings span:nth-child(2) {
  inset: 31% -6%;
}
.rings span:nth-child(3) {
  inset: 38% -6%;
}
@media (max-width: 520px) {
  .rings {
    top: 28%;
    right: -26%;
    width: 86%;
    opacity: 0.75;
  }
}
</style>

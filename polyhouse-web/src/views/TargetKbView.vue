<script setup lang="ts">
import { RouterLink } from 'vue-router'
import TargetKbPanel from '../components/TargetKbPanel.vue'
</script>

<template>
  <div class="targetkb-page">
    <RouterLink class="back" to="/">← 返回首页</RouterLink>
    <header>
      <p class="eyebrow">TARGETKB / 图片处理</p>
      <h1>妙压<span class="workspace-label">本地图片工作台</span></h1>
      <p>让照片刚好装进那个限制。</p>
    </header>
    <TargetKbPanel />
  </div>
</template>

<style scoped>
.targetkb-page {
  width: min(1320px, calc(100% - 96px));
  margin: 28px auto 0;
}
.back {
  color: var(--ph-muted);
  font-size: 14px;
}
header {
  margin: 24px 0 30px;
  padding: 30px 34px;
  background: var(--ph-cream);
  border-radius: 20px;
}
.eyebrow {
  color: var(--ph-muted);
  font-size: 12px;
  letter-spacing: 1.5px;
}
header p {
  line-height: 1.7;
}
h1 {
  font-size: 42px;
  margin: 10px 0;
}
.workspace-label {
  display: inline-block;
  vertical-align: middle;
  font-size: 12px;
  font-weight: 500;
  color: var(--ph-muted);
  margin-left: 18px;
  padding: 7px 12px;
  border: 1px solid var(--ph-line);
  border-radius: 99px;
}
header p:last-child {
  color: var(--ph-muted);
  line-height: 1.7;
  font-size: 14px;
}
@media (max-width: 700px) {
  .targetkb-page {
    width: calc(100% - 40px);
    margin-top: 32px;
  }
  header {
    padding: 24px 20px;
  }
  h1 {
    font-size: 34px;
  }
}
</style>

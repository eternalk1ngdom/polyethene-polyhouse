<script setup lang="ts">
import { RouterLink } from 'vue-router'
import TargetKbPanel from '../components/TargetKbPanel.vue'
</script>

<template>
  <div class="targetkb-page">
    <RouterLink class="back" to="/">← 返回首页</RouterLink>
    <header>
      <h1>妙压<span class="workspace-label">本地图片工作台</span></h1>
      <p>让照片刚好装进那个限制。</p>
    </header>
    <TargetKbPanel />
  </div>
</template>

<style scoped>
.targetkb-page {
  width: min(1320px, calc(100% - 96px));
  margin: 18px auto 0;
}
.back {
  color: var(--ph-muted);
  font-size: 14px;
}
header {
  /* 与妙替工作台保持同一页头尺度，兼顾松弛感并让投放框更快进入首屏。 */
  margin: 14px 0 22px;
  padding: 18px 24px;
  background: var(--ph-cream);
  border-radius: 16px;
}
h1 {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 14px;
  font-size: 28px;
  line-height: 1.25;
  margin: 0 0 6px;
}
.workspace-label {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  font-weight: 600;
  /* 胶囊轻呼应首页妙压折页书的蜜桃色，深墨字保证对比度。 */
  color: var(--ph-ink);
  background: var(--ph-peach);
  padding: 5px 12px;
  border-radius: 99px;
}
header p {
  margin: 0;
  color: var(--ph-muted);
  line-height: 1.7;
  font-size: 14px;
}
@media (max-width: 700px) {
  .targetkb-page {
    width: calc(100% - 40px);
    margin-top: 20px;
  }
  header {
    margin: 12px 0 18px;
    padding: 16px 18px;
  }
  h1 {
    font-size: 24px;
  }
}
</style>

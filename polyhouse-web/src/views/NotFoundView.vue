<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ElIcon } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
</script>

<template>
  <section class="not-found" aria-labelledby="not-found-title">
    <p class="error-code">404 / 走错房间了</p>
    <h1 id="not-found-title">这一间，还没有开门。</h1>
    <p>这个地址不存在，回到首页看看吧。</p>
    <RouterLink to="/"
      >回到聚妙屋<ElIcon><ArrowRight /></ElIcon
    ></RouterLink>
  </section>
</template>

<style scoped>
.not-found {
  width: min(1200px, calc(100% - 96px));
  margin: 100px auto;
}
.error-code {
  color: var(--ph-brand);
  font-size: 13px;
  letter-spacing: 1px;
}
h1 {
  font-size: clamp(27px, 4vw, 46px);
  margin: 22px 0;
}
.not-found > p:not(.error-code) {
  color: var(--ph-muted);
  font-size: 14px;
}
a {
  display: inline-flex;
  align-items: center;
  gap: 24px;
  color: white;
  background: var(--ph-brand);
  border-radius: 8px;
  padding: 14px 20px;
  margin-top: 24px;
  font-size: 14px;
}
@media (max-width: 640px) {
  .not-found {
    width: calc(100% - 40px);
    margin: 64px auto;
  }
}
</style>

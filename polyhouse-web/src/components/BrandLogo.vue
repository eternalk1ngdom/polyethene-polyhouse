<script setup lang="ts">
// 导航、页脚与 index.html 的标签图标指向同一 SVG，避免复制路径绘制造成品牌漂移。
// 更新修订号让浏览器重新加载新图形，标签页沿用相同地址，不生成第二份图案。
const markUrl = '/polyhouse-mark.svg?v=2'
</script>

<template>
  <span class="brand-logo">
    <img class="brand-symbol" :src="markUrl" width="36" height="36" alt="" aria-hidden="true" />
    <span class="brand-wordmark">polyhouse<span class="brand-caption">聚妙屋</span></span>
  </span>
</template>

<style scoped>
.brand-logo {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--ph-ink);
  white-space: nowrap;
}
.brand-symbol {
  display: block;
  flex-shrink: 0;
}
.brand-wordmark {
  font-family: Georgia, serif;
  font-size: 27px;
  font-style: italic;
  font-weight: 700;
  line-height: 1.05;
  letter-spacing: -0.8px;
}
.brand-caption {
  display: block;
  margin-top: 4px;
  font-family: var(--ph-font);
  font-size: 9px;
  font-style: normal;
  font-weight: 500;
  letter-spacing: 2px;
}
@media (max-width: 640px) {
  .brand-wordmark {
    font-size: 25px;
  }
}
/* 超窄屏压缩字标，保留与登录按钮同一行的空间。 */
@media (max-width: 360px) {
  .brand-logo {
    gap: 8px;
  }
  .brand-symbol {
    width: 32px;
    height: 32px;
  }
  .brand-wordmark {
    font-size: 23px;
  }
}
</style>

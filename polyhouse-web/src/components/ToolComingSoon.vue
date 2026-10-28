<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ElIcon } from 'element-plus'
import { ArrowLeft, ArrowRight, Clock, Crop, Switch } from '@element-plus/icons-vue'
import type { ToolRegistryItem } from '../config/toolRegistry'

/** 由路由页面提供已注册的工具，说明组件不自行推断路由或接收文件。 */
interface Props {
  /** 与首页共用元数据，保持名称、用途和计划一致。 */
  tool: ToolRegistryItem
}

defineProps<Props>()
const toolIcons = { Switch, Crop }
</script>

<template>
  <section class="tool-page" aria-labelledby="tool-page-title">
    <RouterLink to="/" class="back-link"
      ><ElIcon><ArrowLeft /></ElIcon>返回首页</RouterLink
    >
    <div class="tool-panel" :class="tool.accent">
      <div class="panel-intro">
        <span class="tool-icon"
          ><ElIcon><component :is="toolIcons[tool.icon]" /></ElIcon
        ></span>
        <p class="tool-subtitle">{{ tool.subtitle }} / {{ tool.tag }}</p>
        <h1 id="tool-page-title">{{ tool.title }}</h1>
        <p class="tool-description">{{ tool.description }}</p>
      </div>
      <div class="development-note" role="status">
        <ElIcon><Clock /></ElIcon><span>正在认真打磨，暂未开放使用</span>
      </div>
      <div class="feature-list">
        <h2>接下来，这里可以做什么？</h2>
        <ul>
          <li v-for="feature in tool.plannedFeatures" :key="feature">
            <span aria-hidden="true">↗</span>{{ feature }}
          </li>
        </ul>
      </div>
      <p class="availability-note">当前是工具介绍页，尚不提供文件处理。准备好之后，在这里见。</p>
      <RouterLink class="home-action" :to="{ path: '/', hash: '#tools' }"
        >看看其他工具<ElIcon><ArrowRight /></ElIcon
      ></RouterLink>
    </div>
  </section>
</template>

<style scoped>
.tool-page {
  width: min(960px, calc(100% - 96px));
  margin: 42px auto 0;
}
.back-link {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  color: var(--ph-muted);
  font-size: 13px;
  padding: 8px 0;
}
.back-link:hover {
  color: var(--ph-green);
}
.tool-panel {
  background: white;
  border: 1px solid var(--ph-line);
  border-radius: 18px;
  margin-top: 24px;
  padding: 46px;
}
.tool-icon {
  width: 58px;
  height: 58px;
  display: grid;
  place-items: center;
  border-radius: 15px;
  background: #fdf7f9;
  color: #8f4a63;
  font-size: 30px;
}
.apricot .tool-icon {
  background: #f8f5fc;
  color: #776989;
}
.tool-subtitle {
  font-size: 11px;
  color: var(--ph-muted);
  margin: 22px 0 10px;
}
h1 {
  font-size: 40px;
  margin: 0;
}
.tool-description {
  font-size: 15px;
  line-height: 1.9;
  color: var(--ph-muted);
  margin: 16px 0 28px;
}
.development-note {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #faf3f5;
  border: 1px solid #f1dce3;
  border-radius: 8px;
  padding: 15px 18px;
  font-size: 13px;
  color: #7e5866;
}
.feature-list {
  margin-top: 32px;
}
h2 {
  font-size: 16px;
  font-weight: 600;
}
ul {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  padding: 0;
  list-style: none;
  margin: 20px 0 28px;
}
li {
  border-bottom: 1px solid var(--ph-line);
  padding-bottom: 16px;
  font-size: 13px;
  line-height: 1.8;
}
li > span {
  margin-right: 10px;
  color: #997683;
}
.availability-note {
  font-size: 12px;
  line-height: 1.8;
  color: var(--ph-muted);
}
.home-action {
  display: inline-flex;
  align-items: center;
  gap: 20px;
  padding: 13px 18px;
  margin-top: 16px;
  color: #613e4d;
  background: var(--ph-green);
  border-radius: 8px;
  font-size: 13px;
}
.home-action:hover {
  background: #f7e8ee;
}
@media (max-width: 640px) {
  .tool-page {
    width: calc(100% - 40px);
    margin-top: 24px;
  }
  .tool-panel {
    padding: 28px 22px;
  }
  h1 {
    font-size: 34px;
  }
  .tool-description {
    font-size: 14px;
  }
  ul {
    grid-template-columns: 1fr;
    gap: 12px;
  }
}
</style>

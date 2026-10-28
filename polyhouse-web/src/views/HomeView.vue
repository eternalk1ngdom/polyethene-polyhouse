<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ElButton, ElIcon, ElInput } from 'element-plus'
import { ArrowDown, ArrowRight, Crop, Search, Switch } from '@element-plus/icons-vue'
import { toolRegistry } from '../config/toolRegistry'
import ToolArtwork from '../components/ToolArtwork.vue'

/** 分类只筛选真实注册工具，不生成尚未交付的入口。 */
interface Category {
  /** all 不限制类别，其他值与注册表一致。 */
  value: 'all' | 'text' | 'image'
  /** 可见名称同时用于辅助阅读。 */
  label: string
}
const categories: readonly Category[] = [
  { value: 'all', label: '全部工具' },
  { value: 'text', label: '文本处理' },
  { value: 'image', label: '图片处理' },
]
const query = ref('')
const category = ref<Category['value']>('all')
const router = useRouter()
const toolIcons = { Switch, Crop }
// 搜索只匹配本地注册表，不上传关键词，也不依赖账号或后端。
const visibleTools = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  return toolRegistry.filter(
    (tool) =>
      (category.value === 'all' || tool.category === category.value) &&
      [tool.title, tool.subtitle, tool.tag, tool.description, ...tool.plannedFeatures]
        .join(' ')
        .toLocaleLowerCase()
        .includes(needle),
  )
})
function resetFilters(): void {
  query.value = ''
  category.value = 'all'
}
/** 搜索即时筛选；按 Enter 也把键盘用户带到结果区。 */
async function showResults(): Promise<void> {
  await router.push({ path: '/', hash: '#tools' })
}
</script>

<template>
  <div class="home-page">
    <section class="hero" aria-labelledby="hero-title">
      <p class="hero-label">给日常一点灵感，给繁琐一个解法。</p>
      <h1 id="hero-title">好用的小工具，<br />让生活轻一点。</h1>
      <p class="hero-description">
        文字、图片，还有更多小小的可能。<br class="mobile-break" />在聚妙屋，找到你的顺手工具。
      </p>
      <form class="tool-search" role="search" @submit.prevent="showResults">
        <ElInput v-model="query" aria-label="搜索工具" placeholder="你想解决什么小麻烦？" clearable
          ><template #prefix
            ><ElIcon><Search /></ElIcon></template
        ></ElInput>
        <RouterLink
          :to="{ path: '/', hash: '#tools' }"
          class="search-action"
          aria-label="查看工具搜索结果"
          ><ElIcon><ArrowRight /></ElIcon
        ></RouterLink>
      </form>
      <div class="popular-searches">
        <span>试试搜索</span><button type="button" @click="query = '文本'">文本替换</button
        ><button type="button" @click="query = '文件夹'">文件夹处理</button
        ><button type="button" @click="query = '图片'">图片处理</button>
      </div>
      <RouterLink class="discover-link" :to="{ path: '/', hash: '#tools' }"
        >发现小工具<ElIcon><ArrowDown /></ElIcon
      ></RouterLink>
    </section>
    <section id="tools" class="tools-section" aria-labelledby="tools-title">
      <div class="gallery-toolbar">
        <h2 id="tools-title">发现顺手的可能<span>THE TOOL COLLECTION</span></h2>
        <div class="categories" role="group" aria-label="工具分类">
          <button
            v-for="item in categories"
            :key="item.value"
            type="button"
            :class="{ selected: category === item.value }"
            :aria-pressed="category === item.value"
            @click="category = item.value"
          >
            {{ item.label }}
          </button>
        </div>
      </div>
      <p class="results-count" role="status">
        {{ query.trim() ? `“${query.trim()}” · ` : '' }}{{ visibleTools.length }} 个工具 ·
        实际开放状态见卡片
      </p>
      <div v-if="visibleTools.length" class="tool-grid">
        <article v-for="tool in visibleTools" :key="tool.code" class="tool-card">
          <RouterLink
            :to="tool.route"
            class="artwork-link"
            :aria-label="`${tool.status === 'available' ? '打开' : '了解'}${tool.title}`"
            ><ToolArtwork :kind="tool.category" /><span class="artwork-cta"
              >{{ tool.status === 'available' ? '开始使用' : '查看计划'
              }}<ElIcon><ArrowRight /></ElIcon></span
          ></RouterLink>
          <div class="card-meta">
            <span class="tool-icon" :class="tool.accent"
              ><ElIcon><component :is="toolIcons[tool.icon]" /></ElIcon></span
            ><RouterLink :to="tool.route" class="tool-title"
              ><h3>{{ tool.title }}</h3>
              <span>{{ tool.subtitle }}</span></RouterLink
            ><span class="tool-status" :class="{ available: tool.status === 'available' }">{{
              tool.status === 'available' ? '已开放' : '筹备中'
            }}</span>
          </div>
          <p class="tool-description">{{ tool.description }}</p>
          <div class="tool-tags">
            <span>{{ tool.tag }}</span
            ><span>{{ tool.status === 'available' ? '本地运行 · 无需登录' : '尚未开放处理' }}</span>
          </div>
        </article>
      </div>
      <div v-else class="empty-search">
        <ElIcon><Search /></ElIcon>
        <h3>还没有找到这个工具</h3>
        <p>换个关键词，或看看全部工具。</p>
        <ElButton @click="resetFilters">查看全部工具</ElButton>
      </div>
    </section>
    <section id="about" class="about-section" aria-labelledby="about-title">
      <p class="about-kicker">SMALL TOOLS. MORE POSSIBILITIES.</p>
      <h2 id="about-title">把时间，留给你喜欢的事。</h2>
      <p>聚妙屋是一份持续生长的日常工具集。<br />从一个小需求出发，认真做好每一次处理。</p>
      <RouterLink to="/account" class="about-action"
        >去我的个人空间<ElIcon><ArrowRight /></ElIcon></RouterLink
      ><span class="about-signature" aria-hidden="true">a little more possibility.</span>
    </section>
  </div>
</template>

<style scoped>
.home-page {
  width: min(1320px, calc(100% - 96px));
  margin: auto;
}
.hero {
  text-align: center;
  padding: 68px 16px 52px;
}
.hero-label {
  display: inline-block;
  background: var(--ph-cream);
  border-radius: 99px;
  padding: 10px 18px;
  font-size: 13px;
  margin: 0 0 26px;
}
h1 {
  font-size: clamp(40px, 4.8vw, 68px);
  font-weight: 650;
  line-height: 1.3;
  letter-spacing: -2px;
  margin: 0;
}
.hero-description {
  color: var(--ph-muted);
  font-size: 16px;
  line-height: 1.9;
  margin: 24px 0 26px;
}
.mobile-break {
  display: none;
}
.tool-search {
  display: flex;
  align-items: center;
  width: min(620px, 100%);
  margin: auto;
  padding: 7px 8px 7px 18px;
  background: var(--ph-soft);
  border: 1px solid var(--ph-line);
  border-radius: 99px;
}
.tool-search:focus-within {
  border-color: var(--ph-accent);
  box-shadow: 0 0 0 4px var(--ph-pink-soft);
}
.tool-search :deep(.el-input__wrapper) {
  background: transparent;
  box-shadow: none;
  padding-left: 0;
}
.tool-search :deep(.el-input__inner) {
  height: 40px;
  font-size: 15px;
  outline: none;
}
.tool-search :deep(.el-input__prefix) {
  margin-right: 10px;
  font-size: 20px;
  color: var(--ph-muted);
}
.search-action {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  background: var(--ph-ink);
  color: white;
  border-radius: 50%;
  font-size: 22px;
}
.popular-searches {
  display: flex;
  justify-content: center;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
  font-size: 12px;
  color: var(--ph-muted);
}
.popular-searches button {
  background: white;
  border: 1px solid var(--ph-line);
  border-radius: 99px;
  padding: 8px 14px;
  min-height: 44px;
  color: var(--ph-ink);
  cursor: pointer;
}
.popular-searches button:hover {
  background: var(--ph-soft);
}
.discover-link {
  display: inline-flex;
  gap: 10px;
  align-items: center;
  margin-top: 24px;
  padding: 8px;
  font-size: 13px;
  color: var(--ph-muted);
}
.gallery-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 24px;
  padding-top: 12px;
}
h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 650;
}
.gallery-toolbar h2 span {
  display: block;
  margin-top: 9px;
  color: var(--ph-muted);
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 1.5px;
}
.categories {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.categories button {
  min-height: 44px;
  padding: 10px 19px;
  background: white;
  border: 1px solid transparent;
  border-radius: 99px;
  cursor: pointer;
  font-size: 14px;
  color: var(--ph-muted);
}
.categories button.selected {
  background: var(--ph-soft);
  color: var(--ph-ink);
  border-color: var(--ph-line);
  font-weight: 600;
}
.categories button:hover {
  color: var(--ph-ink);
}
.results-count {
  font-size: 12px;
  color: var(--ph-muted);
  margin: 24px 0 16px;
  overflow-wrap: anywhere;
}
.tool-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 34px;
}
.tool-card {
  min-width: 0;
}
.artwork-link {
  position: relative;
  display: block;
  overflow: hidden;
  border-radius: 18px;
}
.artwork-cta {
  display: flex;
  align-items: center;
  gap: 10px;
  position: absolute;
  right: 20px;
  bottom: 20px;
  padding: 11px 17px;
  border-radius: 99px;
  background: var(--ph-ink);
  color: white;
  font-size: 12px;
  transition: background 0.2s;
}
.artwork-link:hover .artwork-cta {
  background: var(--ph-accent-strong);
}
.card-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 0 0;
}
.tool-icon {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  font-size: 18px;
  color: var(--ph-ink);
}
.lilac {
  background: var(--ph-lilac);
}
.peach {
  background: var(--ph-peach);
}
.tool-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
}
.tool-title > span {
  font-size: 12px;
  color: var(--ph-muted);
}
.tool-status {
  margin-left: auto;
  font-size: 11px;
  border: 1px solid var(--ph-line);
  border-radius: 6px;
  padding: 4px 8px;
  white-space: nowrap;
  color: var(--ph-muted);
}
.tool-status.available {
  background: var(--ph-cream);
  border-color: var(--ph-cream);
  color: var(--ph-ink);
}
.tool-description {
  font-size: 14px;
  color: var(--ph-muted);
  line-height: 1.8;
  margin: 12px 0;
}
.tool-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--ph-muted);
  font-size: 11px;
}
.tool-tags span {
  padding: 5px 9px;
  background: var(--ph-soft);
  border-radius: 5px;
}
.empty-search {
  text-align: center;
  padding: 64px 20px;
  background: var(--ph-soft);
  border-radius: 18px;
}
.empty-search > .el-icon {
  font-size: 32px;
  color: var(--ph-muted);
}
.empty-search h3 {
  margin-top: 16px;
}
.empty-search p {
  color: var(--ph-muted);
  font-size: 14px;
}
.about-section {
  text-align: center;
  background: var(--ph-cream);
  margin-top: 90px;
  padding: 58px 24px 42px;
  border-radius: 22px;
}
.about-kicker {
  font-size: 10px;
  letter-spacing: 2px;
  margin: 0 0 22px;
}
.about-section h2 {
  font-size: clamp(27px, 3vw, 40px);
  letter-spacing: -1px;
}
.about-section > p:not(.about-kicker) {
  color: var(--ph-muted);
  line-height: 1.9;
  font-size: 15px;
  margin: 20px 0 22px;
}
.about-action {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 14px 22px;
  background: var(--ph-ink);
  color: white;
  border-radius: 99px;
  font-size: 14px;
}
.about-signature {
  display: block;
  font-family: Georgia, serif;
  font-style: italic;
  font-size: 22px;
  margin-top: 34px;
}
@media (max-width: 760px) {
  .home-page {
    width: calc(100% - 40px);
  }
  .hero {
    padding: 44px 0 34px;
  }
  .hero-description {
    font-size: 14px;
  }
  .mobile-break {
    display: initial;
  }
  .gallery-toolbar {
    align-items: flex-start;
    flex-direction: column;
    gap: 18px;
  }
  .tool-grid {
    gap: 30px;
    grid-template-columns: 1fr;
  }
  .categories {
    width: 100%;
  }
  .categories button {
    flex: 1;
    padding: 10px;
  }
  .about-section {
    margin-top: 56px;
  }
  .tool-search {
    padding-left: 14px;
  }
  .popular-searches {
    gap: 6px;
  }
  .popular-searches button {
    padding: 8px 10px;
  }
  .tool-title {
    flex-wrap: wrap;
    gap: 4px 8px;
  }
  .artwork-cta {
    right: 14px;
    bottom: 14px;
  }
}
</style>

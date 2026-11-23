<script setup lang="ts">
import { ElIcon } from 'element-plus'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import type { TrendProject } from '../../api/trend'

/** 相邻一篇的展示资料；地址由父级按当前筛选生成，保证新标签页打开时条件一致。 */
interface SiblingLink {
  /** 相邻篇的仓库快照，只读取排名与仓库名。 */
  project: TrendProject
  /** 真实链接，复制、新标签页和键盘访问不依赖脚本。 */
  href: string
}
interface Props {
  /** 上一篇；首篇为 null，对应格子留空。 */
  previous: SiblingLink | null
  /** 下一篇；末篇为 null。 */
  next: SiblingLink | null
  /** 当前篇序号，从 1 开始。 */
  position: number
  /** 序列总篇数。 */
  total: number
  /** 顺序来源说明，例如“按 Python · AI 智能体 筛选”或“按本期排名”。 */
  scope: string
}
interface Emits {
  open: [event: MouseEvent, fullName: string]
}
defineProps<Props>()
const emit = defineEmits<Emits>()

/** 仓库名拆成组织与项目两段，组织名用浅色，和榜单条目保持同一读法。 */
function nameParts(fullName: string): [string, string] {
  const [owner = '', repo = ''] = fullName.split('/')
  return [owner, repo]
}
</script>

<template>
  <nav v-if="previous || next" class="sibling-nav" aria-label="继续阅读">
    <p class="scope">继续阅读 · 第 {{ position }} / {{ total }} 篇 · {{ scope }}</p>
    <div class="links">
      <a
        v-if="previous"
        class="link previous"
        :href="previous.href"
        rel="prev"
        @click="emit('open', $event, previous.project.fullName)"
      >
        <span class="hint"
          ><ElIcon aria-hidden="true"><ArrowLeft /></ElIcon>上一篇 · 第
          {{ String(previous.project.rankNum).padStart(2, '0') }} 名</span
        >
        <strong
          ><span class="org">{{ nameParts(previous.project.fullName)[0] }}/</span><wbr />{{
            nameParts(previous.project.fullName)[1]
          }}</strong
        >
      </a>
      <span v-else class="placeholder" aria-hidden="true"></span>
      <a
        v-if="next"
        class="link next"
        :href="next.href"
        rel="next"
        @click="emit('open', $event, next.project.fullName)"
      >
        <span class="hint"
          >下一篇 · 第 {{ String(next.project.rankNum).padStart(2, '0') }} 名<ElIcon
            aria-hidden="true"
            ><ArrowRight /></ElIcon
        ></span>
        <strong
          ><span class="org">{{ nameParts(next.project.fullName)[0] }}/</span><wbr />{{
            nameParts(next.project.fullName)[1]
          }}</strong
        >
      </a>
    </div>
  </nav>
</template>

<style scoped>
.sibling-nav {
  margin-top: 36px;
  padding-top: 14px;
  border-top: 2px solid #223e55;
}
.scope {
  margin: 0 0 12px;
  color: #475c6b;
  font-size: 12px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
.links {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.link {
  display: grid;
  align-content: center;
  gap: 4px;
  min-height: 64px;
  padding: 14px 16px;
  border: 1px solid #d5dedf;
  border-radius: 10px;
  background: #ffffff;
  color: #192743;
  overflow-wrap: anywhere;
  transition:
    background 0.2s,
    border-color 0.2s;
}
.next {
  text-align: right;
}
.link:hover {
  background: #f3f7f7;
}
/* 按下与键盘焦点都在原边框上加深，不另叠外圈，和视图切换按钮一致。 */
.link:active {
  border-color: #154e78;
}
.link:focus-visible {
  outline: none;
  border-color: #154e78;
  box-shadow: inset 0 0 0 1px #154e78;
}
.hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #475c6b;
  font-size: 12px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
.next .hint {
  justify-content: flex-end;
}
strong {
  font-size: 16px;
  line-height: 1.35;
  letter-spacing: -0.02em;
}
.org {
  color: #4f6372;
  font-weight: 450;
}
@media (max-width: 520px) {
  .links {
    grid-template-columns: 1fr;
    gap: 10px;
  }
  /* 单列时空格子不占位，下一篇保持右对齐以示方向。 */
  .placeholder {
    display: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .link {
    transition: none;
  }
}
</style>

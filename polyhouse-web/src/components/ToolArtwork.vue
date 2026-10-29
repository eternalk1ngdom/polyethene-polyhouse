<script setup lang="ts">
import { ElIcon } from 'element-plus'
import { ArrowRight, Crop, Picture, Switch } from '@element-plus/icons-vue'
/** 用本项目自己的排版解释工具用途，不使用第三方作品或假处理结果。 */
interface Props {
  /** 封面类别来自注册表。 */
  kind: 'text' | 'image'
}
defineProps<Props>()
</script>
<template>
  <div class="artwork" :class="kind" aria-hidden="true">
    <template v-if="kind === 'text'"
      ><span class="art-label">THE ART OF LESS REPETITION</span>
      <div class="text-composition">
        <div class="text-sheet before">
          <span>01 / BEFORE</span><strong>重复的事<br />交给工具。</strong><i /><i /><i />
        </div>
        <div class="swap">
          <ElIcon><Switch /></ElIcon>
        </div>
        <div class="text-sheet after">
          <span>02 / AFTER</span><strong>喜欢的事<br />留给自己。</strong>
          <p>
            Find your flow.<ElIcon><ArrowRight /></ElIcon>
          </p>
        </div>
      </div>
      <span class="art-caption">PolyReplace<span>文字的事，简单一点。</span></span></template
    >
    <template v-else
      ><span class="art-label">MAKE ROOM FOR THE PERFECT FIT</span>
      <div class="image-composition">
        <div class="image-sheet">
          <div class="landscape">
            <ElIcon><Picture /></ElIcon><span class="hill" />
          </div>
          <div class="image-meta"><span>your-image.jpg</span><span>IMAGE STUDIO</span></div>
        </div>
        <div class="image-aside">
          <div class="crop-token">
            <ElIcon><Crop /></ElIcon>
          </div>
          <div class="size-note">Just the<br /><strong>right size.</strong></div>
        </div>
      </div>
      <span class="art-caption">TargetKB<span>每一张，都刚刚好。</span></span></template
    >
  </div>
</template>
<style scoped>
.artwork {
  container: tool-artwork / inline-size;
  width: 100%;
  aspect-ratio: 1.65;
  /* 最窄双列也要容纳标语、两行正文与底部入口，不再继续压缩文字区。 */
  min-height: 240px;
  position: relative;
  overflow: hidden;
  padding: 28px;
  color: var(--ph-ink);
}
.text {
  background: var(--ph-lilac);
}
.image {
  background: var(--ph-peach);
}
.art-label {
  font-size: 9px;
  letter-spacing: 1.5px;
  position: relative;
  z-index: 1;
}
.text-composition {
  position: absolute;
  inset: 19% 8% 19%;
  /* 为交换标记保留独立列；不能让绝对定位的装饰覆盖旋转纸张中的正文。 */
  display: grid;
  grid-template-columns: minmax(0, 1fr) 42px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
}
.text-sheet {
  min-width: 0;
  height: 100%;
  padding: 20px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 12px 26px #2422380c;
  position: relative;
}
.before {
  transform: rotate(-8deg);
  background: #f5f0ff;
}
.after {
  transform: rotate(6deg);
  background: var(--ph-ink);
  color: white;
}
.text-sheet > span {
  display: block;
  font-size: 8px;
  line-height: 1.4;
  letter-spacing: 1px;
  opacity: 0.7;
}
.text-sheet strong {
  /* 桌面双列与手机单列的卡片宽度不同，字号跟随卡片而非整页视口。 */
  font-size: clamp(14px, 4.4cqi, 30px);
  font-weight: 600;
  line-height: 1.5;
  display: block;
  margin-top: 14px;
  letter-spacing: -1px;
}
/* ElIcon 也输出 i 标签；仅给纸张的直接装饰子元素画线，避免误伤箭头。 */
.before > i {
  display: block;
  width: 76%;
  height: 3px;
  margin-top: 9px;
  background: #cfc5e2;
  border-radius: 2px;
}
.before > i:last-child {
  width: 50%;
}
.text-sheet p {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-family: Georgia, serif;
  font-style: italic;
  font-size: 13px;
  margin-top: 15px;
  color: #f8b9d2;
}
.text-sheet p > .el-icon {
  flex-shrink: 0;
  font-size: 18px;
}
.swap {
  position: relative;
  z-index: 3;
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  background: #f4b8d1;
  border-radius: 50%;
  font-size: 23px;
}
.art-caption {
  position: absolute;
  bottom: 24px;
  left: 28px;
  font-family: Georgia, serif;
  font-size: 17px;
  font-style: italic;
}
.art-caption > span {
  display: block;
  margin-top: 5px;
  font-family: var(--ph-font);
  font-style: normal;
  font-size: 10px;
}
.image-composition {
  position: absolute;
  inset: 22% 8% 28% 11%;
  /* 图片与说明各占一列，旋转后的边界也留出间隔，避免说明遮住图片页脚。 */
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 0.9fr);
  gap: clamp(18px, 4.5cqi, 30px);
  align-items: center;
}
.image-sheet {
  min-width: 0;
  height: 100%;
  background: white;
  padding: clamp(8px, 1.8cqi, 12px);
  border-radius: 9px;
  transform: rotate(-6deg);
  box-shadow: 0 12px 26px #2422380c;
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  gap: 9px;
}
.landscape {
  min-height: 0;
  background: #e9c3b7;
  border-radius: 4px;
  overflow: hidden;
  position: relative;
  display: grid;
  place-items: center;
}
.landscape > .el-icon {
  color: #78595b;
  font-size: clamp(32px, 8cqi, 60px);
  z-index: 1;
}
.hill {
  position: absolute;
  width: 160%;
  height: 100%;
  background: #c6aecc;
  transform: rotate(-25deg);
  top: 65%;
  left: -15%;
}
.image-meta {
  display: flex;
  justify-content: space-between;
  gap: 4px;
  font-size: clamp(7px, 1.2cqi, 8px);
  line-height: 1.4;
  white-space: nowrap;
}
.image-meta > span:last-child {
  color: var(--ph-muted);
}
.image-aside {
  min-width: 0;
  display: grid;
  justify-items: start;
  gap: clamp(12px, 2.8cqi, 20px);
}
.crop-token {
  height: clamp(32px, 8cqi, 52px);
  width: clamp(32px, 8cqi, 52px);
  display: grid;
  place-items: center;
  color: var(--ph-accent-strong);
  background: var(--ph-pink-soft);
  border: 1px solid var(--ph-lilac);
  border-radius: 13px;
  font-size: clamp(20px, 4.5cqi, 28px);
  transform: rotate(6deg);
}
.size-note {
  width: 100%;
  padding: clamp(10px, 2.5cqi, 18px);
  background: var(--ph-cream);
  font-family: Georgia, serif;
  font-size: clamp(15px, 3.4cqi, 27px);
  transform: rotate(3deg);
  line-height: 1.15;
  border-radius: 6px;
}
.size-note strong {
  font-style: italic;
  font-weight: 400;
}
/* 卡片在平板双列时也可能很窄，不能只按手机视口启用紧凑排版。 */
@container tool-artwork (max-width: 400px) {
  .text > .text-composition {
    /* 窄卡片需同时避开顶部标语和底部工具入口，不能只缩小正文。 */
    inset-block: 31% 25%;
    inset-inline: 6%;
    grid-template-columns: minmax(0, 1fr) 32px minmax(0, 1fr);
    gap: 8px;
  }
  .text-sheet {
    padding: 10px;
  }
  .text-sheet strong {
    margin-top: 6px;
  }
  .text-sheet p,
  .before > i {
    display: none;
  }
  .swap {
    width: 32px;
    height: 32px;
    font-size: 20px;
  }
  .image > .image-composition {
    /* 桌面断点刚切为双列时卡片仍窄，旋转图片需额外避开标语和页脚。 */
    inset-block: 26% 32%;
  }
  /* 仅隐藏窄图片页脚的次要标注，文件名保留，文字插画及公共卡片尺寸不变。 */
  .image-meta > span:last-child {
    display: none;
  }
}
@media (max-width: 760px) {
  .artwork {
    padding: 20px;
    aspect-ratio: 1.45;
  }
  /* 窄屏给标题与操作按钮留出底部空间，装饰文字不能相互遮挡。 */
  .text-composition {
    inset-block: 19% 31%;
  }
  .art-caption {
    bottom: 18px;
    left: 20px;
    font-size: 15px;
  }
  .art-caption > span {
    font-size: 9px;
  }
  .art-label {
    font-size: 7px;
    letter-spacing: 1px;
  }
}
</style>

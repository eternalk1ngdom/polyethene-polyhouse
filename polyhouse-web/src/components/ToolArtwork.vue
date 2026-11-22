<script setup lang="ts">
import tideVoyage from '../assets/startide-voyage.webp'
import paperButterfly from '../assets/polyreplace-paper-butterfly.webp'
import paperFox from '../assets/targetkb-paper-fox.webp'

/** 首页用入口类别选择装饰插画，链接的可访问名称由首页提供。 */
interface Props {
  kind: 'trend' | 'text' | 'image'
}
defineProps<Props>()

const covers = {
  trend: {
    image: tideVoyage,
    label: 'GITHUB TRENDING',
    name: 'StarTide',
    caption: '每天，发现开源新星。',
  },
  text: {
    image: paperButterfly,
    label: 'THE ART OF LESS REPETITION',
    name: 'PolyReplace',
    caption: '文字的事，简单一点。',
  },
  image: {
    image: paperFox,
    label: 'MAKE ROOM FOR THE PERFECT FIT',
    name: 'TargetKB',
    caption: '每一张，都刚刚好。',
  },
} as const
</script>

<template>
  <div class="artwork" :class="kind" aria-hidden="true">
    <span class="art-label">{{ covers[kind].label }}</span>
    <img
      class="illustration"
      :src="covers[kind].image"
      alt=""
      width="1200"
      height="600"
      decoding="async"
    />
    <span class="art-caption"
      >{{ covers[kind].name }}<span>{{ covers[kind].caption }}</span></span
    >
  </div>
</template>

<style scoped>
.artwork {
  width: 100%;
  aspect-ratio: 1.65;
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
.trend {
  background: var(--star-tide-cover);
}
.art-label {
  font-size: 9px;
  letter-spacing: 1.5px;
  position: relative;
  z-index: 1;
}
.illustration {
  position: absolute;
  inset: 9% 0 auto;
  width: 100%;
  /* 窄双列先留出题签和按钮的高度，插画只占剩余空间。 */
  height: min(68%, calc(91% - 74px));
  pointer-events: none;
}
.text .illustration,
.image .illustration {
  /* 纸蝶的翼形和纸狐的折面在各宽度下保持原比例。 */
  object-fit: contain;
}
.text .illustration {
  object-position: left center;
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
@media (max-width: 760px) {
  .artwork {
    padding: 20px;
    aspect-ratio: 1.45;
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

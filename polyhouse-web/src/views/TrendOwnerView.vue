<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ElButton, ElIcon } from 'element-plus'
import { Back, Refresh } from '@element-plus/icons-vue'
import { starTide } from '../config/starTide'
import TrendTaskProgress from '../features/trend/TrendTaskProgress.vue'
import TrendScheduleCard from '../features/trend/TrendScheduleCard.vue'
import TrendBriefingControl from '../features/trend/TrendBriefingControl.vue'
import { useTrendOwner } from '../features/trend/useTrendOwner'
import { useAccountUiStore } from '../stores/useAccountUiStore'
import { useUserStore } from '../stores/useUserStore'

const user = useUserStore()
const accountUi = useAccountUiStore()
const {
  allowed,
  job,
  briefing,
  scheduleStatus,
  intent,
  knownJobId,
  jobBusy,
  briefingBusy,
  scheduleBusy,
  scheduleToggling,
  briefingGenerating,
  jobError,
  briefingError,
  scheduleError,
  submissionNote,
  briefingNote,
  active,
  waiting,
  briefingWaiting,
  refreshJob,
  submit,
  refreshBriefing,
  refreshScheduleStatus,
  toggleSchedulePaused,
  generateBriefing,
} = useTrendOwner()
</script>

<template>
  <div class="owner-page">
    <div class="sheet">
      <RouterLink :to="starTide.route" class="back">
        <ElIcon aria-hidden="true"><Back /></ElIcon> 返回星潮日报
      </RouterLink>
      <header class="masthead" :class="{ 'has-actions': allowed }">
        <div class="mast-main">
          <h1>星潮发布台 <em lang="en">StarTide</em></h1>
          <p v-if="allowed" id="update-hint" class="mast-sub">
            更新今天的 GitHub 日报。启用 AI 解读时可能产生费用。
          </p>
          <p v-else class="mast-sub">GitHub 开源日报与近 7 日风口简报 · 屋主控制台</p>
        </div>
        <div v-if="allowed" class="mast-actions">
          <ElButton
            class="publish"
            type="primary"
            :icon="Refresh"
            :loading="jobBusy"
            :disabled="active || waiting"
            aria-describedby="update-hint"
            @click="submit"
          >
            {{
              waiting
                ? '更新冷却中'
                : active
                  ? '任务进行中'
                  : intent
                    ? '继续同一次更新'
                    : '更新本期日报'
            }}
          </ElButton>
        </div>
        <span class="mast-watermark" lang="en" aria-hidden="true">Console</span>
      </header>
      <p v-if="user.status === 'checking'" class="gate" role="status">正在核对登录状态…</p>
      <section v-else-if="!allowed" class="gate" aria-labelledby="owner-gate">
        <h2 id="owner-gate">屋主专用</h2>
        <p v-if="user.status === 'unavailable'">暂时无法核对会话，请重试连接。</p>
        <p v-else-if="user.requiresPasswordChange">请先修改初始密码。</p>
        <p v-else>请使用屋主账号登录。</p>
        <ElButton v-if="user.status === 'unavailable'" @click="user.restoreSession()"
          >重试连接</ElButton
        >
        <ElButton v-else-if="!user.isLoggedIn" @click="accountUi.openLogin">登录</ElButton>
      </section>
      <template v-else>
        <TrendTaskProgress
          :job="intent ? null : job"
          :tracking="Boolean(intent || knownJobId)"
          :busy="jobBusy"
          :waiting="waiting"
          :error="jobError"
          :note="submissionNote"
          @refresh="refreshJob"
        />
        <TrendBriefingControl
          :briefing="briefing"
          :busy="briefingBusy"
          :generating="briefingGenerating"
          :waiting="briefingWaiting"
          :error="briefingError"
          :note="briefingNote"
          @refresh="refreshBriefing"
          @generate="generateBriefing"
        />
        <TrendScheduleCard
          :status="scheduleStatus"
          :busy="scheduleBusy"
          :toggling="scheduleToggling"
          :error="scheduleError"
          @refresh="refreshScheduleStatus"
          @toggle-pause="toggleSchedulePaused"
        />
      </template>
    </div>
  </div>
</template>

<style scoped>
.owner-page {
  color: var(--st-ink);
  background: var(--st-surface);
  padding: 12px 0 52px;
}
.sheet {
  width: min(1110px, calc(100% - 48px));
  margin: auto;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 44px;
  color: var(--st-link);
  font-weight: 650;
}
.back:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}
.masthead {
  position: relative;
  isolation: isolate;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px 28px;
  width: 100%;
  min-height: clamp(132px, 12.5vw, 146px);
  padding: clamp(22px, 2.6vw, 32px) clamp(22px, 3.6vw, 40px);
  overflow: hidden;
  border-radius: 16px;
  border: 1px solid rgba(178, 202, 219, 0.72);
  /* 晨雾弥散柔光（控制台略沉稳版）：与星潮阅读页同源的纯 CSS 冰蓝与淡琥珀柔和过渡，零位图。 */
  background:
    radial-gradient(
      64% 142% at 8% 6%,
      rgba(162, 204, 232, 0.72) 0%,
      rgba(162, 204, 232, 0.26) 48%,
      transparent 76%
    ),
    radial-gradient(
      46% 124% at 90% 18%,
      rgba(246, 214, 166, 0.46) 0%,
      rgba(246, 214, 166, 0.13) 52%,
      transparent 78%
    ),
    radial-gradient(54% 114% at 56% 108%, rgba(192, 222, 226, 0.46) 0%, transparent 74%),
    linear-gradient(118deg, #e5f0f8 0%, #f1f6fa 54%, #f9f5ec 100%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86);
}
.mast-main {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}
.masthead h1 {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 14px;
  color: var(--st-ink-deep);
  font:
    700 clamp(25px, 3.6vw, 44px)/1.12 'Songti SC',
    'STSong',
    'Noto Serif SC',
    Georgia,
    serif;
  letter-spacing: -0.015em;
}
.masthead h1 em {
  color: #345872;
  font:
    italic 400 clamp(15px, 1.8vw, 22px)/1 Georgia,
    serif;
  letter-spacing: 0.01em;
}
.mast-sub {
  margin: 0;
  color: #466072;
  font-size: 13.5px;
  font-weight: 500;
  line-height: 1.5;
  letter-spacing: 0.015em;
}
.mast-actions {
  position: relative;
  z-index: 1;
  flex-shrink: 0;
  display: flex;
  align-items: center;
}
.mast-watermark {
  position: absolute;
  right: clamp(22px, 3.6vw, 40px);
  top: 50%;
  transform: translateY(-50%);
  z-index: 0;
  pointer-events: none;
  user-select: none;
  color: rgba(20, 47, 70, 0.072);
  font:
    italic 400 clamp(40px, 5.2vw, 64px)/0.9 Georgia,
    'Times New Roman',
    serif;
  letter-spacing: -0.02em;
}
/* 屋主已登录态将水印移入主按钮左侧留白带，避免按钮与水印互相压盖。 */
.masthead.has-actions .mast-watermark {
  right: clamp(204px, 21vw, 236px);
  font-size: clamp(34px, 4.6vw, 60px);
  color: rgba(20, 47, 70, 0.068);
}
.publish {
  --el-button-bg-color: #173e55;
  --el-button-border-color: #173e55;
  --el-button-hover-bg-color: #102d40;
  --el-button-hover-border-color: #102d40;
  --el-button-active-bg-color: #102d40;
  --el-button-active-border-color: #081f30;
  --el-button-disabled-bg-color: #546d75;
  --el-button-disabled-border-color: #546d75;
  min-height: 44px;
  padding-inline: 20px;
  border-radius: 10px;
  white-space: nowrap;
}
.gate {
  margin: 24px 0;
  padding: 20px;
  border: 1px solid #cddbd9;
  border-radius: 12px;
  background: #fff;
}
.gate h2 {
  margin: 0;
}
@media (max-width: 640px) {
  .sheet {
    width: calc(100% - 28px);
  }
  .masthead {
    flex-direction: column;
    align-items: flex-start;
    gap: 14px;
    min-height: 0;
    padding: 18px 18px 20px;
    border-radius: 14px;
  }
  .mast-main {
    gap: 6px;
  }
  .masthead h1 {
    gap: 2px 10px;
  }
  .mast-sub {
    font-size: 12.5px;
  }
  .mast-actions {
    width: 100%;
  }
  .mast-watermark {
    right: 14px;
    top: auto;
    bottom: 6px;
    transform: none;
    font-size: clamp(24px, 7.6vw, 34px);
    color: rgba(20, 47, 70, 0.05);
  }
  .masthead.has-actions .mast-watermark {
    right: 16px;
    bottom: 22px;
    font-size: clamp(26px, 8vw, 38px);
    color: rgba(20, 47, 70, 0.065);
  }
}
</style>

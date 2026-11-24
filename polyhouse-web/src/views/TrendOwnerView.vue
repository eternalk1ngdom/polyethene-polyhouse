<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ElButton, ElIcon } from 'element-plus'
import { Back, Refresh } from '@element-plus/icons-vue'
import { starTide } from '../config/starTide'
import TrendTaskProgress from '../features/trend/TrendTaskProgress.vue'
import TrendBriefingControl from '../features/trend/TrendBriefingControl.vue'
import TrendFeeLedger from '../features/trend/TrendFeeLedger.vue'
import { useTrendOwner } from '../features/trend/useTrendOwner'
import { useAccountUiStore } from '../stores/useAccountUiStore'
import { useUserStore } from '../stores/useUserStore'

const user = useUserStore()
const accountUi = useAccountUiStore()
const {
  allowed,
  job,
  fees,
  briefing,
  intent,
  knownJobId,
  jobBusy,
  feesBusy,
  briefingBusy,
  briefingGenerating,
  jobError,
  feeError,
  briefingError,
  submissionNote,
  briefingNote,
  active,
  waiting,
  briefingWaiting,
  refreshJob,
  submit,
  refreshFees,
  refreshBriefing,
  generateBriefing,
} = useTrendOwner()
</script>

<template>
  <div class="owner-page">
    <div class="sheet">
      <RouterLink :to="starTide.route" class="back">
        <ElIcon aria-hidden="true"><Back /></ElIcon> 返回星潮日报
      </RouterLink>
      <header class="masthead">
        <div class="mast-copy">
          <span class="wordmark" lang="en">StarTide</span>
          <h1>星潮发布台</h1>
          <template v-if="allowed">
            <p id="update-hint">更新今天的 GitHub 日报。启用 AI 解读时可能产生费用。</p>
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
          </template>
        </div>
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
        <TrendFeeLedger :fees="fees" :busy="feesBusy" :error="feeError" @refresh="refreshFees" />
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
  overflow: hidden;
  border-radius: 16px;
  background: #cadbe8 url('../assets/startide-tide-atlas.webp') no-repeat right bottom / auto 100%;
}
.mast-copy {
  padding: clamp(20px, 3.5vw, 38px);
  background: linear-gradient(90deg, #cadbe8 30%, rgba(202, 219, 232, 0.97) 41%, transparent 70%);
}
.wordmark {
  font:
    italic 22px/1.2 Georgia,
    serif;
}
h1 {
  font:
    700 clamp(34px, 4vw, 53px)/1.14 Georgia,
    'Songti SC',
    SimSun,
    serif;
  margin: 5px 0 0;
}
.mast-copy p {
  margin: 12px 0 14px;
  font-size: 14px;
  line-height: 1.55;
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
  padding-inline: 19px;
  border-radius: 9px;
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
    background-size: auto 120px;
  }
  .mast-copy {
    padding: 20px 18px 64px;
    background: linear-gradient(180deg, #d7e7e4 calc(100% - 105px), transparent calc(100% - 50px));
  }
}
</style>

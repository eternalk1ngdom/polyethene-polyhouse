<script setup lang="ts">
import { ElButton, ElIcon, ElProgress } from 'element-plus'
import { Check, Refresh, Trophy } from '@element-plus/icons-vue'
import { useUserGrowthStore } from '../stores/useUserGrowthStore'
import { formatAccountNumber } from '../utils/userPresentation'

/** 完整面板和头像小窗复用同一快照，避免不同区域重复维护领取状态。 */
interface Props {
  /** 小窗采用更紧凑的排版。 */
  compact?: boolean
}
withDefaults(defineProps<Props>(), { compact: false })
const growth = useUserGrowthStore()
</script>

<template>
  <section class="growth-panel" :class="{ compact }" aria-label="账号成长">
    <template v-if="growth.summary">
      <div class="growth-heading">
        <div>
          <p v-if="!compact" class="eyebrow">A LITTLE GROWTH, EVERY DAY</p>
          <h2>
            <ElIcon v-if="!compact"><Trophy /></ElIcon
            >{{ compact ? '成长进度' : '每一天，都有一点新成长' }}
          </h2>
        </div>
        <span class="level-badge"
          >Lv.{{ growth.summary.level
          }}<span v-if="!compact"> / {{ growth.summary.maxLevel }}</span></span
        >
      </div>
      <div class="progress-copy">
        <span>{{
          growth.summary.maxLevelReached
            ? '已达到当前最高等级'
            : `向 Lv.${growth.summary.level + 1} 前进`
        }}</span
        ><strong>{{
          growth.summary.maxLevelReached ? '已满级' : `${growth.summary.progressPercent}%`
        }}</strong>
      </div>
      <ElProgress
        :percentage="growth.summary.progressPercent"
        :show-text="false"
        :stroke-width="compact ? 5 : 8"
        :color="growth.summary.maxLevelReached ? 'var(--ph-accent-strong)' : 'var(--ph-brand)'"
        :aria-label="`成长进度 ${growth.summary.progressPercent}%`"
      />
      <p class="next-level">
        {{
          growth.summary.maxLevelReached
            ? '经验和硬币仍会继续累计。'
            : `距离下一级还需 ${formatAccountNumber(growth.summary.experienceToNextLevel)} 经验`
        }}
      </p>
      <div v-if="!compact" class="threshold-note">
        累计经验 {{ formatAccountNumber(growth.summary.experience)
        }}<span v-if="growth.summary.nextLevelExperience"
          >下一级门槛 {{ formatAccountNumber(growth.summary.nextLevelExperience) }}</span
        >
      </div>
      <div class="daily-reward" :class="{ claimed: growth.summary.dailyLoginRewardClaimed }">
        <div class="reward-title">
          <ElIcon v-if="growth.summary.dailyLoginRewardClaimed"><Check /></ElIcon
          ><strong>{{
            growth.summary.dailyLoginRewardClaimed ? '当日登录奖励已到账' : '当日奖励待确认'
          }}</strong>
        </div>
        <p>
          {{ growth.summary.dailyLoginRewardClaimed ? '+' : '预计 +'
          }}{{ growth.summary.dailyLoginExperience }} 经验<span
            >+{{ growth.summary.dailyLoginCoins }} 硬币</span
          >
        </p>
        <small v-if="!compact"
          >{{ growth.summary.rewardDate }} ·
          {{
            growth.summary.rewardTimeZone === 'Asia/Shanghai'
              ? '北京时间'
              : growth.summary.rewardTimeZone
          }}</small
        >
      </div>
      <p v-if="!compact" class="growth-note">
        有效登录后自动结算，每个自然日一次，多端共享。每天零点更新，升级不扣经验，等级不改变账号身份。
      </p>
      <ElButton
        v-if="!compact || !growth.summary.dailyLoginRewardClaimed"
        text
        class="refresh-growth"
        @click="growth.refresh()"
        ><ElIcon><Refresh /></ElIcon>刷新成长状态</ElButton
      >
    </template>
    <div v-else-if="growth.status === 'error'" class="growth-unavailable" role="status">
      <p>{{ growth.errorMessage }}</p>
      <ElButton size="small" @click="growth.refresh()">重试成长查询</ElButton>
    </div>
    <div v-else class="growth-loading" role="status">正在确认成长与奖励状态…</div>
  </section>
</template>

<style scoped>
.growth-panel {
  margin-top: 24px;
  padding: 30px;
  border: 1px solid var(--ph-line);
  background: #ffffff;
  border-radius: 15px;
  color: var(--ph-ink);
}
.growth-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 26px;
}
.eyebrow {
  font-size: 8px;
  color: var(--ph-muted);
  letter-spacing: 1.3px;
  margin: 0 0 8px;
}
h2 {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}
h2 .el-icon {
  color: var(--ph-muted);
}
.level-badge {
  background: var(--ph-soft);
  color: var(--ph-ink);
  padding: 7px 11px;
  border-radius: 7px;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
}
.level-badge > span {
  font-size: 11px;
  font-weight: 400;
  color: var(--ph-muted);
}
.progress-copy {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--ph-muted);
}
.progress-copy strong {
  color: var(--ph-brand);
}
.next-level {
  font-size: 12px;
  color: var(--ph-muted);
  margin: 14px 0;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.threshold-note {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 28px;
  color: var(--ph-muted);
  font-size: 11px;
  overflow-wrap: anywhere;
}
.daily-reward {
  margin-top: 24px;
  padding: 18px 20px;
  border-radius: 10px;
  border: 1px solid var(--ph-line);
  background: #ffffff;
}
.daily-reward.claimed {
  background: #ffffff;
  border-color: var(--ph-line);
}
.reward-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  color: var(--ph-ink);
}
.reward-title strong {
  font-weight: 500;
}
.daily-reward p {
  font-size: 15px;
  color: var(--ph-ink);
  margin: 13px 0 10px;
}
.daily-reward p > span {
  margin-left: 24px;
}
.daily-reward small {
  font-size: 10px;
  color: var(--ph-muted);
}
.growth-note {
  color: var(--ph-muted);
  font-size: 11px;
  line-height: 1.9;
  margin: 18px 0 0;
}
.refresh-growth {
  color: var(--ph-brand);
  font-size: 12px;
  margin: 12px 0 -6px;
}
.refresh-growth .el-icon {
  margin-right: 7px;
}
.growth-loading,
.growth-unavailable {
  font-size: 12px;
  color: var(--ph-muted);
  line-height: 1.8;
}
.growth-unavailable p {
  margin-top: 0;
}
.compact {
  border: 0;
  border-top: 1px solid var(--ph-line);
  border-radius: 0;
  margin: 0 10px;
  padding: 16px 4px;
}
.compact .growth-heading {
  margin-bottom: 15px;
}
.compact h2 {
  font-size: 12px;
}
.compact .level-badge {
  padding: 3px 7px;
  font-size: 10px;
}
.compact .progress-copy,
.compact .next-level {
  font-size: 10px;
}
.compact .next-level {
  margin: 9px 0 0;
}
.compact .daily-reward {
  margin-top: 12px;
  padding: 10px;
}
.compact .reward-title {
  font-size: 10px;
}
.compact .daily-reward p {
  font-size: 11px;
  margin: 8px 0 0;
}
.compact .daily-reward p > span {
  margin-left: 14px;
}
@media (max-width: 640px) {
  .growth-panel:not(.compact) {
    padding: 24px 20px;
  }
  h2 {
    font-size: 18px;
  }
  .daily-reward {
    padding: 15px;
  }
}
</style>

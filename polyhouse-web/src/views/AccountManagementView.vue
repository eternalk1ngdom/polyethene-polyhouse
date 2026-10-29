<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
  ElTag,
} from 'element-plus'
import { useAccountManagement } from '../composables/useAccountManagement'
import { useUserStore } from '../stores/useUserStore'
import { useAccountUiStore } from '../stores/useAccountUiStore'
import type { ManagedAccount } from '../api/accountManagement'
const store = useUserStore()
const accountUi = useAccountUiStore()
const {
  allowed,
  owner,
  filters,
  items,
  total,
  page,
  loading,
  listError,
  notice,
  dialogOpen,
  target,
  action,
  reason,
  ownerPassword,
  saving,
  error,
  result,
  pending,
  stale,
  needsPassword,
  hasNext,
  load,
  open,
  submit,
  confirm,
  close,
  finish,
  copyPassword,
  nextPage,
} = useAccountManagement()
const labels: Record<ManagedAccount['role'], string> = {
  USER: '普通用户',
  ADMIN: '管理员',
  INSIDER: '内部用户',
  OWNER: '屋主',
}
const actionLabel = computed(
  () => ({ BAN: '封禁账号', UNBAN: '解封账号', PASSWORD_RESET: '重置密码' })[action.value],
)
// 深链接也向服务器核实身份；本地缓存不能取得管理权限。
onMounted(() => {
  void store.restoreSession()
})
</script>

<template>
  <div class="management-page">
    <RouterLink class="back-link" to="/account">← 返回个人中心</RouterLink>
    <header class="page-heading">
      <div>
        <p>ACCOUNT MANAGEMENT</p>
        <h1>{{ owner ? '屋主工作台' : '管理中心' }}</h1>
        <span>受控内测 · 账号管理</span>
      </div>
      <RouterLink v-if="owner" class="create-link" to="/manage/accounts">创建账号 →</RouterLink>
    </header>
    <template v-if="allowed">
      <ElAlert
        v-if="notice"
        :title="notice"
        type="info"
        :closable="false"
        show-icon
        role="status"
      />
      <div v-if="pending" class="pending-strip">
        <span>有一笔操作等待核对，请先确认结果。</span
        ><ElButton :loading="saving" @click="confirm">继续核对操作</ElButton>
      </div>
      <section class="accounts-panel" aria-labelledby="accounts-title">
        <h2 id="accounts-title">账号列表</h2>
        <p class="intro">
          {{
            owner
              ? '屋主账号仅供查看。封禁保留资料，解封不会恢复旧登录状态。'
              : '仅显示普通用户；可封禁账号，解封和密码重置由屋主处理。'
          }}
        </p>
        <ElForm class="filters" label-position="top" @submit.prevent="load(true)">
          <ElFormItem label="登录账号前缀"
            ><ElInput v-model="filters.username" maxlength="32" placeholder="例如 ph000" clearable
          /></ElFormItem>
          <ElFormItem v-if="owner" label="身份"
            ><ElSelect v-model="filters.role" aria-label="筛选身份" placeholder="全部身份"
              ><ElOption label="全部身份" value="" /><ElOption
                v-for="(label, key) in labels"
                :key="key"
                :label="label"
                :value="key" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="账号状态"
            ><ElSelect v-model="filters.status" aria-label="筛选状态" placeholder="全部状态"
              ><ElOption label="全部状态" value="" /><ElOption label="正常" :value="1" /><ElOption
                label="已封禁"
                :value="0" /></ElSelect
          ></ElFormItem>
          <ElButton native-type="submit" :loading="loading">查询</ElButton>
        </ElForm>
        <ElAlert v-if="listError" :title="listError" type="error" :closable="false" role="alert" />
        <p v-if="loading" role="status">正在加载账号…</p>
        <p v-else-if="!items.length && !listError" class="empty">没有符合条件的账号。</p>
        <ul class="account-list" :aria-busy="loading">
          <li v-for="account in items" :key="account.id" class="account-row">
            <div class="identity">
              <strong>{{ account.username }}</strong
              ><span>{{ account.nickname }}</span
              ><small>编号 {{ account.id }}</small>
            </div>
            <div class="badges">
              <ElTag type="info">{{ labels[account.role] }}</ElTag
              ><ElTag :type="account.status === 1 ? 'success' : 'danger'">{{
                account.status === 1 ? '正常' : '已封禁'
              }}</ElTag
              ><small v-if="account.passwordChangeRequired">下次登录需改密</small>
            </div>
            <div class="row-actions">
              <span v-if="account.readOnly">屋主账号 · 只读</span>
              <template v-else>
                <ElButton
                  v-if="account.status === 1"
                  :aria-label="`封禁 ${account.username}`"
                  :disabled="!!pending"
                  @click="open(account, 'BAN')"
                  >封禁</ElButton
                >
                <ElButton
                  v-if="owner && account.status === 0"
                  :aria-label="`解封 ${account.username}`"
                  :disabled="!!pending"
                  @click="open(account, 'UNBAN')"
                  >解封</ElButton
                >
                <ElButton
                  v-if="owner"
                  :aria-label="`重置密码 ${account.username}`"
                  :disabled="!!pending"
                  @click="open(account, 'PASSWORD_RESET')"
                  >重置密码</ElButton
                >
              </template>
            </div>
          </li>
        </ul>
        <nav class="pagination" aria-label="账号分页">
          <span>共 {{ total }} 个 · 第 {{ page }} 页</span>
          <div>
            <ElButton :disabled="loading || page === 1" @click="nextPage(-1)">上一页</ElButton
            ><ElButton :disabled="loading || !hasNext" @click="nextPage(1)">下一页</ElButton>
          </div>
        </nav>
      </section>
    </template>
    <section v-else class="accounts-panel">
      <h2>需要管理权限</h2>
      <p>请使用已完成首次改密的屋主或管理员账号。</p>
      <ElButton v-if="!store.isLoggedIn" @click="accountUi.openLogin()">登录账号</ElButton
      ><RouterLink v-else to="/account">返回个人中心</RouterLink>
    </section>
    <ElDialog
      :model-value="dialogOpen"
      :title="result ? '操作结果' : pending ? '核对操作结果' : actionLabel"
      class="management-dialog"
      width="min(560px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      @update:model-value="
        (value) => {
          if (!value) close()
        }
      "
    >
      <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" role="alert" />
      <template v-if="result">
        <p role="status">
          已成功记录：{{ result.username }} ·
          {{ { BAN: '封禁', UNBAN: '解封', PASSWORD_RESET: '重置密码' }[result.action] }}
        </p>
        <p>这是本次操作的成功记录；账号当前状态以最新列表为准。</p>
        <ElForm v-if="result.firstDelivery" label-position="top" @submit.prevent
          ><ElFormItem label="临时密码 · 仅本次展示"
            ><ElInput
              :model-value="result.temporaryPassword ?? ''"
              readonly
              type="password"
              show-password
              autocomplete="off" /></ElFormItem
        ></ElForm>
        <p v-if="result.firstDelivery">
          请私密交付。关闭或刷新后无法找回，接收方首次登录必须改密。
        </p>
        <p v-else-if="result.action === 'PASSWORD_RESET'">
          原密码不能再次取回。若未保存，请结束核对，确认最新账号后显式重新重置。
        </p>
        <div class="dialog-actions">
          <ElButton v-if="result.firstDelivery" @click="copyPassword">复制账号与密码</ElButton
          ><ElButton type="primary" @click="finish">已核对，清除本次结果</ElButton>
        </div>
      </template>
      <template v-else-if="pending">
        <p>
          {{ pending.username }} ·
          {{ { BAN: '封禁', UNBAN: '解封', PASSWORD_RESET: '重置密码' }[pending.action] }}
        </p>
        <p>只查询原操作记录，不会再次执行。原请求仍可能完成；请先核对，勿直接重复操作。</p>
        <div class="dialog-actions">
          <ElButton :loading="saving" @click="confirm">核对操作结果</ElButton
          ><ElButton :disabled="saving" @click="finish">结束核对并刷新列表</ElButton>
        </div>
      </template>
      <ElForm v-else-if="target" label-position="top" :disabled="saving" @submit.prevent="submit">
        <dl class="target-summary">
          <div>
            <dt>目标账号</dt>
            <dd>{{ target.username }} · {{ target.nickname }}</dd>
          </div>
          <div>
            <dt>身份 / 状态</dt>
            <dd>{{ labels[target.role] }} / {{ target.status === 1 ? '正常' : '已封禁' }}</dd>
          </div>
          <div>
            <dt>用户编号</dt>
            <dd>{{ target.id }}</dd>
          </div>
        </dl>
        <p>
          {{
            action === 'BAN'
              ? '封禁后会立即撤销全部登录；资料和成长数据保留。'
              : action === 'UNBAN'
                ? '解封后需要重新登录，旧 Token 不会恢复。'
                : '原密码将失效，全部登录将撤销。重置不会解封已封禁账号。'
          }}
        </p>
        <ElFormItem label="操作原因（必填）"
          ><ElInput
            v-model="reason"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="写明管理原因，请勿填写密码或其他隐私"
        /></ElFormItem>
        <ElFormItem v-if="needsPassword" label="屋主当前密码（再次确认）"
          ><ElInput
            v-model="ownerPassword"
            type="password"
            show-password
            maxlength="72"
            autocomplete="current-password"
        /></ElFormItem>
        <p v-if="stale" role="alert">账号已变化。请关闭此窗口，从最新列表重新选择。</p>
        <div class="dialog-actions">
          <ElButton :disabled="saving" @click="close">取消</ElButton
          ><ElButton type="primary" native-type="submit" :loading="saving" :disabled="stale"
            >确认{{ actionLabel }}</ElButton
          >
        </div>
      </ElForm>
    </ElDialog>
  </div>
</template>

<style scoped>
.management-page {
  max-width: 1120px;
  margin: 0 auto;
  padding: 40px 24px 80px;
}
.back-link {
  color: var(--ph-muted);
  font-size: 14px;
}
.page-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 24px;
  margin: 28px 0;
}
.page-heading p {
  letter-spacing: 0.16em;
  font-size: 11px;
}
.page-heading h1 {
  font-size: clamp(28px, 4vw, 40px);
  margin: 10px 0;
}
.page-heading span,
.intro,
.empty {
  color: var(--ph-muted);
}
.create-link {
  padding: 14px 22px;
  border-radius: 24px;
  background: var(--ph-ink);
  color: white;
  white-space: nowrap;
}
.accounts-panel {
  padding: 28px;
  margin-top: 24px;
  border: 1px solid var(--ph-line);
  border-radius: 20px;
  background: white;
}
.accounts-panel h2 {
  margin: 0 0 12px;
  font-size: 22px;
}
.intro {
  line-height: 1.8;
  margin-bottom: 24px;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: flex-end;
}
.filters .el-form-item {
  flex: 1 1 150px;
  margin-bottom: 16px;
}
.filters > .el-button {
  margin-bottom: 16px;
}
.account-list {
  padding: 0;
  margin: 12px 0;
  list-style: none;
}
.account-row {
  display: grid;
  grid-template-columns: minmax(160px, 1.2fr) minmax(100px, 1fr) auto;
  gap: 20px;
  padding: 22px 0;
  border-bottom: 1px solid var(--ph-line);
  align-items: center;
}
.identity {
  display: grid;
  gap: 7px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.identity small,
.identity span,
.badges small,
.row-actions > span {
  color: var(--ph-muted);
}
.identity small {
  font-size: 11px;
}
.badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.badges small {
  flex-basis: 100%;
}
.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.row-actions .el-button + .el-button {
  margin-left: 0;
}
.pagination,
.pending-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-top: 20px;
}
.pagination span {
  font-size: 13px;
}
.target-summary {
  padding: 16px;
  background: var(--ph-surface);
  border-radius: 12px;
}
.target-summary div {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 10px;
  margin: 8px 0;
}
.target-summary dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.dialog-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}
.dialog-actions .el-button + .el-button {
  margin-left: 0;
}
.management-page :deep(.el-button),
.management-page :deep(.el-input__wrapper),
.management-page :deep(.el-select__wrapper) {
  min-height: 44px;
}
.management-page :deep(.el-alert) {
  margin: 12px 0;
}
@media (max-width: 768px) {
  .account-row {
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
  }
  .badges small {
    flex-basis: auto;
  }
}
@media (max-width: 600px) {
  .management-page {
    padding: 28px 16px 56px;
  }
  .accounts-panel {
    padding: 20px 16px;
  }
  .page-heading {
    flex-wrap: wrap;
  }
  .filters .el-form-item {
    flex-basis: 100%;
  }
  .dialog-actions {
    justify-content: flex-start;
  }
  .target-summary div {
    grid-template-columns: minmax(0, 1fr);
    gap: 4px;
  }
}
</style>

<script setup lang="ts">
/**
 * 通用可复用归档日历选择器（零业务耦合，主题色可通过 --adp-* CSS 变量定制）。
 *
 * 适用场景：星潮日报、账单回溯、成长记录等“离散有值归档”导航。
 * 核心能力：
 * 1. 三段式复合导航条（[ ‹ ] [ 日历图标 日期 · 最新 ▾ ] [ › ]），高频翻期免弹层一步直达；
 * 2. 桌面端轻浮层 Popover + 手机端（≤640px）底部抽屉 Bottom Sheet；
 * 3. 头部分离式年/月有界速选（[ 2026 年 ▾ ] [ 11 月 ▾ ]）：单年归档自动退化为静态年份文本，多年仅渲染真实有归档年份；
 * 4. 仅允许点选有内容的日期，缺采/无内容日置灰且禁用，空月按需提供“回到最新月”兜底；
 * 5. 可选区间高亮带（highlightRange）与快捷跳转胶囊（shortcuts），支持完整键盘方向键与 Esc 焦点还原。
 *
 * 复用示例：
 * <ArchiveDatePicker
 *   v-model="selectedDate"
 *   :latest-date="latestDate"
 *   :earliest-date="earliestDate"
 *   :available-dates="availableDateSet"
 *   :highlight-range="windowRange"
 *   :shortcuts="[{ label: '最新一期', value: '' }]"
 *   @month-change="loadMonth"
 *   @step="handleStep"
 * />
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElIcon } from 'element-plus'
import { ArrowDown, ArrowLeft, ArrowRight, Calendar, Close } from '@element-plus/icons-vue'
import {
  WEEKDAY_LABELS_MONDAY_FIRST,
  buildMonthGrid,
  clampYearMonthToBounds,
  extractYearMonth,
  findAdjacentAvailableDate,
  formatYearMonth,
  isMonthOutOfBounds,
  listArchiveYears,
  parseIsoDate,
  parseYearMonth,
  shiftIsoDate,
  shiftMonth,
  type CalendarDayCell,
  type DateRange,
} from '../core/calendar'

/** 快捷跳转胶囊配置项 */
export interface ArchiveDateShortcut {
  /** 按钮展示文本 */
  label: string
  /** 目标日期（YYYY-MM-DD；空字符串表示回到“最新一期”默认态） */
  value: string
  /** 是否禁用该快捷按钮 */
  disabled?: boolean
}

/** 公共归档日历选择器入参契约 */
export interface ArchiveDatePickerProps {
  /** 当前选中日期（YYYY-MM-DD；空字符串表示默认最新一期） */
  modelValue: string
  /** 全局最新一期日期（YYYY-MM-DD） */
  latestDate?: string | null
  /** 全局最早一期日期（YYYY-MM-DD） */
  earliestDate?: string | null
  /** 当前已知有归档内容的日期集合或列表 */
  availableDates: ReadonlySet<string> | readonly string[]
  /** 当前展示月份是否正在加载 */
  loadingMonth?: boolean
  /** 当前展示月份加载失败提示（非空时底栏展示原位重试） */
  monthError?: string
  /** 可选闭区间高亮带（如近 7 日观察窗口） */
  highlightRange?: DateRange | null
  /** 可选快捷跳转胶囊列表 */
  shortcuts?: readonly ArchiveDateShortcut[]
  /** 是否展示左右快速切期箭头（默认 true） */
  showStepButtons?: boolean
  /** 是否禁用整个选择器 */
  disabled?: boolean
  /** 语义标签（用于无障碍 aria-label） */
  ariaLabel?: string
  /** 尚无任何日期时的占位文案 */
  emptyHint?: string
  /** 底栏当月有内容天数的量词描述（默认“期有内容”） */
  unitLabel?: string
  /** 底栏当月无任何归档内容时的克制提示文案（默认“本月暂无内容”） */
  emptyMonthText?: string
  /** 可选覆写：是否允许点击“上一期”按钮 */
  canStepPrev?: boolean | null
  /** 可选覆写：是否允许点击“下一期”按钮 */
  canStepNext?: boolean | null
}

const props = withDefaults(defineProps<ArchiveDatePickerProps>(), {
  latestDate: null,
  earliestDate: null,
  loadingMonth: false,
  monthError: '',
  highlightRange: null,
  shortcuts: () => [],
  showStepButtons: true,
  disabled: false,
  ariaLabel: '归档日期选择',
  emptyHint: '暂无日期',
  unitLabel: '期有内容',
  emptyMonthText: '本月暂无内容',
  canStepPrev: null,
  canStepNext: null,
})

const emit = defineEmits<{
  /** 选中新日期（或空字符串代表最新一期） */
  (event: 'update:modelValue', value: string): void
  /** 切换浏览月份时通知调用方按需懒加载 */
  (event: 'monthChange', yearMonth: string): void
  /** 点击底栏重试按钮时触发 */
  (event: 'retryMonth', yearMonth: string): void
  /** 点击左右跳期箭头时触发；resolvedDate 非空表示已在已知集合中直接定位，null 表示需调用方跨月解析 */
  (event: 'step', direction: 'prev' | 'next', resolvedDate: string | null): void
}>()

type PanelMode = 'days' | 'months' | 'years'

const rootRef = ref<HTMLElement>()
const triggerBtnRef = ref<HTMLButtonElement>()
const panelRef = ref<HTMLElement>()

const open = ref(false)
const panelMode = ref<PanelMode>('days')

/** 当前实际生效的锚点日期：优先 modelValue，空串时回退 latestDate */
const activeDate = computed(() => {
  if (parseIsoDate(props.modelValue)) return props.modelValue
  if (parseIsoDate(props.latestDate)) return props.latestDate!
  return ''
})

/** 是否正处于最新一期（用于在触发器上展示“最新”微标签） */
const isShowingLatest = computed(() => {
  if (!props.latestDate) return !props.modelValue
  if (!props.modelValue) return true
  return props.modelValue === props.latestDate
})

/** 触发器主文案 */
const displayDateText = computed(() => {
  if (activeDate.value) return activeDate.value
  return props.emptyHint
})

/** 推导初始展示月份 */
function resolveInitialMonth(): string {
  const fromActive = extractYearMonth(activeDate.value)
  if (fromActive) return fromActive
  for (const date of props.availableDates) {
    const ym = extractYearMonth(date)
    if (ym) return ym
  }
  const now = new Date()
  return formatYearMonth(now.getFullYear(), now.getMonth() + 1)
}

const viewYearMonth = ref<string>(resolveInitialMonth())
const focusedDate = ref<string>(activeDate.value)

/** 当外部选中日期变化且面板未打开时，同步浏览月份到当前日期所在月 */
watch(activeDate, (nextDate) => {
  if (!nextDate) return
  if (!open.value) {
    const ym = extractYearMonth(nextDate)
    if (ym) viewYearMonth.value = ym
  }
  focusedDate.value = nextDate
})

const viewParts = computed(() => parseYearMonth(viewYearMonth.value) ?? { year: 2026, month: 1 })
const latestYearMonth = computed(() => extractYearMonth(props.latestDate))

const monthGrid = computed(() =>
  buildMonthGrid(viewYearMonth.value, {
    selectedDate: activeDate.value || null,
    earliestDate: props.earliestDate,
    latestDate: props.latestDate,
    availableDates: props.availableDates,
    highlightRange: props.highlightRange,
  }),
)

const prevMonthYm = computed(() => shiftMonth(viewYearMonth.value, -1))
const nextMonthYm = computed(() => shiftMonth(viewYearMonth.value, 1))

const canGoPrevMonth = computed(() => {
  if (!prevMonthYm.value) return false
  if (!props.earliestDate) return true
  const earliestYm = extractYearMonth(props.earliestDate)
  return !earliestYm || prevMonthYm.value >= earliestYm
})

const canGoNextMonth = computed(() => {
  if (!nextMonthYm.value) return false
  if (!props.latestDate) return true
  const latestYm = extractYearMonth(props.latestDate)
  return !latestYm || nextMonthYm.value <= latestYm
})

const selectableYears = computed(() =>
  listArchiveYears({
    earliestDate: props.earliestDate,
    latestDate: props.latestDate,
    availableDates: props.availableDates,
    fallbackYear: viewParts.value.year,
  }),
)

/** 仅当归档跨度超过 1 个年份时才激活年份下拉胶囊，单年退化为静态年份文本。 */
const canPickYear = computed(() => selectableYears.value.length > 1)

watch(canPickYear, (allowed) => {
  if (!allowed && panelMode.value === 'years') {
    panelMode.value = 'days'
  }
})

const canGoPrevYear = computed(() => {
  if (!canPickYear.value) return false
  const minYear = selectableYears.value[0]
  return minYear !== undefined && viewParts.value.year > minYear
})

const canGoNextYear = computed(() => {
  if (!canPickYear.value) return false
  const maxYear = selectableYears.value[selectableYears.value.length - 1]
  return maxYear !== undefined && viewParts.value.year < maxYear
})

const canGoHeaderPrev = computed(() => {
  if (panelMode.value === 'days') return canGoPrevMonth.value
  if (panelMode.value === 'months') return canGoPrevYear.value
  return false
})

const canGoHeaderNext = computed(() => {
  if (panelMode.value === 'days') return canGoNextMonth.value
  if (panelMode.value === 'months') return canGoNextYear.value
  return false
})

/** 仅在翻到非最新月份且当月无内容时按需显示“回到最新月”兜底入口，平时隐藏不占地。 */
const showBackToLatestMonth = computed(
  () =>
    !props.loadingMonth &&
    !props.monthError &&
    monthGrid.value.availableCount === 0 &&
    latestYearMonth.value !== null &&
    viewYearMonth.value !== latestYearMonth.value,
)

const knownPrevDate = computed(() =>
  activeDate.value ? findAdjacentAvailableDate(activeDate.value, props.availableDates, 'prev') : null,
)
const knownNextDate = computed(() =>
  activeDate.value ? findAdjacentAvailableDate(activeDate.value, props.availableDates, 'next') : null,
)

const prevStepEnabled = computed(() => {
  if (props.disabled || !activeDate.value) return false
  if (typeof props.canStepPrev === 'boolean') return props.canStepPrev
  if (knownPrevDate.value) return true
  return Boolean(props.earliestDate && activeDate.value > props.earliestDate)
})

const nextStepEnabled = computed(() => {
  if (props.disabled || !activeDate.value) return false
  if (typeof props.canStepNext === 'boolean') return props.canStepNext
  if (knownNextDate.value) return true
  return Boolean(props.latestDate && activeDate.value < props.latestDate)
})

const monthPickerItems = computed(() => {
  const year = viewParts.value.year
  const activeYm = extractYearMonth(activeDate.value)
  return Array.from({ length: 12 }, (_, idx) => {
    const month = idx + 1
    const ym = formatYearMonth(year, month)
    return {
      month,
      yearMonth: ym,
      label: `${month} 月`,
      current: ym === viewYearMonth.value,
      hasActive: ym === activeYm,
      disabled: isMonthOutOfBounds(ym, props.earliestDate, props.latestDate),
    }
  })
})

const yearPickerItems = computed(() => {
  const activeYear = parseIsoDate(activeDate.value)?.year ?? null
  return selectableYears.value.map((year) => ({
    year,
    label: `${year} 年`,
    current: year === viewParts.value.year,
    hasActive: year === activeYear,
  }))
})

function setViewMonth(targetYm: string) {
  if (!parseYearMonth(targetYm)) return
  const changed = targetYm !== viewYearMonth.value
  viewYearMonth.value = targetYm
  if (changed) {
    emit('monthChange', targetYm)
  }
}

function openPanel() {
  if (props.disabled) return
  const targetYm = extractYearMonth(activeDate.value) ?? resolveInitialMonth()
  viewYearMonth.value = targetYm
  panelMode.value = 'days'
  open.value = true
  emit('monthChange', targetYm)
  void nextTick(() => {
    focusActiveDayCell()
  })
}

function closePanel(restoreFocus: boolean) {
  if (!open.value) return
  open.value = false
  panelMode.value = 'days'
  if (restoreFocus) {
    void nextTick(() => {
      if (typeof triggerBtnRef.value?.focus === 'function') {
        triggerBtnRef.value.focus()
      }
    })
  }
}

function togglePanel() {
  if (open.value) closePanel(true)
  else openPanel()
}

function handleStep(direction: 'prev' | 'next') {
  const enabled = direction === 'prev' ? prevStepEnabled.value : nextStepEnabled.value
  if (!enabled) return
  const target = direction === 'prev' ? knownPrevDate.value : knownNextDate.value
  if (target) {
    emit('update:modelValue', target)
    const ym = extractYearMonth(target)
    if (ym && ym !== viewYearMonth.value) {
      viewYearMonth.value = ym
      emit('monthChange', ym)
    }
  }
  emit('step', direction, target)
}

function selectDay(cell: CalendarDayCell) {
  if (!cell.available || props.disabled) return
  emit('update:modelValue', cell.date)
  closePanel(true)
}

function selectShortcut(shortcut: ArchiveDateShortcut) {
  if (shortcut.disabled || props.disabled) return
  emit('update:modelValue', shortcut.value)
  closePanel(true)
}

function shiftHeader(delta: number) {
  if (panelMode.value === 'days') {
    if (delta < 0 && !canGoPrevMonth.value) return
    if (delta > 0 && !canGoNextMonth.value) return
    const next = shiftMonth(viewYearMonth.value, delta)
    if (next) setViewMonth(next)
  } else if (panelMode.value === 'months') {
    if (delta < 0 && !canGoPrevYear.value) return
    if (delta > 0 && !canGoNextYear.value) return
    const targetYear = viewParts.value.year + delta
    const clamped = clampYearMonthToBounds(
      formatYearMonth(targetYear, viewParts.value.month),
      props.earliestDate,
      props.latestDate,
    )
    if (clamped) viewYearMonth.value = clamped
  }
}

function toggleYearPicker() {
  if (!canPickYear.value) return
  if (panelMode.value === 'years') {
    panelMode.value = 'days'
    void nextTick(() => focusActiveDayCell())
  } else {
    panelMode.value = 'years'
  }
}

function toggleMonthPicker() {
  if (panelMode.value === 'months') {
    panelMode.value = 'days'
    void nextTick(() => focusActiveDayCell())
  } else {
    panelMode.value = 'months'
  }
}

function pickMonth(yearMonth: string, disabled: boolean) {
  if (disabled) return
  panelMode.value = 'days'
  setViewMonth(yearMonth)
  void nextTick(() => focusActiveDayCell())
}

function pickYear(year: number) {
  const clamped = clampYearMonthToBounds(
    formatYearMonth(year, viewParts.value.month),
    props.earliestDate,
    props.latestDate,
  )
  panelMode.value = 'days'
  if (clamped) setViewMonth(clamped)
  void nextTick(() => focusActiveDayCell())
}

function jumpToLatestMonth() {
  if (!latestYearMonth.value) return
  panelMode.value = 'days'
  setViewMonth(latestYearMonth.value)
  void nextTick(() => focusActiveDayCell())
}

function focusDayByIso(isoDate: string) {
  focusedDate.value = isoDate
  void nextTick(() => {
    const panel = panelRef.value
    if (typeof panel?.querySelector === 'function') {
      panel.querySelector<HTMLButtonElement>(`button[data-date="${isoDate}"]`)?.focus()
    }
  })
}

function focusActiveDayCell() {
  const target =
    monthGrid.value.cells.find((c) => c.selected && c.inCurrentMonth) ??
    monthGrid.value.cells.find((c) => c.inCurrentMonth && c.available) ??
    monthGrid.value.cells.find((c) => c.inCurrentMonth)
  if (target) focusDayByIso(target.date)
}

function onDayKeydown(event: KeyboardEvent, cell: CalendarDayCell) {
  const deltaMap: Record<string, number> = {
    ArrowLeft: -1,
    ArrowRight: 1,
    ArrowUp: -7,
    ArrowDown: 7,
  }
  const delta = deltaMap[event.key]
  if (delta !== undefined) {
    event.preventDefault()
    const nextIso = shiftIsoDate(cell.date, delta)
    if (!nextIso) return
    const nextYm = extractYearMonth(nextIso)
    if (nextYm && nextYm !== viewYearMonth.value) {
      if (isMonthOutOfBounds(nextYm, props.earliestDate, props.latestDate)) return
      setViewMonth(nextYm)
    }
    focusDayByIso(nextIso)
    return
  }
  if (event.key === 'PageUp') {
    event.preventDefault()
    shiftHeader(-1)
    void nextTick(() => focusActiveDayCell())
  } else if (event.key === 'PageDown') {
    event.preventDefault()
    shiftHeader(1)
    void nextTick(() => focusActiveDayCell())
  }
}

function onPanelKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    if (panelMode.value !== 'days') {
      panelMode.value = 'days'
      void nextTick(() => focusActiveDayCell())
    } else {
      closePanel(true)
    }
  }
}

function onOutsidePointerDown(event: PointerEvent) {
  if (!open.value) return
  const target = event.target as Node | null
  if (target && typeof rootRef.value?.contains === 'function' && rootRef.value.contains(target)) {
    return
  }
  closePanel(false)
}

onMounted(() => {
  if (typeof document !== 'undefined') {
    document.addEventListener('pointerdown', onOutsidePointerDown)
  }
})

onBeforeUnmount(() => {
  if (typeof document !== 'undefined') {
    document.removeEventListener('pointerdown', onOutsidePointerDown)
  }
})
</script>

<template>
  <div ref="rootRef" class="adp-root" :class="{ 'adp-open': open, 'adp-disabled': disabled }">
    <div class="adp-trigger-group" role="group" :aria-label="ariaLabel">
      <button
        v-if="showStepButtons"
        type="button"
        class="adp-step-btn adp-step-prev"
        aria-label="上一有内容期"
        :disabled="!prevStepEnabled"
        @click="handleStep('prev')"
      >
        <ElIcon aria-hidden="true"><ArrowLeft /></ElIcon>
      </button>

      <button
        ref="triggerBtnRef"
        type="button"
        class="adp-main-btn"
        :class="{ 'adp-solo': !showStepButtons }"
        :disabled="disabled"
        :aria-expanded="open"
        aria-haspopup="dialog"
        :aria-label="`${ariaLabel}：当前 ${displayDateText}${isShowingLatest ? '，最新一期' : ''}`"
        @click="togglePanel"
      >
        <ElIcon class="adp-cal-icon" aria-hidden="true"><Calendar /></ElIcon>
        <span class="adp-date-text">{{ displayDateText }}</span>
        <span v-if="isShowingLatest && activeDate" class="adp-latest-pill">最新</span>
        <ElIcon class="adp-caret-icon" :class="{ rotated: open }" aria-hidden="true">
          <ArrowDown />
        </ElIcon>
      </button>

      <button
        v-if="showStepButtons"
        type="button"
        class="adp-step-btn adp-step-next"
        aria-label="下一有内容期"
        :disabled="!nextStepEnabled"
        @click="handleStep('next')"
      >
        <ElIcon aria-hidden="true"><ArrowRight /></ElIcon>
      </button>
    </div>

    <div v-if="open" class="adp-backdrop" aria-hidden="true" @click="closePanel(true)" />

    <div
      v-if="open"
      ref="panelRef"
      class="adp-panel"
      role="dialog"
      aria-modal="true"
      :aria-label="`${ariaLabel}面板`"
      @keydown="onPanelKeydown"
    >
      <div class="adp-sheet-handle" aria-hidden="true" />

      <div v-if="shortcuts.length" class="adp-shortcuts" role="group" aria-label="快捷日期跳转">
        <button
          v-for="item in shortcuts"
          :key="item.label"
          type="button"
          class="adp-shortcut-btn"
          :class="{
            active:
              (item.value === '' && isShowingLatest) ||
              (item.value !== '' && item.value === activeDate),
          }"
          :disabled="item.disabled || disabled"
          @click="selectShortcut(item)"
        >
          {{ item.label }}
        </button>
      </div>

      <div class="adp-header">
        <button
          type="button"
          class="adp-nav-btn"
          :aria-label="panelMode === 'months' ? '上一年' : '上个月'"
          :disabled="!canGoHeaderPrev"
          @click="shiftHeader(-1)"
        >
          <ElIcon aria-hidden="true"><ArrowLeft /></ElIcon>
        </button>

        <div class="adp-header-controls">
          <button
            v-if="canPickYear"
            type="button"
            class="adp-header-pill adp-year-pill"
            :class="{ 'is-active': panelMode === 'years' }"
            :aria-expanded="panelMode === 'years'"
            :aria-label="
              panelMode === 'years'
                ? `收起年份选择，返回月历，当前 ${viewParts.year} 年`
                : `选择年份，当前 ${viewParts.year} 年`
            "
            @click="toggleYearPicker"
          >
            <span>{{ viewParts.year }} 年</span>
            <ElIcon
              class="adp-title-caret"
              :class="{ rotated: panelMode === 'years' }"
              aria-hidden="true"
            >
              <ArrowDown />
            </ElIcon>
          </button>
          <span v-else class="adp-year-static">{{ viewParts.year }} 年</span>

          <button
            type="button"
            class="adp-header-pill adp-month-pill"
            :class="{ 'is-active': panelMode === 'months' }"
            :aria-expanded="panelMode === 'months'"
            :aria-label="
              panelMode === 'months'
                ? `收起月份选择，返回月历，当前 ${viewParts.month} 月`
                : `选择月份，当前 ${viewParts.month} 月`
            "
            @click="toggleMonthPicker"
          >
            <span>{{ viewParts.month }} 月</span>
            <ElIcon
              class="adp-title-caret"
              :class="{ rotated: panelMode === 'months' }"
              aria-hidden="true"
            >
              <ArrowDown />
            </ElIcon>
          </button>
        </div>

        <button
          type="button"
          class="adp-nav-btn"
          :aria-label="panelMode === 'months' ? '下一年' : '下个月'"
          :disabled="!canGoHeaderNext"
          @click="shiftHeader(1)"
        >
          <ElIcon aria-hidden="true"><ArrowRight /></ElIcon>
        </button>

        <button
          type="button"
          class="adp-close-btn"
          aria-label="关闭日历面板"
          @click="closePanel(true)"
        >
          <ElIcon aria-hidden="true"><Close /></ElIcon>
        </button>
      </div>

      <template v-if="panelMode === 'days'">
        <div class="adp-weekdays" aria-hidden="true">
          <span v-for="label in WEEKDAY_LABELS_MONDAY_FIRST" :key="label" class="adp-weekday">
            {{ label }}
          </span>
        </div>

        <div class="adp-days-grid" role="grid" :aria-label="`${viewParts.year} 年 ${viewParts.month} 月日历`">
          <button
            v-for="cell in monthGrid.cells"
            :key="cell.date"
            type="button"
            class="adp-day-cell"
            :class="{
              'is-outside': !cell.inCurrentMonth,
              'is-available': cell.available,
              'is-missing': !cell.available,
              'is-selected': cell.selected,
              'is-latest': cell.isLatest,
              'is-in-range': cell.inRange,
              'is-range-start': cell.isRangeStart,
              'is-range-end': cell.isRangeEnd,
            }"
            :data-date="cell.date"
            :disabled="!cell.available"
            :aria-disabled="!cell.available ? 'true' : undefined"
            :aria-pressed="cell.selected"
            :aria-label="`${cell.date}${cell.isLatest ? '，最新一期' : ''}${cell.available ? '，有归档内容' : '，无归档内容'}`"
            @click="selectDay(cell)"
            @keydown="onDayKeydown($event, cell)"
          >
            <span class="adp-day-num">{{ cell.day }}</span>
            <span v-if="cell.available" class="adp-day-dot" aria-hidden="true" />
          </button>
        </div>
      </template>

      <div v-else-if="panelMode === 'months'" class="adp-pick-grid" role="grid" aria-label="选择月份">
        <button
          v-for="item in monthPickerItems"
          :key="item.yearMonth"
          type="button"
          class="adp-pick-cell"
          :class="{ current: item.current, active: item.hasActive }"
          :disabled="item.disabled"
          @click="pickMonth(item.yearMonth, item.disabled)"
        >
          {{ item.label }}
        </button>
      </div>

      <div
        v-else
        class="adp-pick-grid adp-year-grid"
        :class="{ 'is-compact-years': yearPickerItems.length <= 2 }"
        role="grid"
        aria-label="选择年份"
      >
        <button
          v-for="item in yearPickerItems"
          :key="item.year"
          type="button"
          class="adp-pick-cell"
          :class="{ current: item.current, active: item.hasActive }"
          @click="pickYear(item.year)"
        >
          {{ item.label }}
        </button>
      </div>

      <footer class="adp-footer" role="status" aria-live="polite">
        <template v-if="loadingMonth">
          <span class="adp-status-text">正在读取本月归档…</span>
        </template>
        <template v-else-if="monthError">
          <span class="adp-status-error">{{ monthError }}</span>
          <button type="button" class="adp-retry-btn" @click="emit('retryMonth', viewYearMonth)">
            重试
          </button>
        </template>
        <template v-else-if="monthGrid.availableCount === 0">
          <span class="adp-status-text">{{ emptyMonthText }}</span>
          <button
            v-if="showBackToLatestMonth"
            type="button"
            class="adp-latest-jump-btn"
            @click="jumpToLatestMonth"
          >
            回到最新月
          </button>
        </template>
        <template v-else>
          <span class="adp-status-text">
            本月 {{ monthGrid.availableCount }} {{ unitLabel }}
            <template v-if="monthGrid.missingCount > 0"> · 缺采 {{ monthGrid.missingCount }} 天</template>
          </span>
        </template>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.adp-root {
  --adp-accent: #154e78;
  --adp-accent-hover: #103e61;
  --adp-accent-soft: #e6f0f7;
  --adp-range-bg: #edf5fa;
  --adp-surface: #ffffff;
  --adp-surface-muted: #f0f5f7;
  --adp-surface-hover: #e4eef1;
  --adp-border: #cad8de;
  --adp-border-strong: #154e78;
  --adp-ink: #142f46;
  --adp-ink-muted: #5a7082;
  --adp-ink-disabled: #9db0be;
  --adp-error: #b42318;

  position: relative;
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  font-variant-numeric: tabular-nums;
}

.adp-trigger-group {
  display: inline-flex;
  align-items: stretch;
  max-width: 100%;
  border: 1px solid var(--adp-border);
  border-radius: 10px;
  background: var(--adp-surface);
  overflow: hidden;
}

.adp-step-btn,
.adp-main-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--adp-ink);
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  touch-action: manipulation;
  transition: background-color 0.14s ease, border-color 0.14s ease, color 0.14s ease;
}

.adp-step-btn {
  width: 40px;
  flex: 0 0 auto;
  color: var(--adp-ink-muted);
}

.adp-step-prev {
  border-right: 1px solid #e1eaee;
  border-radius: 9px 0 0 9px;
}

.adp-step-next {
  border-left: 1px solid #e1eaee;
  border-radius: 0 9px 9px 0;
}

.adp-main-btn {
  gap: 7px;
  padding: 0 12px;
  min-width: 0;
  white-space: nowrap;
}

.adp-main-btn.adp-solo {
  border-radius: 9px;
}

.adp-step-btn:hover:not(:disabled),
.adp-main-btn:hover:not(:disabled) {
  background: var(--adp-surface-hover);
  color: var(--adp-ink);
}

.adp-step-btn:active:not(:disabled),
.adp-main-btn:active:not(:disabled) {
  border-color: var(--adp-border-strong);
}

.adp-step-btn:disabled,
.adp-main-btn:disabled {
  color: var(--adp-ink-disabled);
  cursor: not-allowed;
}

.adp-step-btn:focus-visible,
.adp-main-btn:focus-visible,
.adp-nav-btn:focus-visible,
.adp-header-pill:focus-visible,
.adp-close-btn:focus-visible,
.adp-shortcut-btn:focus-visible,
.adp-day-cell:focus-visible,
.adp-pick-cell:focus-visible,
.adp-retry-btn:focus-visible,
.adp-latest-jump-btn:focus-visible {
  outline: none;
  border-color: var(--adp-border-strong);
  box-shadow: inset 0 0 0 1px var(--adp-border-strong);
}

.adp-cal-icon {
  color: var(--adp-accent);
  font-size: 15px;
  flex-shrink: 0;
}

.adp-date-text {
  letter-spacing: -0.01em;
}

.adp-latest-pill {
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--adp-accent-soft);
  color: var(--adp-accent);
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
}

.adp-caret-icon {
  color: var(--adp-ink-muted);
  font-size: 13px;
  transition: transform 0.16s ease;
}

.adp-caret-icon.rotated {
  transform: rotate(180deg);
}

.adp-backdrop {
  display: none;
}

.adp-panel {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  z-index: 40;
  width: 324px;
  max-width: calc(100vw - 24px);
  padding: 14px;
  border: 1px solid var(--adp-border);
  border-radius: 14px;
  background: var(--adp-surface);
  box-shadow: 0 14px 34px rgba(20, 47, 70, 0.14);
  overscroll-behavior: contain;
}

.adp-sheet-handle {
  display: none;
}

.adp-shortcuts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.adp-shortcut-btn {
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid var(--adp-border);
  border-radius: 999px;
  background: var(--adp-surface-muted);
  color: var(--adp-ink);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  touch-action: manipulation;
}

.adp-shortcut-btn:hover:not(:disabled) {
  background: var(--adp-surface-hover);
}

.adp-shortcut-btn:active:not(:disabled) {
  border-color: var(--adp-border-strong);
}

.adp-shortcut-btn.active {
  background: var(--adp-accent-soft);
  border-color: var(--adp-accent);
  color: var(--adp-accent);
}

.adp-shortcut-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.adp-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
}

.adp-nav-btn,
.adp-close-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: var(--adp-surface-muted);
  color: var(--adp-ink);
  cursor: pointer;
  touch-action: manipulation;
}

.adp-close-btn {
  display: none;
}

.adp-nav-btn:hover:not(:disabled),
.adp-close-btn:hover {
  background: var(--adp-surface-hover);
}

.adp-nav-btn:active:not(:disabled),
.adp-close-btn:active {
  border-color: var(--adp-border-strong);
}

.adp-nav-btn:disabled {
  color: var(--adp-ink-disabled);
  cursor: not-allowed;
}

.adp-header-controls {
  flex: 1 1 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-width: 0;
}

.adp-year-static {
  display: inline-flex;
  align-items: center;
  min-height: 36px;
  padding: 0 4px;
  color: var(--adp-ink);
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
}

.adp-header-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-height: 36px;
  padding: 0 9px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: var(--adp-ink);
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
  cursor: pointer;
  touch-action: manipulation;
  transition: background-color 0.14s ease, border-color 0.14s ease, color 0.14s ease;
}

.adp-header-pill:hover {
  background: var(--adp-surface-muted);
}

.adp-header-pill:active {
  border-color: var(--adp-border-strong);
}

.adp-header-pill.is-active {
  background: var(--adp-accent-soft);
  border-color: var(--adp-accent);
  color: var(--adp-accent);
}

.adp-title-caret {
  font-size: 12px;
  color: var(--adp-ink-muted);
  transition: transform 0.16s ease, color 0.14s ease;
}

.adp-header-pill.is-active .adp-title-caret {
  color: var(--adp-accent);
}

.adp-title-caret.rotated {
  transform: rotate(180deg);
}

.adp-weekdays {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  margin-bottom: 4px;
}

.adp-weekday {
  text-align: center;
  font-size: 12px;
  font-weight: 650;
  color: var(--adp-ink-muted);
  padding: 4px 0;
}

.adp-days-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  row-gap: 4px;
}

.adp-day-cell {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 40px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: var(--adp-ink);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  touch-action: manipulation;
  transition: background-color 0.12s ease, border-color 0.12s ease;
}

.adp-day-cell.is-in-range {
  background: var(--adp-range-bg);
  border-radius: 0;
}

.adp-day-cell.is-range-start {
  border-radius: 9px 0 0 9px;
}

.adp-day-cell.is-range-end {
  border-radius: 0 9px 9px 0;
}

.adp-day-cell.is-range-start.is-range-end {
  border-radius: 9px;
}

.adp-day-cell.is-outside {
  opacity: 0.45;
}

.adp-day-cell.is-missing {
  color: var(--adp-ink-disabled);
  cursor: not-allowed;
}

.adp-day-cell.is-available:hover:not(.is-selected) {
  background: var(--adp-surface-hover);
}

.adp-day-cell.is-available:active:not(.is-selected) {
  border-color: var(--adp-border-strong);
}

.adp-day-cell.is-latest:not(.is-selected) {
  border-color: #9cbccf;
}

.adp-day-cell.is-selected {
  background: var(--adp-accent);
  border-color: var(--adp-accent);
  color: #ffffff;
  border-radius: 9px;
}

.adp-day-cell.is-selected:hover {
  background: var(--adp-accent-hover);
}

.adp-day-num {
  line-height: 1.2;
}

.adp-day-dot {
  width: 4px;
  height: 4px;
  margin-top: 2px;
  border-radius: 50%;
  background: var(--adp-accent);
}

.adp-day-cell.is-selected .adp-day-dot {
  background: #ffffff;
}

.adp-pick-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  padding: 6px 0;
}

.adp-year-grid {
  max-height: 240px;
  overflow-y: auto;
}

.adp-year-grid.is-compact-years {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.adp-pick-cell {
  min-height: 44px;
  border: 1px solid var(--adp-border);
  border-radius: 10px;
  background: var(--adp-surface);
  color: var(--adp-ink);
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  touch-action: manipulation;
}

.adp-pick-cell:hover:not(:disabled) {
  background: var(--adp-surface-hover);
}

.adp-pick-cell:active:not(:disabled) {
  border-color: var(--adp-border-strong);
}

.adp-pick-cell.current {
  background: var(--adp-accent);
  border-color: var(--adp-accent);
  color: #ffffff;
}

.adp-pick-cell.active:not(.current) {
  border-color: var(--adp-accent);
  color: var(--adp-accent);
}

.adp-pick-cell:disabled {
  background: var(--adp-surface-muted);
  border-color: transparent;
  color: var(--adp-ink-disabled);
  cursor: not-allowed;
}

.adp-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #e4edf1;
  font-size: 12px;
}

.adp-status-text {
  color: var(--adp-ink-muted);
}

.adp-status-error {
  color: var(--adp-error);
  font-weight: 600;
}

.adp-retry-btn {
  min-height: 30px;
  padding: 0 10px;
  border: 1px solid var(--adp-border);
  border-radius: 8px;
  background: var(--adp-surface-muted);
  color: var(--adp-accent);
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}

.adp-retry-btn:hover {
  background: var(--adp-surface-hover);
}

.adp-latest-jump-btn {
  min-height: 30px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: var(--adp-accent);
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
  cursor: pointer;
  touch-action: manipulation;
  transition: background-color 0.14s ease, border-color 0.14s ease;
}

.adp-latest-jump-btn:hover {
  background: var(--adp-accent-soft);
}

.adp-latest-jump-btn:active {
  border-color: var(--adp-border-strong);
}

@media (max-width: 640px) {
  .adp-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 90;
    background: rgba(15, 34, 51, 0.36);
  }

  .adp-panel {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    top: auto;
    z-index: 95;
    width: auto;
    max-width: none;
    border-left: none;
    border-right: none;
    border-bottom: none;
    border-radius: 18px 18px 0 0;
    padding: 10px 16px calc(16px + env(safe-area-inset-bottom, 0px));
    box-shadow: 0 -10px 30px rgba(15, 34, 51, 0.18);
  }

  .adp-sheet-handle {
    display: block;
    width: 38px;
    height: 4px;
    margin: 0 auto 12px;
    border-radius: 999px;
    background: #c7d6de;
  }

  .adp-close-btn {
    display: inline-flex;
    width: 44px;
    height: 44px;
  }

  .adp-nav-btn {
    width: 44px;
    height: 44px;
  }

  .adp-year-static,
  .adp-header-pill {
    min-height: 44px;
  }

  .adp-header-pill {
    padding: 0 10px;
  }

  .adp-latest-jump-btn {
    min-height: 36px;
    padding: 0 10px;
  }

  .adp-shortcut-btn {
    min-height: 38px;
    padding: 0 12px;
  }

  .adp-day-cell {
    min-height: 44px;
    font-size: 14px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .adp-step-btn,
  .adp-main-btn,
  .adp-header-pill,
  .adp-title-caret,
  .adp-caret-icon,
  .adp-day-cell,
  .adp-latest-jump-btn {
    transition: none;
  }
}
</style>

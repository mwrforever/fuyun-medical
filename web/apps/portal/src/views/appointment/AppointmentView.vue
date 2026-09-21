<script setup lang="ts">
// 免登录预约页（FU-M03-02 portal 通道前端面，设计文档 §3.3/§8.4/§5.4/§6.8）：身份确认 →
// 选择号源 → 确认出票，三步纵向流不锁死前进（未完成步 60% 透明 +「先完成上一步」可预览不可
// 操作）；显式格式校验（失焦 + 提交双触发，错误贴字段 + aria-describedby）；出票卡 fuy-ticket
// 过冲编排（emphasis）+「打印出票」色条 + 支付时限倒计时（单一 setInterval 卸载即清）。
// 无 Element Plus，控件全部原生 + tokens（§4.4 portal 基线）；失败弹错归字段级文案。
import { computed, nextTick, onBeforeUnmount, ref } from 'vue';
import { bookPortalAppointment, listPortalPools, resolveErrorCopy } from '@/api/outpatient';
import type { AppointmentVO, CredentialType, NumberPoolVO } from '@/api/outpatient';
import { PortalApiError } from '@/api/http';

/** 介质 tab 词表（与后端 portal 通道准入词表同源） */
const CREDENTIAL_TABS: ReadonlyArray<{ type: CredentialType; label: string }> = [
  { type: 'ID_CARD', label: '身份证号' },
  { type: 'VISIT_CARD', label: '就诊卡号' },
];

/** 诊区选项（P0 无科室主数据出网面，与 workstation 分诊台同源的三诊区演示口径） */
const DEPT_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'DEPT-INT', label: '内科' },
  { code: 'DEPT-SUR', label: '外科' },
  { code: 'DEPT-PED', label: '儿科' },
];

/** 号别中文词表（生成物 apptType 五值枚举的展示映射） */
const APPT_TYPE_LABELS: Record<string, string> = {
  GENERAL: '普通',
  EXPERT: '专家',
  SPECIAL_DISEASE: '专病',
  EMERGENCY: '急诊',
  REVISIT: '复诊',
};

/* ---------- 步骤状态：三步纵流按完成度推进（不锁死前进，§5.4） ---------- */
const credentialType = ref<CredentialType>('ID_CARD');
const credentialNo = ref('');
const identityError = ref('');
const pools = ref<NumberPoolVO[]>([]);
const poolsLoading = ref(false);
const poolsError = ref('');
const selectedPool = ref<NumberPoolVO | null>(null);
const deptCode = ref('DEPT-INT');
/** 就诊日（第 2 步日期横滑条默认首格=今日，yyyy-MM-dd） */
const schedDate = ref(toIsoDate(new Date()));

/** 第 1 步完成判定：介质输入存在即视为完成（格式校验在提交/失焦双触发，此处不做硬校验） */
const identityDone = computed(() => credentialNo.value.trim() !== '');
/** 第 2 步完成判定：已选中号源 */
const poolDone = computed(() => selectedPool.value !== null);
/** 当前步指示（1/2/3：供步骤指示条圆点态） */
const currentStep = computed(() => (poolDone.value ? 3 : identityDone.value ? 2 : 1));

/** RFC 文案：yyyy-MM-dd（本地时区，禁 toISOString 的 UTC 偏移坑） */
function toIsoDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** 未来 7 日日期 chip 数据源（含今日；周末角标仅展示语义） */
const dateChips = computed<ReadonlyArray<{ value: string; label: string; weekday: string }>>(() => {
  const labels = ['日', '一', '二', '三', '四', '五', '六'];
  const today = new Date();
  return Array.from({ length: 7 }, (_, offset) => {
    const day = new Date(today.getFullYear(), today.getMonth(), today.getDate() + offset);
    return {
      value: toIsoDate(day),
      label: `${day.getMonth() + 1}/${day.getDate()}`,
      weekday: offset === 0 ? '今日' : `周${labels[day.getDay()]}`,
    };
  });
});

/** prefers-reduced-motion 探测（scrollIntoView 降级与出票过渡由 CSS 兜底双保险） */
function prefersReducedMotion(): boolean {
  return (
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

/** 步骤推进滚动：完成后自动 scrollIntoView（reduced-motion 时 auto §5.4） */
async function scrollStepIntoView(id: string): Promise<void> {
  await nextTick();
  document.getElementById(id)?.scrollIntoView({
    behavior: prefersReducedMotion() ? 'auto' : 'smooth',
    block: 'start',
  });
}

/* ---------- 第 1 步：身份确认（介质二选一，tab 切换清空另一介质输入） ---------- */

/** 切介质：清空输入与错误（防串介质残留） */
function onCredentialTab(type: CredentialType): void {
  credentialType.value = type;
  credentialNo.value = '';
  identityError.value = '';
}

/**
 * 介质显式格式校验（§5.4）：证件号 18 位数字末位可 X（统一转大写）；就诊卡号 ≥8 位纯数字。
 *
 * @return 校验通过 true；失败置 identityError（贴字段文案）
 */
function validateIdentity(): boolean {
  const raw = credentialNo.value.trim();
  if (credentialType.value === 'ID_CARD') {
    if (!/^\d{17}[\dXx]$/.test(raw)) {
      identityError.value = '身份证号应为 18 位数字（末位可为 X），请核对后重新输入';
      return false;
    }
    credentialNo.value = raw.toUpperCase();
    identityError.value = '';
    return true;
  }
  if (!/^\d{8,}$/.test(raw)) {
    identityError.value = '就诊卡号应为 8 位以上数字，请查看就诊卡正面';
    return false;
  }
  identityError.value = '';
  return true;
}

/** 失焦校验（§5.4 显式校验双触发之一；空值不打扰——未填写不算错误） */
function onCredentialBlur(): void {
  if (credentialNo.value.trim() !== '') {
    validateIdentity();
  }
}

/** 完成第 1 步进入第 2 步：校验通过才滚动展开号源区并自动首查（§8.4 进入即查询） */
async function onIdentityNext(): Promise<void> {
  if (!validateIdentity()) {
    await focusField('credential-input');
    return;
  }
  await scrollStepIntoView('step-pool');
  await onQueryPools();
}

/** 焦点管理：错误时焦点移到字段（§5.4 无障碍口径） */
async function focusField(id: string): Promise<void> {
  await nextTick();
  document.getElementById(id)?.focus();
}

/* ---------- 第 2 步：选择号源（日期横滑条 + 号源卡列表，加载骨架） ---------- */

/** 查询可约号源：诊区/日期变化即查（身份未完成不可达本区——卡片置灰由模板承载） */
async function onQueryPools(): Promise<void> {
  if (!identityDone.value || poolsLoading.value) {
    return;
  }
  poolsLoading.value = true;
  poolsError.value = '';
  try {
    pools.value = await listPortalPools({ deptCode: deptCode.value, date: schedDate.value });
    // 号源刷新后清空已选，防携带失效选择提交
    selectedPool.value = null;
  } catch (error: unknown) {
    poolsError.value =
      error instanceof PortalApiError ? resolveErrorCopy(error) : '网络异常，请稍后重试';
  } finally {
    poolsLoading.value = false;
  }
}

/** 切诊区/日期 chip：重查号源并滚动到本步 */
function onDeptChip(code: string): void {
  deptCode.value = code;
  void onQueryPools();
}

function onDateChip(value: string): void {
  schedDate.value = value;
  void onQueryPools();
}

/** 选号源卡（余 0 禁点）：完成后自动展开第 3 步 */
async function onSelectPool(pool: NumberPoolVO): Promise<void> {
  if ((pool.remaining ?? 0) <= 0) {
    return;
  }
  selectedPool.value = pool;
  await scrollStepIntoView('step-confirm');
}

/* ---------- 第 3 步：确认出票（提交 → fuy-ticket 出票卡 + 倒计时） ---------- */
const submitting = ref(false);
const ticket = ref<AppointmentVO | null>(null);
const ticketError = ref('');
/** 出票卡 DOM 锚（出票后焦点移入 §5.4） */
const ticketCard = ref<HTMLElement | null>(null);

/** 确认预约可提交：身份 + 号源双齐备 */
const canSubmit = computed(() => identityDone.value && poolDone.value);

/**
 * 提交预约：显式校验兜底（提交前触发）→ 出网 → 出票卡替换表单区；失败驻留所选号源卡并按
 * 错误码映射文案（OP-1003/1006/1007 → §5.4 定稿文案）。提交中全表单禁用防重复出号。
 */
async function onSubmit(): Promise<void> {
  if (submitting.value) {
    return;
  }
  if (!validateIdentity()) {
    await focusField('credential-input');
    return;
  }
  if (selectedPool.value === null) {
    ticketError.value = '请先选择号源';
    return;
  }
  ticketError.value = '';
  submitting.value = true;
  try {
    ticket.value = await bookPortalAppointment({
      credentialType: credentialType.value,
      credentialNo: credentialNo.value.trim(),
      poolId: selectedPool.value.id ?? '',
    });
    startCountdown();
    await nextTick();
    ticketCard.value?.focus();
  } catch (error: unknown) {
    ticketError.value =
      error instanceof PortalApiError ? resolveErrorCopy(error) : '网络异常，请稍后重试';
  } finally {
    submitting.value = false;
  }
}

/** 支付时限倒计时（mm:ss，.fuy-num 防抖；剩 5 分钟内转预警色 §5.4） */
const countdownText = ref('--:--');
const countdownUrgent = ref(false);
let countdownTimer: ReturnType<typeof setInterval> | null = null;

function startCountdown(): void {
  stopCountdown();
  const deadline = ticket.value?.payDeadline;
  if (!deadline) {
    countdownText.value = '--:--';
    return;
  }
  const tick = (): void => {
    const remainMs = new Date(deadline).getTime() - Date.now();
    if (remainMs <= 0) {
      countdownText.value = '00:00';
      countdownUrgent.value = true;
      stopCountdown();
      return;
    }
    const totalSeconds = Math.floor(remainMs / 1000);
    const mm = String(Math.floor(totalSeconds / 60)).padStart(2, '0');
    const ss = String(totalSeconds % 60).padStart(2, '0');
    countdownText.value = `${mm}:${ss}`;
    countdownUrgent.value = remainMs <= 5 * 60 * 1000;
  };
  tick();
  countdownTimer = setInterval(tick, 1000);
}

function stopCountdown(): void {
  if (countdownTimer !== null) {
    clearInterval(countdownTimer);
    countdownTimer = null;
  }
}

/** 「再约一个」复位到第 1 步（保留介质输入），清出票与倒计时 */
function onBookAgain(): void {
  stopCountdown();
  ticket.value = null;
  selectedPool.value = null;
  pools.value = [];
  ticketError.value = '';
  void scrollStepIntoView('step-identity');
}

onBeforeUnmount(() => {
  stopCountdown();
});
</script>

<template>
  <div class="fuy-portal-page">
    <!-- 页头 64px：站点名 + 帮助电话（§3.3） -->
    <header class="appt-header">
      <span class="appt-header-title">富云患者门户</span>
      <span class="appt-header-help">帮助电话 400-000-0000</span>
    </header>

    <main class="appt-main">
      <!-- 步骤指示条：3 圆点 + 连接线，当前点品牌色（§3.3） -->
      <ol class="appt-steps" aria-label="预约步骤">
        <li
          v-for="(step, index) in ['身份确认', '选择号源', '确认出票']"
          :key="step"
          class="appt-step-dot"
          :class="{ 'is-current': currentStep === index + 1, 'is-done': currentStep > index + 1 }"
        >
          <span class="appt-dot"></span>
          <span class="appt-step-label">{{ step }}</span>
        </li>
      </ol>

      <div class="appt-cards fuy-stagger">
        <!-- 第 1 步卡：身份确认（就诊卡号/证件号二选一切换 tab） -->
        <section id="step-identity" class="appt-card" :style="{ '--fuy-stagger-index': 0 }">
          <h2 class="appt-card-title">1 身份确认</h2>
          <div class="appt-tabs" role="tablist">
            <button
              v-for="tab in CREDENTIAL_TABS"
              :key="tab.type"
              type="button"
              role="tab"
              class="appt-tab"
              :class="{ 'is-active': credentialType === tab.type }"
              :aria-selected="credentialType === tab.type"
              @click="onCredentialTab(tab.type)"
            >
              {{ tab.label }}
            </button>
          </div>
          <label class="appt-field">
            <span class="appt-label">{{
              credentialType === 'ID_CARD' ? '身份证号' : '就诊卡号'
            }}</span>
            <input
              id="credential-input"
              v-model="credentialNo"
              class="appt-input"
              :class="{ 'is-invalid': identityError !== '' }"
              type="text"
              :placeholder="credentialType === 'ID_CARD' ? '18 位身份证号' : '8 位以上数字卡号'"
              :aria-describedby="identityError !== '' ? 'credential-error' : undefined"
              :disabled="submitting"
              @blur="onCredentialBlur"
            />
          </label>
          <p
            v-if="identityError !== ''"
            id="credential-error"
            class="appt-field-error"
            role="alert"
          >
            {{ identityError }}
          </p>
          <button type="button" class="appt-button" :disabled="submitting" @click="onIdentityNext">
            下一步：选择号源
          </button>
        </section>

        <!-- 第 2 步卡：选择号源（未完成第 1 步：60% 透明 + 副文案，可预览不可操作） -->
        <section
          id="step-pool"
          class="appt-card"
          :class="{ 'is-locked': !identityDone }"
          :style="{ '--fuy-stagger-index': 1 }"
          :aria-disabled="!identityDone"
        >
          <h2 class="appt-card-title">2 选择号源</h2>
          <p v-if="!identityDone" class="appt-locked-note">先完成上一步</p>
          <template v-else>
            <div class="appt-chip-row" role="group" aria-label="选择诊区">
              <button
                v-for="dept in DEPT_OPTIONS"
                :key="dept.code"
                type="button"
                class="appt-chip"
                :class="{ 'is-active': deptCode === dept.code }"
                :disabled="submitting"
                @click="onDeptChip(dept.code)"
              >
                {{ dept.label }}
              </button>
            </div>
            <div class="appt-chip-row appt-chip-row-scroll" role="group" aria-label="选择就诊日期">
              <button
                v-for="chip in dateChips"
                :key="chip.value"
                type="button"
                class="appt-chip"
                :class="{ 'is-active': schedDate === chip.value }"
                :disabled="submitting"
                @click="onDateChip(chip.value)"
              >
                <span>{{ chip.label }}</span>
                <span class="appt-chip-sub">{{ chip.weekday }}</span>
              </button>
            </div>
            <!-- 号源卡列表：首查骨架，内容淡入（§6.7 同源语义） -->
            <div v-if="poolsLoading" class="appt-pool-skeleton" aria-hidden="true">
              <span></span><span></span><span></span>
            </div>
            <template v-else>
              <p v-if="poolsError !== ''" class="appt-field-error" role="alert">{{ poolsError }}</p>
              <div v-if="pools.length > 0" class="appt-pool-list">
                <button
                  v-for="pool in pools"
                  :key="pool.id"
                  type="button"
                  class="appt-pool"
                  :class="{
                    'is-selected': selectedPool?.id === pool.id,
                    'is-disabled': (pool.remaining ?? 0) <= 0,
                  }"
                  :disabled="submitting || (pool.remaining ?? 0) <= 0"
                  @click="onSelectPool(pool)"
                >
                  <span class="appt-pool-type">{{
                    APPT_TYPE_LABELS[pool.apptType ?? ''] ?? pool.apptType
                  }}</span>
                  <span class="appt-pool-slot">{{ pool.slotStart }}~{{ pool.slotEnd }}</span>
                  <span class="fuy-num appt-pool-remaining">余 {{ pool.remaining ?? 0 }}</span>
                </button>
              </div>
              <p v-else class="appt-empty">该诊区当日暂无可约号源，请选择其他日期</p>
            </template>
          </template>
        </section>

        <!-- 第 3 步卡：确认出票（未完成前序步锁定；出票卡替换态 §6.8） -->
        <section
          id="step-confirm"
          class="appt-card"
          :class="{ 'is-locked': !poolDone && ticket === null }"
          :style="{ '--fuy-stagger-index': 2 }"
        >
          <h2 class="appt-card-title">3 确认出票</h2>
          <p v-if="!poolDone && ticket === null" class="appt-locked-note">先完成上一步</p>

          <!-- 出票卡（v-if 替换表单区；fuy-ticket 过冲 + 打印色条 §6.8——色条动画经全局
               .fuy-ticket-bar 类承载（motion.css 唯一 keyframes 来源），页内零副本 -->
          <Transition name="fuy-ticket">
            <div v-if="ticket !== null" ref="ticketCard" class="appt-ticket" tabindex="-1">
              <span class="appt-ticket-bar fuy-ticket-bar" aria-hidden="true"></span>
              <p class="appt-ticket-heading">预约成功</p>
              <p class="fuy-num appt-ticket-no">{{ ticket.apptNo }}</p>
              <dl class="appt-ticket-meta">
                <div>
                  <dt>就诊日期</dt>
                  <dd class="fuy-num">{{ ticket.schedDate }}</dd>
                </div>
                <div>
                  <dt>时段</dt>
                  <dd class="fuy-num">{{ ticket.slotStart }}~{{ ticket.slotEnd }}</dd>
                </div>
              </dl>
              <p class="appt-ticket-countdown">
                请在
                <strong class="fuy-num" :class="{ 'is-urgent': countdownUrgent }">{{
                  countdownText
                }}</strong>
                内完成缴费，逾期号源将自动释放
              </p>
              <button type="button" class="appt-button appt-button-plain" @click="onBookAgain">
                再约一个
              </button>
            </div>

            <div v-else class="appt-confirm-body">
              <dl v-if="poolDone" class="appt-summary">
                <div>
                  <dt>就诊人</dt>
                  <dd>{{ credentialNo ? '已验证身份' : '—' }}</dd>
                </div>
                <div>
                  <dt>介质</dt>
                  <dd>{{ credentialType === 'ID_CARD' ? '身份证' : '就诊卡' }}</dd>
                </div>
                <div>
                  <dt>诊区</dt>
                  <dd>{{ DEPT_OPTIONS.find((dept) => dept.code === deptCode)?.label }}</dd>
                </div>
                <div>
                  <dt>日期</dt>
                  <dd class="fuy-num">{{ schedDate }}</dd>
                </div>
                <div>
                  <dt>号别</dt>
                  <dd>
                    {{
                      selectedPool
                        ? (APPT_TYPE_LABELS[selectedPool.apptType ?? ''] ?? selectedPool.apptType)
                        : '—'
                    }}
                  </dd>
                </div>
                <div>
                  <dt>时段</dt>
                  <dd class="fuy-num">
                    {{ selectedPool ? `${selectedPool.slotStart}~${selectedPool.slotEnd}` : '—' }}
                  </dd>
                </div>
              </dl>
              <p v-if="ticketError !== ''" class="appt-field-error" role="alert">
                {{ ticketError }}
              </p>
              <button
                type="button"
                class="appt-button"
                :class="{ 'is-locked': !canSubmit }"
                :disabled="submitting || !canSubmit"
                @click="onSubmit"
              >
                {{ submitting ? '提交中…' : '确认预约' }}
              </button>
            </div>
          </Transition>
        </section>
      </div>
    </main>

    <!-- 页脚：备案/免责 12px（§3.3） -->
    <footer class="appt-footer">本页预约不构成诊疗建议，急危重症请立即前往急诊</footer>
  </div>
</template>

<style scoped>
/* 页面骨架（§3.3）：min-height 100dvh、亮色暖中性底（palette token）、纵向流 */
.fuy-portal-page {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  background: var(--fuy-palette-gray-50);
  color: var(--fuy-color-text-emphasis);
  font-size: var(--fuy-font-size-md);
}

/* 页头 64px */
.appt-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  padding: 0 var(--fuy-space-6);
  background: #fff;
  border-bottom: var(--fuy-border-hairline);
}
.appt-header-title {
  font-size: var(--fuy-font-size-lg);
  font-weight: 600;
}
.appt-header-help {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}

/* 主区：480px 居中、≥768px 720px 双列（§3.3） */
.appt-main {
  flex: 1;
  width: 100%;
  max-width: 480px;
  margin: 0 auto;
  padding: var(--fuy-space-6);
  box-sizing: border-box;
}
@media (min-width: 768px) {
  .appt-main {
    max-width: 720px;
  }
  .appt-cards {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--fuy-space-6);
    align-items: start;
  }
  .appt-card:first-child {
    grid-column: 1 / -1;
  }
}

/* 步骤指示条：3 圆点 + 连接线（§3.3）：当前点品牌实心、已完成 success、未到中性描边；
   连接线悬于相邻圆点间隙中点（32px gap 内 16px 线段），未到段中性、已越过段随完成态转 success */
.appt-steps {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--fuy-space-8);
  margin: 0 0 var(--fuy-space-6);
  padding: 0;
  list-style: none;
}
.appt-step-dot {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}
.appt-step-dot:not(:last-child) .appt-dot::after {
  content: '';
  position: absolute;
  top: 5px; /* 锚定 12px 圆点本体：垂直中心 6px 减线高半值 */
  left: calc(100% + 12px); /* 悬于「点-点」40px 间距（8+32）的中段，16px 线段光学居中 */
  width: var(--fuy-space-4);
  height: 2px;
  border-radius: 1px;
  background: var(--fuy-palette-brand-200);
}
.appt-step-dot.is-done:not(:last-child) .appt-dot::after {
  background: var(--fuy-color-success-text);
}
.appt-dot {
  position: relative;
  width: 12px;
  height: 12px;
  border-radius: var(--fuy-radius-full);
  border: 2px solid var(--fuy-palette-brand-200);
  background: #fff;
}
.appt-step-dot.is-current .appt-dot {
  border-color: var(--fuy-color-brand);
  background: var(--fuy-color-brand);
}
.appt-step-dot.is-done .appt-dot {
  border-color: var(--fuy-color-success-text);
  background: var(--fuy-color-success-text);
}
.appt-step-label {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}
.appt-step-dot.is-current .appt-step-label {
  color: var(--fuy-color-brand);
  font-weight: 600;
}

/* 单列纵流卡间距 */
.appt-cards {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-6);
}

/* 卡片基线：radius-xl + 常驻 sm 阴影（§2.5 portal 卡片） */
.appt-card {
  position: relative;
  padding: var(--fuy-space-6);
  background: #fff;
  border-radius: var(--fuy-radius-xl);
  box-shadow: var(--fuy-shadow-sm);
}
.appt-card.is-locked {
  opacity: 0.6;
}
.appt-card-title {
  margin: 0 0 var(--fuy-space-4);
  font-size: var(--fuy-font-size-lg);
  font-weight: 600;
}
.appt-locked-note {
  margin: 0;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}

/* 介质 tab 二选一 */
.appt-tabs {
  display: flex;
  gap: var(--fuy-space-2);
  margin-bottom: var(--fuy-space-4);
}
.appt-tab {
  height: 40px;
  padding: 0 var(--fuy-space-5);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-xl);
  background: #fff;
  font-size: var(--fuy-font-size-md);
  color: var(--fuy-color-text-secondary);
  cursor: pointer;
  transition:
    border-color var(--fuy-motion-fast) linear,
    color var(--fuy-motion-fast) linear;
}
.appt-tab.is-active {
  border-color: var(--fuy-color-brand);
  color: var(--fuy-color-brand);
  font-weight: 600;
}

/* 原生控件基线（§4.4 portal 段）：48px 高/12px 圆角/16px 字号/品牌描边+焦点环 */
.appt-field {
  display: block;
  margin-bottom: var(--fuy-space-2);
}
.appt-label {
  display: block;
  margin-bottom: var(--fuy-space-2);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}
.appt-input {
  box-sizing: border-box;
  width: 100%;
  height: 48px;
  padding: 0 var(--fuy-space-4);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-xl);
  font-size: var(--fuy-font-size-md);
  color: var(--fuy-color-text-emphasis);
}
.appt-input:focus {
  outline: none;
  border-color: var(--fuy-color-brand);
  box-shadow: 0 0 0 3px var(--fuy-color-focus-ring);
}
.appt-input.is-invalid {
  border-color: var(--fuy-color-danger-text);
}

/* 错误文案贴字段 + 14px danger（§4.4） */
.appt-field-error {
  margin: var(--fuy-space-2) 0;
  font-size: 14px;
  color: var(--fuy-color-danger-text);
}

/* 主按钮：48px 品牌底白字；plain = 白底品牌描边（再约一个） */
.appt-button {
  width: 100%;
  height: 48px;
  margin-top: var(--fuy-space-4);
  border: none;
  border-radius: var(--fuy-radius-xl);
  background: var(--fuy-color-brand);
  color: #fff;
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
  cursor: pointer;
}
.appt-button:disabled,
.appt-button.is-locked {
  opacity: 0.5;
  cursor: not-allowed;
}
.appt-button-plain {
  border: 1px solid var(--fuy-color-brand);
  background: #fff;
  color: var(--fuy-color-brand);
}

/* 日期/诊区 chip（§3.3 横滑条） */
.appt-chip-row {
  display: flex;
  gap: var(--fuy-space-2);
  margin-bottom: var(--fuy-space-3);
}
.appt-chip-row-scroll {
  overflow-x: auto;
}
.appt-chip {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-width: 56px;
  height: 48px;
  padding: 0 var(--fuy-space-3);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-xl);
  background: #fff;
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
  cursor: pointer;
  transition:
    border-color var(--fuy-motion-fast) linear,
    background-color var(--fuy-motion-fast) linear;
}
.appt-chip.is-active {
  border-color: var(--fuy-color-brand);
  background: var(--fuy-palette-brand-50);
  color: var(--fuy-color-brand);
  font-weight: 600;
}
.appt-chip-sub {
  font-size: 12px;
}

/* 号源卡列表 */
.appt-pool-skeleton {
  display: flex;
  gap: var(--fuy-space-3);
}
/* 静态骨架块（零新 keyframes 红线：motion.css 为全站唯一 keyframes 来源，脉冲不落页面） */
.appt-pool-skeleton span {
  flex: 1;
  height: 88px;
  border-radius: var(--fuy-radius-xl);
  background: var(--fuy-palette-gray-50);
  opacity: 0.7;
}
.appt-pool-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--fuy-space-3);
}
.appt-pool {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--fuy-space-1);
  min-height: 88px;
  padding: var(--fuy-space-4);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-xl);
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--fuy-motion-fast) linear,
    background-color var(--fuy-motion-fast) linear;
}
.appt-pool.is-selected {
  border: 2px solid var(--fuy-color-brand);
  background: var(--fuy-palette-brand-50);
}
.appt-pool.is-disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.appt-pool-type {
  font-weight: 600;
}
.appt-pool-slot {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}
.appt-pool-remaining {
  font-size: var(--fuy-font-size-lg);
  font-weight: 700;
  color: var(--fuy-color-text-emphasis);
}
.appt-empty {
  margin: var(--fuy-space-4) 0 0;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}

/* 摘要 descriptions */
.appt-summary {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--fuy-space-2) var(--fuy-space-4);
  margin: 0 0 var(--fuy-space-2);
}
.appt-summary dt {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}
.appt-summary dd {
  margin: 0;
  font-weight: 500;
}

/* 出票卡（§6.8）：fuy-ticket 过冲在 motion.css；顶部 3px 品牌色条 scaleX 打印隐喻——
   动画经全局 .fuy-ticket-bar 类承载（motion.css），本块只留色条定位形态 */
.appt-ticket {
  position: relative;
  overflow: hidden;
  padding: var(--fuy-space-6);
  border-radius: var(--fuy-radius-xl);
  background: #fff;
  box-shadow: var(--fuy-shadow-md);
}
.appt-ticket-bar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: var(--fuy-color-brand);
}
.appt-ticket-heading {
  margin: 0;
  font-size: var(--fuy-font-size-lg);
  font-weight: 600;
  color: var(--fuy-color-success-text);
}
.appt-ticket-no {
  margin: var(--fuy-space-2) 0;
  font-size: var(--fuy-font-size-ticket);
  font-weight: 700;
}
.appt-ticket-meta {
  display: flex;
  gap: var(--fuy-space-6);
  margin: 0 0 var(--fuy-space-3);
}
.appt-ticket-meta dt {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}
.appt-ticket-meta dd {
  margin: 0;
  font-weight: 600;
}
.appt-ticket-countdown {
  margin: 0;
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}
.appt-ticket-countdown .is-urgent {
  color: var(--fuy-color-warning-text);
}

/* 页脚 12px 免责（§3.3） */
.appt-footer {
  padding: var(--fuy-space-4) var(--fuy-space-6);
  text-align: center;
  font-size: 12px;
  color: var(--fuy-color-info-text);
}
</style>

<script setup lang="ts">
// PDA 移动护理页（顶层路由 /pda，设计文档 §4）：床旁单手操作面——扫腕带/卡号 → 核对
// 脱敏患者卡 → 录体征 → 巡视打卡四段流。全部原生控件（EP 默认控件高不满足 48px 触控基线，
// 覆盖面大得不偿失），触控目标 ≥48px、字号 16px 基线、Enter 即提交（扫码枪回车形态）。
// 超敏字段零渲染：数据源为脱敏摘要（无证件/手机号字段，spec 冻结断言）；三个动作各自独立
// 在途守卫 + 按钮 disabled，双击零出网。失败弹错：AxiosError 归拦截器，其余形态兜底展示。
import { computed, nextTick, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（portal 原生基线同款口径）
import 'element-plus/es/components/message/style/css';
import axios from 'axios';
import { pda, vitalSigns } from '@/api/nursing';
import type { NursingTaskVO, PdaPatientSummaryVO } from '@/api/nursing';
import { TEMP_SITE_OPTIONS } from '@/api/nursing';

/** 腕带/卡号格式：I 型 14 位住院号 或 8 位以上数字患者卡号（二选一，§4.2 冻结） */
const WRISTBAND_PATTERN = /^I\d{13}$/;
const CARD_NO_PATTERN = /^\d{8,}$/;

/** 护理级别 → 徽标类（卡墙同款 token 消费） */
const NURSING_LEVEL_BADGE: Record<string, string> = {
  SPECIAL: 'fuy-nursing-level-badge--special',
  CRITICAL: 'fuy-nursing-level-badge--l1',
  NORMAL: 'fuy-nursing-level-badge--l3',
};

/** 护理级别中文词表 */
const NURSING_LEVEL_LABELS: Record<string, string> = {
  SPECIAL: '特级护理',
  CRITICAL: '病重护理',
  NORMAL: '普通护理',
};

/* ==================== 第 1 段：患者识别 ==================== */
const identifierInput = ref('');
/** 最近一次识别成功的标识（打卡入参回溯——识别后输入框已清空 §4.4） */
const lastIdentifier = ref('');
const identifierError = ref('');
const identifying = ref(false);
/** 输入框元素引用（识别成功后清空并保持聚焦——连续扫下一个患者的床旁节奏 §4.4） */
const identifierField = ref<HTMLInputElement | null>(null);

/** 患者识别（Enter/按钮双入口；显式格式校验非法零出网，spec 冻结）。 */
async function onIdentify(): Promise<void> {
  if (identifying.value) {
    return;
  }
  const raw = identifierInput.value.trim();
  if (!WRISTBAND_PATTERN.test(raw) && !CARD_NO_PATTERN.test(raw)) {
    identifierError.value = '腕带号应为 I 开头 14 位，或 8 位以上数字卡号，请重新扫描';
    return;
  }
  identifierError.value = '';
  identifying.value = true;
  try {
    summary.value = await pda.patientSummary(raw);
    // 识别成功：留存标识（打卡入参回溯）、复位体征与打卡态、清空输入并保持聚焦
    lastIdentifier.value = raw;
    vitalForm.value = {
      temperature: '',
      tempSite: 'AXILLARY',
      pulse: '',
      respiration: '',
      systolicBp: '',
      diastolicBp: '',
      spo2: '',
    };
    patrolTask.value = null;
    void ElMessage.success(
      `已识别：${summary.value.patientName ?? ''}（${summary.value.bedNo ?? ''}）`,
    );
    identifierInput.value = '';
    await nextTick();
    identifierField.value?.focus();
  } catch (error) {
    // AxiosError 归拦截器弹错；其余形态（api 层直抛对象）按 404 口径兜底
    if (!axios.isAxiosError(error)) {
      const detail = (error as { detail?: unknown } | null | undefined)?.detail;
      void ElMessage.error(
        typeof detail === 'string' && detail.length > 0
          ? detail
          : '未识别到该患者，请核对腕带或改用患者卡号',
      );
    }
  } finally {
    identifying.value = false;
  }
}

/* ==================== 第 2 段：患者卡（脱敏） ==================== */
const summary = ref<PdaPatientSummaryVO | null>(null);
/** 段解锁态（未识别时 2/3/4 段 60% 透明度 + 引导文案） */
const identified = computed(() => summary.value !== null);

/** 过敏源清单（脱敏摘要承载；空列表显示无已知过敏） */
const allergyText = computed(() => {
  const items = (summary.value?.allergies ?? [])
    .map((item) => item.itemName ?? item.itemCode ?? '')
    .filter((name) => name !== '');
  return items.length > 0 ? items.join('、') : '无已知过敏';
});

/* ==================== 第 3 段：体征录入（五字段子集） ==================== */
const vitalForm = ref({
  temperature: '',
  tempSite: 'AXILLARY',
  pulse: '',
  respiration: '',
  systolicBp: '',
  diastolicBp: '',
  spo2: '',
});
const recording = ref(false);

/** 整数字段显式校验（纯数字正则 + 范围判定，禁裸 parse） */
function isValidInt(raw: string, min: number, max: number): boolean {
  if (!/^\d+$/.test(raw)) {
    return false;
  }
  const value = Number(raw);
  return Number.isInteger(value) && value >= min && value <= max;
}

/** 一位小数字段显式校验 */
function isValidDecimal(raw: string, min: number, max: number): boolean {
  if (!/^\d{1,3}(\.\d)?$/.test(raw)) {
    return false;
  }
  const value = Number(raw);
  return value >= min && value <= max;
}

/** 体征提交（校验口径与护士站 §3.7 同族文案；在途守卫双击零出网）。 */
async function onRecordVitals(): Promise<void> {
  if (recording.value || summary.value === null) {
    return;
  }
  // 脱敏摘要面无 visitId：I 型腕带就诊码即 visitId；卡号路径 visitId 为 required 必填、
  // 空串出网必被后端 4xx 拒——前端判空拦截零出网并明确提示改用腕带（R1 finding ③）
  const visitId = WRISTBAND_PATTERN.test(lastIdentifier.value) ? lastIdentifier.value : '';
  if (visitId === '') {
    void ElMessage.warning('卡号识别无法录入体征，请改用腕带扫描（I 开头 14 位）后重试');
    return;
  }
  const form = vitalForm.value;
  if (form.temperature !== '' && !isValidDecimal(form.temperature, 35, 42)) {
    void ElMessage.warning('体温应为 35.0–42.0 的数值（如 36.5），请重新测量输入');
    return;
  }
  if (form.pulse !== '' && !isValidInt(form.pulse, 20, 250)) {
    void ElMessage.warning('脉搏应为 20–250 的整数');
    return;
  }
  if (form.respiration !== '' && !isValidInt(form.respiration, 5, 60)) {
    void ElMessage.warning('呼吸应为 5–60 的整数');
    return;
  }
  if (form.systolicBp !== '' && !isValidInt(form.systolicBp, 60, 250)) {
    void ElMessage.warning('收缩压应为 60–250 的整数');
    return;
  }
  if (form.diastolicBp !== '' && !isValidInt(form.diastolicBp, 30, 180)) {
    void ElMessage.warning('舒张压应为 30–180 的整数');
    return;
  }
  if (form.spo2 !== '' && !isValidInt(form.spo2, 50, 100)) {
    void ElMessage.warning('血氧应为 50–100 的整数');
    return;
  }
  if (
    form.temperature === '' &&
    form.pulse === '' &&
    form.respiration === '' &&
    form.systolicBp === '' &&
    form.diastolicBp === '' &&
    form.spo2 === ''
  ) {
    void ElMessage.warning('请至少录入一项体征数据');
    return;
  }
  recording.value = true;
  try {
    await vitalSigns.record({
      visitId,
      source: 'PDA',
      temperature: form.temperature === '' ? undefined : Number(form.temperature),
      tempSite: form.temperature === '' ? undefined : form.tempSite,
      pulse: form.pulse === '' ? undefined : Number(form.pulse),
      respiration: form.respiration === '' ? undefined : Number(form.respiration),
      systolicBp: form.systolicBp === '' ? undefined : Number(form.systolicBp),
      diastolicBp: form.diastolicBp === '' ? undefined : Number(form.diastolicBp),
      spo2: form.spo2 === '' ? undefined : Number(form.spo2),
    });
    void ElMessage.success('体征已录入');
    vitalForm.value = {
      temperature: '',
      tempSite: 'AXILLARY',
      pulse: '',
      respiration: '',
      systolicBp: '',
      diastolicBp: '',
      spo2: '',
    };
  } catch (error) {
    if (!axios.isAxiosError(error)) {
      const detail = (error as { detail?: unknown } | null | undefined)?.detail;
      if (typeof detail === 'string' && detail.length > 0) {
        void ElMessage.error(detail);
      }
    }
  } finally {
    recording.value = false;
  }
}

/* ==================== 第 4 段：巡视打卡 ==================== */
const patrolling = ref(false);
/** 打卡回执（成功后按钮转已完成态 + taskNo 回显，spec 冻结语义） */
const patrolTask = ref<NursingTaskVO | null>(null);

/** 巡视打卡（identifier 透传；visitId 取 I 型腕带就诊码——脱敏摘要面无 visitId 字段，
 * 卡号路径空串必被后端 4xx 拒，前端判空拦截零出网并提示改用腕带，P2 摘要补 visitId 后切换）。 */
async function onPatrol(): Promise<void> {
  if (patrolling.value || summary.value === null || patrolTask.value !== null) {
    return;
  }
  const identifier = lastIdentifier.value;
  // 卡号路径 visitId 为空串：required 必填出网必 4xx——判空拦截零出网（R1 finding ③）
  const visitId = WRISTBAND_PATTERN.test(identifier) ? identifier : '';
  if (visitId === '') {
    void ElMessage.warning('卡号识别无法巡视打卡，请改用腕带扫描（I 开头 14 位）后重试');
    return;
  }
  patrolling.value = true;
  try {
    patrolTask.value = await pda.patrol({ identifier, visitId });
    void ElMessage.success('巡视打卡完成');
  } catch (error) {
    if (!axios.isAxiosError(error)) {
      const detail = (error as { detail?: unknown } | null | undefined)?.detail;
      if (typeof detail === 'string' && detail.length > 0) {
        void ElMessage.error(detail);
      }
    }
  } finally {
    patrolling.value = false;
  }
}
</script>

<template>
  <div class="pda-page">
    <!-- 页头：品牌 + 当前护士 -->
    <header class="pda-header">
      <span class="pda-header-title">富云移动护理</span>
      <span class="pda-header-nurse">PDA 床旁操作面</span>
    </header>

    <!-- 第 1 段：患者识别（扫码枪即键盘：autofocus + Enter 直接触发） -->
    <section class="pda-card" aria-label="患者识别">
      <h2 class="pda-card-title">患者识别</h2>
      <input
        ref="identifierField"
        v-model="identifierInput"
        class="pda-input pda-identify-input"
        type="text"
        autocomplete="off"
        autofocus
        placeholder="扫描腕带或输入患者卡号"
        aria-describedby="pda-identify-error"
        @keyup.enter="onIdentify"
      />
      <p v-if="identifierError !== ''" id="pda-identify-error" class="pda-field-error" role="alert">
        {{ identifierError }}
      </p>
      <button
        type="button"
        class="pda-button pda-button-primary"
        :disabled="identifying"
        @click="onIdentify"
      >
        {{ identifying ? '查询中…' : '查询' }}
      </button>
    </section>

    <!-- 第 2 段：患者卡（脱敏；超敏字段零渲染——无证件/手机号字段） -->
    <section class="pda-card" :class="{ 'pda-card-locked': !identified }" aria-label="患者卡">
      <h2 class="pda-card-title">患者卡</h2>
      <Transition name="fuy-content-fade">
        <div v-if="identified" class="pda-patient">
          <div class="pda-patient-head">
            <span class="pda-patient-name">{{ summary?.patientName ?? '—' }}</span>
            <span
              v-if="NURSING_LEVEL_BADGE[summary?.nursingLevel ?? ''] !== undefined"
              class="fuy-nursing-level-badge"
              :class="NURSING_LEVEL_BADGE[summary?.nursingLevel ?? '']"
              >{{ NURSING_LEVEL_LABELS[summary?.nursingLevel ?? ''] ?? '' }}</span
            >
          </div>
          <div class="pda-patient-row">
            <span class="pda-patient-label">在区床位</span>
            <span class="fuy-num pda-patient-bed">{{ summary?.bedNo ?? '—' }}</span>
          </div>
          <div class="pda-patient-row">
            <span class="pda-patient-label">过敏</span>
            <span class="fuy-nursing-flag fuy-nursing-flag--danger">敏</span>
            <span class="pda-patient-allergy">{{ allergyText }}</span>
          </div>
          <div class="pda-patient-row">
            <span class="pda-patient-label">在途任务</span>
            <span class="fuy-num">{{ summary?.inFlightTaskCount ?? 0 }} 条</span>
          </div>
        </div>
      </Transition>
      <p v-if="!identified" class="pda-card-hint">先完成患者识别</p>
    </section>

    <!-- 第 3 段：体征录入（五字段巡床高频子集） -->
    <section class="pda-card" :class="{ 'pda-card-locked': !identified }" aria-label="体征录入">
      <h2 class="pda-card-title">体征录入</h2>
      <div class="pda-vital-grid">
        <label class="pda-field">
          <span class="pda-field-label">体温（℃）</span>
          <input
            v-model="vitalForm.temperature"
            class="pda-input"
            type="text"
            inputmode="decimal"
            placeholder="36.5"
          />
        </label>
        <label class="pda-field">
          <span class="pda-field-label">部位</span>
          <select v-model="vitalForm.tempSite" class="pda-input">
            <option v-for="site in TEMP_SITE_OPTIONS" :key="site.code" :value="site.code">
              {{ site.label }}
            </option>
          </select>
        </label>
        <label class="pda-field">
          <span class="pda-field-label">脉搏（次/分）</span>
          <input
            v-model="vitalForm.pulse"
            class="pda-input"
            type="text"
            inputmode="numeric"
            placeholder="80"
          />
        </label>
        <label class="pda-field">
          <span class="pda-field-label">呼吸（次/分）</span>
          <input
            v-model="vitalForm.respiration"
            class="pda-input"
            type="text"
            inputmode="numeric"
            placeholder="18"
          />
        </label>
        <label class="pda-field">
          <span class="pda-field-label">收缩压（mmHg）</span>
          <input
            v-model="vitalForm.systolicBp"
            class="pda-input"
            type="text"
            inputmode="numeric"
            placeholder="120"
          />
        </label>
        <label class="pda-field">
          <span class="pda-field-label">舒张压（mmHg）</span>
          <input
            v-model="vitalForm.diastolicBp"
            class="pda-input"
            type="text"
            inputmode="numeric"
            placeholder="80"
          />
        </label>
        <label class="pda-field">
          <span class="pda-field-label">血氧（%）</span>
          <input
            v-model="vitalForm.spo2"
            class="pda-input"
            type="text"
            inputmode="numeric"
            placeholder="98"
          />
        </label>
      </div>
      <button
        type="button"
        class="pda-button pda-button-primary"
        :disabled="recording || !identified"
        @click="onRecordVitals"
      >
        {{ recording ? '提交中…' : '提交体征' }}
      </button>
    </section>

    <!-- 第 4 段：巡视打卡（成功后转已完成态 + taskNo 回显） -->
    <section class="pda-card" :class="{ 'pda-card-locked': !identified }" aria-label="巡视打卡">
      <h2 class="pda-card-title">巡视打卡</h2>
      <button
        type="button"
        class="pda-button"
        :class="patrolTask !== null ? 'pda-button-done' : 'pda-button-primary'"
        :disabled="patrolling || !identified || patrolTask !== null"
        @click="onPatrol"
      >
        {{ patrolTask !== null ? '已巡视 ✓' : patrolling ? '打卡中…' : '巡视打卡' }}
      </button>
      <p v-if="patrolTask !== null" class="pda-patrol-task fuy-num">
        巡视任务 {{ patrolTask.taskNo ?? '' }} 已完成
      </p>
    </section>
  </div>
</template>

<style scoped>
/* 自持移动布局（§4.1 冻结：480px 居中 / 16px 基线 / overscroll 防误触下拉刷新） */
.pda-page {
  max-width: var(--fuy-pda-page-width);
  margin: 0 auto;
  min-height: 100dvh;
  padding: var(--fuy-space-4);
  background: var(--fuy-palette-gray-50);
  font-size: var(--fuy-pda-font-base);
  overscroll-behavior: contain;
}

.pda-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  min-height: 48px;
}
.pda-header-title {
  font-size: var(--fuy-font-size-lg);
  font-weight: 600;
}
.pda-header-nurse {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}

/* 段卡（1px 描边无阴影，PR-5 卡片口径；段间 8px 触控间距基线） */
.pda-card {
  margin-top: var(--fuy-space-2);
  padding: var(--fuy-space-4);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-xl);
  background: #fff;
}
/* 未解锁段：60% 透明度 + 引导（§4.2） */
.pda-card-locked {
  opacity: 0.6;
}
.pda-card-title {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
}
.pda-card-hint {
  margin: var(--fuy-space-2) 0 0;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}

/* 输入框（§4.3 冻结：高 48px / 圆角 12px / 聚焦品牌描边 + 3px 焦点环） */
.pda-input {
  width: 100%;
  height: var(--fuy-pda-touch);
  padding: 0 var(--fuy-space-3);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-xl);
  background: #fff;
  font-size: var(--fuy-pda-font-base);
  color: var(--fuy-color-text-emphasis);
}
.pda-input:focus-visible {
  outline: none;
  border-color: var(--fuy-color-brand);
  box-shadow: 0 0 0 3px var(--fuy-color-focus-ring);
}
.pda-identify-input {
  margin-bottom: var(--fuy-space-2);
}

/* 主按钮（§4.3：48px 高全宽品牌底白字，禁用 60% 透明度；按压微缩触觉反馈） */
.pda-button {
  width: 100%;
  min-height: var(--fuy-pda-touch);
  margin-top: var(--fuy-space-2);
  border: none;
  border-radius: var(--fuy-radius-xl);
  font-size: var(--fuy-pda-font-base);
  font-weight: 600;
  cursor: pointer;
}
.pda-button:active {
  transform: scale(0.98);
}
.pda-button-primary {
  background: var(--fuy-color-brand);
  color: #fff;
}
.pda-button-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
/* 已完成态（绿底白字，巡视打卡成功后 §4.2） */
.pda-button-done {
  background: var(--fuy-color-success-text);
  color: #fff;
  cursor: default;
}

/* 字段级错误文案（14px 危险色贴字段 + aria 关联 §4.3） */
.pda-field-error {
  margin: var(--fuy-space-2) 0;
  color: var(--fuy-color-danger-text);
  font-size: var(--fuy-font-size-md);
}

/* 患者卡（脱敏展示；姓名 18px/600） */
.pda-patient-head {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}
.pda-patient-name {
  font-size: 18px;
  font-weight: 600;
}
.pda-patient-row {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  margin-top: var(--fuy-space-2);
}
.pda-patient-label {
  min-width: 64px;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.pda-patient-bed {
  font-size: var(--fuy-font-size-lg);
  font-weight: 700;
}
.pda-patient-allergy {
  color: var(--fuy-color-danger-text);
  font-size: var(--fuy-font-size-sm);
}

/* 体征五字段（两列 grid，行距 8px 触控间距基线） */
.pda-vital-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--fuy-space-2);
}
.pda-field {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
}
.pda-field-label {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}

/* 巡视回执（taskNo 回显 13px .fuy-num） */
.pda-patrol-task {
  margin: var(--fuy-space-2) 0 0;
  color: var(--fuy-color-success-text);
  font-size: var(--fuy-font-size-sm);
}

/* 段解锁 fade（PR-5 §6.7 既有类；leave 段页内补齐同 token） */
.fuy-content-fade-leave-active {
  transition: opacity var(--fuy-motion-fast) var(--fuy-ease-exit);
}
.fuy-content-fade-leave-to {
  opacity: 0;
}
</style>

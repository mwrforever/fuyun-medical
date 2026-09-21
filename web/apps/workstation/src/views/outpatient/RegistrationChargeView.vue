<script setup lang="ts">
// 挂号收费联动页（FU-M03-07 前端面，设计文档 §3.2/§8.1）：选患者 → 选排班/号别 → 挂号
// （WINDOW 渠道，当日号 TAKEN 直出 visitId）→ 右列挂号费收费联动（资金面全走既有 billing
// api，前端零金额运算——A.3-6）；动作按钮 loading + 在途守卫双保险（W-22⑥ 合规形态自带），
// 失败弹错归响应拦截器。动效编排照设计文档 §8.1 表：进场 stagger / 号源骨架→内容 /
// 号源卡选中色值过渡（paint 级单元素反馈）/ 挂号成功待缴行入场 / 缴费完成对勾（emphasis）。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（billing 三页同款口径）
import 'element-plus/es/components/message/style/css';
import { listFees, previewSettlement, settle } from '@/api/billing';
import type { FeeRecordVO, SettlementPreviewVO, SettlementVO } from '@/api/billing';
import { createAppointment, listAvailablePools } from '@/api/outpatient';
import type { AppointmentVO, NumberPoolVO } from '@/api/outpatient';
import { searchPatients } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import { fenToYuanDisplay } from '@/utils/money';

/** 号别中文词表（生成物 apptType 五值枚举的展示映射；V705 appt-type 字典同源） */
const APPT_TYPE_LABELS: Record<string, string> = {
  GENERAL: '普通',
  EXPERT: '专家',
  SPECIAL_DISEASE: '专病',
  EMERGENCY: '急诊',
  REVISIT: '复诊',
};

/** 页头当日挂号计数（会话内挂号成功数；非持久化统计，禁伪数据） */
const todayCount = ref(0);

/* ---------- 第 1 步：选择患者（行高 40px 紧凑检索，行点击回填） ---------- */
const keyword = ref('');
const patientLoading = ref(false);
const patients = ref<PatientVO[]>([]);
/** 已选患者（null=未选；挂号与收费的 patientId 来源） */
const selectedPatient = ref<PatientVO | null>(null);

/** 检索患者：空词前置拦截不出网（patient 检索页同款口径），结果驻留供行点击回填 */
async function onSearchPatients(): Promise<void> {
  if (patientLoading.value) {
    return;
  }
  if (keyword.value.trim() === '') {
    void ElMessage.warning('请输入检索词（姓名/证件号/手机号）');
    return;
  }
  patientLoading.value = true;
  try {
    const page = await searchPatients({ keyword: keyword.value.trim(), page: 0, size: 10 });
    patients.value = page.content;
  } catch {
    // 失败弹错归响应拦截器；驻留旧结果
  } finally {
    patientLoading.value = false;
  }
}

/** 行点击回填选中患者（步骤 1 完成态） */
function onSelectPatient(row: PatientVO): void {
  selectedPatient.value = row;
}

/* ---------- 第 2 步：选择排班/号别（日期 + 诊区 → 号源卡阵列 3 列 grid） ---------- */
const deptCode = ref('');
const poolDate = ref('');
const poolsLoading = ref(false);
const pools = ref<NumberPoolVO[]>([]);
/** 已选号源池（null=未选；挂号入参 poolId 来源） */
const selectedPool = ref<NumberPoolVO | null>(null);

/** 余号状态三态：0= danger 条 + 卡体弱化禁点；≤5 = warning 条 +「紧张」角标；其余品牌色条 */
function poolTone(pool: NumberPoolVO): 'danger' | 'warning' | 'brand' {
  if ((pool.remaining ?? 0) <= 0) {
    return 'danger';
  }
  return (pool.remaining ?? 0) <= 5 ? 'warning' : 'brand';
}

/** 查询可约号源：诊区/日期前置拦截不出网（availablePools 契约双必填） */
async function onQueryPools(): Promise<void> {
  if (poolsLoading.value) {
    return;
  }
  if (deptCode.value.trim() === '' || poolDate.value === '') {
    void ElMessage.warning('请先填写诊区编码与排班日期');
    return;
  }
  poolsLoading.value = true;
  try {
    pools.value = await listAvailablePools({
      deptCode: deptCode.value.trim(),
      date: poolDate.value,
    });
    // 号源阵列刷新后清空已选池，防携带失效选择提交
    selectedPool.value = null;
  } catch {
    // 失败弹错归响应拦截器；驻留旧阵列
  } finally {
    poolsLoading.value = false;
  }
}

/** 点选号源卡（余 0 禁点）：选中态描边转品牌 + 底 brand-100（120ms 色值过渡） */
function onSelectPool(pool: NumberPoolVO): void {
  if ((pool.remaining ?? 0) <= 0) {
    return;
  }
  selectedPool.value = pool;
}

/* ---------- 第 3 步：确认挂号（WINDOW 渠道） ---------- */
const registering = ref(false);
/** 最近一次挂号结果（fee 联动面板与记录表的数据锚点） */
const lastAppointment = ref<AppointmentVO | null>(null);

/** 确认挂号可提交：患者 + 号源双齐备（摘要卡按钮启停口径） */
const canRegister = computed(() => selectedPatient.value !== null && selectedPool.value !== null);

/**
 * 提交挂号：WINDOW 渠道出网；当日号 TAKEN 直出 visitId → 自动带出右列收费联动；
 * 预约号 RESERVED 提示支付时限内缴费取号。成功后记录表插入新行（计数 +1）。
 * 入口在途早退守卫：重渲染前到达的第二击直接拦截，根除重复挂号出网。
 */
async function onRegister(): Promise<void> {
  if (registering.value) {
    return;
  }
  const patient = selectedPatient.value;
  const pool = selectedPool.value;
  if (patient === null || pool === null) {
    void ElMessage.warning('请先完成患者与号源选择');
    return;
  }
  registering.value = true;
  try {
    const appt = await createAppointment({
      patientId: patient.patientId ?? '',
      poolId: pool.id ?? '',
      channel: 'WINDOW',
    });
    lastAppointment.value = appt;
    todayCount.value += 1;
    // 页头计数滚动补间（§6.4：600ms easeOutCubic，reduced-motion/隐藏页直赋终值）
    tweenTodayCount(todayCount.value);
    // 挂号成功即会话记录表追加一行（§8.1 记录表插入新行；仅成功路径追加防失败重放旧单）
    appendRecord(appt);
    if (appt.status === 'TAKEN' && appt.visitId) {
      // 当日号一步取号：右列收费面板按 visitId 拉起挂号费待缴行（§8.1 联动主链）
      // 新就诊联动前清上一诊的预结算/完成态，防串诊残留
      preview.value = null;
      settled.value = null;
      await loadFees(appt.visitId);
      void ElMessage.success(`挂号成功，就诊号 ${appt.visitId}`);
    } else {
      // 预约号：窗口不收费，提示支付时限内线上缴费后取号
      void ElMessage.success('预约成功，请在支付时限内完成缴费后取号');
    }
  } catch {
    // 失败弹错归响应拦截器；选择驻留供重试
  } finally {
    registering.value = false;
  }
}

/* ---------- 右列：挂号费收费联动（复用 billing api，资金面零前端运算） ---------- */
const fees = ref<FeeRecordVO[]>([]);
const feesLoading = ref(false);
const preview = ref<SettlementPreviewVO | null>(null);
const previewing = ref(false);
const settled = ref<SettlementVO | null>(null);
const settling = ref(false);
/** 收费联动面板挂载的 visit（null=未挂号或非当日号无联动） */
const chargeVisitId = computed(() =>
  lastAppointment.value?.status === 'TAKEN' ? (lastAppointment.value.visitId ?? '') : '',
);

/** 待缴费用行（status=UNPAID；已结算行不进收费面板） */
const unpaidFees = computed(() => fees.value.filter((fee) => fee.status === 'UNPAID'));

/** 拉取就诊费用（挂号成功联动入口与结算成功重刷共用；完成态由调用方按 visit 变更自行清理） */
async function loadFees(visitId: string): Promise<void> {
  feesLoading.value = true;
  try {
    const page = await listFees({ visitId, page: 0, size: 20 });
    fees.value = page.content;
  } catch {
    // 失败弹错归响应拦截器；驻留旧费用
  } finally {
    feesLoading.value = false;
  }
}

/** 预结算（自费口径，生成可结算草稿单） */
async function onPreview(): Promise<void> {
  if (previewing.value || chargeVisitId.value === '') {
    return;
  }
  previewing.value = true;
  try {
    preview.value = await previewSettlement({
      patientId: lastAppointment.value?.patientId ?? '',
      visitId: chargeVisitId.value,
      payerType: 'SELF_PAY',
    });
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    previewing.value = false;
  }
}

/**
 * 确认收费（全现金单行支付——2026-09-17 审查裁决①同款形态：amount 取预结算回传
 * totalAmount 原样字符串透传，页面零金额运算）。成功转绿色完成态。
 */
async function onSettle(): Promise<void> {
  if (settling.value || preview.value === null) {
    return;
  }
  settling.value = true;
  try {
    settled.value = await settle({
      settleNo: preview.value.settleNo ?? '',
      payments: [{ method: 'CASH', amount: preview.value.totalAmount ?? '' }],
    });
    void ElMessage.success('挂号费结算完成');
    // 会话记录表同步收费标记（按预约号锚定本次挂号行）
    const apptNo = lastAppointment.value?.apptNo ?? '';
    const record = records.value.find((item) => item.apptNo === apptNo);
    if (record) {
      record.charged = true;
    }
    await loadFees(chargeVisitId.value);
  } catch {
    // 失败弹错归响应拦截器；草稿驻留供重试（幂等由后端 settleNo 终态承载）
  } finally {
    settling.value = false;
  }
}

/** 页头刷新（§3.2 布局树「页面题 + 当日计数 + 刷新按钮」缺项补齐）：重拉当前就诊费用行；
 * 无联动就诊（未挂号或预约号）时按钮禁用（无可刷新面），点击零出网 */
async function onRefreshFees(): Promise<void> {
  if (chargeVisitId.value === '' || feesLoading.value) {
    return;
  }
  await loadFees(chargeVisitId.value);
}

/* ---------- 当日挂号记录（会话内追加，fuy-flip 入场） ---------- */ interface RegistrationRecord {
  apptNo: string;
  patientName: string;
  deptCode: string;
  schedDate: string;
  /** 已收费标记（TAKEN 且结算完成转 true） */
  charged: boolean;
  status: string;
}

const records = ref<RegistrationRecord[]>([]);

/** 挂号成功后追加会话记录行（§8.1 记录表插入新行） */
function appendRecord(appt: AppointmentVO): void {
  records.value = [
    {
      apptNo: appt.apptNo ?? '',
      patientName: selectedPatient.value?.name ?? '',
      deptCode: deptCode.value,
      schedDate: appt.schedDate ?? '',
      charged: false,
      status: appt.status ?? '',
    },
    ...records.value,
  ];
}

/* ---------- 页头计数滚动补间（§6.4：600ms easeOutCubic，仅数字变更时启动） ---------- */
let tweenHandle = 0;
/** 计数渲染值（补间中间态；reduced-motion/隐藏页直赋终值） */
const todayCountDisplay = ref(0);

function tweenTodayCount(target: number): void {
  const reduced =
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (reduced || document.hidden) {
    todayCountDisplay.value = target;
    return;
  }
  cancelAnimationFrame(tweenHandle);
  const from = todayCountDisplay.value;
  const start = performance.now();
  const step = (now: number): void => {
    const t = Math.min((now - start) / 600, 1);
    // easeOutCubic：1 - (1-t)^3（§2.6 数字补间指定缓动）
    todayCountDisplay.value = Math.round(from + (target - from) * (1 - Math.pow(1 - t, 3)));
    if (t < 1) {
      tweenHandle = requestAnimationFrame(step);
    }
  };
  tweenHandle = requestAnimationFrame(step);
}

onMounted(() => {
  todayCountDisplay.value = 0;
});

onBeforeUnmount(() => {
  cancelAnimationFrame(tweenHandle);
});
</script>

<template>
  <div class="fuy-page registration-charge">
    <!-- 页头 48px（§3.2 布局树）：页面题 + 当日挂号计数（.fuy-num 防宽度跳动）+ 刷新按钮 -->
    <header class="registration-charge-header">
      <h2 class="registration-charge-title">挂号收费</h2>
      <span class="fuy-num registration-charge-count">今日挂号 {{ todayCountDisplay }}</span>
      <el-button
        class="registration-charge-refresh"
        :loading="feesLoading"
        :disabled="chargeVisitId === ''"
        title="重新拉取当前就诊的收费联动费用行"
        @click="onRefreshFees"
        >刷新</el-button
      >
    </header>

    <el-row :gutter="16" class="registration-charge-main">
      <!-- 左：挂号流三步卡（§8.1 stagger index 0-2） -->
      <el-col :md="24" :lg="16" class="fuy-stagger">
        <el-card class="registration-charge-card" :style="{ '--fuy-stagger-index': 0 }">
          <template #header>
            <span class="fuy-step-badge" :class="{ 'is-done': selectedPatient !== null }">1</span>
            选择患者
          </template>
          <div class="registration-charge-toolbar">
            <el-input
              v-model="keyword"
              class="registration-charge-keyword"
              placeholder="姓名/证件号/手机号"
              @keyup.enter="onSearchPatients"
            />
            <el-button
              type="primary"
              :loading="patientLoading"
              :disabled="patientLoading"
              @click="onSearchPatients"
              >检索患者</el-button
            >
          </div>
          <!-- 检索结果行高 40px 紧凑列表，行点击回填（§8.1 患者检索组件口径） -->
          <TransitionGroup
            v-if="patients.length > 0"
            name="fuy-flip"
            tag="ul"
            class="fuy-patient-list"
          >
            <li
              v-for="row in patients"
              :key="row.patientId"
              class="fuy-patient-row"
              :class="{ 'is-active': selectedPatient?.patientId === row.patientId }"
              @click="onSelectPatient(row)"
            >
              <span>{{ row.name }}</span>
              <span class="fuy-patient-row-meta">{{ row.idCardNo ?? row.patientId }}</span>
            </li>
          </TransitionGroup>
          <el-empty
            v-else
            :image-size="72"
            description="输入检索词后检索患者，点击结果行完成选择"
          />
        </el-card>

        <el-card class="registration-charge-card" :style="{ '--fuy-stagger-index': 1 }">
          <template #header>
            <span class="fuy-step-badge" :class="{ 'is-done': selectedPool !== null }">2</span>
            选择排班/号别
          </template>
          <div class="registration-charge-toolbar">
            <el-input
              v-model="deptCode"
              class="registration-charge-dept"
              placeholder="诊区编码，如 DEPT-INT"
            />
            <el-date-picker
              v-model="poolDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="排班日期"
            />
            <el-button
              type="primary"
              :loading="poolsLoading"
              :disabled="poolsLoading"
              @click="onQueryPools"
              >查询号源</el-button
            >
          </div>
          <!-- 骨架 → 内容（§6.7：min-height 锁定防 CLS） -->
          <div class="registration-charge-pools">
            <el-skeleton v-if="poolsLoading" :rows="3" animated />
            <Transition v-else-if="pools.length > 0" name="fuy-content-fade" appear>
              <div class="registration-charge-pool-grid">
                <button
                  v-for="pool in pools"
                  :key="pool.id"
                  type="button"
                  class="registration-charge-pool"
                  :class="[
                    `is-${poolTone(pool)}`,
                    {
                      'is-selected': selectedPool?.id === pool.id,
                      'is-disabled': (pool.remaining ?? 0) <= 0,
                    },
                  ]"
                  :disabled="(pool.remaining ?? 0) <= 0"
                  @click="onSelectPool(pool)"
                >
                  <span v-if="poolTone(pool) === 'warning'" class="registration-charge-tight"
                    >紧张</span
                  >
                  <strong class="registration-charge-pool-type">{{
                    APPT_TYPE_LABELS[pool.apptType ?? ''] ?? pool.apptType
                  }}</strong>
                  <span class="registration-charge-pool-slot"
                    >{{ pool.slotStart ?? '' }}~{{ pool.slotEnd ?? '' }}</span
                  >
                  <span class="fuy-num registration-charge-pool-remaining"
                    >余 {{ pool.remaining ?? 0 }}</span
                  >
                </button>
              </div>
            </Transition>
            <el-empty v-else :image-size="72" description="填写诊区与日期后查询可约号源" />
          </div>
        </el-card>

        <el-card class="registration-charge-card" :style="{ '--fuy-stagger-index': 2 }">
          <template #header>
            <span class="fuy-step-badge" :class="{ 'is-done': lastAppointment !== null }">3</span>
            确认挂号
          </template>
          <el-descriptions :column="3" border>
            <el-descriptions-item label="患者">{{
              selectedPatient?.name ?? '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="诊区">{{ deptCode || '—' }}</el-descriptions-item>
            <el-descriptions-item label="日期">{{ poolDate || '—' }}</el-descriptions-item>
            <el-descriptions-item label="号别">{{
              selectedPool
                ? (APPT_TYPE_LABELS[selectedPool.apptType ?? ''] ?? selectedPool.apptType)
                : '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="时段">{{
              selectedPool ? `${selectedPool.slotStart ?? ''}~${selectedPool.slotEnd ?? ''}` : '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="渠道">窗口（WINDOW）</el-descriptions-item>
          </el-descriptions>
          <div class="registration-charge-submit">
            <el-button
              type="primary"
              class="registration-charge-submit-btn"
              :disabled="!canRegister || registering"
              :loading="registering"
              @click="onRegister"
              >确认挂号</el-button
            >
          </div>
        </el-card>
      </el-col>

      <!-- 右：收费联动（stagger index 3） -->
      <el-col :md="24" :lg="8" class="fuy-stagger">
        <el-card class="registration-charge-card" :style="{ '--fuy-stagger-index': 3 }">
          <template #header>挂号费收费</template>
          <!-- 待缴行入场（§8.1：fuy-flip-enter，visit 维度键控） -->
          <Transition name="fuy-flip">
            <div v-if="chargeVisitId === ''" key="idle" class="registration-charge-idle">
              <el-empty :image-size="72" description="完成当日挂号后自动带出挂号费待缴行" />
            </div>
            <div v-else key="active">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="就诊号">
                  <span class="fuy-num">{{ chargeVisitId }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="单据号">
                  <span class="fuy-num">{{ lastAppointment?.apptNo ?? '—' }}</span>
                </el-descriptions-item>
              </el-descriptions>

              <!-- 缴费完成态（绿色对勾，scale 0.6→1 240ms emphasis——§8.1 允许两处之一） -->
              <Transition name="registration-charge-check">
                <div v-if="settled !== null" class="registration-charge-paid">
                  <span class="registration-charge-check" aria-hidden="true">✓</span>
                  <div>
                    <p>收费完成</p>
                    <p class="fuy-num registration-charge-paid-amount">
                      ￥{{ fenToYuanDisplay(settled.totalAmount ?? '') }}
                    </p>
                  </div>
                </div>
              </Transition>

              <template v-if="settled === null">
                <p class="fuy-section-title">待缴费用</p>
                <div v-loading="feesLoading" class="registration-charge-fees">
                  <el-table :data="unpaidFees" class="fuy-dense">
                    <el-table-column prop="itemNameSnapshot" label="项目" min-width="110" />
                    <el-table-column prop="quantity" label="数量" width="64" align="right">
                      <template #default="{ row }">
                        <span class="fuy-num">{{ row.quantity }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column prop="amount" label="金额（元）" width="100" align="right">
                      <template #default="{ row }">
                        <span class="fuy-num">{{ fenToYuanDisplay(row.amount ?? '') }}</span>
                      </template>
                    </el-table-column>
                    <template #empty>
                      <el-empty :image-size="56" description="无待缴费用行" />
                    </template>
                  </el-table>
                </div>

                <template v-if="preview !== null">
                  <p class="fuy-section-title">应收合计</p>
                  <p class="registration-charge-total">
                    <span class="fuy-num registration-charge-total-amount">{{
                      fenToYuanDisplay(preview.totalAmount ?? '')
                    }}</span>
                    <span class="registration-charge-total-unit">元（自费）</span>
                  </p>
                </template>
                <div class="registration-charge-pay-actions">
                  <el-button
                    v-if="preview === null"
                    type="primary"
                    :loading="previewing"
                    :disabled="previewing || unpaidFees.length === 0"
                    @click="onPreview"
                    >预结算</el-button
                  >
                  <el-button
                    v-else
                    type="primary"
                    :loading="settling"
                    :disabled="settling"
                    @click="onSettle"
                    >确认收费</el-button
                  >
                </div>
              </template>
            </div>
          </Transition>
        </el-card>
      </el-col>
    </el-row>

    <!-- 当日挂号记录（会话内追加；el-table 无法承载行级 TransitionGroup，空→有以容器 fuy-flip 入场） -->
    <el-card>
      <template #header>当日挂号记录</template>
      <Transition name="fuy-flip">
        <el-table v-if="records.length > 0" :data="records" class="fuy-dense">
          <el-table-column prop="apptNo" label="预约号" min-width="160">
            <template #default="{ row }">
              <span class="fuy-num">{{ row.apptNo }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="patientName" label="患者" min-width="100" />
          <el-table-column prop="deptCode" label="诊区" min-width="90" />
          <el-table-column prop="schedDate" label="就诊日期" min-width="110" />
          <el-table-column prop="status" label="状态" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'TAKEN' ? 'success' : 'primary'">{{
                row.status === 'TAKEN' ? '已取号' : '已预约'
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="charged" label="挂号费" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="row.charged ? 'success' : 'warning'" class="fuy-tag-aa">{{
                row.charged ? '已收费' : '待收费'
              }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else :image-size="72" description="今日尚无挂号记录，完成挂号后自动登记" />
      </Transition>
    </el-card>
  </div>
</template>

<style scoped>
/* 页头 48px（§3.2）：页面题 18px/600 + 计数 24px */
.registration-charge-header {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-4);
  min-height: 48px;
}
.registration-charge-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  line-height: 1.3;
}
.registration-charge-count {
  font-size: var(--fuy-font-size-3xl);
  font-weight: 700;
  color: var(--fuy-color-text-emphasis);
}
/* 刷新按钮靠页头右缘（§3.2 布局树页头三元素两端排布） */
.registration-charge-refresh {
  margin-left: auto;
}

/* 上区高度锁定（§3.2 min-height 480px）与步骤卡间距 */
.registration-charge-main {
  min-height: 480px;
}
.registration-charge-card {
  margin-bottom: var(--fuy-space-3);
}

/* 步骤序标：22px 圆形品牌底白字，完成态转 success 底（§8.1） */
.fuy-step-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  margin-right: var(--fuy-space-2);
  border-radius: var(--fuy-radius-full);
  background: var(--fuy-color-brand);
  color: var(--el-color-white);
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  vertical-align: middle;
}
.fuy-step-badge.is-done {
  background: var(--el-color-success);
}

/* 检索条与紧凑患者列表（行高 40px） */
.registration-charge-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--fuy-space-3);
  min-height: 48px;
}
.registration-charge-keyword {
  width: 240px;
}
.registration-charge-dept {
  width: 180px;
}
.fuy-patient-list {
  margin: var(--fuy-space-2) 0 0;
  padding: 0;
  list-style: none;
}
.fuy-patient-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 40px;
  padding: 0 var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  cursor: pointer;
  /* 单元素状态反馈（§8.1 限定条款）：底色 hover/选中 120ms 色值过渡 */
  transition: background-color var(--fuy-motion-fast) linear;
}
.fuy-patient-row:hover {
  background: var(--fuy-palette-brand-50);
}
.fuy-patient-row.is-active {
  background: var(--fuy-palette-brand-100);
}
.fuy-patient-row-meta {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}

/* 号源卡阵列 3 列 grid，单卡 min-height 88px + 左缘 3px 状态色条（§3.2/§8.1） */
.registration-charge-pools {
  min-height: 240px;
}
.registration-charge-pool-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--fuy-space-3);
}
.registration-charge-pool {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--fuy-space-1);
  min-height: 88px;
  padding: var(--fuy-space-3);
  border: 1px solid var(--el-border-color);
  border-left-width: 3px;
  border-radius: var(--fuy-radius-md);
  background: var(--el-bg-color);
  text-align: left;
  cursor: pointer;
  /* 点选号源：描边+底色 120ms 色值过渡（paint 级单元素状态反馈，§8.1 限定条款） */
  transition:
    border-color var(--fuy-motion-fast) linear,
    background-color var(--fuy-motion-fast) linear;
}
.registration-charge-pool.is-brand {
  border-left-color: var(--fuy-color-brand);
}
.registration-charge-pool.is-warning {
  border-left-color: var(--fuy-color-warning-text);
}
.registration-charge-pool.is-danger {
  border-left-color: var(--fuy-color-danger-text);
}
.registration-charge-pool:hover {
  background: var(--fuy-palette-brand-50);
}
.registration-charge-pool.is-selected {
  border-color: var(--fuy-color-brand);
  border-left-color: var(--fuy-color-brand);
  background: var(--fuy-palette-brand-100);
}
/* 余 0：卡体 60% 透明禁点（§8.1） */
.registration-charge-pool.is-disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.registration-charge-pool-type {
  font-size: var(--fuy-font-size-md);
}
.registration-charge-pool-slot {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.registration-charge-pool-remaining {
  font-size: var(--fuy-font-size-2xl);
  font-weight: 700;
  color: var(--fuy-color-text-emphasis);
}
/* 「紧张」角标 12px（余 ≤5） */
.registration-charge-tight {
  position: absolute;
  top: var(--fuy-space-2);
  right: var(--fuy-space-2);
  color: var(--fuy-color-warning-text);
  font-size: var(--fuy-font-size-xs);
}

/* 确认提交区（按钮 96px 宽 §3.2） */
.registration-charge-submit {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--fuy-space-3);
}
.registration-charge-submit-btn {
  width: 96px;
}

/* 收费面板：合计强调与完成态 */
.registration-charge-idle {
  min-height: 240px;
}
.registration-charge-fees {
  min-height: 120px;
}
.registration-charge-total {
  margin: var(--fuy-space-2) 0;
}
.registration-charge-total-amount {
  font-size: var(--fuy-font-size-2xl);
  font-weight: 700;
  color: var(--fuy-color-text-emphasis);
}
.registration-charge-total-unit {
  margin-left: var(--fuy-space-1);
  color: var(--fuy-color-text-secondary);
}
.registration-charge-pay-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--fuy-space-3);
}
.registration-charge-paid {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-3);
  margin-top: var(--fuy-space-3);
  padding: var(--fuy-space-3) var(--fuy-space-4);
  border-radius: var(--fuy-radius-md);
  background: var(--el-color-success-light-9);
}
.registration-charge-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--fuy-radius-full);
  background: var(--el-color-success);
  color: var(--el-color-white);
  font-size: var(--fuy-font-size-lg);
  font-weight: 700;
}
/* 缴费完成对勾：scale 0.6→1 240ms emphasis（§6.8 同款过冲时刻；过渡类非 keyframes，零新增） */
.registration-charge-check-enter-active {
  transition: transform 240ms var(--fuy-ease-emphasis);
}
.registration-charge-check-enter-from {
  transform: scale(0.6);
}
.registration-charge-paid-amount {
  font-weight: 700;
  color: var(--fuy-color-success-text);
}
</style>

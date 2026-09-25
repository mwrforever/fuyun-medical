<script setup lang="ts">
// 出院管理页（/inpatient/discharge，M04 FU-M04-07 前端面）：四态 tab 申请列表
// （REQUESTED 预审中/READY 待离院确认/BLOCKED 挂账审批中/COMPLETED 已离院）+ 申请发起
// 弹窗（预出院时间/离院方式病案首页词表）+ 清理与预审结果面板（ClearanceVO 渲染：清理
// 三清单计数+追踪清单+欠费额[分→元全站唯一件换算]+结算标记+挂账凭证）+ 挂账审批引导
// （BLOCKED 提示走 M13 收费面既有页，凭 billing.arrears.approved 自动转 READY）+ 离院
// 确认（双条件=预审 READY 且结算完成，与后端 GC19 前置同语义；不满禁用按钮+原因提示）。
// 说明：冻结 REST 面无出院申请列表 GET 端点，列表由本会话发起的申请单承载（后端补列表
// 端点后可平滑切换，PR 描述登记）。
// 全部写操作自带在途守卫（入口早退先于一切 await）；失败弹错归响应拦截器（AxiosError 防双弹）。
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import axios from 'axios';
import { discharge, DISCHARGE_STATUS_LABELS, DISCHARGE_WAY_OPTIONS } from '@/api/inpatient';
import type { ClearanceVO, DischargeRequestVO } from '@/api/inpatient';
import { fenToYuanDisplay } from '@/utils/money';

/** 业务失败兜底展示：AxiosError 已由响应拦截器弹错（防双弹）；其余形态（api 层直抛的
 * ProblemDetail 对象）在此展示 detail 原文 */
function surfaceBizError(error: unknown): void {
  if (axios.isAxiosError(error)) {
    return;
  }
  const detail = (error as { detail?: unknown } | null | undefined)?.detail;
  if (typeof detail === 'string' && detail.length > 0) {
    void ElMessage.error(detail);
  }
}

/** 时点展示串（MM-dd HH:mm，申请/结算时点列共用） */
function formatTime(raw: string | undefined): string {
  if (!raw) {
    return '—';
  }
  const date = new Date(raw);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 四态 tab 词表（后端 DischargeRequestStatus 主链四态；CANCELLED 行在创建来源态内自然消隐） */
const STATUS_TABS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'REQUESTED', label: '预审中' },
  { code: 'READY', label: '待离院确认' },
  { code: 'BLOCKED', label: '挂账审批中' },
  { code: 'COMPLETED', label: '已离院' },
];

/* ==================== 申请列表（会话内承载） ==================== */
/** 本会话发起的申请单（无列表 GET 端点，详见文件头说明） */
const requests = ref<DischargeRequestVO[]>([]);
const statusFilter = ref('REQUESTED');

/** 当前 tab 过滤后的申请行 */
const filteredRequests = computed(() =>
  requests.value.filter((row) => (row.status ?? '') === statusFilter.value),
);

/** 状态中文词表反查（行状态列） */
function statusLabel(code: string | undefined): string {
  return DISCHARGE_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/** 离院方式中文词表反查（行离院方式列） */
function dischargeWayLabel(code: string | undefined): string {
  return DISCHARGE_WAY_OPTIONS.find((item) => item.code === code)?.label ?? code ?? '—';
}

/* ---------- 申请发起弹窗 ---------- */
const createVisible = ref(false);
const creating = ref(false);
const createForm = ref({ visitId: '', expectDischargeAt: '', dischargeWay: '1' });

/** 打开申请弹窗（复位表单） */
function openCreate(): void {
  createForm.value = { visitId: '', expectDischargeAt: '', dischargeWay: '1' };
  createVisible.value = true;
}

/**
 * 提交出院申请：显式校验（就诊号/预出院时间，零出网）→ 出网（在途清理+费用预审单事务
 * 归后端编排）→ 申请单落会话列表并自动切至返回实态 tab（READY/BLOCKED 预审实态）。
 * 入口在途早退守卫防双击重复申请。
 */
async function onCreate(): Promise<void> {
  if (creating.value) {
    return;
  }
  if (createForm.value.visitId.trim() === '') {
    void ElMessage.warning('请填写在院就诊号');
    return;
  }
  const rawTime = createForm.value.expectDischargeAt;
  const parsed = rawTime === '' ? null : new Date(rawTime);
  if (parsed === null || Number.isNaN(parsed.getTime())) {
    void ElMessage.warning('请选择预出院时间');
    return;
  }
  creating.value = true;
  try {
    // datetime-local 本地时点 → ISO date-time（OffsetDateTime 契约可解析形态）
    const saved = await discharge.create(createForm.value.visitId.trim(), {
      expectDischargeAt: parsed.toISOString(),
      dischargeWay: createForm.value.dischargeWay,
    });
    requests.value.push(saved);
    statusFilter.value = saved.status ?? 'REQUESTED';
    createVisible.value = false;
    if (saved.status === 'BLOCKED') {
      void ElMessage.warning(
        `出院申请 ${saved.requestNo ?? ''} 已提交：预审未通过（欠费挂账审批中）`,
      );
    } else {
      void ElMessage.success(`出院申请 ${saved.requestNo ?? ''} 已提交`);
    }
  } catch (error) {
    surfaceBizError(error);
  } finally {
    creating.value = false;
  }
}

/* ---------- 取消申请（仅 REQUESTED 态，回在院医嘱不复活） ---------- */
const cancelling = ref(false);

/** 取消出院申请（中档确认；状态机由后端 IP-1017 把守） */
async function onCancel(row: DischargeRequestVO): Promise<void> {
  if (cancelling.value) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      `即将取消出院申请 ${row.requestNo ?? ''}（就诊回在院，医嘱不复活），确认？`,
      '取消出院申请',
      { confirmButtonText: '确认取消', cancelButtonText: '返回' },
    );
  } catch {
    return;
  }
  cancelling.value = true;
  try {
    const saved = await discharge.cancel(row.requestNo ?? '');
    row.status = saved.status;
    void ElMessage.success(`出院申请已取消：${saved.requestNo ?? ''}`);
  } catch (error) {
    surfaceBizError(error);
  } finally {
    cancelling.value = false;
  }
}

/* ==================== 清理与预审结果面板 ==================== */
const clearance = ref<ClearanceVO | null>(null);
const clearanceLoading = ref(false);
/** 面板锚定申请单（清理预审/离院确认共用） */
const panelRequest = ref<DischargeRequestVO | null>(null);

/** 拉取清理与预审结果（清理三清单+追踪清单+欠费额+结算标记+挂账凭证） */
async function loadClearance(row: DischargeRequestVO): Promise<void> {
  panelRequest.value = row;
  clearanceLoading.value = true;
  try {
    // 归一化兜底：异常空响应收敛 null，防 undefined 穿透守卫致渲染面空引用
    clearance.value = (await discharge.clearance(row.requestNo ?? '')) ?? null;
  } catch {
    // 失败弹错归响应拦截器；驻留旧面板
  } finally {
    clearanceLoading.value = false;
  }
}

/** 欠费额展示（分→元，全站唯一件换算；0 或空=无欠费） */
const arrearsDisplay = computed(() => {
  const raw = clearance.value?.arrearsAmount;
  if (raw === undefined || raw === null) {
    return null;
  }
  return fenToYuanDisplay(raw);
});

/** 预审 BLOCKED（挂账审批引导可见性） */
const isBlocked = computed(() => clearance.value?.status === 'BLOCKED');

/** 离院确认双条件：预审 READY 且结算完成（后端 GC19 前置同语义，前端禁用为同态预检） */
const canConfirm = computed(
  () =>
    clearance.value?.status === 'READY' &&
    clearance.value?.settlementCompletedAt !== undefined &&
    clearance.value?.settlementCompletedAt !== null,
);

/** 双条件不满原因提示（逐条件判定位） */
const confirmBlockReason = computed(() => {
  if (!clearance.value || canConfirm.value) {
    return '';
  }
  if (clearance.value.status === 'BLOCKED') {
    return '预审未通过（欠费挂账审批中），不可确认离院';
  }
  if (clearance.value.status !== 'READY') {
    return `预审状态非 READY（当前=${statusLabel(clearance.value.status)}），不可确认离院`;
  }
  return '出院结算未完成，不可确认离院';
});

/* ---------- 离院确认 ---------- */
const confirming = ref(false);

/**
 * 离院确认（双条件放行；床位终末消毒/出院带药放行/随访生成由后端联动）。
 * 随访三参数缺省=7 日/电话/「出院随访」由后端承载，前端不录（Spec 缺省语义直传空体）。
 */
async function onConfirm(): Promise<void> {
  if (confirming.value || panelRequest.value === null || !canConfirm.value) {
    return;
  }
  confirming.value = true;
  try {
    const saved = await discharge.confirm(panelRequest.value.requestNo ?? '', {});
    panelRequest.value.status = saved.status;
    void ElMessage.success(`离院确认完成：${saved.requestNo ?? ''}（床位转终末消毒）`);
    await loadClearance(panelRequest.value);
  } catch (error) {
    // 双条件后端兜底 IP-1017：detail 原文透出
    surfaceBizError(error);
  } finally {
    confirming.value = false;
  }
}

onMounted(() => {
  // 会话列表无初载出网（无列表端点）；页面挂载即空态
});
</script>

<template>
  <div class="fuy-page discharge-manage fuy-stagger">
    <!-- 页头：标题 + 发起申请 -->
    <header class="discharge-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="discharge-title">出院管理</h2>
      <span class="discharge-hint">在途清理 + 费用预审单事务编排 · 双条件离院放行</span>
      <el-button
        type="primary"
        class="discharge-create-btn"
        :disabled="creating"
        @click="openCreate"
        >发起出院申请</el-button
      >
    </header>

    <el-row :gutter="16" class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <!-- 左：四态 tab 申请列表 -->
      <el-col :md="24" :lg="14">
        <el-card>
          <template #header>
            <el-radio-group v-model="statusFilter" size="small">
              <el-radio-button v-for="tab in STATUS_TABS" :key="tab.code" :label="tab.code">{{
                tab.label
              }}</el-radio-button>
            </el-radio-group>
          </template>
          <div>
            <el-table
              v-if="filteredRequests.length > 0"
              :data="filteredRequests"
              class="fuy-dense"
              size="small"
            >
              <el-table-column label="申请单号" min-width="120">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.requestNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="就诊号" min-width="120">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.visitId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="离院方式" width="110">
                <template #default="{ row }">
                  {{ dischargeWayLabel(row.dischargeWay) }}
                </template>
              </el-table-column>
              <el-table-column label="预出院时间" width="110">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.expectDischargeAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag size="small" class="fuy-tag-aa">{{ statusLabel(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="150" class-name="fuy-ops-8">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="loadClearance(row)"
                    >清理预审</el-button
                  >
                  <el-button
                    v-if="row.status === 'REQUESTED'"
                    link
                    type="danger"
                    size="small"
                    :disabled="cancelling"
                    @click="onCancel(row)"
                    >取消</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="暂无出院申请" />
          </div>
        </el-card>
      </el-col>

      <!-- 右：清理与预审结果面板 -->
      <el-col :md="24" :lg="10">
        <el-card>
          <template #header>
            <span
              >清理与预审结果{{
                panelRequest === null ? '' : `（${panelRequest.requestNo ?? ''}）`
              }}</span
            >
          </template>
          <div v-loading="clearanceLoading">
            <template v-if="clearance">
              <!-- 清理三清单计数 -->
              <el-descriptions :column="3" border size="small" class="discharge-desc">
                <el-descriptions-item label="长期医嘱停止">
                  <span class="fuy-num">{{ clearance.stoppedLongCount ?? 0 }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="作废计划数">
                  <span class="fuy-num">{{ clearance.cancelledPlanCount ?? 0 }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="预审状态">
                  {{ statusLabel(clearance.status) }}
                </el-descriptions-item>
              </el-descriptions>

              <!-- 追踪清单 -->
              <h4 class="discharge-panel-sub">医嘱追踪清单</h4>
              <el-table
                v-if="(clearance.trackedOrders ?? []).length > 0"
                :data="clearance.trackedOrders"
                class="fuy-dense"
                size="small"
              >
                <el-table-column label="医嘱号" min-width="130">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.orderNo }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="分类" width="70">
                  <template #default="{ row }">
                    {{ row.orderClass === 'LONG' ? '长期' : '临时' }}
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">
                    {{ row.status }}
                  </template>
                </el-table-column>
              </el-table>
              <p v-else class="discharge-empty-line">追踪清单为空（在途医嘱已全部终态）</p>

              <!-- 欠费额与结算标记 -->
              <el-descriptions :column="2" border size="small" class="discharge-desc">
                <el-descriptions-item label="欠费额（元）">
                  <span class="fuy-num discharge-arrears">{{ arrearsDisplay ?? '0.00' }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="结算完成">
                  <span class="fuy-num">{{
                    clearance.settlementCompletedAt
                      ? formatTime(clearance.settlementCompletedAt)
                      : '未完成'
                  }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="挂账凭证">
                  <span class="fuy-num">{{ clearance.approvalNo ?? '—' }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="申请状态">
                  {{ statusLabel(clearance.status) }}
                </el-descriptions-item>
              </el-descriptions>

              <!-- 挂账审批引导（BLOCKED：走 M13 收费面既有页，凭事件自动转 READY） -->
              <p v-if="isBlocked" class="discharge-blocked-guide">
                预审未通过（欠费 {{ arrearsDisplay ?? '0.00' }} 元）：须走计费域「欠费挂账审批」
                （收费面既有页）提交审批，审批通过凭 billing.arrears.approved 自动转「待离院确认」。
              </p>

              <!-- 离院确认（双条件不满禁用 + 原因提示） -->
              <div class="discharge-confirm-row">
                <span
                  v-if="!canConfirm && panelRequest !== null"
                  class="discharge-confirm-reason"
                  >{{ confirmBlockReason }}</span
                >
                <el-button
                  type="danger"
                  plain
                  :loading="confirming"
                  :disabled="confirming || !canConfirm"
                  @click="onConfirm"
                  >确认离院</el-button
                >
              </div>
            </template>
            <el-empty v-else :image-size="64" description="从左侧申请单点击「清理预审」查看结果" />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 申请发起弹窗 -->
    <el-dialog v-model="createVisible" title="发起出院申请" width="420px">
      <label class="discharge-field-label">在院就诊号</label>
      <input
        v-model="createForm.visitId"
        class="discharge-input fuy-num"
        placeholder="I 型 14 位就诊号"
        aria-label="在院就诊号"
      />
      <label class="discharge-field-label">预出院时间（必填）</label>
      <input
        v-model="createForm.expectDischargeAt"
        type="datetime-local"
        class="discharge-input fuy-num"
        aria-label="预出院时间"
      />
      <label class="discharge-field-label">离院方式（病案首页词表）</label>
      <select v-model="createForm.dischargeWay" class="discharge-input" aria-label="离院方式">
        <option v-for="item in DISCHARGE_WAY_OPTIONS" :key="item.code" :value="item.code">
          {{ item.label }}
        </option>
      </select>
      <template #footer>
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :loading="creating"
          :disabled="creating"
          @click="onCreate"
          >提交申请</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 出院管理布局：左申请列表右预审面板，token 取色禁自创色值 */
.discharge-toolbar {
  align-items: center;
}

.discharge-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.discharge-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.discharge-create-btn {
  margin-left: auto;
}

.discharge-desc {
  margin-bottom: var(--fuy-space-3);
}

.discharge-panel-sub {
  margin: 0 0 var(--fuy-space-2);
  font-size: var(--fuy-font-size-sm);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.discharge-empty-line {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 欠费额（金额强调色） */
.discharge-arrears {
  font-weight: 700;
  color: var(--fuy-color-danger-text);
}

/* 挂账审批引导（BLOCKED：warning 系弱底提示条） */
.discharge-blocked-guide {
  margin: var(--fuy-space-3) 0;
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-brand-50);
  border-left: 3px solid var(--fuy-color-warning-text);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-emphasis);
}

/* 离院确认行：原因提示居左，按钮居右 */
.discharge-confirm-row {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--fuy-space-3);
}

.discharge-confirm-reason {
  flex: 1;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-danger-text);
}

/* 弹窗表单基元（token 取色） */
.discharge-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.discharge-input {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: var(--fuy-space-3);
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}
</style>

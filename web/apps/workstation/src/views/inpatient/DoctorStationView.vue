<script setup lang="ts">
// 住院医生站页（/inpatient/station，M04 FU-M04-03/04 前端面）：三区布局——
// 左=在院患者列表（病区维度切换/护理级别徽标/欠费标识/过敏标识懒加载）；
// 中=患者上下文 + 在院医嘱列表（行点选驱动追溯）+ 医嘱开立表单（九类型下拉/长期临时/
// 嘱托开关[仅长期]/频次选择/成组行编辑[多行共用组号]；保存=开立→CREATED→用药类「待药师审」）；
// 右=闭环追溯面板（单医嘱开立→审核→转抄→执行→状态迁移五环节时间线）。
// 开立前显式格式校验（IP-1011 类：明细必填/数量正数/长期频次）零出网拦截；执业授权（IP-1012）
// 与过敏冲突（IP-1013）由后端 403/409 业务拒绝，detail 中文原文经 surfaceBizError 兜底透出。
// 全部写操作自带在途守卫（入口早退先于一切 await）；失败弹错归响应拦截器（AxiosError 防双弹）。
// 患者摘要按脱敏口径仅展示就诊号/床号（后端 VO 契约即不含姓名）。
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import axios from 'axios';
import {
  MEDICATION_ORDER_TYPES,
  ORDER_CLASS_OPTIONS,
  ORDER_FREQUENCY_OPTIONS,
  ORDER_STATUS_LABELS,
  ORDER_TYPE_OPTIONS,
  TRACE_STAGE_LABELS,
  orders,
  visits,
  WARD_OPTIONS,
} from '@/api/inpatient';
import type { MedicalOrderVO, OrderItemPayload, TraceEntry } from '@/api/inpatient';
import { wardPatients } from '@/api/nursing';
import type { WardPatientVO } from '@/api/nursing';

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

/** 时点展示串（MM-dd HH:mm，医嘱开立时间列与追溯环节共用） */
function formatTime(raw: string | undefined): string {
  if (!raw) {
    return '—';
  }
  const date = new Date(raw);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 护理级别中文词表（后端 NursingLevel 三值，与 nursing 域同源） */
const NURSING_LEVEL_LABELS: Record<string, string> = {
  SPECIAL: '特级护理',
  CRITICAL: '病重护理',
  NORMAL: '普通护理',
};

/** 护理级别 → 全局徽标类映射（element-plus.css 工具类，零自创色） */
const NURSING_LEVEL_BADGE: Record<string, string> = {
  SPECIAL: 'fuy-nursing-level-badge--special',
  CRITICAL: 'fuy-nursing-level-badge--l1',
  NORMAL: 'fuy-nursing-level-badge--l3',
};

/* ==================== 左栏：在院患者列表 ==================== */
const wardId = ref(WARD_OPTIONS[0].code);
const patients = ref<WardPatientVO[]>([]);
const patientsLoading = ref(false);
/** 欠费在院集合（arrears 聚合按 visitId 命中行内「欠费」标识） */
const arrearsVisitIds = ref<Set<string>>(new Set());
/** 过敏标识缓存（选中行 detail 懒加载回填；true=过敏，false=已查无过敏） */
const allergyFlagMap = ref<Map<string, boolean>>(new Map());
/** 当前选中在院患者 */
const selectedVisit = ref<WardPatientVO | null>(null);

/** 加载在院患者列表与病区欠费清单（病区切换/操作后刷新共用） */
async function loadPatients(): Promise<void> {
  patientsLoading.value = true;
  try {
    const [rows, arrears] = await Promise.all([
      wardPatients.list(wardId.value),
      visits.arrears(wardId.value),
    ]);
    patients.value = rows;
    arrearsVisitIds.value = new Set(
      arrears.map((row) => row.visitId ?? '').filter((id) => id !== ''),
    );
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    patientsLoading.value = false;
  }
}

/** 行点击选中：驱动中列上下文与医嘱列表，并懒加载过敏标识（行内与上下文徽标共用缓存） */
async function selectPatient(row: WardPatientVO): Promise<void> {
  selectedVisit.value = row;
  orderRows.value = [];
  selectedOrder.value = null;
  traceEntries.value = [];
  if (row.visitId !== undefined && !allergyFlagMap.value.has(row.visitId)) {
    try {
      const detail = await wardPatients.detail(row.visitId);
      allergyFlagMap.value.set(row.visitId ?? '', detail.allergyFlag === true);
    } catch {
      // 失败弹错归响应拦截器；过敏标识缺省不展示（不误标）
    }
  }
  await loadOrders();
}

/** 行内过敏标识（已加载且 true 才展示，未查不误标） */
function allergyFlagOf(row: WardPatientVO): boolean {
  return row.visitId !== undefined && allergyFlagMap.value.get(row.visitId) === true;
}

/* ==================== 中栏：医嘱列表与开立 ==================== */
const orderRows = ref<MedicalOrderVO[]>([]);
const ordersLoading = ref(false);
/** 当前点选医嘱（追溯锚点） */
const selectedOrder = ref<MedicalOrderVO | null>(null);

/** 拉取在院医嘱分页（开立时间倒序，开立/选中患者后重刷共用） */
async function loadOrders(): Promise<void> {
  if (selectedVisit.value?.visitId === undefined) {
    return;
  }
  ordersLoading.value = true;
  try {
    const page = await orders.list({
      visitId: selectedVisit.value.visitId,
      page: 0,
      size: 50,
    });
    orderRows.value = page.content ?? [];
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    ordersLoading.value = false;
  }
}

/** 医嘱行点选：驱动右栏闭环追溯 */
async function selectOrder(row: MedicalOrderVO): Promise<void> {
  selectedOrder.value = row;
  await loadTrace();
}

/** 医嘱类型中文词表反查（列表类型列） */
function orderTypeLabel(code: string | undefined): string {
  return ORDER_TYPE_OPTIONS.find((item) => item.code === code)?.label ?? code ?? '—';
}

/** 医嘱分类中文词表反查（列表分类列） */
function orderClassLabel(code: string | undefined): string {
  return ORDER_CLASS_OPTIONS.find((item) => item.code === code)?.label ?? code ?? '—';
}

/* ---------- 开立表单（九类型/长期临时/嘱托/频次/成组行编辑） ---------- */
const creating = ref(false);
const createForm = ref({
  orderType: 'DRUG',
  orderClass: 'STAT',
  standbyFlag: false,
  freqCode: '',
});
/** 明细行编辑态（多行=成组医嘱共用组号；行序号即提交序；quantity 经 v-model.number 承载 number） */
interface ItemRowDraft {
  itemCode: string;
  itemName: string;
  dosage: string;
  dosageUnit: string;
  route: string;
  quantity: number | '';
}
const itemRows = ref<ItemRowDraft[]>([
  { itemCode: '', itemName: '', dosage: '', dosageUnit: '', route: '', quantity: 1 },
]);
/** 当前类型是否用药类（剂量/单位/途径必填面 + 待药师审提示面） */
const isMedicationType = computed(() => MEDICATION_ORDER_TYPES.has(createForm.value.orderType));
/** 是否长期分类（频次必填 + 嘱托开关可用面） */
const isLongClass = computed(() => createForm.value.orderClass === 'LONG');
/** 成组提示（多行明细共用组号） */
const groupHint = computed(() =>
  itemRows.value.length > 1
    ? '本单共 ' + itemRows.value.length + ' 行明细，按成组医嘱开立（共用组号）'
    : '多行明细按成组医嘱开立（共用组号），单行自成一组',
);

/** 添加明细行（成组录入入口） */
function addItemRow(): void {
  itemRows.value.push({
    itemCode: '',
    itemName: '',
    dosage: '',
    dosageUnit: '',
    route: '',
    quantity: 1,
  });
}

/** 移除明细行（至少保留一行） */
function removeItemRow(index: number): void {
  if (itemRows.value.length > 1) {
    itemRows.value.splice(index, 1);
  }
}

/**
 * 保存医嘱（开立）：显式格式校验（IP-1011 类）零出网 → 出网 → CREATED 返回后按用药类
 * 提示「待药师审」并刷新医嘱列表。入口在途早退守卫防双击重复开立。
 */
async function onSaveOrder(): Promise<void> {
  if (creating.value) {
    return;
  }
  if (selectedVisit.value?.visitId === undefined) {
    void ElMessage.warning('请先从左侧选择在院患者');
    return;
  }
  // 明细行显式校验+规范化一并完成（校验通过的行即时窄化入列，quantity 收敛 number）：
  // 编码/名称必填、数量正数；用药类加剂量/单位/途径必填（IP-1011 类）
  const validatedItems: OrderItemPayload[] = [];
  for (let i = 0; i < itemRows.value.length; i += 1) {
    const row = itemRows.value[i];
    if (row.itemCode.trim() === '' || row.itemName.trim() === '') {
      void ElMessage.warning(`第 ${i + 1} 行项目编码/名称必填`);
      return;
    }
    if (typeof row.quantity !== 'number' || !Number.isFinite(row.quantity) || row.quantity <= 0) {
      void ElMessage.warning(`第 ${i + 1} 行数量须为正数`);
      return;
    }
    if (isMedicationType.value) {
      if (row.dosage.trim() === '' || row.dosageUnit.trim() === '' || row.route.trim() === '') {
        void ElMessage.warning(`第 ${i + 1} 行剂量/单位/途径必填（用药类 IP-1011 校验项）`);
        return;
      }
    }
    validatedItems.push({
      itemType: createForm.value.orderType,
      itemCode: row.itemCode.trim(),
      itemName: row.itemName.trim(),
      dosage: isMedicationType.value ? row.dosage.trim() : undefined,
      dosageUnit: isMedicationType.value ? row.dosageUnit.trim() : undefined,
      route: isMedicationType.value ? row.route.trim() : undefined,
      quantity: row.quantity,
    });
  }
  // 长期医嘱频次必填（IP-1011 类；后端频次字典无命中另拒 IP-1021）
  if (isLongClass.value && createForm.value.freqCode === '') {
    void ElMessage.warning('长期医嘱须选择频次（IP-1011 校验项）');
    return;
  }
  creating.value = true;
  try {
    const saved = await orders.create(selectedVisit.value.visitId, {
      orderType: createForm.value.orderType,
      orderClass: createForm.value.orderClass,
      standbyFlag: isLongClass.value && createForm.value.standbyFlag ? true : undefined,
      freqCode: isLongClass.value ? createForm.value.freqCode : undefined,
      items: validatedItems,
    });
    // 用药类 CREATED 停留语义=「待药师审」（M06 审方通过后迁 AUDITED）
    if (MEDICATION_ORDER_TYPES.has(createForm.value.orderType)) {
      void ElMessage.success(`医嘱 ${saved.orderNo ?? ''} 已开立（待药师审）`);
    } else {
      void ElMessage.success(`医嘱 ${saved.orderNo ?? ''} 已开立`);
    }
    itemRows.value = [
      { itemCode: '', itemName: '', dosage: '', dosageUnit: '', route: '', quantity: 1 },
    ];
    createForm.value.freqCode = '';
    createForm.value.standbyFlag = false;
    await loadOrders();
  } catch (error) {
    // 执业授权 IP-1012/过敏冲突 IP-1013 等业务拒绝：detail 中文原文兜底透出
    surfaceBizError(error);
  } finally {
    creating.value = false;
  }
}

/* ==================== 右栏：闭环追溯 ==================== */
const traceLoading = ref(false);
const traceEntries = ref<TraceEntry[]>([]);
const traceStatus = ref('');

/** 拉取选中医嘱闭环追溯（开立→审核→转抄→执行→状态迁移时间线） */
async function loadTrace(): Promise<void> {
  if (selectedOrder.value?.orderNo === undefined) {
    return;
  }
  traceLoading.value = true;
  try {
    const trace = await orders.trace(selectedOrder.value.orderNo);
    traceEntries.value = trace.entries ?? [];
    traceStatus.value = trace.status ?? '';
  } catch {
    // 失败弹错归响应拦截器；驻留旧时间线
  } finally {
    traceLoading.value = false;
  }
}

/** 环节中文词表反查（时间线环节名） */
function stageLabel(code: string | undefined): string {
  return TRACE_STAGE_LABELS[code ?? ''] ?? code ?? '—';
}

onMounted(() => {
  void loadPatients();
});
</script>

<template>
  <div class="fuy-page station-view">
    <!-- 页头：标题 + 病区切换 + 刷新 -->
    <header class="station-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="station-title">住院医生站</h2>
      <select v-model="wardId" class="station-ward-select" aria-label="病区" @change="loadPatients">
        <option v-for="ward in WARD_OPTIONS" :key="ward.code" :value="ward.code">
          {{ ward.label }}
        </option>
      </select>
      <el-button class="station-refresh" :loading="patientsLoading" @click="loadPatients"
        >刷新</el-button
      >
    </header>

    <el-row class="fuy-stagger" :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
      <!-- 左栏：在院患者列表 -->
      <el-col :md="24" :lg="6">
        <el-card>
          <template #header>
            <span>在院患者（{{ patients.length }}）</span>
          </template>
          <div v-loading="patientsLoading" class="station-patient-list">
            <ul v-if="patients.length > 0" class="station-patient-ul">
              <li
                v-for="row in patients"
                :key="row.visitId"
                class="station-patient-row"
                :class="{ 'is-current': selectedVisit?.visitId === row.visitId }"
                @click="selectPatient(row)"
              >
                <div class="station-patient-head">
                  <span class="fuy-num station-visit-id">{{ row.visitId }}</span>
                  <span class="fuy-num station-bed-no">{{ row.bedNo }} 床</span>
                </div>
                <div class="station-patient-flags">
                  <span
                    v-if="NURSING_LEVEL_BADGE[row.nursingLevel ?? ''] !== undefined"
                    class="fuy-nursing-level-badge"
                    :class="NURSING_LEVEL_BADGE[row.nursingLevel ?? '']"
                    >{{ NURSING_LEVEL_LABELS[row.nursingLevel ?? ''] ?? row.nursingLevel }}</span
                  >
                  <!-- 欠费标识（后端 arrears 聚合命中；机器判据类） -->
                  <span v-if="arrearsVisitIds.has(row.visitId ?? '')" class="station-arrears-flag"
                    >欠费</span
                  >
                  <!-- 过敏标识（详情懒加载命中；未查不误标） -->
                  <span v-if="allergyFlagOf(row)" class="fuy-nursing-flag station-allergy-flag"
                    >过敏</span
                  >
                </div>
              </li>
            </ul>
            <el-empty v-else :image-size="64" description="当前病区暂无在院患者" />
          </div>
        </el-card>
      </el-col>

      <!-- 中栏：患者上下文 + 医嘱列表 + 开立表单 -->
      <el-col :md="24" :lg="12">
        <el-card>
          <template #header>
            <div class="station-context-head">
              <span class="fuy-num station-context-visit">{{
                selectedVisit?.visitId ?? '未选择患者'
              }}</span>
              <span v-if="selectedVisit !== null" class="fuy-num station-context-bed"
                >{{ selectedVisit.bedNo }} 床</span
              >
              <span
                v-if="selectedVisit !== null && allergyFlagOf(selectedVisit)"
                class="fuy-nursing-flag station-allergy-flag"
                >过敏</span
              >
              <span
                v-if="selectedVisit !== null && arrearsVisitIds.has(selectedVisit.visitId ?? '')"
                class="station-arrears-flag"
                >欠费</span
              >
            </div>
          </template>

          <!-- 在院医嘱列表（开立时间倒序；行点选驱动追溯） -->
          <div v-loading="ordersLoading" class="station-orders">
            <el-table
              v-if="orderRows.length > 0"
              :data="orderRows"
              class="fuy-dense"
              size="small"
              :row-class-name="
                ({ row }) => (selectedOrder?.orderNo === row.orderNo ? 'is-current-row' : '')
              "
            >
              <el-table-column label="医嘱号" min-width="130">
                <template #default="{ row }">
                  <span class="fuy-num station-order-row" @click="selectOrder(row)">{{
                    row.orderNo
                  }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="70">
                <template #default="{ row }">
                  {{ orderTypeLabel(row.orderType) }}
                </template>
              </el-table-column>
              <el-table-column label="分类" width="60">
                <template #default="{ row }">
                  {{ orderClassLabel(row.orderClass) }}
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" class="fuy-tag-aa">{{
                    ORDER_STATUS_LABELS[row.status ?? ''] ?? row.status
                  }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="开立" width="100">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.orderedAt) }}</span>
                </template>
              </el-table-column>
            </el-table>
            <el-empty
              v-else
              :image-size="56"
              :description="
                selectedVisit === null ? '从左侧选择在院患者' : '暂无医嘱，可在下方开立'
              "
            />
          </div>

          <!-- 医嘱开立表单 -->
          <div class="station-create-block">
            <h3 class="station-block-title">医嘱开立</h3>
            <el-form label-position="left" label-width="72px" size="small">
              <div class="station-create-row">
                <div class="station-create-field">
                  <label class="station-field-label">医嘱类型（九类）</label>
                  <select
                    v-model="createForm.orderType"
                    class="station-input"
                    aria-label="医嘱类型"
                  >
                    <option v-for="item in ORDER_TYPE_OPTIONS" :key="item.code" :value="item.code">
                      {{ item.label }}
                    </option>
                  </select>
                </div>
                <div class="station-create-field">
                  <label class="station-field-label">分类</label>
                  <div class="station-radio-row">
                    <label
                      v-for="item in ORDER_CLASS_OPTIONS"
                      :key="item.code"
                      class="station-radio-item"
                    >
                      <input
                        v-model="createForm.orderClass"
                        type="radio"
                        name="orderClass"
                        :value="item.code"
                        :aria-label="`医嘱分类${item.label}`"
                      />
                      {{ item.label }}
                    </label>
                  </div>
                </div>
                <div class="station-create-field">
                  <label class="station-field-label">频次（长期必填）</label>
                  <select
                    v-model="createForm.freqCode"
                    class="station-input"
                    aria-label="医嘱频次"
                    :disabled="!isLongClass"
                  >
                    <option value="">未选择</option>
                    <option
                      v-for="item in ORDER_FREQUENCY_OPTIONS"
                      :key="item.code"
                      :value="item.code"
                    >
                      {{ item.label }}
                    </option>
                  </select>
                </div>
                <label class="station-radio-item station-standby-item">
                  <input
                    v-model="createForm.standbyFlag"
                    type="checkbox"
                    :disabled="!isLongClass"
                    aria-label="嘱托标记"
                  />
                  嘱托（仅长期）
                </label>
              </div>

              <!-- 成组行编辑：多行共用组号（服务层回填），行内校验前置 -->
              <div v-for="(row, index) in itemRows" :key="index" class="station-item-row">
                <span class="fuy-num station-item-seq">{{ index + 1 }}</span>
                <input
                  v-model="row.itemCode"
                  class="station-input station-item-code"
                  :placeholder="`第${index + 1}行项目编码`"
                  :aria-label="`第${index + 1}行项目编码`"
                />
                <input
                  v-model="row.itemName"
                  class="station-input station-item-name"
                  :placeholder="`第${index + 1}行项目名称`"
                  :aria-label="`第${index + 1}行项目名称`"
                />
                <template v-if="isMedicationType">
                  <input
                    v-model="row.dosage"
                    class="station-input station-item-dosage"
                    placeholder="剂量"
                    :aria-label="`第${index + 1}行剂量`"
                  />
                  <input
                    v-model="row.dosageUnit"
                    class="station-input station-item-unit"
                    placeholder="单位"
                    :aria-label="`第${index + 1}行单位`"
                  />
                  <input
                    v-model="row.route"
                    class="station-input station-item-route"
                    placeholder="途径"
                    :aria-label="`第${index + 1}行途径`"
                  />
                </template>
                <input
                  v-model="row.quantity"
                  class="station-input station-item-quantity"
                  type="number"
                  min="1"
                  placeholder="数量"
                  :aria-label="`第${index + 1}行数量`"
                />
                <el-button
                  link
                  type="danger"
                  size="small"
                  :disabled="itemRows.length === 1"
                  @click="removeItemRow(index)"
                  >删行</el-button
                >
              </div>
              <div class="station-item-ops">
                <el-button link type="primary" size="small" @click="addItemRow"
                  >加一行（成组）</el-button
                >
                <span class="station-group-hint">{{ groupHint }}</span>
              </div>
              <el-button
                type="primary"
                class="station-save"
                :loading="creating"
                :disabled="creating || selectedVisit === null"
                @click="onSaveOrder"
                >保存医嘱</el-button
              >
            </el-form>
          </div>
        </el-card>
      </el-col>

      <!-- 右栏：闭环追溯面板 -->
      <el-col :md="24" :lg="6">
        <el-card>
          <template #header>
            <span>闭环追溯</span>
          </template>
          <div v-loading="traceLoading" class="station-trace">
            <p v-if="selectedOrder !== null" class="station-trace-order fuy-num">
              {{ selectedOrder.orderNo }}
              <el-tag size="small" class="fuy-tag-aa">{{
                ORDER_STATUS_LABELS[selectedOrder.status ?? ''] ?? selectedOrder.status
              }}</el-tag>
            </p>
            <ol v-if="traceEntries.length > 0" class="station-trace-list">
              <li v-for="(entry, index) in traceEntries" :key="index" class="station-trace-item">
                <div class="station-trace-head">
                  <span class="station-trace-stage">{{ stageLabel(entry.stage) }}</span>
                  <span class="fuy-num station-trace-time">{{ formatTime(entry.occurredAt) }}</span>
                </div>
                <div class="station-trace-body">
                  <span class="station-trace-result">{{ entry.result ?? '—' }}</span>
                  <span v-if="entry.detail" class="station-trace-detail">{{ entry.detail }}</span>
                </div>
              </li>
            </ol>
            <el-empty
              v-else
              :image-size="64"
              :description="selectedOrder === null ? '从中列医嘱列表点选医嘱' : '暂无追溯环节'"
            />
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
/* 三栏医生站布局：左患者/中医嘱/右追溯，token 取色禁自创色值 */
.station-toolbar {
  align-items: center;
}

.station-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

/* 病区切换（native select 与 EP 密度口径对齐，jsdom 可测） */
.station-ward-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

/* 左栏患者行：56px 行高 + 选中左缘品牌色条（门诊医生站同形态） */
.station-patient-list {
  min-height: 200px;
}

.station-patient-ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

.station-patient-row {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  cursor: pointer;
  transition: background-color var(--fuy-motion-fast) linear;
}

.station-patient-row:hover {
  background: var(--fuy-palette-brand-50);
}

.station-patient-row.is-current {
  background: var(--fuy-palette-brand-100);
}

.station-patient-row.is-current::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: var(--fuy-radius-full);
  background: var(--fuy-color-brand);
}

.station-patient-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.station-visit-id {
  font-weight: 700;
  color: var(--fuy-color-text-emphasis);
}

.station-bed-no {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}

.station-patient-flags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-1);
}

/* 欠费标识（红字描边弱底，语义=欠费提醒不拦阻） */
.station-arrears-flag {
  padding: 0 var(--fuy-space-1);
  border: 1px solid var(--fuy-color-danger-text);
  border-radius: var(--fuy-radius-sm);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-danger-text);
}

/* 过敏标识沿用全局 fuy-nursing-flag 工具类（红底白字），仅补间距 */
.station-allergy-flag {
  margin-left: auto;
}

/* 中列上下文头 */
.station-context-head {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

.station-context-visit {
  font-size: var(--fuy-font-size-2xl);
  font-weight: 700;
  color: var(--fuy-color-text-emphasis);
}

.station-context-bed {
  color: var(--fuy-color-text-secondary);
}

.station-orders {
  min-height: 120px;
}

/* 医嘱号点选（追溯入口，行内 link 语义） */
.station-order-row {
  cursor: pointer;
  color: var(--fuy-color-brand);
}

/* 开立表单块 */
.station-create-block {
  margin-top: var(--fuy-space-4);
  padding-top: var(--fuy-space-3);
  border-top: 1px solid var(--el-border-color);
}

.station-block-title {
  margin: 0 0 var(--fuy-space-2);
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.station-create-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--fuy-space-3);
  margin-bottom: var(--fuy-space-3);
}

.station-create-field {
  min-width: 150px;
}

.station-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 表单基元：native input/select 对齐 WardBoardView 形态（token 取色） */
.station-input {
  width: 100%;
  box-sizing: border-box;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

.station-radio-row {
  display: flex;
  gap: var(--fuy-space-3);
}

.station-radio-item {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.station-standby-item {
  padding-bottom: 5px;
}

/* 成组行编辑：行内横排基元，编码/名称占主宽 */
.station-item-row {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  margin-bottom: var(--fuy-space-2);
}

.station-item-seq {
  min-width: 16px;
  text-align: right;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.station-item-code {
  flex: 2;
  min-width: 0;
}

.station-item-name {
  flex: 2;
  min-width: 0;
}

.station-item-dosage,
.station-item-unit,
.station-item-route {
  flex: 1;
  min-width: 0;
}

.station-item-quantity {
  flex: 1;
  min-width: 72px;
}

.station-item-ops {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-3);
  margin-bottom: var(--fuy-space-2);
}

.station-group-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.station-save {
  width: 100%;
}

/* 右栏闭环追溯时间线：左缘线 + 环节行 */
.station-trace {
  min-height: 200px;
}

.station-trace-order {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  margin: 0 0 var(--fuy-space-3);
  font-weight: 700;
  color: var(--fuy-color-text-emphasis);
}

.station-trace-list {
  position: relative;
  margin: 0;
  padding: 0 0 0 var(--fuy-space-4);
  list-style: none;
  border-left: 2px solid var(--fuy-palette-brand-200);
}

.station-trace-item {
  position: relative;
  padding: 0 0 var(--fuy-space-3) var(--fuy-space-2);
}

.station-trace-item::before {
  content: '';
  position: absolute;
  left: calc(-1 * var(--fuy-space-4) - 5px);
  top: 4px;
  width: 8px;
  height: 8px;
  border-radius: var(--fuy-radius-full);
  background: var(--fuy-color-brand);
}

.station-trace-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.station-trace-stage {
  font-weight: 600;
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.station-trace-time {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.station-trace-body {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-2);
  margin-top: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
}

.station-trace-result {
  color: var(--fuy-color-success-text);
}

.station-trace-detail {
  color: var(--fuy-color-text-secondary);
}
</style>

<script setup lang="ts">
// 药房住院摆药页（/pharmacy/inpatient-dispense，PR-3 Task 16，FU-M06-05 前端面）三区布局：
// ①医嘱检索区（m04OrderNo 输入→审方状态回显[复用 review-tasks 面：后端清单端点无医嘱号
// 过滤参数，拉取后前端本地匹配]→审方通过后生成计划按钮——APPROVED 前置零出网拦截，后端
// 同语义守卫兜底）②计划看板五状态列分栏（CREATED/PICKING/PICKED/CHECKED/DELIVERED 行卡=
// 计划号/患者号面/给药时点/类型标签[单剂量/PIVAS/整包]+PIVAS 排批；CANCELLED 作废态与退药
// 态不进列——退药态由调剂行承载，计划行不迁）③操作流（行内按状态出下一动作按钮五步在途
// 互斥；PIVAS 行出贴签查看弹窗[label 数据面，床号无 pharmacy 侧数据源恒空——展示即所得]；
// DELIVERED 行退药弹窗直调 pharmacy dispense-returns 住院扩展形态[nursing 侧退药开关校验
// 端点未建，Task 8 minor③ 注记——前端注记不越界实现]）。摆药五步状态机以后端
// DispenseStatus 住院链实测词表为准；deliver 为 CHECKED 态内配送交接时间线半步不迁状态
// （Task 8 裁决）——行内动作成功后一律重拉看板、不本地迁移状态（服务端回包为唯一状态源）。
// 贴签弹窗打开先清旧数据+回包比对当前计划号（EX-45/FE-A1-04 纪律——Task 14 P1-1 同族）。
// 失败弹错归响应拦截器（AxiosError 防双弹，业务拒绝对象由 surfaceBizError 兜底）。
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import {
  DISPENSE_PLAN_COLUMNS,
  DISPENSE_PLAN_TYPE_LABELS,
  REVIEW_TASK_STATUS_OPTIONS,
  createDispenseReturn,
  dispensePlans,
  reviewTasks,
} from '@/api/pharmacy';
import type { DispensePlanLabelVO, DispensePlanVO, ReviewTaskVO } from '@/api/pharmacy';
import { WARD_OPTIONS } from '@/api/nursing';
import { usePagedList } from '@/composables/usePagedList';
import { useAuthStore } from '@/stores/auth';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

/* ==================== ① 医嘱检索区 ==================== */
/** 当前病区（会话内记忆：切换/刷新不回默认病区，口径同护理执行工作台） */
const wardId = ref(sessionStorage.getItem('pharmacy.dispense.wardId') ?? WARD_OPTIONS[0].code);

/** 医嘱号检索输入（审方状态回显与计划生成共用锚） */
const orderNoInput = ref('');

/** 审方任务检索在途标志 */
const reviewLoading = ref(false);
/** 检索命中的审方任务（null=未检索或无命中） */
const reviewStatus = ref<ReviewTaskVO | null>(null);

/**
 * 检索审方状态：review-tasks 清单端点无医嘱号过滤参数（后端 GET 只支持 status/page/size），
 * 拉取首页百条后本地匹配 m04OrderNo（演示量级口径）；无命中提示并清空回显区。
 */
async function onSearchReview(): Promise<void> {
  const no = orderNoInput.value.trim();
  if (no === '') {
    void ElMessage.warning('请输入住院医嘱号');
    return;
  }
  reviewLoading.value = true;
  try {
    const page = await reviewTasks.list({ page: 0, size: 100 });
    reviewStatus.value = (page.content ?? []).find((row) => row.m04OrderNo === no) ?? null;
    if (reviewStatus.value === null) {
      void ElMessage.warning('未找到该医嘱的审方任务（先经住院审方台处置）');
    }
  } catch {
    // 失败弹错归响应拦截器；驻留旧回显
  } finally {
    reviewLoading.value = false;
  }
}

/** 审方状态展示文案（词表命中回显，未知状态原样透出防后端扩态白屏） */
const reviewStateText = computed(() => {
  if (reviewStatus.value === null) {
    return '';
  }
  return (
    REVIEW_TASK_STATUS_OPTIONS.find((item) => item.code === reviewStatus.value?.status)?.label ??
    reviewStatus.value.status ??
    ''
  );
});

/** 计划生成在途标志（防双击二次出网） */
const generating = ref(false);

/**
 * 生成摆药计划：审方通过前置（reviewStatus 命中且 APPROVED——零出网前置拦截，后端
 * APPROVED 守卫兜底）→ 出网 generate（携医嘱号+目标病区，病区由发起端显式声明——后端
 * 裁决口径）→ 提示新建条数并重拉看板。
 */
async function onGenerate(): Promise<void> {
  if (generating.value) {
    return;
  }
  const no = orderNoInput.value.trim();
  if (no === '') {
    void ElMessage.warning('请先输入住院医嘱号');
    return;
  }
  if (reviewStatus.value?.status !== 'APPROVED') {
    void ElMessage.warning('医嘱审方通过后才可生成摆药计划');
    return;
  }
  generating.value = true;
  try {
    const plans = await dispensePlans.generate({ m04OrderNo: no, wardId: wardId.value });
    void ElMessage.success(`摆药计划已生成（该医嘱共 ${plans.length} 条）`);
    await loadBoard();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    generating.value = false;
  }
}

/* ==================== ② 计划看板（五状态列分栏） ==================== */
/** 看板清单（EX-49 范式：固定首页清单形态——page 恒 0、size 200 直出，无翻页 UI；
 * 病区过滤归本页持有经快照工厂实时取值合并出网） */
const {
  rows,
  loading: boardLoading,
  fetch: loadBoard,
} = usePagedList({
  params: () => ({ wardId: wardId.value === '' ? undefined : wardId.value }),
  fetcher: (query) => dispensePlans.list(query),
  pageSize: 200,
});

/** 五列看板（终态外五活动态逐列分栏；列内按后端返回序——planTime 升序由后端承载） */
const columns = computed(() =>
  DISPENSE_PLAN_COLUMNS.map((column) => ({
    ...column,
    rows: rows.value.filter((row) => row.status === column.status),
  })),
);

/** 病区切换：会话记忆 + 看板重拉 */
function onWardChange(): void {
  sessionStorage.setItem('pharmacy.dispense.wardId', wardId.value);
  void loadBoard();
}

/** 行卡类型标签（三值词表命中回显，未知类型原样透出防白屏） */
function typeLabel(row: DispensePlanVO): string {
  return DISPENSE_PLAN_TYPE_LABELS[row.planType ?? ''] ?? row.planType ?? '';
}

/* ==================== ③ 操作流（五步在途互斥） ==================== */
/** 行内动作编码（五步 + 贴签查看 + 退药入口；按钮按状态机出位） */
type PlanAction = 'pick' | 'verify' | 'issue' | 'deliver' | 'receive' | 'label' | 'return';

/** 行内动作在途锚（当前在途计划号；空=无在途——五步互斥防双击二次出网） */
const actingNo = ref('');

/**
 * 状态-动作矩阵（spec 冻结断言，以后端 DispenseStatus 实测迁移为准）：CREATED=摆药开始；
 * PICKING=药师核对；PICKED=出库交接；CHECKED 按 issuedAt 分位——配送交接半步前置（后端
 * receive 要求 issuedAt 非空 PH-1026），未配送出「配送交接」、已配送出「病区签收」；
 * DELIVERED=退药入口；PIVAS 行（任意状态）附贴签查看（查看类置前、写操作置后）。
 */
function rowActions(row: DispensePlanVO): PlanAction[] {
  const actions: PlanAction[] = [];
  if (row.planType === 'PIVAS') {
    actions.push('label');
  }
  switch (row.status) {
    case 'CREATED':
      actions.push('pick');
      break;
    case 'PICKING':
      actions.push('verify');
      break;
    case 'PICKED':
      actions.push('issue');
      break;
    case 'CHECKED':
      actions.push((row.issuedAt ?? '') === '' ? 'deliver' : 'receive');
      break;
    case 'DELIVERED':
      actions.push('return');
      break;
    default:
      break;
  }
  return actions;
}

/** 行内动作按钮文案词表 */
const ACTION_LABELS: Record<PlanAction, string> = {
  pick: '摆药开始',
  verify: '药师核对',
  issue: '出库交接',
  deliver: '配送交接',
  receive: '病区签收',
  label: '贴签',
  return: '退药',
};

/**
 * 摆药流前三步统一通道（pick/verify/issue——无入参三步同构）：在途互斥早退守卫 → 出网 →
 * 重拉看板。成功后不本地迁移状态（服务端回包为唯一状态源——deliver 半步裁决同纪律）。
 */
async function runStep(row: DispensePlanVO, action: 'pick' | 'verify' | 'issue'): Promise<void> {
  if (actingNo.value !== '') {
    return;
  }
  const no = row.planNo ?? '';
  actingNo.value = no;
  try {
    if (action === 'pick') {
      await dispensePlans.pick(no);
      void ElMessage.success(`已开始摆药：${no}`);
    } else if (action === 'verify') {
      await dispensePlans.verify(no);
      void ElMessage.success(`药师核对通过：${no}`);
    } else {
      await dispensePlans.issue(no);
      void ElMessage.success(`已出库交接：${no}`);
    }
    await loadBoard();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    actingNo.value = '';
  }
}

/**
 * 配送交接（CHECKED 态内时间线半步）：不迁移状态（Task 8 裁决——receive 才迁 DELIVERED），
 * 成功提示注记语义；重拉看板后行仍在已核对待交接列、按钮切换为病区签收（issuedAt 已置）。
 */
async function onDeliver(row: DispensePlanVO): Promise<void> {
  if (actingNo.value !== '') {
    return;
  }
  const no = row.planNo ?? '';
  actingNo.value = no;
  try {
    await dispensePlans.deliver(no);
    void ElMessage.success(`已配送交接：${no}（状态保持待交接，病区签收后迁移）`);
    await loadBoard();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    actingNo.value = '';
  }
}

/** 行内动作分派（按钮点击统一出口） */
function onRowAction(row: DispensePlanVO, action: PlanAction): void {
  switch (action) {
    case 'pick':
    case 'verify':
    case 'issue':
      void runStep(row, action);
      break;
    case 'deliver':
      void onDeliver(row);
      break;
    case 'receive':
      openReceive(row);
      break;
    case 'label':
      void openLabel(row);
      break;
    case 'return':
      openReturn(row);
      break;
  }
}

/* ==================== 病区签收弹窗（零手输——签收人=会话身份） ==================== */
const auth = useAuthStore();
const receiveVisible = ref(false);
const receiving = ref(false);
/** 签收目标行（弹窗期间行锚） */
const receiveTarget = ref<DispensePlanVO | null>(null);

/** 签收人展示文案（当前登录人 displayName 优先、缺省回退 userId——W-72 签收人取会话身份） */
const receiverText = computed(
  () => auth.user?.displayName ?? auth.user?.userId ?? '—（未登录）',
);

/** 打开签收弹窗（零手输：签收人以会话身份展示回显，无录入面） */
function openReceive(row: DispensePlanVO): void {
  receiveTarget.value = row;
  receiveVisible.value = true;
}

/**
 * 确认签收：签收人取会话身份（W-72——服务端一律以令牌身份落值，无手输面）；会话缺
 * 身份显式拦截零出网（循 PdaView requireExecutorId 同款口径）→ 出网 receive（receivedBy
 * 携会话 userId 兼容保留）→ 关窗重拉看板。入口在途早退守卫防双击重复签收。
 */
async function onReceive(): Promise<void> {
  if (receiving.value) {
    return;
  }
  const receivedBy = auth.user?.userId ?? '';
  if (receivedBy === '') {
    void ElMessage.warning('会话缺少签收人身份，无法签收（请重新登录后再试）');
    return;
  }
  receiving.value = true;
  try {
    await dispensePlans.receive(receiveTarget.value?.planNo ?? '', { receivedBy });
    void ElMessage.success(`病区已签收：${receiveTarget.value?.planNo ?? ''}`);
    receiveVisible.value = false;
    await loadBoard();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    receiving.value = false;
  }
}

/* ==================== PIVAS 贴签弹窗（label 数据面） ==================== */
const labelVisible = ref(false);
const labelLoading = ref(false);
/** 贴签目标行（EX-45 回包比对锚） */
const labelTarget = ref<DispensePlanVO | null>(null);
/** 贴签数据面（null=未回包，弹窗内 v-loading 兜底） */
const labelData = ref<DispensePlanLabelVO | null>(null);

/**
 * 打开贴签弹窗并拉取数据面：换行先清旧数据（防 A 行贴签驻留串台到 B 行计划号名下），
 * 回包先比对当前目标计划号再落值——回包期间换行即在途回包过期直接丢弃（EX-45/FE-A1-04
 * 纪律，Task 14 P1-1 同族防线）。
 */
async function openLabel(row: DispensePlanVO): Promise<void> {
  const no = row.planNo ?? '';
  labelTarget.value = row;
  // 换行先清旧：B 行数据未回包前弹窗数据缺位不渲染，防 A 行数据串台
  labelData.value = null;
  labelVisible.value = true;
  labelLoading.value = true;
  try {
    const result = await dispensePlans.label(no);
    // 过期回包丢弃：换行后目标计划号已变，旧计划回包不得落值
    if (labelTarget.value?.planNo === no) {
      labelData.value = result;
    }
  } catch {
    // 失败弹错归响应拦截器；弹窗数据缺位占位
  } finally {
    labelLoading.value = false;
  }
}

/* ==================== 退药弹窗（dispense-returns 住院扩展形态） ==================== */
const returnVisible = ref(false);
const returning = ref(false);
/** 退药目标行（弹窗期间行锚） */
const returnTarget = ref<DispensePlanVO | null>(null);
/** 医嘱明细序号录入（退药锚定=医嘱明细 itemSeq，纯数字） */
const returnItemSeq = ref('');
/** 退药数量录入（DECIMAL string 承载，禁 number 转换） */
const returnQuantity = ref('');
/** 追溯码录入（逗号/空格分隔；住院摆药采集为空集——后端非空码即拒，正常留空） */
const returnTraceCodes = ref('');

/** 打开退药弹窗（复位录入） */
function openReturn(row: DispensePlanVO): void {
  returnTarget.value = row;
  returnItemSeq.value = '';
  returnQuantity.value = '';
  returnTraceCodes.value = '';
  returnVisible.value = true;
}

/**
 * 确认退药：明细序号纯数字+数量数字必填显式校验（零出网）→ 出网 createDispenseReturn
 * 住院扩展形态（dispensePlanNo+returnLines，不带门诊 mode/items 面）→ 关窗重拉看板。
 * 注记：nursing 侧退药开关校验端点未建（Task 8 minor③），本入口直调 pharmacy 端点；
 * 受理成功后计划行仍处病区已签收列（退药态由调剂行承载，计划行不迁——后端裁决口径）。
 */
async function onReturn(): Promise<void> {
  if (returning.value) {
    return;
  }
  const seq = returnItemSeq.value.trim();
  const quantity = returnQuantity.value.trim();
  if (!/^\d+$/.test(seq)) {
    void ElMessage.warning('医嘱明细序号须为纯数字');
    return;
  }
  if (quantity === '' || Number.isNaN(Number(quantity))) {
    void ElMessage.warning('退药数量须为数字');
    return;
  }
  returning.value = true;
  try {
    await createDispenseReturn({
      dispensePlanNo: returnTarget.value?.planNo ?? '',
      returnLines: [
        {
          itemSeq: seq,
          returnQuantity: quantity,
          traceCodes: returnTraceCodes.value.split(/[，,\s]+/).filter(Boolean),
        },
      ],
    });
    void ElMessage.success(`退药受理完成：${returnTarget.value?.planNo ?? ''}`);
    returnVisible.value = false;
    await loadBoard();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    returning.value = false;
  }
}

onMounted(() => {
  void loadBoard();
});
</script>

<template>
  <div class="fuy-page dispense-plan fuy-stagger">
    <!-- ① 医嘱检索区：病区 / 医嘱号检索 / 审方状态回显 / 生成计划 / 刷新 -->
    <header class="plan-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="plan-title">药房住院摆药台</h2>
      <span class="plan-hint">摆药五步：摆药开始 → 药师核对 → 出库交接 → 配送交接 → 病区签收</span>
      <el-select v-model="wardId" class="plan-ward" @change="onWardChange">
        <el-option
          v-for="ward in WARD_OPTIONS"
          :key="ward.code"
          :label="ward.label"
          :value="ward.code"
        />
      </el-select>
      <el-input
        v-model="orderNoInput"
        placeholder="住院医嘱号"
        class="plan-order-input"
        clearable
        @keyup.enter="onSearchReview"
      />
      <el-button :loading="reviewLoading" @click="onSearchReview">检索审方</el-button>
      <!-- 审方状态回显（号面脱敏口径：医嘱号/患者号面/状态/明细） -->
      <span v-if="reviewStatus !== null" class="plan-review-state">
        <span class="fuy-num">{{ reviewStatus.m04OrderNo }}</span>
        审方：
        <el-tag size="small" class="fuy-tag-aa">{{ reviewStateText }}</el-tag>
        <span class="plan-review-items" :title="reviewStatus.items ?? ''">{{
          reviewStatus.items ?? '—'
        }}</span>
      </span>
      <el-button
        type="primary"
        :loading="generating"
        :disabled="reviewStatus?.status !== 'APPROVED'"
        @click="onGenerate"
        >生成摆药计划</el-button
      >
      <el-button :loading="boardLoading" @click="loadBoard">刷新</el-button>
    </header>

    <!-- ② 五状态列看板（CANCELLED/退药态不进列；列内按后端返回序=planTime 升序） -->
    <div
      v-loading="boardLoading"
      class="plan-board fuy-stagger"
      :style="{ '--fuy-stagger-index': 1 }"
    >
      <section
        v-for="column in columns"
        :key="column.status"
        class="plan-column"
        :aria-label="column.label"
      >
        <header class="plan-column-head">
          <span class="plan-column-title">{{ column.label }}</span>
          <span class="fuy-num plan-column-count">{{ column.rows.length }}</span>
        </header>
        <div class="plan-column-body">
          <div v-for="row in column.rows" :key="row.planNo" class="plan-card">
            <div class="plan-card-head">
              <span class="fuy-num plan-card-no" :title="row.planNo ?? ''">{{
                row.planNo ?? '—'
              }}</span>
              <span class="fuy-num plan-card-plan">{{ formatTime(row.planTime) }}</span>
            </div>
            <div
              class="plan-card-patient fuy-num"
              :title="`${row.visitId ?? ''} / ${row.patientId ?? ''} / ${row.wardId ?? ''}`"
            >
              {{ row.visitId ?? '—' }} / {{ row.patientId ?? '—' }} / {{ row.wardId ?? '—' }}
            </div>
            <div class="plan-card-badges">
              <span class="plan-badge plan-badge--type">{{ typeLabel(row) }}</span>
              <span
                v-if="(row.pivasBatchNo ?? '') !== ''"
                class="plan-badge plan-badge--pivas fuy-num"
                >排批 {{ row.pivasBatchNo }}</span
              >
            </div>
            <!-- 行内动作组（按状态机出位；在途互斥：当前行 loading、他行 disabled） -->
            <div class="plan-card-actions">
              <el-button
                v-for="action in rowActions(row)"
                :key="action"
                :type="action === 'return' ? 'danger' : 'primary'"
                link
                size="small"
                :loading="actingNo === row.planNo"
                :disabled="actingNo !== '' && actingNo !== row.planNo"
                @click="onRowAction(row, action)"
                >{{ ACTION_LABELS[action] }}</el-button
              >
            </div>
          </div>
          <p v-if="column.rows.length === 0" class="plan-column-empty">—</p>
        </div>
      </section>
      <el-empty
        v-if="columns.every((column) => column.rows.length === 0)"
        :image-size="72"
        description="暂无摆药计划"
        class="plan-board-empty"
      />
    </div>

    <!-- ③ 病区签收弹窗（零手输——签收人=当前登录人会话身份，服务端令牌留痕） -->
    <el-dialog v-model="receiveVisible" title="摆药病区签收" width="420px">
      <p class="plan-dialog-target fuy-num">计划 {{ receiveTarget?.planNo ?? '' }}</p>
      <label class="plan-field-label">签收人</label>
      <p class="plan-receive-receiver fuy-num">
        {{ receiverText }}（{{ auth.user?.userId ?? '—' }}）——当前登录人，服务端留痕
      </p>
      <p class="plan-dialog-hint">未配送不可签收（配送交接为签收必要前置，后端 PH-1026 把守）</p>
      <template #footer>
        <el-button size="small" @click="receiveVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :loading="receiving"
          :disabled="receiving"
          @click="onReceive"
          >确认签收</el-button
        >
      </template>
    </el-dialog>

    <!-- ③ PIVAS 贴签弹窗（label 数据面；床号无 pharmacy 侧数据源恒空——展示即所得） -->
    <el-dialog v-model="labelVisible" title="PIVAS 贴签数据面" width="560px">
      <div v-loading="labelLoading">
        <template v-if="labelData !== null">
          <p class="plan-dialog-target fuy-num">计划 {{ labelData.planNo ?? '—' }}</p>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="医嘱号">
              <span class="fuy-num">{{ labelData.m04OrderNo ?? '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="患者（脱敏）">
              {{ labelData.patientName ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="就诊号">
              <span class="fuy-num">{{ labelData.visitId ?? '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="病区/床号">
              <span class="fuy-num"
                >{{ labelData.wardId ?? '—' }} / {{ labelData.bedNo ?? '—' }}</span
              >
            </el-descriptions-item>
            <el-descriptions-item label="排批号">
              <span class="fuy-num">{{ labelData.pivasBatchNo ?? '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="给药时点">
              <span class="fuy-num">{{ formatTime(labelData.planTime) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="摆药师">
              <span class="fuy-num">{{ labelData.pickedBy ?? '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="核药师">
              <span class="fuy-num">{{ labelData.verifiedBy ?? '—' }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <!-- 贴签药品明细（药名/剂量/单位/途径/数量——贴签「批次」承载=排批号） -->
          <el-table :data="labelData.items ?? []" class="plan-label-items" size="small">
            <el-table-column prop="itemCode" label="编码" min-width="90" />
            <el-table-column prop="itemName" label="药品名称" min-width="160" />
            <el-table-column prop="dosage" label="单次剂量" width="80" align="right" />
            <el-table-column prop="unit" label="单位" width="60" />
            <el-table-column prop="route" label="途径" width="90" />
            <el-table-column prop="quantity" label="摆药数量" width="80" align="right" />
          </el-table>
          <p class="plan-dialog-hint">打印归 M01 打印服务接入（当前为数据面查看）</p>
        </template>
        <p v-else class="plan-dialog-hint">贴签数据加载中…</p>
      </div>
    </el-dialog>

    <!-- ③ 退药弹窗（DELIVERED 行；直调 pharmacy dispense-returns 住院扩展形态） -->
    <el-dialog v-model="returnVisible" title="住院退药受理" width="420px">
      <p class="plan-dialog-target fuy-num">计划 {{ returnTarget?.planNo ?? '' }}</p>
      <label class="plan-field-label">医嘱明细序号（必填，纯数字 itemSeq）</label>
      <input
        v-model="returnItemSeq"
        class="plan-dialog-input plan-return-seq"
        type="text"
        inputmode="numeric"
        autocomplete="off"
        placeholder="如 1"
        aria-label="医嘱明细序号"
      />
      <label class="plan-field-label">退药数量（必填，数字）</label>
      <input
        v-model="returnQuantity"
        class="plan-dialog-input plan-return-qty"
        type="text"
        inputmode="decimal"
        autocomplete="off"
        placeholder="如 2"
        aria-label="退药数量"
      />
      <label class="plan-field-label">追溯码（住院摆药未采集，正常留空；逗号分隔）</label>
      <input
        v-model="returnTraceCodes"
        class="plan-dialog-input plan-return-trace"
        type="text"
        autocomplete="off"
        placeholder="可空"
        aria-label="追溯码"
      />
      <p class="plan-dialog-hint">
        受理成功后计划行仍在病区已签收列（退药态由调剂行承载）；nursing
        侧退药开关校验端点未建，本入口直调药房退药受理
      </p>
      <template #footer>
        <el-button size="small" @click="returnVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :loading="returning"
          :disabled="returning"
          @click="onReturn"
          >提交退药</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* ① 医嘱检索区（工具条：token 取色禁自创色值） */
.plan-toolbar {
  align-items: center;
  flex-wrap: wrap;
  row-gap: var(--fuy-space-2);
}
.plan-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}
.plan-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}
.plan-ward {
  width: 150px;
}
.plan-order-input {
  width: 200px;
}
/* 审方状态回显（超长明细省略，title 悬停全览） */
.plan-review-state {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  max-width: 340px;
  overflow: hidden;
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
  white-space: nowrap;
}
.plan-review-items {
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ② 五列看板（等宽 grid；min-height CLS 锁） */
.plan-board {
  position: relative;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--fuy-space-3);
  min-height: 320px;
}
.plan-column {
  display: flex;
  flex-direction: column;
  min-width: 0;
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-lg);
  background: var(--fuy-palette-gray-50);
}
.plan-column-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-bottom: var(--fuy-border-hairline);
}
.plan-column-title {
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}
.plan-column-count {
  min-width: 18px;
  padding: 0 4px;
  border-radius: var(--fuy-radius-full);
  background: var(--el-color-primary-light-8);
  color: var(--fuy-color-brand);
  font-size: var(--fuy-font-size-xs);
  font-weight: 700;
  text-align: center;
}
.plan-column-body {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
  padding: var(--fuy-space-2);
}
.plan-column-empty {
  margin: 0;
  text-align: center;
  color: var(--fuy-color-text-secondary);
}
/* 行卡（1px 描边白底；信息密度照执行工作台先例） */
.plan-card {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-lg);
  background: var(--el-bg-color);
  transition:
    border-color var(--fuy-motion-fast) linear,
    background-color var(--fuy-motion-fast) linear;
}
.plan-card:hover {
  border-color: var(--fuy-color-brand);
  background: var(--fuy-palette-brand-100);
}
.plan-card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.plan-card-no {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fuy-font-size-sm);
  font-weight: 700;
}
.plan-card-plan {
  flex: none;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.plan-card-patient {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.plan-card-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
/* 标记（类型/排批，token 取色禁自创色值） */
.plan-badge {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  border-radius: var(--fuy-radius-full);
  font-size: var(--fuy-font-size-xs);
  line-height: 1;
}
.plan-badge--type {
  background: var(--el-color-primary-light-8);
  color: var(--fuy-color-brand);
}
.plan-badge--pivas {
  background: var(--el-color-info-light-8);
  color: var(--el-text-color-secondary);
}
.plan-card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-1);
  margin-top: var(--fuy-space-1);
}
.plan-board-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ③ 弹窗（签收/贴签/退药共用录入形态） */
.plan-dialog-target {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}
.plan-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}
.plan-dialog-input {
  width: 100%;
  box-sizing: border-box;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}
.plan-dialog-input:focus-visible {
  outline: 2px solid var(--fuy-color-brand);
  outline-offset: 0;
}
.plan-dialog-input + .plan-field-label {
  margin-top: var(--fuy-space-2);
}
/* 签收人展示行（零手输：会话身份回显只读态，浅灰底示不可编辑） */
.plan-receive-receiver {
  margin: 0;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-palette-gray-50);
}
.plan-dialog-hint {
  margin: var(--fuy-space-2) 0 0;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}
.plan-label-items {
  margin-top: var(--fuy-space-3);
}
</style>

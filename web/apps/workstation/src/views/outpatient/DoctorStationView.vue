<script setup lang="ts">
// 门诊医生站页（FU-M03-05/06 前端面，暖纸卷宗换脸重排，逐页蓝图 P06）：左候诊列表（我的队列，
// 诊区真数据下拉入卡头）→ 接诊 → 中列接诊工作区（患者上下文卡：大号 visitId + 分诊级别徽标 →
// 在诊单据卡：fuy-tabs 卷宗签两签 + 在途数徽标迁卡头 → 底部诊毕收口条：去向 select + 在途确认
// 勾选 + 在途数徽标 + 诊毕 danger plain 钮同行，以「签」分隔线与单据分界——终态不可逆高危动作
// 与在途单据（在途判断依据）同列，收口动线终点）→ 右列开检查检验单 / 开处方两折叠卡（M06 衔接
// openPrescription）。假常量清零：诊区文本输入 → listOrgs({ type: 'DEPT' }) 真数据下拉（未选
// 诊区前置拦截零出网）；离院去向假常量 → listDictItems('outpatient.disposition')
// 真字典三态（typeCode 以 V705 门诊字典种子登记为准；字典失败/空清单时去向选择禁用、诊毕随
// canFinish 禁用——数据态守卫不变）。动效照 §8.3 表：进场 stagger / 候诊列表 TransitionGroup
// fuy-flip / 开单表单 v-show+scaleY 200ms / 诊毕后中列 fade-out 120ms（收口条随中列同退）。
// 动作在途守卫先于一切 await；失败弹错归响应拦截器。
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（billing 三页同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import {
  admitVisit,
  createOrder,
  finishVisit,
  listOrdersByVisit,
  listPatientQueue,
  openPrescription,
} from '@/api/outpatient';
import type {
  ClinicOrderVO,
  DoctorQueueItemVO,
  FinishVisitRequest,
  VisitVO,
} from '@/api/outpatient';
import { listDictItems, listOrgs } from '@/api/system';
import type { DictItemVO, OrgVO } from '@/api/system';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { useAuthStore } from '@/stores/auth';

/** 诊毕离院去向字典类型编码（V705 门诊字典种子登记 typeCode=outpatient.disposition，真数据单源） */
const DISPOSITION_DICT_TYPE = 'outpatient.disposition';

/** 开单单据类型词表（ClinicOrderVO orderType 枚举的医生站可用面，RX_REF 由开方自动生成） */
const ORDER_TYPE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'EXAM', label: '检查' },
  { code: 'LAB', label: '检验' },
  { code: 'TREATMENT', label: '治疗' },
  { code: 'DISPOSAL', label: '处置' },
  { code: 'MATERIAL', label: '卫材' },
];

/** 申请单状态中文词表（ClinicOrderVO status 五值展示映射） */
const ORDER_STATUS_LABELS: Record<string, string> = {
  CREATED: '已开立',
  PENDING_FEE: '待缴费',
  CHARGED: '已缴费',
  IN_EXECUTION: '执行中',
  COMPLETED: '已完成',
  CANCELLED: '已作废',
};

/** 门诊侧发药镜像状态中文词表（W-29 D-3 契约消费：ClinicOrderVO.dispenseStatus 三值，
 * M06 发药/退药回执回流镜像；照药房工作台单状态词表先例形态） */
const DISPENSE_MIRROR_LABELS: Record<string, string> = {
  DISPENSED: '已发药',
  PART_RETURNED: '部分退药',
  FULL_RETURNED: '全额退药',
};

/** 发药镜像状态 tag 色型映射（照 DispenseWorkbenchView 单状态 tag 先例：已发药 success、
 * 部分退药 warning、全额退药 info；未知值不在册→纯文本原样回显不猜色，防后端扩值误导） */
const DISPENSE_MIRROR_TAG_TYPES: Record<string, 'success' | 'warning' | 'info'> = {
  DISPENSED: 'success',
  PART_RETURNED: 'warning',
  FULL_RETURNED: 'info',
};

/** 分诊级别徽标文案（Ⅰ危/Ⅱ急/Ⅲ重/Ⅳ普；VisitVO.triageLevel 生成物在位，色值走 §4.3 徽标 token） */
const TRIAGE_LEVEL_LABELS: Record<number, string> = { 1: 'Ⅰ级', 2: 'Ⅱ级', 3: 'Ⅲ级', 4: 'Ⅳ级' };

/**
 * visit 状态标签映射（§4.3「visit 状态沿用映射法」六值原样落码 + 同法延伸三值：
 * IN_EXECUTION/PENDING_MEDICATION 随 PENDING_FEE 归 warning 在途系，NO_SHOW 比照
 * CANCELLED 灰删除线——色型仅复用映射表既有 palette，无自造色）。
 */
const VISIT_STATUS_META: Record<
  string,
  { type: 'primary' | 'warning' | 'success' | 'info'; text: string; strike?: boolean }
> = {
  REGISTERED: { type: 'info', text: '已挂号' },
  WAITING: { type: 'primary', text: '候诊中' },
  IN_CONSULT: { type: 'success', text: '接诊中' },
  PENDING_FEE: { type: 'warning', text: '待缴费' },
  IN_EXECUTION: { type: 'warning', text: '执行中' },
  PENDING_MEDICATION: { type: 'warning', text: '待取药' },
  FINISHED: { type: 'info', text: '已完成' },
  CANCELLED: { type: 'info', text: '已取消', strike: true },
  NO_SHOW: { type: 'info', text: '已失约', strike: true },
};

/** 患者上下文状态 tag 映射（未知态灰底原文回显，防后端扩态白屏） */
function visitStatusMeta(status: string | undefined): {
  type: 'primary' | 'warning' | 'success' | 'info';
  text: string;
  strike?: boolean;
} {
  return VISIT_STATUS_META[status ?? ''] ?? { type: 'info', text: status ?? '—' };
}

/** 就诊类型词表（VisitVO.visitType 六值枚举展示映射，F-8 同款枚举直出修复口径） */
const VISIT_TYPE_LABELS: Record<string, string> = {
  GENERAL: '普通',
  EMERGENCY: '急诊',
  SPECIAL: '专病',
  INTERNET: '互联网',
  MDT: '多学科',
  OTHER: '其他',
};

/** 中列头部级别徽标类（1-4 越界防御：契约值域外不渲染徽标） */
function triageBadgeClass(level: number | undefined): string | null {
  if (level === undefined || level === null || level < 1 || level > 4) {
    return null;
  }
  return `fuy-triage-badge--l${level}`;
}

const auth = useAuthStore();
/** 出诊医生：会话用户 id 与显示名（队列与叫号的双参定位来源） */
const doctorId = computed(() => auth.user?.userId ?? '');
const doctorName = computed(() => auth.user?.displayName ?? '—');

/**
 * 门牌批注行时刻标签（YYYY-MM-DD 周X）：病历页眉日期批注语法，纯本地时钟零出网。
 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/* ---------- 诊区真数据下拉（假常量 DEPT-INT 清零：listOrgs DEPT 单源，三页同款 160px 档） ---------- */
/** 启用诊区清单（listOrgs 真数据；页内三态缓存，挂载拉取一次） */
const deptOptions = ref<OrgVO[]>([]);
const { loading: deptLoading, run: loadDepts } = useAsyncTask(async () => {
  deptOptions.value = await listOrgs({ type: 'DEPT' });
});

/** 诊区编码（patientQueue 双参之一；真数据下拉单源，默认空=未选不出网） */
const deptCode = ref('');
/** 门牌批注行诊区显示名（所选诊区中文名；未选或清单未含时 — 占位零伪数据） */
const deptLabel = computed(
  () => deptOptions.value.find((org) => org.orgCode === deptCode.value)?.orgName ?? '—',
);

/* ---------- 左列：候诊列表（我的队列，行高 56px + current-row 左缘品牌色条） ---------- */
const queue = ref<DoctorQueueItemVO[]>([]);
/** 当前选中候诊行（↑↓/点击双通道；接诊入口） */
const selectedRow = ref<DoctorQueueItemVO | null>(null);
/** 接诊在途标志（左列主按钮 + 中列上下文加载共用） */
const admitting = ref(false);

/** 中列当前接诊上下文（admit 响应 VisitVO；null=未接诊） */
const currentVisit = ref<VisitVO | null>(null);
/** 当前就诊的在诊单据（RX_REF 与检查检验同源列表，tabs 过滤呈现） */
const orders = ref<ClinicOrderVO[]>([]);

/** 候诊行等待时长（分钟，§8.3 行内展示；≥30 分钟预警色与分诊台同口径） */
function waitingMinutes(row: DoctorQueueItemVO): number {
  if (!row.queueTime) {
    return 0;
  }
  return Math.max(0, Math.floor((Date.now() - new Date(row.queueTime).getTime()) / 60000));
}

/** 拉取我的候诊队列（接诊/诊毕后重刷共用；SERVED 行由状态自然灰化下沉）：loading 骨架经
 * useAsyncTask 收拢（EX-42 范式迁移，行为与迁移前一致——失败弹错归响应拦截器、驻留旧
 * 队列）；未登录或未选诊区早退随任务体保留（查询前置拦截，不发无效出网）。 */
const { loading: queueLoading, run: loadQueue } = useAsyncTask(async () => {
  if (doctorId.value === '' || deptCode.value === '') {
    // 未登录会话（路由守卫已拦）或未选诊区：静默驻留空队列，不发无效出网
    queue.value = [];
    return;
  }
  queue.value = await listPatientQueue({
    deptCode: deptCode.value,
    doctorId: doctorId.value,
  });
});

/** 诊区变更：清空上下文并重拉队列 */
function onDeptChange(): void {
  selectedRow.value = null;
  currentVisit.value = null;
  orders.value = [];
  void loadQueue();
}

/** 行点击/键盘移动共用选中入口 */
function selectRow(row: DoctorQueueItemVO): void {
  selectedRow.value = row;
}

/** 列表容器键盘上下文（§5.3：↑↓ 移动选中行，Enter 接诊；输入框聚焦时让位） */
function onQueueKeydown(event: KeyboardEvent): void {
  const target = event.target as HTMLElement | null;
  if (target !== null && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) {
    return;
  }
  if (queue.value.length === 0) {
    return;
  }
  const currentIndex =
    selectedRow.value === null
      ? -1
      : queue.value.findIndex((row) => row.ticketId === selectedRow.value?.ticketId);
  if (event.key === 'ArrowDown') {
    event.preventDefault();
    selectRow(queue.value[Math.min(currentIndex + 1, queue.value.length - 1)] ?? queue.value[0]);
  } else if (event.key === 'ArrowUp') {
    event.preventDefault();
    selectRow(queue.value[Math.max(currentIndex - 1, 0)] ?? queue.value[0]);
  } else if (event.key === 'Enter') {
    event.preventDefault();
    void onAdmit();
  }
}

/**
 * 接诊（§5.2 中风险档：confirm 带患者摘要）→ admit 响应承载 visit 上下文并加载在诊单据。
 * 入口在途早退守卫：重渲染前第二击零出网。
 */
async function onAdmit(): Promise<void> {
  if (admitting.value || selectedRow.value === null) {
    return;
  }
  const row = selectedRow.value;
  admitting.value = true;
  try {
    try {
      await ElMessageBox.confirm(
        `即将接诊 ${row.ticketNo ?? ''} ${row.patientName ?? ''}，确认？`,
        '接诊确认',
      );
    } catch {
      return;
    }
    const visit = await admitVisit(row.visitId ?? '');
    currentVisit.value = visit;
    await loadOrders(visit.visitId ?? '');
    void ElMessage.success(`已接诊：${row.ticketNo ?? ''}`);
    await loadQueue();
  } catch {
    // 失败弹错归响应拦截器（含重复接诊 409：由后端状态机拒绝）
  } finally {
    admitting.value = false;
  }
}

/** 拉取在诊单据（开单/开方/诊毕后重刷共用）：loading 骨架经 useAsyncTask 收拢（EX-42
 * 范式迁移，行为与迁移前一致——失败弹错归响应拦截器、驻留旧单据）。 */
const { loading: ordersLoading, run: loadOrders } = useAsyncTask(async (visitId: string) => {
  orders.value = await listOrdersByVisit({ visitId });
});

/** 检查检验/处置单据（tab 1 呈现面） */
const examOrders = computed(() => orders.value.filter((order) => order.orderType !== 'RX_REF'));
/** 处方引用行（RX_REF，tab 2 呈现面；extRef=M06 rxNo） */
const rxRefOrders = computed(() => orders.value.filter((order) => order.orderType === 'RX_REF'));

/** 在途未结单据数（诊毕确认勾选旁徽标：CREATED/PENDING_FEE/CHARGED 视为在途） */
const ongoingOrderCount = computed(
  () =>
    orders.value.filter((order) =>
      ['CREATED', 'PENDING_FEE', 'CHARGED'].includes(order.status ?? ''),
    ).length,
);

/* ---------- 诊毕去向真字典（离院去向假常量清零：V705 种子 typeCode 真数据） ---------- */
/** 离院去向字典条目（listDictItems 真数据；useAsyncTask 三态缓存于页内，挂载拉取一次） */
const dispositionItems = ref<DictItemVO[]>([]);
const { loading: dispositionLoading, run: loadDispositions } = useAsyncTask(async () => {
  dispositionItems.value = await listDictItems(DISPOSITION_DICT_TYPE);
});
/** 去向选择可用性（三态之禁用态：字典加载失败或空清单=无可选去向；诊毕随 canFinish 禁用） */
const dispositionUnavailable = computed(
  () => !dispositionLoading.value && dispositionItems.value.length === 0,
);

/* ---------- 右列：开检查检验单（项目码+数量，quantity 显式整数校验 W-22⑦） ---------- */
const orderFormVisible = ref(false);
const orderType = ref('EXAM');
const itemCode = ref('');
/** 数量表单态（el-input-number 承载；@change 显式 Number.isInteger 校验，禁裸 parse） */
const quantity = ref<number>(1);
const orderSubmitting = ref(false);

/**
 * el-input-number 变更回调：非整数即置回 1 并 4xx 口径提示（W-22⑦ 禁裸 parse——
 * 步长 1 仍可能经键入产生小数，提交前 @change 双触发兜底）。
 */
function onQuantityChange(): void {
  if (!Number.isInteger(quantity.value) || quantity.value <= 0) {
    quantity.value = 1;
    void ElMessage.warning('数量应为正整数，请核对后重试');
  }
}

/**
 * 提交开单：展开表单 → 校验 → 出网 → 单据行落位中列 tabs（fuy-flip enter 语义由列表刷新承载）。
 * 入口在途早退守卫防双击重复开单。
 */
async function onSubmitOrder(): Promise<void> {
  if (orderSubmitting.value || currentVisit.value === null) {
    return;
  }
  if (itemCode.value.trim() === '') {
    void ElMessage.warning('请填写项目编码');
    return;
  }
  if (!Number.isInteger(quantity.value) || quantity.value <= 0) {
    void ElMessage.warning('数量应为正整数，请核对后重试');
    return;
  }
  orderSubmitting.value = true;
  try {
    await createOrder(currentVisit.value.visitId ?? '', {
      orderType: orderType.value,
      items: [{ itemCode: itemCode.value.trim(), quantity: String(quantity.value) }],
    });
    void ElMessage.success('开单完成');
    itemCode.value = '';
    quantity.value = 1;
    orderFormVisible.value = false;
    await loadOrders(currentVisit.value.visitId ?? '');
  } catch {
    // 失败弹错归响应拦截器；表单驻留供重试
  } finally {
    orderSubmitting.value = false;
  }
}

/* ---------- 右列：开处方（M06 衔接 openPrescription，表单形态沿用 pharmacy 处方表） ---------- */
const rxFormVisible = ref(false);
/** 处方类型词表（OUTPATIENT/EMERGENCY，词表外由 pharmacy 主链显式拒 400） */
const rxType = ref('OUTPATIENT');
const skinTestRequired = ref(false);
const drugIdInput = ref('');
/** 处方数量表单态（与开单同款显式整数校验） */
const rxQuantity = ref<number>(1);
const frequencyInput = ref('');
const rxSubmitting = ref(false);

function onRxQuantityChange(): void {
  if (!Number.isInteger(rxQuantity.value) || rxQuantity.value <= 0) {
    rxQuantity.value = 1;
    void ElMessage.warning('数量应为正整数，请核对后重试');
  }
}

/**
 * 提交开方：经门诊 M06 衔接端点（执业授权强校验后端承载，成功返回 RX_REF 引用单）。
 * 入口在途早退守卫防双击重复开方。
 */
async function onSubmitRx(): Promise<void> {
  if (rxSubmitting.value || currentVisit.value === null) {
    return;
  }
  if (drugIdInput.value.trim() === '') {
    void ElMessage.warning('请填写药品 ID');
    return;
  }
  if (!Number.isInteger(rxQuantity.value) || rxQuantity.value <= 0) {
    void ElMessage.warning('数量应为正整数，请核对后重试');
    return;
  }
  rxSubmitting.value = true;
  try {
    await openPrescription(currentVisit.value.visitId ?? '', {
      rxType: rxType.value,
      skinTestRequired: skinTestRequired.value,
      items: [
        {
          drugId: drugIdInput.value.trim(),
          quantity: String(rxQuantity.value),
          frequency: frequencyInput.value.trim() === '' ? undefined : frequencyInput.value.trim(),
        },
      ],
    });
    void ElMessage.success('处方开立完成');
    drugIdInput.value = '';
    rxQuantity.value = 1;
    frequencyInput.value = '';
    rxFormVisible.value = false;
    await loadOrders(currentVisit.value.visitId ?? '');
  } catch {
    // 失败弹错归响应拦截器（含执业授权未过 PH-1018）
  } finally {
    rxSubmitting.value = false;
  }
}

/* ---------- 诊毕收口条：去向八项真字典 + 在途单据显式确认 + danger 确认弹窗 ---------- */
const disposition = ref('');
const explicitConfirm = ref(false);
const finishing = ref(false);

/**
 * 诊毕可提交（§8.3「在途单据未确认禁用」语义）：去向必选 + 仅当存在在途单据时要求显式勾选确认。
 * 无在途单据（纯问诊，门诊最常见路径）或单据全终态时短路放行——勾选框此时禁用（无可确认项，
 * 勾选无意义）但不得阻断诊毕；有在途单据未勾选则按钮禁用（后端 OP-1011 同语义双保险）。
 * 字典失败/空清单时去向恒空，canFinish 恒假——诊毕随数据态禁用（守卫不变）。
 */
const canFinish = computed(
  () => disposition.value !== '' && (ongoingOrderCount.value === 0 || explicitConfirm.value),
);

/**
 * 诊毕（§5.2 高风险档：danger 确认弹窗带回显摘要）→ 成功后中列 fade-out（收口条随退）、
 * 表单复位、左列重刷（原行 SERVED 灰化下沉）。M09 文书校验为提醒不拦截（注记文案随卡展示）。
 */
async function onFinish(): Promise<void> {
  if (finishing.value || currentVisit.value === null || !canFinish.value) {
    return;
  }
  const visit = currentVisit.value;
  const label =
    dispositionItems.value.find((item) => item.itemCode === disposition.value)?.itemName ??
    disposition.value;
  finishing.value = true;
  try {
    try {
      await ElMessageBox.confirm(
        `即将完成就诊 ${visit.visitId ?? ''}（去向：${label}），诊毕后不可恢复，确认？`,
        '诊毕确认',
        {
          type: 'warning',
          confirmButtonText: '确认诊毕',
          // W-26：取消按钮补中文字案（EP 默认英文 Cancel）
          cancelButtonText: '取消',
          // 诊毕属 §5.2 高风险档（终态不可恢复）：确认按钮 danger 红样式承载不可逆警示
          confirmButtonClass: 'el-button--danger',
        },
      );
    } catch {
      return;
    }
    const payload: FinishVisitRequest = { disposition: disposition.value, explicitConfirm: true };
    await finishVisit(visit.visitId ?? '', payload);
    void ElMessage.success('诊毕完成');
    // 中列 fade-out 120ms（§8.3）后清空上下文与表单复位
    await resetConsultContext();
    await loadQueue();
  } catch {
    // 失败弹错归响应拦截器（含在途单据未结 OP-1011）
  } finally {
    finishing.value = false;
  }
}

/** 中列内容淡出后再清空（fuy-content-fade leave 120ms，scoped 过渡类承载） */
const contextVisible = ref(true);

async function resetConsultContext(): Promise<void> {
  contextVisible.value = false;
  await new Promise<void>((resolve) => setTimeout(resolve, 120));
  currentVisit.value = null;
  orders.value = [];
  selectedRow.value = null;
  disposition.value = '';
  explicitConfirm.value = false;
  contextVisible.value = true;
}

onMounted(() => {
  // 未选诊区时队列被前置拦截短路（驻留空队列）；诊区清单与去向字典真数据并行预拉（页内三态缓存）
  void loadQueue();
  void loadDepts();
  void loadDispositions();
});
</script>

<template>
  <div class="fuy-page doctor-station">
    <!-- 门牌页首（契约 ⑧.1）：衬线标题 + 接诊中状态胶囊（未接诊不显）+ 出诊医生·诊区·时刻
         批注行 + 2px 墨规收底（脸样式归全局 .fuy-page-head 族，本页零私有标题样式） -->
    <header class="fuy-page-head">
      <div class="fuy-page-head-main">
        <h1 class="fuy-page-title">门诊医生站</h1>
      </div>
      <div class="fuy-page-status">
        <span v-if="currentVisit !== null" class="fuy-status-pill fuy-status-pill--success">
          接诊中 · <span class="fuy-num">{{ currentVisit.visitId }}</span>
        </span>
      </div>
      <p class="fuy-page-note">
        出诊医生 {{ doctorName }} · 诊区 {{ deptLabel }} · <time>{{ todayLabel }}</time>
      </p>
    </header>

    <el-row :gutter="16" class="fuy-stagger">
      <!-- 左：候诊列表（6/12/6 三栏之 6；行高 56px + current-row 左缘 3px 品牌色条） -->
      <el-col :md="24" :lg="6" :style="{ '--fuy-stagger-index': 0 }">
        <section class="fuy-card">
          <!-- 卡头：标题 + 诊区真数据下拉入既有位（域级 160px 档三页同款，popper 硬 snap） -->
          <div class="fuy-card-head">
            <h2 class="fuy-card-title">候诊列表</h2>
            <el-select
              v-model="deptCode"
              class="doctor-station-dept"
              placeholder="选择诊区"
              :loading="deptLoading"
              popper-class="fuy-snap-popper"
              @change="onDeptChange"
            >
              <el-option
                v-for="org in deptOptions"
                :key="org.orgCode"
                :label="org.orgName"
                :value="org.orgCode ?? ''"
              />
              <!-- 下拉空态给引导（清单为空=无启用诊区，指向系统管理维护路径） -->
              <template #empty>
                <p class="doctor-station-dept-empty">暂无启用诊区，请联系管理员维护科室档案</p>
              </template>
            </el-select>
          </div>
          <div class="fuy-card-body">
            <!-- 接诊主按钮（顶部常驻 §3.2）；医生接诊码（PR-4F #10）v-perm 直挂——与
                 admitting 在途/选中行数据态 :disabled 正交叠加 -->
            <el-button
              v-perm="'outpatient:doctor:btn:admit'"
              type="primary"
              class="doctor-station-admit-btn"
              :loading="admitting"
              :disabled="admitting || selectedRow === null"
              @click="onAdmit"
              >接诊</el-button
            >
            <!-- 虚拟就绪列表（单医生队列天然 <50 直渲染 §7.2）；↑↓/Enter 键盘通道 §5.3 -->
            <div
              class="doctor-station-queue"
              tabindex="0"
              v-loading="queueLoading"
              @keydown="onQueueKeydown"
            >
              <TransitionGroup name="fuy-flip" tag="ul" class="doctor-station-queue-list">
                <li
                  v-for="row in queue"
                  :key="row.ticketId"
                  class="doctor-station-queue-row"
                  :class="{
                    'is-current': selectedRow?.ticketId === row.ticketId,
                    'is-served': row.status === 'SERVED',
                  }"
                  @click="selectRow(row)"
                >
                  <span class="fuy-num doctor-station-ticket">{{ row.ticketNo }}</span>
                  <span class="doctor-station-name">{{ row.patientName }}</span>
                  <!-- DoctorQueueItemVO 无 triageLevel 字段（契约面缺失，禁虚构）——以优先级分承载 -->
                  <span class="fuy-num doctor-station-score">{{ row.priorityScore }}</span>
                  <span
                    class="fuy-num doctor-station-wait"
                    :class="{ 'is-long-wait': waitingMinutes(row) >= 30 }"
                    >{{ waitingMinutes(row) }}′</span
                  >
                </li>
              </TransitionGroup>
              <!-- 空态两态门控（未选诊区给操作指引 / 已选给业务结果），.fuy-empty 脸承载 -->
              <div v-if="queue.length === 0" class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <template v-if="deptCode === ''">
                  <p class="fuy-empty-title">尚无候诊队列</p>
                  <p class="fuy-empty-hint">请先在卡头选择出诊诊区，再加载我的候诊患者。</p>
                </template>
                <template v-else>
                  <p class="fuy-empty-title">当前诊区暂无候诊患者</p>
                  <p class="fuy-empty-hint">候诊患者报到后自动进入队列，可切换诊区或稍后刷新。</p>
                </template>
              </div>
            </div>
          </div>
        </section>
      </el-col>

      <!-- 中：接诊工作区（患者上下文 + 在诊单据 + 诊毕收口条；诊毕后 fade-out 120ms 随中列同退） -->
      <el-col
        :md="24"
        :lg="12"
        class="doctor-station-mid-col"
        :style="{ '--fuy-stagger-index': 1 }"
      >
        <Transition name="fuy-content-fade">
          <div v-if="contextVisible && currentVisit !== null">
            <!-- 患者上下文卡：卡头=页面锚点（§8.3）大号 visit 标识 + 分诊级别徽标 -->
            <section class="fuy-card doctor-station-mid-card">
              <div class="fuy-card-head doctor-station-context-head">
                <span class="fuy-num doctor-station-visit-id">{{ currentVisit.visitId }}</span>
                <span
                  v-if="triageBadgeClass(currentVisit.triageLevel) !== null"
                  class="fuy-triage-badge"
                  :class="triageBadgeClass(currentVisit.triageLevel)"
                  >{{ TRIAGE_LEVEL_LABELS[currentVisit.triageLevel ?? 0] ?? '—' }}</span
                >
              </div>
              <div class="fuy-card-body">
                <el-descriptions :column="4" border size="small">
                  <el-descriptions-item label="就诊号">
                    <span class="fuy-num">{{ currentVisit.visitId }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="患者 ID">
                    <span class="fuy-num">{{ currentVisit.patientId }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="状态">
                    <!-- aa 修正对 warning/success/danger 生效文字色 AA，primary/info 无副作用（§4.3）；
                         strike 承载已取消/已失约弱化态 -->
                    <el-tag
                      size="small"
                      :type="visitStatusMeta(currentVisit.status).type"
                      class="fuy-tag-aa"
                      :class="{ 'fuy-tag-strike': visitStatusMeta(currentVisit.status).strike }"
                      >{{ visitStatusMeta(currentVisit.status).text }}</el-tag
                    >
                  </el-descriptions-item>
                  <el-descriptions-item label="就诊类型">{{
                    VISIT_TYPE_LABELS[currentVisit.visitType ?? ''] ?? currentVisit.visitType ?? '—'
                  }}</el-descriptions-item>
                </el-descriptions>
              </div>
            </section>

            <!-- 在诊单据卡：卷宗签两签 + 在途数徽标迁卡头（与单据列表同位）+ 底部诊毕收口条 -->
            <section class="fuy-card">
              <div class="fuy-card-head">
                <h2 class="fuy-card-title">在诊单据</h2>
                <!-- 在途数徽标显隐沿显影家族（fuy-content-fade，enter 200ms/leave 120ms 单
                     opacity）：随开单/诊毕清零的计数变化平滑显影，零新增 keyframes -->
                <Transition name="fuy-content-fade">
                  <span
                    v-if="ongoingOrderCount > 0"
                    class="doctor-station-ongoing fuy-num"
                    aria-label="在途单据数"
                    >{{ ongoingOrderCount }}</span
                  >
                </Transition>
              </div>
              <div class="fuy-card-body">
                <!-- 卷宗签语法（契约 ⑤#8）：激活签顶部 2px 朱砂签线 + 浓墨 600 由 .fuy-tabs 承载 -->
                <el-tabs class="fuy-tabs">
                  <el-tab-pane label="检查检验/处置">
                    <div v-loading="ordersLoading" class="doctor-station-orders">
                      <el-table v-if="examOrders.length > 0" :data="examOrders" class="fuy-dense">
                        <el-table-column prop="orderNo" label="单据号" min-width="150">
                          <template #default="{ row }">
                            <span class="fuy-num">{{ row.orderNo }}</span>
                          </template>
                        </el-table-column>
                        <el-table-column prop="orderType" label="类型" width="90">
                          <template #default="{ row }">
                            {{
                              ORDER_TYPE_OPTIONS.find((item) => item.code === row.orderType)
                                ?.label ?? row.orderType
                            }}
                          </template>
                        </el-table-column>
                        <el-table-column prop="status" label="状态" width="90">
                          <template #default="{ row }">
                            {{ ORDER_STATUS_LABELS[row.status] ?? row.status }}
                          </template>
                        </el-table-column>
                        <el-table-column label="明细" min-width="160">
                          <template #default="{ row }">
                            <span
                              >{{ row.items?.[0]?.itemCode ?? '—' }} ×<span class="fuy-num">{{
                                row.items?.[0]?.quantity ?? '—'
                              }}</span></span
                            >
                          </template>
                        </el-table-column>
                      </el-table>
                      <div v-else class="fuy-empty" role="status">
                        <span class="fuy-empty-mark" aria-hidden="true">空</span>
                        <p class="fuy-empty-title">暂无检查检验/处置单据</p>
                        <p class="fuy-empty-hint">
                          在右列展开「开检查检验单」，录入项目编码与数量。
                        </p>
                      </div>
                    </div>
                  </el-tab-pane>
                  <el-tab-pane label="处方引用">
                    <div v-loading="ordersLoading" class="doctor-station-orders">
                      <el-table v-if="rxRefOrders.length > 0" :data="rxRefOrders" class="fuy-dense">
                        <el-table-column prop="extRef" label="处方号" min-width="150">
                          <template #default="{ row }">
                            <span class="fuy-num">{{ row.extRef }}</span>
                          </template>
                        </el-table-column>
                        <el-table-column prop="status" label="状态" width="90">
                          <template #default="{ row }">
                            {{ ORDER_STATUS_LABELS[row.status] ?? row.status }}
                          </template>
                        </el-table-column>
                        <!-- 发药状态镜像列（W-29 D-3 消费面：医生站可见已发药 Spec :142）；
                             空=未发生发药回流，「未发药」纯文本承载初始语义 -->
                        <el-table-column label="发药状态" width="96">
                          <template #default="{ row }">
                            <el-tag
                              v-if="
                                DISPENSE_MIRROR_TAG_TYPES[row.dispenseStatus ?? ''] !== undefined
                              "
                              size="small"
                              class="fuy-tag-aa"
                              :type="DISPENSE_MIRROR_TAG_TYPES[row.dispenseStatus ?? '']"
                              >{{ DISPENSE_MIRROR_LABELS[row.dispenseStatus ?? ''] }}</el-tag
                            >
                            <span v-else>{{
                              DISPENSE_MIRROR_LABELS[row.dispenseStatus ?? ''] ??
                              row.dispenseStatus ??
                              '未发药'
                            }}</span>
                          </template>
                        </el-table-column>
                      </el-table>
                      <div v-else class="fuy-empty" role="status">
                        <span class="fuy-empty-mark" aria-hidden="true">空</span>
                        <p class="fuy-empty-title">暂无处方引用行</p>
                        <p class="fuy-empty-hint">
                          在右列展开「开处方」，开立处方后自动生成引用行。
                        </p>
                      </div>
                    </div>
                  </el-tab-pane>
                </el-tabs>

                <!-- 诊毕收口条（蓝图 P06）：终态不可逆高危动作升格中列底部收口动线终点，
                     与在途单据同卡同列，「签」分隔线与单据分界；去向/确认勾选/在途徽标/诊毕钮同行 -->
                <div class="doctor-station-finish-bar">
                  <div class="fuy-sign-divider" aria-hidden="true"></div>
                  <el-form
                    class="fuy-form doctor-station-finish-form"
                    label-position="left"
                    label-width="auto"
                    :disabled="currentVisit === null"
                  >
                    <el-form-item label="去向">
                      <el-select
                        v-model="disposition"
                        class="doctor-station-disposition-select"
                        placeholder="离院去向"
                        :loading="dispositionLoading"
                        :disabled="dispositionUnavailable"
                        popper-class="fuy-snap-popper"
                      >
                        <el-option
                          v-for="item in dispositionItems"
                          :key="item.itemCode"
                          :label="item.itemName"
                          :value="item.itemCode ?? ''"
                        />
                      </el-select>
                    </el-form-item>
                    <el-form-item>
                      <!-- 无在途单据时勾选框禁用（无可确认项，勾选无意义）——诊毕放行由 canFinish
                           按 ongoingOrderCount===0 短路承载，勾选框不构成门禁阻断（§8.3 语义） -->
                      <el-checkbox v-model="explicitConfirm" :disabled="ongoingOrderCount === 0">
                        {{
                          ongoingOrderCount === 0
                            ? '无在途单据，无需确认'
                            : `在途单据已确认（当前 ${ongoingOrderCount} 笔）`
                        }}
                      </el-checkbox>
                    </el-form-item>
                    <!-- 在途数徽标（收口条同款显影过渡，与卡头徽标同律） -->
                    <Transition name="fuy-content-fade">
                      <span
                        v-if="ongoingOrderCount > 0"
                        class="doctor-station-ongoing fuy-num"
                        aria-label="在途单据数"
                        >{{ ongoingOrderCount }}</span
                      >
                    </Transition>
                    <!-- 诊毕按钮（PR-4F #10）：与接诊同码 v-perm 直挂——与 canFinish 双条件数据态
                         :disabled 正交叠加（权限决定在不在 DOM，在途单据清空与否决定可不可点） -->
                    <el-button
                      v-perm="'outpatient:doctor:btn:admit'"
                      type="danger"
                      plain
                      :loading="finishing"
                      :disabled="finishing || currentVisit === null || !canFinish"
                      @click="onFinish"
                      >诊毕</el-button
                    >
                  </el-form>
                  <!-- M09 文书校验提醒不拦截注记（12px 灰，§3.2） -->
                  <p class="doctor-station-note">M09 门诊电子病历文书校验为提醒不拦截（P1 接入）</p>
                </div>
              </div>
            </section>
          </div>
          <section v-else class="fuy-card doctor-station-mid-card">
            <div class="fuy-card-head">
              <h2 class="fuy-card-title">接诊工作区</h2>
            </div>
            <div class="fuy-card-body">
              <div class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">尚无接诊中的患者</p>
                <p class="fuy-empty-hint">从左侧候诊列表选择患者并点击接诊。</p>
              </div>
            </div>
          </section>
        </Transition>
      </el-col>

      <!-- 右：开立区（开检查检验单 / 开处方两折叠卡纵叠；诊毕已升格中列收口条） -->
      <el-col
        :md="24"
        :lg="6"
        class="doctor-station-side-col"
        :style="{ '--fuy-stagger-index': 2 }"
      >
        <section class="fuy-card doctor-station-side-card">
          <div class="fuy-card-head">
            <h2 class="fuy-card-title">开检查检验单</h2>
            <!-- 开单入口（PR-4F #11）：医嘱开立码 v-perm 直挂——折叠表单默认收起，入口
                 隐藏则表单不可展开、提交按钮随容器不可达（免重复挂接） -->
            <el-button
              v-perm="'outpatient:doctor:btn:order'"
              link
              type="primary"
              :disabled="currentVisit === null"
              @click="orderFormVisible = !orderFormVisible"
            >
              {{ orderFormVisible ? '收起' : '展开' }}
            </el-button>
          </div>
          <div class="fuy-card-body">
            <!-- 开单表单：v-show + scaleY 200ms §6.6（禁 grid/max-height 布局动画）；
                 label-width 110px 系 §4.4 开单档（与处置档 96px 分档） -->
            <Transition name="doctor-station-fold">
              <el-form
                v-show="orderFormVisible"
                label-position="right"
                label-width="110px"
                :disabled="currentVisit === null"
                class="fuy-form doctor-station-fold-origin"
              >
                <el-form-item label="单据类型">
                  <el-select v-model="orderType" popper-class="fuy-snap-popper">
                    <el-option
                      v-for="item in ORDER_TYPE_OPTIONS"
                      :key="item.code"
                      :label="item.label"
                      :value="item.code"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="项目编码">
                  <el-input v-model="itemCode" placeholder="物价库项目 code" />
                </el-form-item>
                <el-form-item label="数量">
                  <el-input-number
                    v-model="quantity"
                    :min="1"
                    :step="1"
                    :value-on-clear="1"
                    @change="onQuantityChange"
                  />
                </el-form-item>
                <el-form-item>
                  <el-button
                    type="primary"
                    :loading="orderSubmitting"
                    :disabled="orderSubmitting || currentVisit === null"
                    @click="onSubmitOrder"
                    >提交开单</el-button
                  >
                </el-form-item>
              </el-form>
            </Transition>
            <p v-if="!orderFormVisible" class="doctor-station-hint">展开后录入项目编码与数量开单</p>
          </div>
        </section>

        <section class="fuy-card doctor-station-side-card">
          <div class="fuy-card-head">
            <h2 class="fuy-card-title">开处方</h2>
            <!-- 开方入口（PR-4F #11）：与开单同码 v-perm 直挂——折叠表单默认收起，
                 入口隐藏则表单不可展开、提交按钮随容器不可达 -->
            <el-button
              v-perm="'outpatient:doctor:btn:order'"
              link
              type="primary"
              :disabled="currentVisit === null"
              @click="rxFormVisible = !rxFormVisible"
            >
              {{ rxFormVisible ? '收起' : '展开' }}
            </el-button>
          </div>
          <div class="fuy-card-body">
            <!-- 开方表单同开单档 label-width 110px（§4.4） -->
            <Transition name="doctor-station-fold">
              <el-form
                v-show="rxFormVisible"
                label-position="right"
                label-width="110px"
                :disabled="currentVisit === null"
                class="fuy-form doctor-station-fold-origin"
              >
                <el-form-item label="处方类型">
                  <el-select v-model="rxType" popper-class="fuy-snap-popper">
                    <el-option label="门诊处方" value="OUTPATIENT" />
                    <el-option label="急诊处方" value="EMERGENCY" />
                  </el-select>
                </el-form-item>
                <el-form-item label="药品 ID">
                  <el-input v-model="drugIdInput" placeholder="药品字典 drugId" />
                </el-form-item>
                <el-form-item label="数量">
                  <el-input-number
                    v-model="rxQuantity"
                    :min="1"
                    :step="1"
                    :value-on-clear="1"
                    @change="onRxQuantityChange"
                  />
                </el-form-item>
                <el-form-item label="频次">
                  <el-input v-model="frequencyInput" placeholder="如 qd / bid / tid" />
                </el-form-item>
                <el-form-item label="皮试">
                  <el-switch v-model="skinTestRequired" />
                </el-form-item>
                <el-form-item>
                  <el-button
                    type="primary"
                    :loading="rxSubmitting"
                    :disabled="rxSubmitting || currentVisit === null"
                    @click="onSubmitRx"
                    >提交开方</el-button
                  >
                </el-form-item>
              </el-form>
            </Transition>
            <p v-if="!rxFormVisible" class="doctor-station-hint">
              展开后录入药品与用法开方（执业授权由后端校验）
            </p>
          </div>
        </section>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
/* 门牌页首/卷宗卡/卡头/空态脸/状态胶囊/签分隔线样式全部由 element-plus.css 全局挂类承载，
   本块只留本页布局私项与既有交互过渡 */

/* 诊区下拉（卡头既有位，域级 160px 档三页同款） */
.doctor-station-dept {
  width: 160px;
}
.doctor-station-dept-empty {
  margin: 0;
  padding: var(--fuy-space-3);
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
  text-align: center;
}

/* 左列候诊列表：行高 56px，current-row 左缘 3px 品牌色条（§3.2） */
.doctor-station-admit-btn {
  width: 100%;
  margin-bottom: var(--fuy-space-2);
}
.doctor-station-queue {
  min-height: 240px;
  outline-offset: -2px;
}
.doctor-station-queue-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.doctor-station-queue-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  height: 56px;
  padding: 0 var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  cursor: pointer;
  transition: background-color var(--fuy-motion-fast) linear;
}
.doctor-station-queue-row:hover {
  background: var(--fuy-palette-brand-50);
}
.doctor-station-queue-row.is-current::before {
  content: '';
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 3px;
  border-radius: var(--fuy-radius-full);
  background: var(--fuy-color-brand);
}
.doctor-station-queue-row.is-current {
  background: var(--fuy-palette-brand-100);
}
/* 诊毕后 SERVED 行灰化下沉（§8.3） */
.doctor-station-queue-row.is-served {
  opacity: 0.55;
}
.doctor-station-ticket {
  font-weight: 700;
}
.doctor-station-score {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.doctor-station-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doctor-station-wait {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.doctor-station-wait.is-long-wait {
  color: var(--fuy-color-warning-text);
}

/* 中列上下文卡与在诊单据卡的纵向间距 */
.doctor-station-mid-card {
  margin-bottom: var(--fuy-space-3);
}
/* 中列卡头（§8.3 页面锚点）：大号 visit 标识（2xl/700 emphasis）+ 级别徽标同行排布 */
.doctor-station-context-head {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-3);
}
.doctor-station-visit-id {
  font-size: var(--fuy-font-size-2xl);
  font-weight: 700;
  color: var(--fuy-color-text-emphasis);
  line-height: 1.3;
}
.doctor-station-side-card {
  margin-bottom: var(--fuy-space-3);
}
.doctor-station-orders {
  min-height: 120px;
}
.doctor-station-hint,
.doctor-station-note {
  margin: var(--fuy-space-2) 0 0;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}

/* 诊毕收口条（蓝图 P06）：「签」分隔线与单据分界，去向/确认勾选/在途徽标/诊毕钮同行收口 */
.doctor-station-finish-bar {
  margin-top: var(--fuy-space-4);
}
.doctor-station-finish-bar .fuy-sign-divider {
  margin-bottom: var(--fuy-space-3);
}
.doctor-station-finish-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  /* 列间距收 8px（验段派回修正）：1440 目标分辨率中列可用 ~514px，四件+3×16 间距 ≈565px
     超出致诊毕钮换行——16→8 省 24px，配合去向 select 收 160 档（-40px）保四件同单行 */
  gap: var(--fuy-space-2);
}
.doctor-station-finish-form :deep(.el-form-item) {
  margin-bottom: 0;
}
/* 去向 select 域级 160px 档（三页诊区选择器同款档位，验段派回修正：200px 档撤销） */
.doctor-station-disposition-select {
  width: 160px;
}
/* 「在途单据」未结数徽标（warning 底白字圆形 18px，卡头/收口条同行两处承载；白字走 EP 白色
   变量通道——§7.6-1 零裸 hex） */
.doctor-station-ongoing {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: var(--fuy-radius-full);
  background: var(--el-color-warning);
  color: var(--el-color-white);
  font-size: var(--fuy-font-size-xs);
  font-weight: 700;
}

/* 开单/开方表单展开收起：v-show + opacity/scaleY 200ms standard（§6.6，仅合成层属性） */
.doctor-station-fold-origin {
  transform-origin: top;
}
.doctor-station-fold-enter-active,
.doctor-station-fold-leave-active {
  transition:
    opacity var(--fuy-motion-base) var(--fuy-ease-standard),
    transform var(--fuy-motion-base) var(--fuy-ease-standard);
}
.doctor-station-fold-enter-from,
.doctor-station-fold-leave-to {
  opacity: 0;
  transform: scaleY(0.96);
}

/* 诊毕后中列 fade-out 120ms（§8.3；motion.css 仅定义 enter 段，leave 段按同 token 在页内补齐） */
.fuy-content-fade-leave-active {
  transition: opacity var(--fuy-motion-fast) var(--fuy-ease-exit);
}
.fuy-content-fade-leave-to {
  opacity: 0;
}
</style>

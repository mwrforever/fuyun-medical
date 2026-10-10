<script setup lang="ts">
// 药房住院摆药页（/pharmacy/inpatient-dispense，PR-3 Task 16，FU-M06-05 前端面；暖纸卷宗 P14
// 重排——工具条 8 元素解散，蓝图 P14.2：低频「生成计划」收编左置 sticky 生成卡与高频五状态
// 看板主从分区）。页面纵向序=门牌页首 → 主工作区双区（左计划生成卡 300px sticky：病区真数据
// 下拉/医嘱号输入/检索审方钮/审方状态回显块/生成计划主钮；右五状态列看板：CREATED/PICKING/
// PICKED/CHECKED/DELIVERED 行卡=计划号/患者号面/给药时点/类型标签[单剂量/PIVAS/整包]+PIVAS
// 排批——CANCELLED 作废态与退药态不进列，退药态由调剂行承载，计划行不迁）→ 弹层三件（病区
// 签收[零手输]/PIVAS 贴签[数据面]/住院退药[可退明细多行表]，均挂 .fuy-dialog 弹层脸）。
// 数据面（换脸不换业务，零变动）：审方状态回显复用 review-tasks 面（后端清单端点无医嘱号
// 过滤参数，拉取后前端本地匹配）；审方通过后生成计划按钮——APPROVED 前置零出网拦截，后端
// 同语义守卫兜底；摆药五步状态机以后端 DispenseStatus 住院链实测词表为准，deliver 为 CHECKED
// 态内配送交接时间线半步不迁状态（Task 8 裁决）——行内动作成功后一律重拉看板、不本地迁移
// 状态（服务端回包为唯一状态源）；退药弹窗 W-66 打开拉 returnable 读面——非 DELIVERED 409
// 归失败弹错；逐行数量数字正则（小数位≤3，DECIMAL(12,3) 精度对齐——评审 C-F1）+可退净量
// 双验 D-8，提交全 NORMAL 行逐行 returnLines 直调 pharmacy dispense-returns 住院扩展形态
// （nursing 侧退药开关校验端点未建，Task 8 minor③ 注记，前端注记不越界实现）。
// 病区假常量清零（蓝图 P14.5）：nursing.WARD_OPTIONS → listOrgs({type:'WARD'}) 真下拉
// useAsyncTask 三态（加载禁用/失败批注行+重试/空清单引导），sessionStorage 会话记忆保留——
// 清单到达后回填记忆值、缺失取首项；病区必选口径：清单未到达/空清单时看板骨架不出网。
// 贴签弹窗与退药弹窗打开均先清旧数据+回包比对当前计划号（EX-45/FE-A1-04 纪律——Task 14
// P1-1 同族）。失败弹错归响应拦截器（AxiosError 防双弹，业务拒绝对象由 surfaceBizError 兜底）。
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
import type {
  DispensePlanLabelVO,
  DispensePlanReturnableVO,
  DispensePlanVO,
  ReviewTaskVO,
} from '@/api/pharmacy';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { usePagedList } from '@/composables/usePagedList';
import { useAuthStore } from '@/stores/auth';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

/* ==================== 会话身份与门牌批注（「谁·何时」真值，空值 — 占位） ==================== */
const auth = useAuthStore();

/** 签认人展示名（门牌批注行：displayName 优先，空值 — 占位禁伪数据） */
const signerName = computed(() => auth.user?.displayName ?? '—');

/** 当日时刻标签（门牌批注行「何时」位：YYYY-MM-DD 周X） */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/* ==================== 病区真数据下拉（假常量清零：WARD_OPTIONS → listOrgs） ==================== */
/** 病区会话记忆键（切换/刷新不回默认病区，口径同护理执行工作台既有） */
const WARD_MEMORY_KEY = 'pharmacy.dispense.wardId';

/** 当前病区（会话记忆初值优先——清单到达前仅回显记忆码，看板待病区定档后才出网） */
const wardId = ref(sessionStorage.getItem(WARD_MEMORY_KEY) ?? '');

/** 病区清单（listOrgs 真数据源，V1123 种子 W01/1001 同链路）；仅保留带 code 的机构行 */
const wards = ref<OrgVO[]>([]);
/** 病区清单是否已落定（成败均计）：区分「未加载」与「加载为空」，看板指引/空态不抢跑 */
const wardsSettled = ref(false);

/**
 * 病区清单加载（useAsyncTask 三态）：加载中下拉禁用 / 失败批注行+重试 / 空清单禁用+引导；
 * 失败态只落 error ref 驱动页内失败批注行与重试钮，错误详情弹错归响应拦截器——页内不重弹
 * （P04 诊区下拉同款口径）。就绪后回填目标病区：会话记忆优先，记忆缺失取首项，清单为空
 * 保持空串（病区必选——空码时看板骨架不出网，见 kickBoardAfterWards）。
 */
const {
  loading: wardsLoading,
  error: wardsError,
  run: loadWards,
} = useAsyncTask(
  async () => {
    wards.value = (await listOrgs({ type: 'WARD' })).filter((org) => org.orgCode);
    // 会话记忆回填（蓝图 P14.5）：清单到达后记忆值优先、缺失取首项；空清单保持空串走指引
    const remembered = sessionStorage.getItem(WARD_MEMORY_KEY) ?? '';
    wardId.value = remembered !== '' ? remembered : (wards.value[0]?.orgCode ?? '');
  },
  {
    onFinally: () => {
      wardsSettled.value = true;
    },
  },
);

/** 空清单态：清单已落定且为空（下拉禁用 + 维护引导，不伪装成可选项） */
const wardsEmpty = computed(
  () => wardsSettled.value && wardsError.value === null && wards.value.length === 0,
);

/** 病区下拉禁用：加载中 / 失败待重试 / 空清单三态均无可选项，禁用防误操作 */
const wardsDisabled = computed(
  () => wardsLoading.value || wardsError.value !== null || wardsEmpty.value,
);

/** 选中病区展示名（门牌批注行「当前病区」位）：清单内按 code 反查名称，缺失回退原码 */
const wardLabel = computed(() => {
  if (wardId.value === '') {
    return '—';
  }
  return wards.value.find((org) => org.orgCode === wardId.value)?.orgName ?? wardId.value;
});

/* ==================== ① 计划生成卡（医嘱检索 → 审方回显 → 生成计划） ==================== */
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

/** 在办计数（门牌状态位胶囊）：前两列（待摆药+摆药中）合计，随看板行集实时归一 */
const activeCount = computed(() =>
  columns.value.slice(0, 2).reduce((sum, column) => sum + column.rows.length, 0),
);

/** 病区必选指引：清单已落定且病区未定（空清单或初载失败）——看板出「请选择病区」零出网 */
const boardGuidance = computed(() => wardsSettled.value && wardId.value === '');

/**
 * 病区清单落定后续动（初载与失败重试共用出口）：病区必选口径——病区码已定才拉看板
 * （清单未到达/空清单不发起 dispensePlans.list，看板骨架静候）。
 */
async function kickBoardAfterWards(): Promise<void> {
  if (wardsError.value === null && wardId.value !== '') {
    await loadBoard();
  }
}

/** 病区清单失败重试（失败批注行重试钮出口）：重拉清单并按落定结果续动看板 */
async function onWardsRetry(): Promise<void> {
  await loadWards();
  await kickBoardAfterWards();
}

/** 病区切换：会话记忆 + 看板重拉（空码不发起——病区必选零出网守卫） */
function onWardChange(): void {
  sessionStorage.setItem(WARD_MEMORY_KEY, wardId.value);
  if (wardId.value === '') {
    return;
  }
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
 * 行内动作码位过滤（PR-4F #20/#21）：行内按钮经 v-for 单渲染源生成，v-perm 无法逐项
 * 直挂，以 hasPerm 过滤清单等效承载（无码全隐藏 D-34，fail-closed 不弱于 el.remove，
 * Task 10 #15 actionsOf 先例）——摆药五步（pick/verify/issue/deliver/receive）归
 * #20 摆药计划族（含「病区签收」入口）；退药（return）归 #21 退药族；贴签（label）
 * 为纯读数据面且附件 A 元素列未列，不挂码保持全量可见。
 *
 * @param actions 状态机产出的原始动作集
 * @return 按会话码位过滤后的可渲染动作集（无对应码即整组隐藏）
 */
function filterRowActionsByPerm(actions: PlanAction[]): PlanAction[] {
  const canPlan = auth.hasPerm('pharmacy:dispense:btn:plan');
  const canReturn = auth.hasPerm('pharmacy:dispense:btn:return');
  return actions.filter((action) => {
    if (action === 'label') {
      return true;
    }
    // 摆药族动作与退药动作分属两码，互不干扰（同视图双码各自独立判定）
    return action === 'return' ? canReturn : canPlan;
  });
}

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
  return filterRowActionsByPerm(actions);
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
      void openReturn(row);
      break;
  }
}

/* ==================== 病区签收弹窗（零手输——签收人=会话身份） ==================== */
const receiveVisible = ref(false);
const receiving = ref(false);
/** 签收目标行（弹窗期间行锚） */
const receiveTarget = ref<DispensePlanVO | null>(null);

/** 签收人展示文案（当前登录人 displayName 优先、缺省回退 userId——W-72 签收人取会话身份） */
const receiverText = computed(() => auth.user?.displayName ?? auth.user?.userId ?? '—（未登录）');

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

/* ==================== 退药弹窗（可退明细多行表——W-66/D-8） ==================== */
const returnVisible = ref(false);
const returning = ref(false);
/** 退药目标行（EX-45 回包比对锚） */
const returnTarget = ref<DispensePlanVO | null>(null);
/** 可退明细读面（null=未回包，弹窗内 v-loading 兜底） */
const returnData = ref<DispensePlanReturnableVO | null>(null);
/** 逐行数量录入（itemSeq→DECIMAL string 承载，禁 number 转换；空=该行未交代） */
const returnQtys = ref<Record<string, string>>({});
/** 可退明细拉取在途标志 */
const returnLoading = ref(false);

/**
 * 退药数量行级校验（D-8 收口，循 PdaView isValidNeedleVolume 正则+范围双验先例）：
 * 数字正则拦负数/科学计数/Infinity/多小数点形态，且小数位 ≤3（DECIMAL(12,3) 列精度对齐——
 * 超 3 位后端必拒且 PG 会静默舍入致勾稽漂移，评审 C-F1 前后端双钉）；范围比对可退净量
 * （已发-已退）——0 不允许（零量退药无业务意义），后端服务层同口径兜底。
 */
function isValidReturnQty(raw: string, returnableQty: string): boolean {
  if (!/^\d+(\.\d{1,3})?$/.test(raw)) {
    return false;
  }
  const qty = Number(raw);
  return qty > 0 && qty <= Number(returnableQty);
}

/**
 * 打开退药弹窗并拉取可退明细读面：换行先清旧数据+清录入（防 A 行明细与数量驻留串台到
 * B 行计划号名下），回包先比对当前目标计划号再落值——回包期间换行即在途回包过期直接丢弃
 * （EX-45/FE-A1-04 纪律，循贴签弹窗 openLabel 同款防线）。
 */
async function openReturn(row: DispensePlanVO): Promise<void> {
  const no = row.planNo ?? '';
  returnTarget.value = row;
  // 换行先清旧：B 行读面未回包前弹窗数据缺位不渲染，防 A 行可退明细串台
  returnData.value = null;
  returnQtys.value = {};
  returnVisible.value = true;
  returnLoading.value = true;
  try {
    const result = await dispensePlans.returnable(no);
    // 过期回包丢弃：换行后目标计划号已变，旧计划回包不得落值
    if (returnTarget.value?.planNo === no) {
      returnData.value = result;
    }
  } catch {
    // 失败弹错归响应拦截器（非 DELIVERED 409 等）；弹窗数据缺位占位
  } finally {
    returnLoading.value = false;
  }
}

/**
 * 确认退药：逐行交代校验（每行数量必填+数字正则+可退净量范围双验——D-8 收口；任一行
 * 不合法或空即整单零出网——后端缺行守卫 400 的前端对齐）→ 出网 createDispenseReturn
 * 住院扩展形态（全 NORMAL 行逐行 returnLines，不带门诊 mode/items 面）→ 关窗重拉看板。
 * 追溯码输入面已删除：住院摆药采集恒空集，非空码后端必拒 PH-1012——原输入面是必 409
 * 陷阱（如实化注记留档，防回流核验语义由后端 UT 承载），提交恒传 traceCodes: []。
 * 注记：nursing 侧退药开关校验端点未建（Task 8 minor③），本入口直调 pharmacy 端点；
 * 受理成功后计划行仍处病区已签收列（退药态由调剂行承载，计划行不迁——后端裁决口径）。
 */
async function onReturn(): Promise<void> {
  if (returning.value) {
    return;
  }
  const items = returnData.value?.items ?? [];
  // 读面未回包（加载中）或无可退明细：显式拦截零出网（空 returnLines 必败于后端守卫）
  if (items.length === 0) {
    void ElMessage.warning('可退明细未就绪，无法提交退药');
    return;
  }
  // 逐行交代：任一行数量缺填或不合法即整单拦截（明细序号锚+可退净量随警示透出）
  for (const item of items) {
    const raw = (returnQtys.value[item.itemSeq ?? ''] ?? '').trim();
    if (!isValidReturnQty(raw, item.returnableQty ?? '')) {
      void ElMessage.warning(
        `明细 ${item.itemSeq ?? ''} 退药数量须为不超过可退净量 ${item.returnableQty ?? '—'} 的正数`,
      );
      return;
    }
  }
  returning.value = true;
  try {
    await createDispenseReturn({
      dispensePlanNo: returnTarget.value?.planNo ?? '',
      returnLines: items.map((item) => ({
        itemSeq: item.itemSeq ?? '',
        returnQuantity: (returnQtys.value[item.itemSeq ?? ''] ?? '').trim(),
        traceCodes: [],
      })),
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
  // 病区必选口径：先拉病区清单定档 wardId（记忆回填/首项），落定后才发起看板出网
  void loadWards().then(() => kickBoardAfterWards());
});
</script>

<template>
  <div class="fuy-page dispense-plan">
    <!-- 门牌页首（契约 ⑧.1 / 蓝图 P14.2）：衬线标题 + 右挂状态位（刷新钮 + 在办计数胶囊=
         前两列合计）+ 签认人·当前病区·时刻批注行（五步注记收批注行尾，旧工具条注记迁入）；
         2px 墨规收底走全局 .fuy-page-head 脸，本页零私有标题样式 -->
    <header class="fuy-page-head">
      <div class="fuy-page-head-main">
        <h1 class="fuy-page-title">住院摆药台</h1>
      </div>
      <div class="fuy-page-status plan-head-side">
        <el-button :loading="boardLoading" title="重新拉取当前病区摆药看板" @click="loadBoard"
          >刷新</el-button
        >
        <!-- 状态位=在办计数胶囊（前两列合计 .fuy-num；amber 与 P11 队列/P13 待审两枚域内
             计数胶囊同语义档——待办=amber；静态计数不挂呼吸点，总则 2 双轨制） -->
        <span class="fuy-status-pill fuy-status-pill--amber">
          在办 <span class="fuy-num">{{ activeCount }}</span>
        </span>
      </div>
      <p class="fuy-page-note">
        签认人 {{ signerName }} · 当前病区 {{ wardLabel }} · <time>{{ todayLabel }}</time> ·
        摆药五步：摆药开始 → 药师核对 → 出库交接 → 配送交接 → 病区签收
      </p>
    </header>

    <!-- 主工作区（蓝图 P14.2/P14.3）：grid 300px 1fr gap 16——左计划生成卡（低频准备动作
         收编，sticky top 16 滚动常驻）+ 右五状态看板（高频主面）；工具条 8 元素解散。
         stagger 两档（生成卡 0 / 看板 1）；页根禁挂 .fuy-stagger（契约 ⑦.4：路由进场过渡
         归 MainLayout，防双重进场节奏） -->
    <div class="plan-workarea fuy-stagger">
      <!-- 左：计划生成卡（病区下拉/医嘱号/检索审方/审方回显/生成计划五件纵向收口） -->
      <section class="fuy-card plan-generate-card" :style="{ '--fuy-stagger-index': 0 }">
        <header class="fuy-card-head">
          <h2 class="fuy-card-title">计划生成</h2>
        </header>
        <div class="fuy-card-body plan-generate-body">
          <!-- 病区真数据下拉（蓝图 P14.5 假常量清零）：listOrgs({type:'WARD'}) 单源，
               显式 popper 挂 fuy-snap-popper；value 仍为 string code，dispensePlans.list
               的 wardId 契约不变；会话记忆回填语义见 script -->
          <el-select
            v-model="wardId"
            class="plan-ward"
            placeholder="请选择病区"
            :loading="wardsLoading"
            :disabled="wardsDisabled"
            popper-class="fuy-snap-popper"
            @change="onWardChange"
          >
            <el-option
              v-for="org in wards"
              :key="org.id ?? org.orgCode"
              :label="org.orgName ?? org.orgCode"
              :value="org.orgCode ?? ''"
            />
          </el-select>
          <!-- 病区清单三态批注行：失败给重试入口 / 空清单给维护引导（错误态不伪装空态） -->
          <div v-if="wardsError !== null" class="plan-ward-state" role="status">
            <span>病区清单加载失败</span>
            <el-button link type="primary" :loading="wardsLoading" @click="onWardsRetry"
              >重试</el-button
            >
          </div>
          <p v-else-if="wardsEmpty" class="plan-ward-hint" role="status">
            暂无可用病区，请联系管理员维护组织机构
          </p>
          <el-input
            v-model="orderNoInput"
            placeholder="住院医嘱号"
            class="plan-order-input"
            clearable
            @keyup.enter="onSearchReview"
          />
          <el-button :loading="reviewLoading" @click="onSearchReview">检索审方</el-button>
          <!-- 审方状态回显块（号面脱敏口径：医嘱号/患者号面/状态/明细，既有结构迁入卡） -->
          <div v-if="reviewStatus !== null" class="plan-review-state">
            <span class="fuy-num">{{ reviewStatus.m04OrderNo }}</span>
            审方：
            <el-tag size="small" class="fuy-tag-aa">{{ reviewStateText }}</el-tag>
            <span class="plan-review-items" :title="reviewStatus.items ?? ''">{{
              reviewStatus.items ?? '—'
            }}</span>
          </div>
          <!-- 生成摆药计划（PR-4F #20，PHARMACIST 绑定）：v-perm 直挂，与 :disabled 审方
               通过数据态正交——行内摆药五步动作经 rowActions 码位过滤同码承载（见 script） -->
          <el-button
            v-perm="'pharmacy:dispense:btn:plan'"
            type="primary"
            class="plan-generate-button"
            :loading="generating"
            :disabled="reviewStatus?.status !== 'APPROVED'"
            @click="onGenerate"
            >生成摆药计划</el-button
          >
        </div>
      </section>

      <!-- 右：五状态列看板（CANCELLED/退药态不进列；列内按后端返回序=planTime 升序，
           列头/行卡结构零改动——蓝图 P14.2 状态语义族零改动） -->
      <div v-loading="boardLoading" class="plan-board" :style="{ '--fuy-stagger-index': 1 }">
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
        <!-- 病区必选指引（清单已落定且病区未定——零出网静候，错误态不伪装空态语义见批注行） -->
        <div v-if="boardGuidance" class="plan-board-empty fuy-empty" role="status">
          <span class="fuy-empty-mark" aria-hidden="true">空</span>
          <p class="fuy-empty-title">请选择病区</p>
          <p class="fuy-empty-hint">病区清单就绪后自动载入该病区摆药看板，可在左侧选择病区。</p>
        </div>
        <!-- 看板级空态（契约 ⑥ .fuy-empty 脸禁纸箱插画）：主句合「暂无」语法，说明给下一步 -->
        <div
          v-else-if="wardsSettled && columns.every((column) => column.rows.length === 0)"
          class="plan-board-empty fuy-empty"
          role="status"
        >
          <span class="fuy-empty-mark" aria-hidden="true">空</span>
          <p class="fuy-empty-title">暂无摆药计划</p>
          <p class="fuy-empty-hint">
            当前病区暂无摆药计划，可切换病区、检索医嘱生成计划或点击刷新。
          </p>
        </div>
      </div>
    </div>

    <!-- 病区签收弹窗（零手输——签收人=当前登录人会话身份，服务端令牌留痕；挂 .fuy-dialog
         弹层脸，页面级成功反馈=ElMessage 批注条不钤印，总则 1） -->
    <el-dialog v-model="receiveVisible" title="摆药病区签收" width="420px" class="fuy-dialog">
      <p class="plan-dialog-target fuy-num">计划 {{ receiveTarget?.planNo ?? '' }}</p>
      <label class="plan-field-label">签收人</label>
      <p class="plan-receive-receiver fuy-num">
        {{ receiverText }}（{{ auth.user?.userId ?? '—' }}）——当前登录人，服务端留痕
      </p>
      <p class="plan-dialog-hint">未配送不可签收（配送交接为签收必要前置，后端 PH-1026 把守）</p>
      <template #footer>
        <!-- footer 双钮默认尺寸档（弹窗脸六族横向律，与 P07/P10 弹窗 footer 同律） -->
        <el-button @click="receiveVisible = false">取消</el-button>
        <!-- 签收确认（PR-4F #20）：与行内「病区签收」入口（rowActions 过滤）同码 v-perm
             直挂双保险（Task 10 #15 先例——入口过滤 + 弹窗提交直挂） -->
        <el-button
          v-perm="'pharmacy:dispense:btn:plan'"
          type="primary"
          :loading="receiving"
          :disabled="receiving"
          @click="onReceive"
          >确认签收</el-button
        >
      </template>
    </el-dialog>

    <!-- PIVAS 贴签弹窗（label 数据面；床号无 pharmacy 侧数据源恒空——展示即所得；
         挂 .fuy-dialog 弹层脸） -->
    <el-dialog v-model="labelVisible" title="PIVAS 贴签数据面" width="560px" class="fuy-dialog">
      <!-- 弹窗内容容器挂 fuy-dense：密度唯一通道承载弹窗内表格（§9.4 通用落点同律） -->
      <div v-loading="labelLoading" class="fuy-dense">
        <template v-if="labelData !== null">
          <p class="plan-dialog-target fuy-num">计划 {{ labelData.planNo ?? '—' }}</p>
          <!-- descriptions 默认尺寸档（与 P11/P12 摘要 descriptions 同律，size 双轨退役） -->
          <el-descriptions :column="2" border>
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
          <!-- 贴签药品明细（药名/剂量/单位/途径/数量——贴签「批次」承载=排批号）；
               密度经容器挂 fuy-dense 唯一通道（§9.9-2 EP size 双轨退役，与页内表格容器同律）；
               编码/单次剂量/摆药数量=号码与数字列挂 fuy-num 等宽 -->
          <el-table :data="labelData.items ?? []" class="plan-label-items">
            <el-table-column prop="itemCode" label="编码" min-width="90" class-name="fuy-num" />
            <el-table-column prop="itemName" label="药品名称" min-width="160" />
            <el-table-column
              prop="dosage"
              label="单次剂量"
              width="80"
              align="right"
              class-name="fuy-num"
            />
            <el-table-column prop="unit" label="单位" width="60" />
            <el-table-column prop="route" label="途径" width="90" />
            <el-table-column
              prop="quantity"
              label="摆药数量"
              width="80"
              align="right"
              class-name="fuy-num"
            />
          </el-table>
          <p class="plan-dialog-hint">打印归 M01 打印服务接入（当前为数据面查看）</p>
        </template>
        <p v-else class="plan-dialog-hint">贴签数据加载中…</p>
      </div>
    </el-dialog>

    <!-- 退药弹窗（DELIVERED 行；可退明细多行表——W-66 打开即拉 returnable 读面；
         挂 .fuy-dialog 弹层脸） -->
    <el-dialog v-model="returnVisible" title="住院退药受理" width="640px" class="fuy-dialog">
      <p class="plan-dialog-target fuy-num">计划 {{ returnTarget?.planNo ?? '' }}</p>
      <div v-loading="returnLoading" class="fuy-dense">
        <template v-if="returnData !== null">
          <!-- 可退明细多行表（明细/编码/批号/已发/已退/可退/数量录入——D-8 双验=数字正则+可退净量）；
               密度经容器挂 fuy-dense 唯一通道（EP size 双轨退役）；号码与数字列挂 fuy-num 等宽 -->
          <el-table :data="returnData.items ?? []" class="plan-return-items">
            <el-table-column prop="itemSeq" label="明细" width="50" class-name="fuy-num" />
            <el-table-column prop="itemCode" label="编码" min-width="90" class-name="fuy-num" />
            <el-table-column prop="batchNo" label="批号" min-width="110" class-name="fuy-num" />
            <el-table-column
              prop="issuedQty"
              label="已发"
              width="70"
              align="right"
              class-name="fuy-num"
            />
            <el-table-column
              prop="returnedQty"
              label="已退"
              width="70"
              align="right"
              class-name="fuy-num"
            />
            <el-table-column
              prop="returnableQty"
              label="可退"
              width="70"
              align="right"
              class-name="fuy-num"
            />
            <el-table-column label="退药数量" width="130">
              <template #default="{ row }">
                <!-- 可退净量≤0 行（防御面，正常不可达）只读禁输，该行无法交代即整单零出网 -->
                <input
                  v-model="returnQtys[row.itemSeq ?? '']"
                  class="plan-dialog-input plan-return-qty"
                  type="text"
                  inputmode="decimal"
                  autocomplete="off"
                  placeholder="如 1"
                  :disabled="Number(row.returnableQty ?? '0') <= 0"
                  :aria-label="`明细 ${row.itemSeq ?? ''} 退药数量`"
                />
              </template>
            </el-table-column>
          </el-table>
          <p class="plan-dialog-hint">
            逐行填写退药数量（全明细逐行交代）；追溯码免录——住院摆药未采集追溯码，
            防回流核验由后端承载。受理成功后计划行仍在病区已签收列（退药态由调剂行承载）； nursing
            侧退药开关校验端点未建，本入口直调药房退药受理
          </p>
        </template>
        <p v-else class="plan-dialog-hint">可退明细加载中…</p>
      </div>
      <template #footer>
        <!-- footer 双钮默认尺寸档（弹窗脸六族横向律，与 P07/P10 弹窗 footer 同律） -->
        <el-button @click="returnVisible = false">取消</el-button>
        <!-- 退药提交（PR-4F #21，PHARMACIST 绑定）：与行内「退药」入口（rowActions 过滤）
             同码 v-perm 直挂双保险；与门诊退药受理页（DispenseReturnView）同码跨视图收口 -->
        <el-button
          v-perm="'pharmacy:dispense:btn:return'"
          type="primary"
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
/* 门牌状态位侧（刷新钮 + 在办计数胶囊 baseline 对齐——P04 同款口径；门牌脸走全局类） */
.plan-head-side {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 主工作区（蓝图 P14.3）：grid 300px 1fr gap 16——左生成卡 sticky 常驻，右看板随页滚 */
.plan-workarea {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: var(--fuy-space-4);
  align-items: start;
}
/* 左计划生成卡 sticky（滚动常驻——生成动作在视线内，一次合成零 JS 代价） */
.plan-generate-card {
  position: sticky;
  top: 16px;
}
.plan-generate-body {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: var(--fuy-space-2);
}
.plan-ward {
  width: 100%;
}
.plan-order-input {
  width: 100%;
}
.plan-generate-button {
  width: 100%;
}
/* 病区清单三态批注行：失败行给重试钮，空清单行给维护引导 */
.plan-ward-state {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-2);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}
.plan-ward-hint {
  margin: 0;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}
/* 审方状态回显块（迁入生成卡：纵向排布适配 300px 列宽，超长明细省略 + title 悬停全览） */
.plan-review-state {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-1);
  padding: var(--fuy-space-2);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-gray-50);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}
.plan-review-items {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 右：五列看板（等宽 grid；min-height CLS 锁） */
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
/* 行卡（1px 描边白底；信息密度照执行工作台先例；hover 暖底/金洗随 brand-100 换血承继） */
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
/* 看板级空态/病区必选指引（.fuy-empty 脸居中悬浮于列上） */
.plan-board-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 弹窗（签收/贴签/退药共用录入形态；弹层脸 .fuy-dialog 走全局类） */
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
/* 退药可退明细多行表（贴签明细表同款间距口径） */
.plan-return-items {
  margin-top: var(--fuy-space-3);
}
</style>

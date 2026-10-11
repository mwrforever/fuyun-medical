<script setup lang="ts">
// 住院医生站页（/inpatient/station，M04 FU-M04-03/04 前端面 · 暖纸卷宗换脸重排 乙-3，
// 「换脸不换业务」：开立表单模型/成组行编辑/九类型分叉/换患者复位草稿/EX-45 守卫/
// IP-1011 前置校验零变动）纵向序：门牌页首（衬线标题 INPATIENT DESK + 病区真数据下拉 +
// 刷新 +「开立人 谁 · 时刻 · 用药类开立后待药师审」批注行）→ 三列（lg 6/12/6 既有断点）：
// 左=在院患者列表卡（选中驱动全页，选中=金洗底+左缘墨条；>200 行按床号段分组签行折叠——
// ⑨.4 分组分页路径，病区常规 <60 不触发），中=拆两卡（PM 重排裁决：医嘱列表卡（上）+
// 医嘱开立卡（下，卡头带「成组 N 行」计数副注，.fuy-form 挂文书脸；开立明细行编辑 grid，
// 用药类字段显隐既有）），右=闭环追溯卡（dot+左缘连线时间线纸语法，零动画）→ 页脚批注
// （数据时点）。保存成功盖钤印（用药类「已开立（待药师审）」/非用药「已开立」，v-if 挂载制
// ≤1200ms 收场；失败与业务拒绝归 message/拦截器，钤印永不承失败）。
// 频次下拉换真数据（G4：ORDER_FREQUENCY_OPTIONS 假常量清零 → listDictItems('medication.frequency')，
// typeCode 经 V607 迁移实核；DictItemVO 按 itemCode/itemName 消费，useAsyncTask 三态），
// 病区换 listOrgs({type:'WARD'})（WARD_OPTIONS 假常量本页零消费）。
// 开立前显式格式校验（IP-1011 类：明细必填/数量正数/长期频次）零出网拦截；执业授权（IP-1012）
// 与过敏冲突（IP-1013）由后端 403/409 业务拒绝，detail 中文原文经 surfaceBizError 兜底透出。
// 全部写操作自带在途守卫（入口早退先于一切 await）；失败弹错归响应拦截器（AxiosError 防双弹）。
// 患者摘要按脱敏口径仅展示就诊号/床号（WardPatientDetailVO 含可选 patientName，本页展示仅取
// 号面属合规裁剪而非契约缺位）。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import {
  MEDICATION_ORDER_TYPES,
  ORDER_CLASS_OPTIONS,
  ORDER_STATUS_LABELS,
  ORDER_TYPE_OPTIONS,
  TRACE_STAGE_LABELS,
  orders,
  visits,
} from '@/api/inpatient';
import type { InpatientOrderItemRequest, MedicalOrderVO, TraceEntry } from '@/api/inpatient';
import { wardPatients } from '@/api/nursing';
import type { WardPatientVO } from '@/api/nursing';
import { listDictItems, listOrgs } from '@/api/system';
import type { DictItemVO, OrgVO } from '@/api/system';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { useAuthStore } from '@/stores/auth';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

// 会话身份入口（批注行「开立人 谁」真值；权限判定仍由 v-perm 指令承载）
const auth = useAuthStore();

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

/** 频次字典类型编码（V607 迁移实核：medication.frequency=M06 药事域用药频次，非蓝图速记名） */
const FREQUENCY_DICT_TYPE = 'medication.frequency';

/** 患者列表渲染阈值：>200 行触发床号段分组签行（⑨.4 长列表策略的分组分页路径；
 * 病区常规 <60 行不触发，阈值断言防一次全量渲染） */
const PATIENT_LIST_THRESHOLD = 200;

/* ==================== 门牌：病区清单（listOrgs WARD 档真数据面，G4） ==================== */
/** 病区下拉选项（listOrgs 真数据面；展示与出网值=orgCode） */
const wardOptions = ref<OrgVO[]>([]);
/** 当前病区（初值待清单装配裁决；切换即重载患者与欠费清单） */
const wardId = ref('');
/** 病区清单装载（三态经 useAsyncTask 承载：在途骨架 loading / 失败恢复 error / 空清单空态脸） */
const wardLoad = useAsyncTask(async () => {
  wardOptions.value = await listOrgs({ type: 'WARD' });
  // wardId 初值裁决：空/不在启用清单内一律回落首项（清单空则驻留空串，患者面加载守卫拦截出网）
  if (!wardOptions.value.some((option) => option.orgCode === wardId.value)) {
    wardId.value = wardOptions.value[0]?.orgCode ?? '';
  }
});
const wardOptionsLoading = wardLoad.loading;
/** 病区清单失败态（null=无失败；患者卡内给「说问题+给恢复」恢复行，不伪装空态） */
const wardOptionsError = computed(() => wardLoad.error.value !== null);
/** 病区下拉禁用态：清单在途或空清单（出网值必须锚定真实病区编码） */
const wardDisabled = computed(() => wardOptionsLoading.value || wardOptions.value.length === 0);

/**
 * 病区清单（重）装载出网（失败弹错归响应拦截器，恢复经「重新加载」重入）。
 *
 * @return true=清单就绪且存在可用病区（装配链据此决定是否续拉患者清单）
 */
async function loadWardOptions(): Promise<boolean> {
  await wardLoad.run();
  return wardOptions.value.length > 0;
}

/* ==================== 左列：在院患者列表 ==================== */
const patients = ref<WardPatientVO[]>([]);
/** 欠费在院集合（arrears 聚合按 visitId 命中行内「欠费」标识） */
const arrearsVisitIds = ref<Set<string>>(new Set());
/** 过敏标识缓存（选中行 detail 懒加载回填；true=过敏，false=已查无过敏） */
const allergyFlagMap = ref<Map<string, boolean>>(new Map());
/** 当前选中在院患者 */
const selectedVisit = ref<WardPatientVO | null>(null);
/** 患者清单数据时点（最近一次成功拉取，页脚批注消费；null=尚未成功拉取） */
const patientsLoadedAt = ref<string | null>(null);

/** 页脚数据时点展示串（未完成过任何一次成功加载以 — 占位，零伪数据） */
const patientsLoadedAtLabel = computed(() =>
  patientsLoadedAt.value === null ? '—' : formatTime(patientsLoadedAt.value),
);

/** 加载在院患者列表与病区欠费清单（病区切换/操作后刷新共用）：loading 骨架经 useAsyncTask
 * 收拢（EX-42 范式迁移，行为与迁移前一致——失败弹错归响应拦截器、驻留旧清单）。回包判空
 * 兜底（EX-45/FE-A1-09）随任务体原样保留：契约外整包缺失（rows/arrears 为 null）时兜底
 * 空数组，防渲染层 patients.length 空指针白屏与旧清单驻留误导。病区上下文未就绪（清单在途/
 * 失败/空清单）时零出网，防空病区码请求。 */
const { loading: patientsLoading, run: loadPatients } = useAsyncTask(async () => {
  if (wardId.value === '') {
    return;
  }
  const [rows, arrears] = await Promise.all([
    wardPatients.list(wardId.value),
    visits.arrears(wardId.value),
  ]);
  // 判空兜底：rows 缺失兜空清单（渲染层不再接触 undefined），arrears 缺失兜空集
  patients.value = rows ?? [];
  arrearsVisitIds.value = new Set(
    (arrears ?? []).map((row) => row.visitId ?? '').filter((id) => id !== ''),
  );
  // 分组默认展开态复位：常规清单平铺无组状态；超阈值分组形态默认仅展开首组（其余折叠可逐组
  // 放行——⑨.4 禁一次全量渲染的分组分页路径）
  syncGroupOpenState();
  // 数据时点回填（页脚批注「数据时点」消费；失败路径驻留旧时点）
  patientsLoadedAt.value = new Date().toISOString();
});

/** 床号段分组形态（组头=「01-10 床」石规签行；空 label=无组头的单组平铺，BedMapView 同构） */
interface PatientGroup {
  /** 分组稳定键（段起始床号 / all / other） */
  key: string;
  /** 组头展示串（空串=无组头的单组平铺形态） */
  label: string;
  /** 组内患者（上游床位序切片，不重排） */
  rows: WardPatientVO[];
}

/** 床号数字前缀 → 十位段起始（非数字床号返回 null，归「其他床号」尾组） */
function bedSegmentStart(bedNo: string): number | null {
  const match = /^(\d+)/.exec(bedNo.trim());
  const no = match === null ? Number.NaN : Number.parseInt(match[1] ?? '', 10);
  return Number.isFinite(no) && no > 0 ? Math.floor((no - 1) / 10) * 10 + 1 : null;
}

/**
 * 患者清单分组（⑨.4 长列表策略的分组分页路径，BedMapView groupWallBeds 同构页内实现）：
 * ≤阈值单组平铺（无组头）；超阈值按床号十位段分组，非数字床号归「其他床号」尾组，组内
 * 保持后端床位序不重排。
 *
 * @param rows 当前病区在院患者清单（后端床位序）
 * @return 分组结果；空清单返回空数组（调用方渲染空态脸）
 */
function groupPatients(rows: WardPatientVO[]): PatientGroup[] {
  if (rows.length <= PATIENT_LIST_THRESHOLD) {
    return rows.length === 0 ? [] : [{ key: 'all', label: '', rows }];
  }
  const buckets = new Map<string, PatientGroup>();
  const others: WardPatientVO[] = [];
  for (const row of rows) {
    const start = bedSegmentStart(row.bedNo ?? '');
    if (start === null) {
      others.push(row);
      continue;
    }
    const key = `seg-${start}`;
    const bucket = buckets.get(key);
    if (bucket === undefined) {
      // 组头「01-10 床」形态：段界两位补零（三位床号段仍以两位展示段界）
      buckets.set(key, {
        key,
        label: `${String(start).padStart(2, '0')}-${String(start + 9).padStart(2, '0')} 床`,
        rows: [row],
      });
    } else {
      bucket.rows.push(row);
    }
  }
  const groups = [...buckets.values()];
  if (others.length > 0) {
    groups.push({ key: 'other', label: '其他床号', rows: others });
  }
  return groups;
}

/** 分组结果（≤阈值单组无组头平铺；>阈值床号段分组） */
const patientGroups = computed(() => groupPatients(patients.value));
/** 已展开床号段键集（分组形态默认仅含首组；平铺形态恒空——'all' 单组恒展开不消费） */
const openGroups = ref<readonly string[]>([]);

/** 组展开态（平铺单组恒展开；分组形态按键集判定） */
function isGroupOpen(key: string): boolean {
  return key === 'all' || openGroups.value.includes(key);
}

/** 组头折叠开关（逐组放行，折叠组不渲染行节点） */
function toggleGroup(key: string): void {
  openGroups.value = isGroupOpen(key)
    ? openGroups.value.filter((open) => open !== key)
    : [...openGroups.value, key];
}

/** 分组默认展开态复位（患者清单每次成功装载后调用）：常规平铺恒空；超阈值分组仅展开首组 */
function syncGroupOpenState(): void {
  const groups = groupPatients(patients.value);
  openGroups.value = groups.length > 1 ? [groups[0].key] : [];
}

/** 行点击选中：驱动中列上下文与医嘱列表，并懒加载过敏标识（行内与上下文徽标共用缓存）；
 * 换患者即复位开立草稿——前患者未提交的明细行/频次不跨患者滞留，否则续提即开错患者（用药安全） */
async function selectPatient(row: WardPatientVO): Promise<void> {
  selectedVisit.value = row;
  orderRows.value = [];
  selectedOrder.value = null;
  traceEntries.value = [];
  resetOrderDraft();
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

/* ==================== 中列：医嘱列表与开立 ==================== */
const orderRows = ref<MedicalOrderVO[]>([]);
/** 当前点选医嘱（追溯锚点） */
const selectedOrder = ref<MedicalOrderVO | null>(null);

/** 拉取在院医嘱分页（开立时间倒序，开立/选中患者后重刷共用）：loading 骨架经 useAsyncTask
 * 收拢（EX-42 范式迁移）。EX-45/FE-A1-03 竞态守卫随任务体原样保留：发起时锚定当前选中
 * 患者，回包时已切换患者则整包丢弃——旧患者慢回包晚到不得覆盖新患者的医嘱列表（守卫形态
 * 同 nursing useWardContext 系先例）。 */
const { loading: ordersLoading, run: loadOrders } = useAsyncTask(async () => {
  const visitId = selectedVisit.value?.visitId;
  if (visitId === undefined) {
    return;
  }
  // 发起时锚定当前选中患者：回包前再切换患者即形成在途竞态（EX-45/FE-A1-03）
  const page = await orders.list({
    visitId,
    page: 0,
    size: 50,
  });
  // 过期回包丢弃：旧患者慢回包晚到不得覆盖新患者的医嘱列表
  if (selectedVisit.value?.visitId !== visitId) {
    return;
  }
  orderRows.value = page.content ?? [];
});

/** 医嘱行点选：驱动右列闭环追溯 */
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

/* ---------- 频次真数据（G4：ORDER_FREQUENCY_OPTIONS 假常量清零 → listDictItems 三态） ---------- */
/** 用药频次字典条目（V607 medication.frequency 真数据；useAsyncTask 三态缓存页内，挂载拉取一次） */
const freqItems = ref<DictItemVO[]>([]);
const { loading: freqLoading, run: loadFreqItems } = useAsyncTask(async () => {
  freqItems.value = await listDictItems(FREQUENCY_DICT_TYPE);
});
/** 频次选择可用性（三态之禁用态：字典加载失败或空清单=无可选频次，长期开立防盲选） */
const freqUnavailable = computed(() => freqLoading.value || freqItems.value.length === 0);

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
/** 成组提示（多行明细共用组号；卡头另带「成组 N 行」计数副注） */
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

/** 复位开立草稿：明细行归一空行 + 长期项（频次/嘱托）清空，类型/分类为医生录入偏好保留
 * （与提交成功后复位同口径），换患者选中与开立成功共用，防前患者草稿跨上下文续提。 */
function resetOrderDraft(): void {
  itemRows.value = [
    { itemCode: '', itemName: '', dosage: '', dosageUnit: '', route: '', quantity: 1 },
  ];
  createForm.value.freqCode = '';
  createForm.value.standbyFlag = false;
}

/**
 * 保存医嘱（开立）：显式格式校验（IP-1011 类）零出网 → 出网 → CREATED 返回后按用药类盖钤印
 * （用药类「已开立（待药师审）」/非用药「已开立」）并刷新医嘱列表。入口在途早退守卫防双击
 * 重复开立；失败与业务拒绝不盖印（归 message 批注条/拦截器）。
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
  const validatedItems: InpatientOrderItemRequest[] = [];
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
    await orders.create(selectedVisit.value.visitId, {
      orderType: createForm.value.orderType,
      orderClass: createForm.value.orderClass,
      standbyFlag: isLongClass.value && createForm.value.standbyFlag ? true : undefined,
      freqCode: isLongClass.value ? createForm.value.freqCode : undefined,
      items: validatedItems,
    });
    // 用药类 CREATED 停留语义=「待药师审」（M06 审方通过后迁 AUDITED）——钤印双文案分叉
    if (MEDICATION_ORDER_TYPES.has(createForm.value.orderType)) {
      showStamp('已开立（待药师审）');
    } else {
      showStamp('已开立');
    }
    // 开立成功后复位草稿（与换患者复位同口径）
    resetOrderDraft();
    await loadOrders();
  } catch (error) {
    // 执业授权 IP-1012/过敏冲突 IP-1013 等业务拒绝：detail 中文原文兜底透出（不盖印）
    surfaceBizError(error);
  } finally {
    creating.value = false;
  }
}

/* ---------- 钤印成功反馈（契约 ⑧.5，≤1200ms 收场） ---------- */
/** 钤印文案（空串=无钤印在展）；seq 每盖一次递增，模板以 :key 重挂重放落章动画；
 * v-if 挂载制=乙-1 登记台先例同构：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤为
 * 常显空朱块，挂载即 is-show（stamp-in 特异性压过 rise）根治 */
const stampText = ref('');
const stampVisible = ref(false);
const stampSeq = ref(0);
let stampTimer: ReturnType<typeof setTimeout> | null = null;

/** 盖钤印（只承成功确认；失败与业务拒绝归 message 批注条/拦截器，钤印永不承失败） */
function showStamp(text: string): void {
  stampText.value = text;
  stampSeq.value += 1;
  stampVisible.value = true;
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
  }
  stampTimer = setTimeout(() => {
    stampVisible.value = false;
    stampTimer = null;
  }, 1200);
}

onBeforeUnmount(() => {
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
  }
});

/* ==================== 右列：闭环追溯 ==================== */
const traceEntries = ref<TraceEntry[]>([]);

/** 拉取选中医嘱闭环追溯（开立→审核→转抄→执行→状态迁移时间线）：loading 骨架经
 * useAsyncTask 收拢（EX-42 范式迁移，行为与迁移前一致——失败弹错归响应拦截器、驻留旧
 * 时间线）。回包状态语义由中列医嘱行 status tag 承载（selectedOrder 同源），本卡不再
 * 落第二份状态态。 */
const { loading: traceLoading, run: loadTrace } = useAsyncTask(async () => {
  if (selectedOrder.value?.orderNo === undefined) {
    return;
  }
  const trace = await orders.trace(selectedOrder.value.orderNo);
  traceEntries.value = trace.entries ?? [];
});

/** 环节中文词表反查（时间线环节名） */
function stageLabel(code: string | undefined): string {
  return TRACE_STAGE_LABELS[code ?? ''] ?? code ?? '—';
}

/* ---------- 门牌批注行：开立人（会话身份）· 时刻（本地时钟零出网） ---------- */
/** 开立人批注：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const operatorName = computed(() => auth.user?.displayName ?? '—');

/** 当日批注行时刻标签（YYYY-MM-DD 周X）：病历页眉日期批注语法，纯本地时钟零出网 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/** 装配链：病区清单就绪后再拉患者与欠费清单（失败/空清单驻留恢复态/空态脸，零患者面出网） */
async function bootWard(): Promise<void> {
  if (await loadWardOptions()) {
    void loadPatients();
  }
}

/** 门牌病区切换：重载当前病区患者与欠费清单 */
function onWardChange(): void {
  void loadPatients();
}

onMounted(() => {
  void bootWard();
  void loadFreqItems();
});
</script>

<template>
  <div class="fuy-page station-view">
    <!-- 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块——乙-1
         admission-flow 同构形态）：三段级联序 门牌 0 / 三列 1 / 页脚 2 -->
    <div class="fuy-stagger station-flow">
      <!-- ① 门牌页首（契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（病区真数据下拉 +
           刷新）+「开立人 谁 · 时刻 · 用药类开立后待药师审」批注行 + 2px 墨规收底 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">住院医生站</h1>
          <span class="fuy-page-en" aria-hidden="true">INPATIENT DESK</span>
        </div>
        <div class="fuy-page-status station-head-controls">
          <!-- 病区下拉换真数据（乙-3.5：WARD_OPTIONS 假常量清零→listOrgs WARD 档）；
               清单在途/空清单禁用——出网值必须锚定真实病区编码 -->
          <select
            v-model="wardId"
            class="station-ward"
            aria-label="病区选择"
            :disabled="wardDisabled"
            @change="onWardChange"
          >
            <option v-for="org in wardOptions" :key="org.orgCode" :value="org.orgCode ?? ''">
              {{ org.orgName }}（{{ org.orgCode }}）
            </option>
          </select>
          <el-button class="station-refresh" :loading="patientsLoading" @click="loadPatients"
            >刷新</el-button
          >
        </div>
        <p class="fuy-page-note">
          开立人 {{ operatorName }} · <time>{{ todayLabel }}</time> · 用药类开立后待药师审
        </p>
      </header>

      <!-- ② 三列（el-row gutter 16，lg 6/12/6 既有断点）：左患者（选中驱动）/ 中两卡 / 右追溯 -->
      <el-row class="station-cols" :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 左列：在院患者列表卡（选中=金洗底+左缘墨条；>200 行床号段分组签行折叠） -->
        <el-col :md="24" :lg="6">
          <section class="fuy-card station-patients-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">在院患者</h2>
              <span class="station-patient-total"
                >共 <span class="fuy-num">{{ patients.length }}</span> 名</span
              >
            </header>
            <div class="fuy-card-body station-patient-list">
              <!-- 病区清单三态（listOrgs 真数据面）：在途骨架 / 失败恢复 / 空清单空态脸 -->
              <div v-if="wardOptionsLoading" class="station-patient-sk" aria-hidden="true">
                <span v-for="i in 6" :key="i" class="fuy-sk" />
              </div>
              <div v-else-if="wardOptionsError" class="fuy-empty station-ward-error" role="alert">
                <p class="fuy-empty-title">病区清单加载失败</p>
                <p class="fuy-empty-hint">无法获取病区清单，请检查网络后点击重新加载恢复。</p>
                <el-button type="primary" size="small" @click="bootWard">重新加载</el-button>
              </div>
              <div v-else-if="wardOptions.length === 0" class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无可用病区</p>
                <p class="fuy-empty-hint">组织机构未配置启用病区，请联系管理员维护后重试。</p>
              </div>
              <template v-else>
                <!-- 患者清单首拉骨架（200px CLS 锁）；重刷经刷新钮转圈不闪骨架 -->
                <div
                  v-if="patientsLoading && patients.length === 0"
                  class="station-patient-sk"
                  aria-hidden="true"
                >
                  <span v-for="i in 6" :key="i" class="fuy-sk" />
                </div>
                <!-- 患者空态脸（G6：暂无〈业务客体〉+ 给下一步） -->
                <div v-else-if="patients.length === 0" class="fuy-empty" role="status">
                  <span class="fuy-empty-mark" aria-hidden="true">空</span>
                  <p class="fuy-empty-title">暂无在院患者</p>
                  <p class="fuy-empty-hint">当前病区暂无在院记录，可切换病区或稍后刷新。</p>
                </div>
                <template v-else>
                  <template v-for="group in patientGroups" :key="group.key">
                    <!-- 床号段组头（石规签行折叠开关；平铺形态无组头不渲染） -->
                    <button
                      v-if="group.label !== ''"
                      type="button"
                      class="station-patient-group-head"
                      :aria-expanded="isGroupOpen(group.key)"
                      @click="toggleGroup(group.key)"
                    >
                      <span aria-hidden="true">{{ isGroupOpen(group.key) ? '▾' : '▸' }}</span
                      >{{ group.label }}（<span class="fuy-num">{{ group.rows.length }}</span
                      >）
                    </button>
                    <ul v-if="isGroupOpen(group.key)" class="station-patient-ul">
                      <li
                        v-for="row in group.rows"
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
                            v-if="NURSING_LEVEL_LABELS[row.nursingLevel ?? ''] !== undefined"
                            class="fuy-nursing-level-badge"
                            :class="NURSING_LEVEL_BADGE[row.nursingLevel ?? '']"
                            >{{
                              NURSING_LEVEL_LABELS[row.nursingLevel ?? ''] ?? row.nursingLevel
                            }}</span
                          >
                          <!-- 欠费标识（后端 arrears 聚合命中；机器判据类） -->
                          <span
                            v-if="arrearsVisitIds.has(row.visitId ?? '')"
                            class="station-arrears-flag"
                            >欠费</span
                          >
                          <!-- 过敏标识（详情懒加载命中；未查不误标） -->
                          <span
                            v-if="allergyFlagOf(row)"
                            class="fuy-nursing-flag station-allergy-flag"
                            >过敏</span
                          >
                        </div>
                      </li>
                    </ul>
                  </template>
                </template>
              </template>
            </div>
          </section>
        </el-col>

        <!-- 中列：拆两卡（PM 重排裁决：医嘱列表卡（上）+ 医嘱开立卡（下，独立文书表单卡）） -->
        <el-col :md="24" :lg="12" class="station-col-mid">
          <!-- 医嘱列表卡（上）：患者上下文承载卡头 + 在院医嘱密排表（行点选驱动追溯） -->
          <section class="fuy-card station-orders-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">在院医嘱</h2>
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
            </header>
            <div class="fuy-card-body station-orders-body">
              <!-- 医嘱首拉骨架（200px CLS 锁）；重刷经刷新钮/开立后重载不闪骨架 -->
              <div
                v-if="ordersLoading && orderRows.length === 0"
                class="station-orders-sk"
                aria-hidden="true"
              >
                <span v-for="i in 6" :key="i" class="fuy-sk" />
              </div>
              <template v-else>
                <el-table
                  v-if="orderRows.length > 0"
                  :data="orderRows"
                  class="fuy-dense"
                  :row-class-name="
                    ({ row }: { row: MedicalOrderVO }) =>
                      selectedOrder?.orderNo === row.orderNo ? 'is-current-row' : ''
                  "
                >
                  <el-table-column label="医嘱号" min-width="130">
                    <template #default="{ row }">
                      <!-- 医嘱号点选=墨色 link（追溯入口） -->
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
                <!-- 空态脸（G6）：未选患者引导 / 已选无医嘱给下一步 -->
                <div v-else class="fuy-empty" role="status">
                  <span class="fuy-empty-mark" aria-hidden="true">空</span>
                  <p class="fuy-empty-title">
                    {{ selectedVisit === null ? '未选择在院患者' : '暂无在院医嘱' }}
                  </p>
                  <p class="fuy-empty-hint">
                    {{
                      selectedVisit === null
                        ? '从左侧患者列表点选后浏览在院医嘱。'
                        : '可在下方开立新医嘱，保存成功后自动刷新列表。'
                    }}
                  </p>
                </div>
              </template>
            </div>
          </section>

          <!-- 医嘱开立卡（下，独立文书表单卡）：卡头带「成组 N 行」计数副注，.fuy-form 挂脸 -->
          <section class="fuy-card station-create-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">医嘱开立</h2>
              <span class="station-group-count"
                >成组 <span class="fuy-num">{{ itemRows.length }}</span> 行</span
              >
            </header>
            <div class="fuy-card-body">
              <el-form class="fuy-form" label-position="left" label-width="72px" size="small">
                <div class="station-create-row">
                  <div class="station-create-field">
                    <label class="station-field-label">医嘱类型（九类）</label>
                    <select
                      v-model="createForm.orderType"
                      class="station-input"
                      aria-label="医嘱类型"
                    >
                      <option
                        v-for="item in ORDER_TYPE_OPTIONS"
                        :key="item.code"
                        :value="item.code"
                      >
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
                    <!-- 频次下拉换真数据（G4：ORDER_FREQUENCY_OPTIONS 假常量清零→
                         listDictItems medication.frequency 档，DictItemVO itemCode/itemName 消费）；
                         字典在途/失败/空清单禁用（三态之禁用态，长期开立防盲选） -->
                    <select
                      v-model="createForm.freqCode"
                      class="station-input"
                      aria-label="医嘱频次"
                      :disabled="!isLongClass || freqUnavailable"
                    >
                      <option value="">未选择</option>
                      <option
                        v-for="item in freqItems"
                        :key="item.itemCode"
                        :value="item.itemCode ?? ''"
                      >
                        {{ item.itemName }}
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

                <!-- 成组行编辑 grid：序号/编码/名称/剂量/单位/途径/数量/删行（用药类字段显隐既有）；
                     多行共用组号（服务层回填），行内校验前置；≤5 行零滚动（无定高无滚动容器） -->
                <div
                  v-for="(row, index) in itemRows"
                  :key="index"
                  class="station-item-row"
                  :class="{ 'is-medication': isMedicationType }"
                >
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
                <!-- 保存医嘱（PR-4F #17）：医生医嘱码 v-perm 直挂——与 selectedVisit 选中
                     数据态 :disabled 正交叠加（F6② 扩绑后 DOCTOR 可入页） -->
                <el-button
                  v-perm="'inpatient:station:btn:order'"
                  type="primary"
                  class="station-save"
                  :loading="creating"
                  :disabled="creating || selectedVisit === null"
                  @click="onSaveOrder"
                  >保存医嘱</el-button
                >
              </el-form>
            </div>
          </section>
        </el-col>

        <!-- 右列：闭环追溯卡（dot+左缘连线时间线纸语法，零动画） -->
        <el-col :md="24" :lg="6">
          <section class="fuy-card station-trace-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">闭环追溯</h2>
            </header>
            <div class="fuy-card-body station-trace">
              <!-- 追溯首拉骨架（200px CLS 锁）；点选后重查经按钮在途态不闪骨架 -->
              <div
                v-if="traceLoading && traceEntries.length === 0"
                class="station-trace-sk"
                aria-hidden="true"
              >
                <span v-for="i in 4" :key="i" class="fuy-sk" />
              </div>
              <template v-else>
                <p v-if="selectedOrder !== null" class="station-trace-order fuy-num">
                  {{ selectedOrder.orderNo }}
                  <el-tag size="small" class="fuy-tag-aa">{{
                    ORDER_STATUS_LABELS[selectedOrder.status ?? ''] ?? selectedOrder.status
                  }}</el-tag>
                </p>
                <ol v-if="traceEntries.length > 0" class="station-trace-list">
                  <li
                    v-for="(entry, index) in traceEntries"
                    :key="index"
                    class="station-trace-item"
                  >
                    <div class="station-trace-head">
                      <span class="station-trace-stage">{{ stageLabel(entry.stage) }}</span>
                      <span class="fuy-num station-trace-time">{{
                        formatTime(entry.occurredAt)
                      }}</span>
                    </div>
                    <div class="station-trace-body">
                      <span class="station-trace-result">{{ entry.result ?? '—' }}</span>
                      <span v-if="entry.detail" class="station-trace-detail">{{
                        entry.detail
                      }}</span>
                    </div>
                  </li>
                </ol>
                <!-- 空态脸（G6）：未选医嘱引导 / 已选无环节给恢复 -->
                <div v-else class="fuy-empty" role="status">
                  <span class="fuy-empty-mark" aria-hidden="true">空</span>
                  <p class="fuy-empty-title">
                    {{ selectedOrder === null ? '未选择医嘱' : '暂无追溯环节' }}
                  </p>
                  <p class="fuy-empty-hint">
                    {{
                      selectedOrder === null
                        ? '从中列医嘱列表点击医嘱号查看闭环追溯。'
                        : '该医嘱尚无闭环记录，可稍后点选医嘱号重查。'
                    }}
                  </p>
                </div>
              </template>
            </div>
          </section>
        </el-col>
      </el-row>

      <!-- ③ 页脚批注（契约 ⑧.8）：数据时点=最近一次成功加载时点 + 手动刷新取数语义（禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 2 }">
        数据时点 {{ patientsLoadedAtLabel }} · 手动刷新取数
      </footer>

      <!-- 钤印确认反馈（契约 ⑧.5：只承成功确认；已开立（待药师审）/已开立 双文案分叉；
           v-if 挂载制=乙-1 登记台先例同构——防 stagger 误伤常驻泄漏，seq 递增重挂重放落章动画，
           ≤1200ms 收场） -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 住院医生站页（暖纸乙-3）：门牌页首/卷宗卡/页脚/空态脸/钤印归全局族（element-plus.css）；
   三列 6/12/6 与拆两卡结构，取色全走 --fuy-* 语义 token（禁自创色值），版面内零投影零新 hex */

/* 页内级联容器（契约 ⑦.4）：本层成为 .fuy-page 唯一子级后根级 flex gap 失效，纵排与
   区块间距由本层同值承接（.fuy-page 同构 flex+gap），保证视觉零漂移 */
.station-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：病区 select + 刷新（wrap 兜窄屏，baseline 随页首族） */
.station-head-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 病区切换（native select 与 EP 密度口径对齐，jsdom 可测；纸面基元=最亮纸白底+石规描边） */
.station-ward {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

/* 左列患者卡头计数（12px 灰墨，卡头右侧随 .fuy-card-head 空间分布） */
.station-patient-total {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 患者卡体 CLS 锁：加载/空态/清单三态切换零塌陷 */
.station-patient-list {
  min-height: 200px;
}

/* 病区清单与患者首拉骨架（.fuy-sk 静态墨洗纸块，零 shimmer 常驻动画） */
.station-patient-sk {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
}

.station-orders-sk {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
}

.station-trace-sk {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
}

/* 床号段组头（石规签行折叠开关，BedMapView 同构语法；平铺形态不渲染） */
.station-patient-group-head {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-1);
  width: 100%;
  margin: var(--fuy-space-2) 0;
  padding: var(--fuy-space-1) 0;
  border: 0;
  border-top: 1px solid var(--fuy-color-rule-stone);
  background: transparent;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
  letter-spacing: 0.08em;
  text-align: left;
  cursor: pointer;
}

.station-patient-ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 患者行：金洗底+左缘墨条选中形态（既有形态承新值，token 换血自动） */
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

/* 欠费标识（状态朱红字描边弱底，语义=欠费提醒不拦阻；色值经 danger 语义 token） */
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

/* 中列医嘱列表卡头患者上下文（就诊号+床号+过敏/欠费标识随卡头右挂） */
.station-context-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
}

.station-context-visit {
  font-size: var(--fuy-font-size-md);
  font-weight: 700;
  color: var(--fuy-color-text-emphasis);
}

.station-context-bed {
  color: var(--fuy-color-text-secondary);
}

/* 医嘱卡体 CLS 锁：骨架/空态/表格三态切换零塌陷 */
.station-orders-body {
  min-height: 200px;
}

/* 医嘱号点选（追溯入口，行内墨色 link 语义） */
.station-order-row {
  cursor: pointer;
  color: var(--fuy-color-brand);
}

/* 中列两卡纵距（el-col 内无根级 gap，卡间距自给——开立卡随列表卡之后） */
.station-create-card {
  margin-top: var(--fuy-space-3);
}

/* 开立卡头计数副注（「成组 N 行」随明细行数联动，12px 灰墨） */
.station-group-count {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
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

/* 表单基元：native input/select 对齐乙-1 admission-input 形态（jsdom 可测）；
   围合取值直取 --fuy-* 语义通道（契约 ⑤#2 输入围合=最亮纸白底+石规描边，与 .station-ward
   门牌下拉同形；石规与 EP border 映射同源，底色自卡面亮纸矫正为表单域最亮纸白） */
.station-input {
  width: 100%;
  box-sizing: border-box;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
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

/* 成组行编辑 grid：序号/编码/名称/剂量/单位/途径/数量/删行（用药类八字段轨）；
   ≤5 行零滚动（无定高无滚动容器，自然纵排） */
.station-item-row {
  display: grid;
  grid-template-columns:
    20px minmax(0, 1.3fr) minmax(0, 1.7fr) minmax(0, 0.8fr) minmax(0, 0.6fr) minmax(0, 0.9fr)
    minmax(0, 0.7fr) auto;
  gap: var(--fuy-space-2);
  align-items: center;
  margin-bottom: var(--fuy-space-2);
}

/* 非用药类（剂量/单位/途径显隐既有）：五轨降列，格线不错位 */
.station-item-row:not(.is-medication) {
  grid-template-columns: 20px minmax(0, 1.3fr) minmax(0, 1.7fr) minmax(0, 0.7fr) auto;
}

.station-item-seq {
  text-align: right;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.station-item-code,
.station-item-name {
  min-width: 0;
}

.station-item-quantity {
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

/* 右列追溯卡体 CLS 锁 */
.station-trace {
  min-height: 200px;
}

/* 闭环追溯时间线（纸语法）：左缘连线 + 环节 dot；零动画（防闭环时序误读） */
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

/* 环节结果码原文承中性浓墨（后端 result 取值域含 REJECTED 审核驳回等非正向态——
   确认绿只承「确认/在档」语义通道，恒绿会把驳回环节错染为通过；状态码原文不作
   语义着色，色永不单独承义） */
.station-trace-result {
  color: var(--fuy-color-text-emphasis);
}

.station-trace-detail {
  color: var(--fuy-color-text-secondary);
}
</style>

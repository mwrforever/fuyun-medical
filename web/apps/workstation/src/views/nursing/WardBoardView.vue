<script setup lang="ts">
// 护士工作站页（/nursing/ward，M05 前端面，设计文档 §3 八区块）：病区选择与入区登记 →
// 床位序患者卡墙（选中驱动全页患者上下文）→ 患者详情/责任护士分配 → 体征录入与待复核 →
// 体温单渲染（§5 符号契约）与特殊事件 → 护理评估（量表打分判级）→ 护理任务与交接班双签。
// 全部写操作自带在途守卫（入口早退先于一切 await）+ 4xx 口径显式校验（禁裸 parse，
// 数值字段一律文本承载经正则+范围双验）；失败弹错归响应拦截器（AxiosError 防双弹，
// 非 AxiosError 的业务拒绝对象由 surfaceBizError 兜底展示 detail 原文）。
// 体温单坐标计算在 tempChart.ts 纯函数（spec 双层断言），视图仅做 SVG 映射渲染。
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import axios from 'axios';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import {
  assessments,
  assignments,
  chart,
  CONDITION_TAG_OPTIONS,
  handovers,
  ioRecords,
  NURSING_LEVEL_OPTIONS,
  SPECIAL_EVENT_OPTIONS,
  SHIFT_OPTIONS,
  tasks,
  TEMP_SITE_OPTIONS,
  vitalSigns,
  WARD_OPTIONS,
  wardPatients,
} from '@/api/nursing';
import type {
  NurseAssignmentVO,
  NursingAssessmentVO,
  NursingTaskVO,
  ScaleDefinitionVO,
  ShiftHandoverVO,
  TemperatureChartVO,
  VitalSignVO,
  WardPatientDetailVO,
  WardPatientVO,
} from '@/api/nursing';
import { useAuthStore } from '@/stores/auth';
import { AXIS_WIDTH, buildTempChart, DAILY_ROWS } from './tempChart';

/** 护理级别 → 徽标类映射（后端 NursingLevel 三值；--l2 留全族定义防词表扩值 §2.3） */
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

/** 病情标记 → 角标类/文案映射（§3.11：娩归 brand 系） */
const CONDITION_FLAG_META: Record<string, { cls: string; text: string }> = {
  CRITICAL: { cls: 'fuy-nursing-flag--danger', text: '危' },
  SEVERE: { cls: 'fuy-nursing-flag--warning', text: '重' },
  NEW: { cls: 'fuy-nursing-flag--brand', text: '新' },
  SURGERY: { cls: 'fuy-nursing-flag--info', text: '术' },
  DELIVERY: { cls: 'fuy-nursing-flag--brand', text: '娩' },
};

/** 床旁风险标识 → 空心角标文案（§3.11：跌倒/压疮红描边空心） */
const RISK_FLAG_LABELS: Record<string, string> = {
  FALL: '跌',
  PRESSURE: '压',
  TUBE: '管',
};

/** 任务类型中文词表（NursingTaskVO.taskType 十值枚举展示映射） */
const TASK_TYPE_LABELS: Record<string, string> = {
  MEDICATION: '给药',
  INFUSION_CARE: '输液',
  TURN: '翻身',
  PATROL: '巡视',
  SPECIMEN: '标本',
  IO_MONITOR: '出入量',
  IOT_LINKAGE: 'IoT 联动',
  ASSESS_REMIND: '评估提醒',
  MANUAL: '手工',
  PREVENTION: '防范',
};

/** 任务状态 tag 映射（§3.11：PENDING/IN_PROGRESS/COMPLETED+aa/CANCELLED+strike） */
const TASK_STATUS_META: Record<
  string,
  { type: 'primary' | 'warning' | 'success' | 'info'; text: string; strike?: boolean }
> = {
  PENDING: { type: 'info', text: '待执行' },
  IN_PROGRESS: { type: 'primary', text: '执行中' },
  COMPLETED: { type: 'success', text: '已完成' },
  CANCELLED: { type: 'info', text: '已取消', strike: true },
};

/** 评估风险判级映射（§3.11：HIGH/MEDIUM/LOW 三档 tag 文案） */
const RISK_LEVEL_META: Record<string, { type: 'danger' | 'warning' | 'success'; text: string }> = {
  HIGH: { type: 'danger', text: '高风险' },
  MEDIUM: { type: 'warning', text: '中风险' },
  LOW: { type: 'success', text: '低风险' },
};

/** 分配类型词表（PRIMARY 责任患者 / BED 管床） */
const ASSIGNMENT_TYPE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'BED', label: '管床' },
  { code: 'PRIMARY', label: '责任患者' },
];

/** 出入量类型词表（IoType 两值：入量/出量） */
const IO_TYPE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'INTAKE', label: '入量' },
  { code: 'OUTPUT', label: '出量' },
];

const auth = useAuthStore();
/** 当班护士（交接班确认回显的交班人锚点） */
const operatorName = computed(() => auth.user?.displayName ?? '—');

/** 本地日期串（yyyy-MM-dd，任务/交接班当日过滤共用） */
function todayString(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** 时点展示串（MM-dd HH:mm，表格列与确认回显共用） */
function formatTime(raw: string | undefined): string {
  if (!raw) {
    return '—';
  }
  const date = new Date(raw);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 病情标记逗号串拆解（condition_tags 存储「CRITICAL,SEVERE」形态） */
function splitTags(raw: string | undefined): string[] {
  return (raw ?? '')
    .split(',')
    .map((tag) => tag.trim())
    .filter((tag) => tag.length > 0);
}

/** 业务失败兜底展示：AxiosError 已由响应拦截器弹错（防双弹）；其余形态（如 api 层直抛的
 * ProblemDetail 对象）在此展示 detail 原文——NS 域 4xx detail 已是中文业务口径（§3.7） */
function surfaceBizError(error: unknown): void {
  if (axios.isAxiosError(error)) {
    return;
  }
  const detail = (error as { detail?: unknown } | null | undefined)?.detail;
  if (typeof detail === 'string' && detail.length > 0) {
    void ElMessage.error(detail);
  }
}

/* ==================== ① 病区选择 + 入区登记 ==================== */
/** 当前病区（会话内记忆：切换/刷新不回默认病区） */
const wardId = ref(sessionStorage.getItem('nursing.wardId') ?? WARD_OPTIONS[0].code);
/** 当前班次（分配与交接班的班次上下文） */
const shiftCode = ref(SHIFT_OPTIONS[0].code);

function onWardChange(): void {
  sessionStorage.setItem('nursing.wardId', wardId.value);
  selectedVisitId.value = null;
  void loadWard();
}

function onShiftChange(): void {
  void loadAssignments();
  void loadHandoverOfDay();
}

/** 入区登记弹窗态 */
const registerVisible = ref(false);
const registering = ref(false);
const registerForm = ref({
  patientId: '',
  visitId: '',
  patientName: '',
  bedNo: '',
  nursingLevel: 'NORMAL',
  conditionTags: [] as string[],
});

/** 重置登记表单（弹窗打开/提交成功后） */
function resetRegisterForm(): void {
  registerForm.value = {
    patientId: '',
    visitId: '',
    patientName: '',
    bedNo: '',
    nursingLevel: 'NORMAL',
    conditionTags: [],
  };
}

/** visit 号格式：I 前缀 + 13 位数字（I+8 位日期+5 位流水，共 14 字符，§3.3 冻结） */
const VISIT_NO_PATTERN = /^I\d{13}$/;

/** 入区登记提交：显式校验（缺项/格式非法零出网）→ 出网 → 成功关窗重载卡墙。 */
async function onRegister(): Promise<void> {
  if (registering.value) {
    return;
  }
  const form = registerForm.value;
  if (!/^\d+$/.test(form.patientId.trim())) {
    void ElMessage.warning('患者 ID 应为数字编号，请核对住院登记');
    return;
  }
  if (!VISIT_NO_PATTERN.test(form.visitId.trim())) {
    void ElMessage.warning('visit 号应以 I 开头共 14 位（I+日期+流水），请核对入区单');
    return;
  }
  if (form.patientName.trim() === '') {
    void ElMessage.warning('请填写患者姓名');
    return;
  }
  if (form.bedNo.trim() === '') {
    void ElMessage.warning('请填写床位号');
    return;
  }
  if (form.nursingLevel === '') {
    void ElMessage.warning('请选择护理级别');
    return;
  }
  registering.value = true;
  try {
    await wardPatients.register({
      visitId: form.visitId.trim(),
      patientId: form.patientId.trim(),
      wardId: wardId.value,
      bedNo: form.bedNo.trim(),
      patientName: form.patientName.trim(),
      nursingLevel: form.nursingLevel,
      conditionTags: form.conditionTags.join(','),
    });
    void ElMessage.success('入区登记完成');
    registerVisible.value = false;
    resetRegisterForm();
    await loadWard();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    registering.value = false;
  }
}

/* ==================== ② 床位序患者卡墙 ==================== */
const patientList = ref<WardPatientVO[]>([]);
const wardLoading = ref(false);
/** 详情按 visitId 索引（卡墙姓名/角标富化 + ③ 详情面板数据源） */
const detailMap = ref<Record<string, WardPatientDetailVO>>({});
/** 选中患者 visitId（全页患者上下文锚点；null=未选） */
const selectedVisitId = ref<string | null>(null);

/** 床位序患者清单（床位号字符串升序=临床序，spec 冻结语序断言） */
const sortedPatients = computed(() =>
  [...patientList.value].sort((a, b) => (a.bedNo ?? '').localeCompare(b.bedNo ?? '')),
);

/** 病区概览计数（在区/危/重，头部病情计数行） */
const wardCounts = computed(() => {
  let critical = 0;
  let severe = 0;
  for (const patient of patientList.value) {
    const tags = splitTags(detailMap.value[patient.visitId ?? '']?.conditionTags);
    if (tags.includes('CRITICAL')) {
      critical += 1;
    }
    if (tags.includes('SEVERE')) {
      severe += 1;
    }
  }
  return { total: patientList.value.length, critical, severe };
});

/** 卡墙角标行（§3.4：显示优先级 过敏>危>重>风险>其余，上限 4 个 + 溢出 +N） */
function bedFlags(detail: WardPatientDetailVO | undefined): {
  shown: Array<{ cls: string; text: string }>;
  overflow: number;
} {
  if (detail === undefined) {
    return { shown: [], overflow: 0 };
  }
  const ordered: Array<{ cls: string; text: string; rank: number }> = [];
  if (detail.allergyFlag === true) {
    ordered.push({ cls: 'fuy-nursing-flag--danger', text: '敏', rank: 0 });
  }
  for (const tag of splitTags(detail.conditionTags)) {
    const meta = CONDITION_FLAG_META[tag];
    if (meta !== undefined) {
      ordered.push({
        cls: meta.cls,
        text: meta.text,
        rank: tag === 'CRITICAL' ? 1 : tag === 'SEVERE' ? 2 : 4,
      });
    }
  }
  for (const risk of splitTags(detail.riskFlags)) {
    const label = RISK_FLAG_LABELS[risk];
    if (label !== undefined) {
      ordered.push({ cls: 'fuy-nursing-flag--outline', text: label, rank: 3 });
    }
  }
  ordered.sort((a, b) => a.rank - b.rank);
  return {
    shown: ordered.slice(0, 4).map(({ cls, text }) => ({ cls, text })),
    overflow: Math.max(0, ordered.length - 4),
  };
}

/** 卡墙在途任务数（无详情时缺省 0） */
function inFlightCount(detail: WardPatientDetailVO | undefined): number {
  return detail?.inFlightTasks?.length ?? 0;
}

/** 卡墙责任护士（当班分配首条 nurseId 直显；M01 姓名随 P2 组织机构对齐） */
function dutyNurseId(detail: WardPatientDetailVO | undefined): string {
  return detail?.assignments?.[0]?.nurseId ?? '—';
}

/** 病区主加载：一览 → 逐床详情富化（Promise.allSettled 容错，单床失败不阻塞卡墙）→
 * 待复核/分配/任务/交接班并行重载。 */
async function loadWard(): Promise<void> {
  wardLoading.value = true;
  try {
    patientList.value = await wardPatients.list(wardId.value);
    const results = await Promise.allSettled(
      patientList.value.map((patient) => wardPatients.detail(patient.visitId ?? '')),
    );
    const map: Record<string, WardPatientDetailVO> = {};
    results.forEach((result, index) => {
      if (result.status === 'fulfilled') {
        map[patientList.value[index]?.visitId ?? ''] = result.value;
      }
    });
    detailMap.value = map;
    await Promise.all([loadPendingReview(), loadAssignments(), loadTasks(), loadHandoverOfDay()]);
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    wardLoading.value = false;
  }
}

/** 卡墙选中：驱动 ③⑤⑥⑦ 患者上下文 */
function selectPatient(patient: WardPatientVO): void {
  selectedVisitId.value = patient.visitId ?? null;
  void loadLatestVitals();
  void loadChart();
  void loadAssessmentHistory();
}

/** 出区在途标志（防双击重复出区） */
const removing = ref(false);

/** 出区（高风险档 §6.2：danger 确认 + 必填原因 + 回显摘要） */
async function onRemovePatient(detail: WardPatientDetailVO): Promise<void> {
  if (removing.value) {
    return;
  }
  removing.value = true;
  try {
    try {
      const { value } = await ElMessageBox.prompt(
        `即将为 ${detail.bedNo ?? ''} ${detail.patientName ?? ''} 办理出区，出区后不可恢复`,
        '出区确认',
        {
          type: 'warning',
          confirmButtonText: '确认出区',
          confirmButtonClass: 'el-button--danger',
          inputPlaceholder: '出区原因（必填）',
          inputValidator: (input: string) => (input.trim() === '' ? '出区原因不能为空' : true),
        },
      );
      if (value.trim() === '') {
        return;
      }
      await wardPatients.remove(detail.visitId ?? '', { reason: value.trim() });
      void ElMessage.success(`已出区：${detail.bedNo ?? ''} ${detail.patientName ?? ''}`);
      if (selectedVisitId.value === detail.visitId) {
        selectedVisitId.value = null;
      }
      await loadWard();
    } catch {
      // 用户取消或出区失败：取消静默，失败弹错归拦截器
    }
  } finally {
    removing.value = false;
  }
}

/* ==================== ③ 患者详情面板 ==================== */
const selectedDetail = computed<WardPatientDetailVO | undefined>(() =>
  selectedVisitId.value === null ? undefined : detailMap.value[selectedVisitId.value],
);
const selectedPatient = computed<WardPatientVO | undefined>(() =>
  patientList.value.find((patient) => patient.visitId === selectedVisitId.value),
);

/** 最新体征行（前端另调 GET /vital-signs 近 24h 组装，简报冻结口径） */
const latestVitals = ref<VitalSignVO | null>(null);
const latestVitalsLoading = ref(false);

async function loadLatestVitals(): Promise<void> {
  const detail = selectedDetail.value;
  if (detail === undefined || !detail.patientId) {
    latestVitals.value = null;
    return;
  }
  latestVitalsLoading.value = true;
  try {
    const from = new Date(Date.now() - 24 * 3600 * 1000).toISOString();
    const to = new Date().toISOString();
    const rows = await vitalSigns.list({ patientId: String(detail.patientId), from, to });
    latestVitals.value = rows.length > 0 ? (rows[rows.length - 1] ?? null) : null;
  } catch {
    // 失败弹错归响应拦截器；驻留旧体征
  } finally {
    latestVitalsLoading.value = false;
  }
}

/** 体温部位单字符号（详情面板最新体征行 ×/●/〇 直显） */
function tempSiteMark(site: string | undefined): string {
  if (site === 'AXILLARY') {
    return '×';
  }
  if (site === 'ORAL') {
    return '●';
  }
  if (site === 'RECTAL') {
    return '〇';
  }
  return '';
}

/* ==================== ④ 责任护士分配 ==================== */
const assignmentList = ref<NurseAssignmentVO[]>([]);
const assignmentLoading = ref(false);
const assigning = ref(false);
/** 新增分配内联表单（BED 管床需床位；PRIMARY 责任需患者 ID） */
const assignFormVisible = ref(false);
const assignForm = ref({ nurseId: '', assignmentType: 'BED', bedNo: '', patientId: '' });

async function loadAssignments(): Promise<void> {
  assignmentLoading.value = true;
  try {
    assignmentList.value = await assignments.list(wardId.value, shiftCode.value);
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    assignmentLoading.value = false;
  }
}

/** 新增分配：必填面前置校验零出网 → 出网 → 重载（FU-M05-01 拖拽批量分配 P-later 登记）。 */
async function onAssign(): Promise<void> {
  if (assigning.value) {
    return;
  }
  const form = assignForm.value;
  if (form.nurseId.trim() === '') {
    void ElMessage.warning('请填写护士工号');
    return;
  }
  if (form.assignmentType === 'BED' && form.bedNo.trim() === '') {
    void ElMessage.warning('管床分配需填写床位号');
    return;
  }
  assigning.value = true;
  try {
    await assignments.create({
      wardId: wardId.value,
      nurseId: form.nurseId.trim(),
      assignmentType: form.assignmentType,
      shiftCode: shiftCode.value,
      bedNo: form.assignmentType === 'BED' ? form.bedNo.trim() : undefined,
      patientId:
        form.assignmentType === 'PRIMARY' && form.patientId.trim() !== ''
          ? form.patientId.trim()
          : undefined,
    });
    void ElMessage.success('分配已保存');
    assignFormVisible.value = false;
    assignForm.value = { nurseId: '', assignmentType: 'BED', bedNo: '', patientId: '' };
    await loadAssignments();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    assigning.value = false;
  }
}

/** 移除分配（中档确认带回显） */
async function onUnassign(row: NurseAssignmentVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `即将移除护士 ${row.nurseId ?? ''} 的${row.assignmentType === 'BED' ? `管床（${row.bedNo ?? ''}）` : '责任患者分配'}，确认？`,
      '移除分配确认',
      { confirmButtonText: '确认移除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    await assignments.remove(String(row.id ?? ''));
    void ElMessage.success('分配已移除');
    await loadAssignments();
  } catch (error) {
    surfaceBizError(error);
  }
}

/* ==================== ⑤ 体征录入 + 待复核 ==================== */
/** 体征录入表单（文本承载：整数/小数一律经正则+范围双验后数值化，禁裸 parse） */
const vitalForm = ref({
  temperature: '',
  tempSite: 'AXILLARY',
  pulse: '',
  respiration: '',
  systolicBp: '',
  diastolicBp: '',
  spo2: '',
  weight: '',
  height: '',
  painScore: '',
});
const recording = ref(false);

/** 整数字段显式校验（纯数字正则 + 范围判定） */
function isValidInt(raw: string, min: number, max: number): boolean {
  if (!/^\d+$/.test(raw)) {
    return false;
  }
  const value = Number(raw);
  return Number.isInteger(value) && value >= min && value <= max;
}

/** 一位小数字段显式校验（一到三位整数 + 可选一位小数 + 范围判定） */
function isValidDecimal(raw: string, min: number, max: number): boolean {
  if (!/^\d{1,3}(\.\d)?$/.test(raw)) {
    return false;
  }
  const value = Number(raw);
  return value >= min && value <= max;
}

/** 各字段校验器（文案冻结于设计文档 §3.7 表） */
const VITAL_VALIDATORS: Record<string, () => boolean> = {
  temperature: () =>
    vitalForm.value.temperature === '' ||
    isValidDecimal(vitalForm.value.temperature, 35, 42) ||
    warn('体温应为 35.0–42.0 的数值（如 36.5），请重新输入'),
  pulse: () =>
    vitalForm.value.pulse === '' ||
    isValidInt(vitalForm.value.pulse, 20, 250) ||
    warn('脉搏应为 20–250 的整数'),
  respiration: () =>
    vitalForm.value.respiration === '' ||
    isValidInt(vitalForm.value.respiration, 5, 60) ||
    warn('呼吸应为 5–60 的整数'),
  systolicBp: () =>
    vitalForm.value.systolicBp === '' ||
    isValidInt(vitalForm.value.systolicBp, 60, 250) ||
    warn('收缩压应为 60–250 的整数'),
  diastolicBp: () =>
    vitalForm.value.diastolicBp === '' ||
    isValidInt(vitalForm.value.diastolicBp, 30, 180) ||
    warn('舒张压应为 30–180 的整数'),
  spo2: () =>
    vitalForm.value.spo2 === '' ||
    isValidInt(vitalForm.value.spo2, 50, 100) ||
    warn('血氧应为 50–100 的整数'),
  weight: () =>
    vitalForm.value.weight === '' ||
    isValidDecimal(vitalForm.value.weight, 20, 300) ||
    warn('体重应为 20–300 的数值（kg）'),
  height: () =>
    vitalForm.value.height === '' ||
    isValidInt(vitalForm.value.height, 30, 250) ||
    warn('身高应为 30–250 的整数（cm）'),
  painScore: () =>
    vitalForm.value.painScore === '' ||
    isValidInt(vitalForm.value.painScore, 0, 10) ||
    warn('疼痛评分应为 0–10 的整数'),
};

/** 提示并返回 false（校验器短路出口） */
function warn(message: string): boolean {
  void ElMessage.warning(message);
  return false;
}

/** 字段 change 校验（@change 触发单字段；提交时全字段双触发兜底） */
function onVitalFieldChange(field: string): void {
  VITAL_VALIDATORS[field]?.();
}

/** 数值化出参（校验通过后按类型安全转换；体温/体重保留一位小数语义由字符串直转承载） */
function toNumberOrNull(raw: string): number | undefined {
  return raw === '' ? undefined : Number(raw);
}

/** 体征录入提交：全字段显式校验 → 至少一项 → 出网 → 成功清表单并刷新体温单。 */
async function onRecordVitals(): Promise<void> {
  if (recording.value) {
    return;
  }
  if (selectedPatient.value === null || selectedPatient.value === undefined) {
    void ElMessage.warning('请先从床位卡墙选择患者');
    return;
  }
  const form = vitalForm.value;
  const allValid = Object.values(VITAL_VALIDATORS).every((validate) => validate());
  if (!allValid) {
    return;
  }
  if (
    form.temperature === '' &&
    form.pulse === '' &&
    form.respiration === '' &&
    form.systolicBp === '' &&
    form.diastolicBp === '' &&
    form.spo2 === '' &&
    form.weight === '' &&
    form.height === '' &&
    form.painScore === ''
  ) {
    void ElMessage.warning('请至少录入一项体征数据');
    return;
  }
  recording.value = true;
  try {
    await vitalSigns.record({
      visitId: selectedPatient.value.visitId ?? '',
      source: 'MANUAL',
      temperature: toNumberOrNull(form.temperature),
      tempSite: form.temperature === '' ? undefined : form.tempSite,
      pulse: toNumberOrNull(form.pulse),
      respiration: toNumberOrNull(form.respiration),
      systolicBp: toNumberOrNull(form.systolicBp),
      diastolicBp: toNumberOrNull(form.diastolicBp),
      spo2: toNumberOrNull(form.spo2),
      weight: toNumberOrNull(form.weight),
      height: toNumberOrNull(form.height),
      painScore: toNumberOrNull(form.painScore),
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
      weight: '',
      height: '',
      painScore: '',
    };
    await loadChart();
    await loadLatestVitals();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    recording.value = false;
  }
}

/* ---------- 待复核列表 ---------- */
const pendingList = ref<VitalSignVO[]>([]);
const pendingLoading = ref(false);
const confirmingId = ref<string | null>(null);

async function loadPendingReview(): Promise<void> {
  pendingLoading.value = true;
  try {
    pendingList.value = await vitalSigns.pendingReview(wardId.value);
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    pendingLoading.value = false;
  }
}

/** 待复核确认（中档确认带回显「确认将 … 入体温单？」；成功该行移除，spec 冻结语义） */
async function onConfirmVital(row: VitalSignVO): Promise<void> {
  if (confirmingId.value !== null) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认将 ${formatTime(row.measuredAt)} 体温 ${row.temperature ?? '—'} 入体温单？`,
      '体征复核确认',
      { confirmButtonText: '确认', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  confirmingId.value = String(row.id ?? '');
  try {
    await vitalSigns.confirm(String(row.id ?? ''));
    void ElMessage.success('已确认入体温单');
    pendingList.value = pendingList.value.filter((item) => String(item.id) !== String(row.id));
  } catch (error) {
    surfaceBizError(error);
  } finally {
    confirmingId.value = null;
  }
}

/** 待复核驳回（中档确认 + 必填原因） */
async function onRejectVital(row: VitalSignVO): Promise<void> {
  if (confirmingId.value !== null) {
    return;
  }
  try {
    const { value } = await ElMessageBox.prompt(
      `驳回 ${formatTime(row.measuredAt)} 体温 ${row.temperature ?? '—'} 的体征数据`,
      '体征驳回确认',
      {
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '驳回原因（必填）',
        inputValidator: (input: string) => (input.trim() === '' ? '驳回原因不能为空' : true),
      },
    );
    confirmingId.value = String(row.id ?? '');
    await vitalSigns.reject(String(row.id ?? ''), { reason: value.trim() });
    void ElMessage.success('已驳回该体征');
    pendingList.value = pendingList.value.filter((item) => String(item.id) !== String(row.id));
  } catch (error) {
    if (!axios.isAxiosError(error)) {
      // 用户取消弹窗：静默返回（ElMessageBox 取消抛非 Axios 的 reject('cancel')）
      return;
    }
    surfaceBizError(error);
  } finally {
    confirmingId.value = null;
  }
}

/* ==================== ⑥ 体温单渲染区 + 特殊事件 ==================== */
/** 当前月页键（yyyy-MM） */
const chartMonth = ref(
  `${new Date().getFullYear()}-${String(new Date().getMonth() + 1).padStart(2, '0')}`,
);
const chartData = ref<TemperatureChartVO | null>(null);
const chartLoading = ref(false);
/** 月页体征值行（体温/脉搏数值经 vitalRef 关联补齐的取值来源） */
const monthVitals = ref<VitalSignVO[]>([]);

/** 月页起止 ISO（vital-signs 查询窗口） */
function monthWindow(month: string): { from: string; to: string } {
  const [year, mon] = month.split('-').map((part) => Number(part));
  const from = new Date(year, mon - 1, 1);
  const to = new Date(year, mon, 1);
  return { from: from.toISOString(), to: to.toISOString() };
}

/** 月键加减一月 */
function shiftMonth(month: string, delta: number): string {
  const [year, mon] = month.split('-').map((part) => Number(part));
  const date = new Date(year, mon - 1 + delta, 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

/** 早于入院月的上月按钮禁用（§5.8 越界禁用） */
const prevMonthDisabled = computed(() => {
  const admittedAt = selectedDetail.value?.admittedAt;
  if (!admittedAt) {
    return false;
  }
  const admittedMonth = admittedAt.slice(0, 7);
  return chartMonth.value <= admittedMonth;
});

async function loadChart(): Promise<void> {
  const detail = selectedDetail.value;
  if (detail === undefined || !detail.visitId || !detail.patientId) {
    chartData.value = null;
    monthVitals.value = [];
    return;
  }
  chartLoading.value = true;
  try {
    const range = monthWindow(chartMonth.value);
    const [chartPage, vitals] = await Promise.all([
      chart.query(detail.visitId, chartMonth.value),
      vitalSigns.list({ patientId: String(detail.patientId), from: range.from, to: range.to }),
    ]);
    chartData.value = chartPage;
    monthVitals.value = vitals;
  } catch {
    // 失败弹错归响应拦截器；驻留旧月页
  } finally {
    chartLoading.value = false;
  }
}

function onMonthChange(delta: number): void {
  if (delta < 0 && prevMonthDisabled.value) {
    return;
  }
  chartMonth.value = shiftMonth(chartMonth.value, delta);
  void loadChart();
}

/** 体温单渲染模型（纯函数 computed 缓存，§7 单月页节点预算内） */
const chartModel = computed(() =>
  buildTempChart(chartData.value, monthVitals.value, chartMonth.value),
);

/** 日行值单元格取值（按天 × 行键） */
function dailyCellValue(
  day: number,
  rowKey: string,
): { text: string; ruleClass?: string } | undefined {
  const cell = chartModel.value.dailyCells.find(
    (item) => item.day === day && item.rowKey === rowKey,
  );
  return cell === undefined ? undefined : { text: cell.valueText, ruleClass: cell.ruleClass };
}

/* ---------- 特殊事件录入 ---------- */
const specialEventType = ref('ADMISSION');
const specialEventRemark = ref('');
const specialEventRecording = ref(false);

/** 特殊事件记录（§3.8：事件类型 + 备注；时点由后端服务器时间承载 GC25） */
async function onAddSpecialEvent(): Promise<void> {
  if (specialEventRecording.value) {
    return;
  }
  const detail = selectedDetail.value;
  if (detail === undefined || !detail.visitId) {
    void ElMessage.warning('请先从床位卡墙选择患者');
    return;
  }
  specialEventRecording.value = true;
  try {
    await chart.addSpecialEvent(detail.visitId, {
      eventType: specialEventType.value,
      remark: specialEventRemark.value.trim() === '' ? undefined : specialEventRemark.value.trim(),
    });
    void ElMessage.success('特殊事件已记录');
    specialEventRemark.value = '';
    await loadChart();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    specialEventRecording.value = false;
  }
}

/* ==================== ⑦ 护理评估 ==================== */
const scaleList = ref<ScaleDefinitionVO[]>([]);
const scaleType = ref('');
/** 当前量表条目定义（条目码/名称/选项分值对齐展开） */
const currentScale = computed<ScaleDefinitionVO | undefined>(() =>
  scaleList.value.find((scale) => scale.scaleType === scaleType.value),
);
/** 打分表答案（条目码 → 分值） */
const scaleAnswers = ref<Record<string, number>>({});
const assessing = ref(false);
const assessResult = ref<NursingAssessmentVO | null>(null);
const assessHistory = ref<NursingAssessmentVO[]>([]);

/** 量表中文词表（scaleType 五值展示映射） */
const SCALE_TYPE_LABELS: Record<string, string> = {
  BRADEN: 'Braden 压疮',
  MORSE: 'Morse 跌倒',
  NRS: 'NRS 疼痛',
  BARTHEL: 'Barthel 自理',
  MEWS: 'MEWS 早期预警',
};

async function loadScales(): Promise<void> {
  try {
    scaleList.value = await assessments.scales();
    if (scaleList.value.length > 0 && scaleType.value === '') {
      scaleType.value = scaleList.value[0]?.scaleType ?? '';
    }
  } catch {
    // 失败弹错归响应拦截器；评估区块空量表态
  }
}

function onScaleChange(): void {
  scaleAnswers.value = {};
  assessResult.value = null;
}

/** 评估提交：未选患者/未选量表/存在未答条目前置拦截零出网 → 出网 → 结果条 + 历史刷新。 */
async function onAssess(): Promise<void> {
  if (assessing.value) {
    return;
  }
  const detail = selectedDetail.value;
  if (detail === undefined || !detail.visitId) {
    void ElMessage.warning('请先从床位卡墙选择患者');
    return;
  }
  const scale = currentScale.value;
  if (scale === undefined) {
    void ElMessage.warning('请选择评估量表');
    return;
  }
  const unanswered = (scale.itemCodes ?? []).filter(
    (code) => scaleAnswers.value[code] === undefined,
  );
  if (unanswered.length > 0) {
    void ElMessage.warning('存在未作答条目，请完成全部条目后提交');
    return;
  }
  assessing.value = true;
  try {
    assessResult.value = await assessments.create({
      visitId: detail.visitId,
      scaleType: scale.scaleType ?? '',
      answers: { ...scaleAnswers.value },
      assessedAt: new Date().toISOString(),
    });
    void ElMessage.success('评估已提交');
    await loadAssessmentHistory();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    assessing.value = false;
  }
}

async function loadAssessmentHistory(): Promise<void> {
  const detail = selectedDetail.value;
  if (detail === undefined || !detail.visitId) {
    assessHistory.value = [];
    return;
  }
  try {
    assessHistory.value = await assessments.list({ visitId: detail.visitId });
  } catch {
    // 失败弹错归响应拦截器
  }
}

/* ==================== ⑧ 护理任务 + 交接班双签 ==================== */
/** 任务动作在途标志（完成/取消互斥，同任务同时至多一个可发） */
const actingTaskNo = ref<string | null>(null);
const taskList = ref<NursingTaskVO[]>([]);
const taskLoading = ref(false);
const taskStatusFilter = ref('');

async function loadTasks(): Promise<void> {
  taskLoading.value = true;
  try {
    taskList.value = await tasks.list({
      wardId: wardId.value,
      status: taskStatusFilter.value === '' ? undefined : taskStatusFilter.value,
      date: todayString(),
    });
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    taskLoading.value = false;
  }
}

/** 任务行类（§3.10 逾期契约：overdueFlag=true 挂 .fuy-task-overdue，spec 机器判据） */
function taskRowClass({ row }: { row: NursingTaskVO }): string {
  return row.overdueFlag === true ? 'fuy-task-overdue' : '';
}

function taskStatusMeta(status: string | undefined): {
  type: 'primary' | 'warning' | 'success' | 'info';
  text: string;
  strike?: boolean;
} {
  return TASK_STATUS_META[status ?? ''] ?? { type: 'info', text: status ?? '—' };
}

/** 任务患者回显名（visitId → 详情映射姓名；缺详情回退床位号） */
function taskPatientLabel(row: NursingTaskVO): string {
  const detail = detailMap.value[row.visitId ?? ''];
  return detail?.patientName ?? row.bedNo ?? '—';
}

/** 完成任务（中档确认带回显；逾期任务仍可完成——M05 Spec §5 状态机口径） */
async function onCompleteTask(row: NursingTaskVO): Promise<void> {
  if (actingTaskNo.value !== null) {
    return;
  }
  const typeLabel = TASK_TYPE_LABELS[row.taskType ?? ''] ?? row.taskType ?? '';
  try {
    await ElMessageBox.confirm(
      `完成任务 ${row.taskNo ?? ''}（${row.bedNo ?? ''} ${taskPatientLabel(row)} ${typeLabel}）？`,
      '任务完成确认',
      { confirmButtonText: '确认完成', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  actingTaskNo.value = row.taskNo ?? '';
  try {
    await tasks.complete(row.taskNo ?? '');
    void ElMessage.success(`任务已完成：${row.taskNo ?? ''}`);
    await loadTasks();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    actingTaskNo.value = null;
  }
}

/** 取消任务（中档确认 + 必填原因） */
async function onCancelTask(row: NursingTaskVO): Promise<void> {
  if (actingTaskNo.value !== null) {
    return;
  }
  try {
    const { value } = await ElMessageBox.prompt(
      `取消任务 ${row.taskNo ?? ''}（${row.bedNo ?? ''} ${taskPatientLabel(row)}），取消原因必填`,
      '任务取消确认',
      {
        confirmButtonText: '确认取消任务',
        cancelButtonText: '返回',
        inputPlaceholder: '取消原因（必填）',
        inputValidator: (input: string) => (input.trim() === '' ? '取消原因不能为空' : true),
      },
    );
    actingTaskNo.value = row.taskNo ?? '';
    await tasks.cancel(row.taskNo ?? '', { reason: value.trim() });
    void ElMessage.success(`任务已取消：${row.taskNo ?? ''}`);
    await loadTasks();
  } catch (error) {
    if (!axios.isAxiosError(error)) {
      return;
    }
    surfaceBizError(error);
  } finally {
    actingTaskNo.value = null;
  }
}

/* ---------- 交接班双签 ---------- */
const handover = ref<ShiftHandoverVO | null>(null);
const handoverLoading = ref(false);
const generating = ref(false);
const completingHandover = ref(false);
/** 接班护士工号（完成交接必填入参） */
const incomingNurseId = ref('');

async function loadHandoverOfDay(): Promise<void> {
  handoverLoading.value = true;
  try {
    const rows = await handovers.list({ wardId: wardId.value, date: todayString() });
    handover.value = rows.length > 0 ? (rows[rows.length - 1] ?? null) : null;
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    handoverLoading.value = false;
  }
}

/** 生成交接班（SBAR 自动汇总；本班次已存在时后端幂等返回当日材料） */
async function onGenerateHandover(): Promise<void> {
  if (generating.value) {
    return;
  }
  generating.value = true;
  try {
    handover.value = await handovers.generate({ wardId: wardId.value, shiftCode: shiftCode.value });
    void ElMessage.success('交接班材料已生成');
  } catch (error) {
    surfaceBizError(error);
  } finally {
    generating.value = false;
  }
}

/** 完成交接双签（中档确认带回显「交班 X → 接班 Y」；DRAFT 可点 / COMPLETED 置灰 §3.10） */
async function onCompleteHandover(): Promise<void> {
  if (completingHandover.value || handover.value === null) {
    return;
  }
  if (incomingNurseId.value.trim() === '') {
    void ElMessage.warning('请填写接班护士工号');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `交班 ${operatorName.value} → 接班 ${incomingNurseId.value.trim()}，确认完成交接？`,
      '完成交接确认',
      { confirmButtonText: '确认完成交接', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  completingHandover.value = true;
  try {
    handover.value = await handovers.complete(handover.value.handoverNo ?? '', {
      incomingNurseId: incomingNurseId.value.trim(),
    });
    void ElMessage.success('交接已完成');
  } catch (error) {
    surfaceBizError(error);
  } finally {
    completingHandover.value = false;
  }
}

/** 出入量明细快录（⑥ 日行值的生产入口；quantity string 透传零运算） */
const ioVisible = ref(false);
const ioForm = ref({ ioType: 'INTAKE', itemCode: '', quantity: '', unit: 'ml' });
const ioRecording = ref(false);

async function onCreateIoRecord(): Promise<void> {
  if (ioRecording.value) {
    return;
  }
  const detail = selectedDetail.value;
  if (detail === undefined || !detail.visitId) {
    void ElMessage.warning('请先从床位卡墙选择患者');
    return;
  }
  if (ioForm.value.itemCode.trim() === '') {
    void ElMessage.warning('请填写项目编码');
    return;
  }
  if (!/^\d+(\.\d)?$/.test(ioForm.value.quantity.trim())) {
    void ElMessage.warning('数量应为数值（最多一位小数）');
    return;
  }
  ioRecording.value = true;
  try {
    await ioRecords.create({
      visitId: detail.visitId,
      ioType: ioForm.value.ioType,
      itemCode: ioForm.value.itemCode.trim(),
      quantity: ioForm.value.quantity.trim(),
      unit: ioForm.value.unit.trim() === '' ? undefined : ioForm.value.unit.trim(),
      source: 'MANUAL',
    });
    void ElMessage.success('出入量明细已记录');
    ioForm.value = { ioType: 'INTAKE', itemCode: '', quantity: '', unit: 'ml' };
  } catch (error) {
    surfaceBizError(error);
  } finally {
    ioRecording.value = false;
  }
}

onMounted(() => {
  void loadScales();
  void loadWard();
});
</script>

<template>
  <div class="fuy-page ward-board fuy-stagger">
    <!-- ① 病区选择 + 病情计数 + 待复核徽标 + 入区登记 -->
    <header class="ward-board-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <el-select v-model="wardId" class="ward-board-ward" @change="onWardChange">
        <el-option
          v-for="ward in WARD_OPTIONS"
          :key="ward.code"
          :label="ward.label"
          :value="ward.code"
        />
      </el-select>
      <div class="ward-board-counts">
        <span class="fuy-num ward-board-count-total">在区 {{ wardCounts.total }}</span>
        <span class="fuy-num ward-board-count-critical">危 {{ wardCounts.critical }}</span>
        <span class="fuy-num ward-board-count-severe">重 {{ wardCounts.severe }}</span>
      </div>
      <a class="ward-board-review-anchor" href="#ward-pending-review">
        待复核
        <span v-if="pendingList.length > 0" class="ward-board-review-badge fuy-num">{{
          pendingList.length
        }}</span>
      </a>
      <el-button
        type="primary"
        class="ward-board-register-btn"
        :loading="registering"
        :disabled="registering"
        @click="registerVisible = true"
        >入区登记</el-button
      >
      <el-button class="ward-board-refresh" :loading="wardLoading" @click="loadWard"
        >刷新</el-button
      >
    </header>

    <!-- ② 床位序患者卡墙（全宽） -->
    <el-card class="ward-board-wall-card" :style="{ '--fuy-stagger-index': 1 }">
      <template #header>床位卡墙（{{ wardId }}）</template>
      <div v-loading="wardLoading" class="ward-board-wall">
        <TransitionGroup name="fuy-flip" tag="div" class="ward-board-wall-grid">
          <button
            v-for="patient in sortedPatients"
            :key="patient.visitId"
            type="button"
            class="ward-bed-card"
            :class="{ 'is-selected': selectedVisitId === patient.visitId }"
            @click="selectPatient(patient)"
          >
            <div class="ward-bed-card-head">
              <span class="fuy-num ward-bed-no">{{ patient.bedNo }}</span>
              <span class="ward-bed-name">{{
                detailMap[patient.visitId ?? '']?.patientName ?? '—'
              }}</span>
              <span
                v-if="NURSING_LEVEL_BADGE[patient.nursingLevel ?? ''] !== undefined"
                class="fuy-nursing-level-badge"
                :class="NURSING_LEVEL_BADGE[patient.nursingLevel ?? '']"
                >{{
                  NURSING_LEVEL_LABELS[patient.nursingLevel ?? ''] ?? patient.nursingLevel
                }}</span
              >
            </div>
            <div class="ward-bed-flags">
              <span
                v-for="flag in bedFlags(detailMap[patient.visitId ?? '']).shown"
                :key="flag.text + flag.cls"
                class="fuy-nursing-flag"
                :class="flag.cls"
                >{{ flag.text }}</span
              >
              <span
                v-if="bedFlags(detailMap[patient.visitId ?? '']).overflow > 0"
                class="ward-bed-overflow"
                >+{{ bedFlags(detailMap[patient.visitId ?? '']).overflow }}</span
              >
            </div>
            <div class="ward-bed-foot">
              <span>责任 {{ dutyNurseId(detailMap[patient.visitId ?? '']) }}</span>
              <span>{{ inFlightCount(detailMap[patient.visitId ?? '']) }} 在途</span>
            </div>
          </button>
        </TransitionGroup>
        <el-empty
          v-if="sortedPatients.length === 0"
          :image-size="72"
          description="当前病区暂无在区患者"
        />
      </div>
    </el-card>

    <!-- 操作层三列：③④ / ⑤⑦ / ⑧（行级 stagger 承载三列级联） -->
    <el-row class="fuy-stagger" :gutter="16" :style="{ '--fuy-stagger-index': 2 }">
      <el-col :md="24" :lg="6" :style="{ '--fuy-stagger-index': 1 }">
        <!-- ③ 患者详情面板 -->
        <el-card class="ward-board-mid-card">
          <template #header>患者详情</template>
          <div v-loading="latestVitalsLoading">
            <template v-if="selectedDetail !== undefined">
              <div class="ward-detail-head">
                <span class="ward-detail-name">{{ selectedDetail.patientName }}</span>
                <span class="ward-detail-base"
                  >{{ selectedDetail.gender ?? '—' }} / {{ selectedDetail.age ?? '—' }}岁</span
                >
                <span
                  v-if="NURSING_LEVEL_BADGE[selectedDetail.nursingLevel ?? ''] !== undefined"
                  class="fuy-nursing-level-badge"
                  :class="NURSING_LEVEL_BADGE[selectedDetail.nursingLevel ?? '']"
                  >{{ NURSING_LEVEL_LABELS[selectedDetail.nursingLevel ?? ''] ?? '' }}</span
                >
              </div>
              <div class="ward-detail-allergy">
                <span
                  class="fuy-nursing-flag fuy-nursing-flag--danger"
                  :class="{ 'fuy-nursing-flag--outline': selectedDetail.allergyFlag !== true }"
                  >敏</span
                >
                <template v-if="selectedDetail.allergyFlag === true">
                  <span class="ward-detail-allergy-list">{{
                    (selectedDetail.allergies ?? [])
                      .map((item) => item.itemName ?? item.itemCode ?? '')
                      .filter((name) => name !== '')
                      .join('、') || '过敏源未登记'
                  }}</span>
                </template>
                <span v-else class="ward-detail-allergy-none">无已知过敏</span>
              </div>
              <div class="ward-detail-risk">
                <span
                  v-for="risk in splitTags(selectedDetail.riskFlags)"
                  :key="risk"
                  class="fuy-nursing-flag fuy-nursing-flag--outline"
                  >{{ RISK_FLAG_LABELS[risk] ?? risk }}</span
                >
                <span
                  v-if="splitTags(selectedDetail.riskFlags).length === 0"
                  class="ward-detail-allergy-none"
                  >无风险标识</span
                >
              </div>
              <el-descriptions :column="1" border size="small" class="ward-detail-desc">
                <el-descriptions-item label="床位">
                  <span class="fuy-num">{{ selectedDetail.bedNo }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="责任护士">
                  {{ dutyNurseId(selectedDetail) }}
                </el-descriptions-item>
                <el-descriptions-item label="在途任务">
                  {{ inFlightCount(selectedDetail) }} 条
                </el-descriptions-item>
              </el-descriptions>
              <!-- 最新体征行（前端另调 GET /vital-signs 组装，近 24h 末次值） -->
              <div v-if="latestVitals !== null" class="ward-detail-vitals">
                <span class="fuy-num"
                  >体温 {{ latestVitals.temperature ?? '—'
                  }}{{ tempSiteMark(latestVitals.tempSite) }}</span
                >
                <span class="fuy-num">脉搏 {{ latestVitals.pulse ?? '—' }}</span>
                <span class="fuy-num">呼吸 {{ latestVitals.respiration ?? '—' }}</span>
                <span class="fuy-num"
                  >血压 {{ latestVitals.systolicBp ?? '—' }}/{{
                    latestVitals.diastolicBp ?? '—'
                  }}</span
                >
                <span class="fuy-num">血氧 {{ latestVitals.spo2 ?? '—' }}%</span>
                <span class="ward-detail-vitals-time">{{
                  formatTime(latestVitals.measuredAt)
                }}</span>
              </div>
            </template>
            <el-empty v-else :image-size="72" description="从床位卡墙选择患者查看详情" />
          </div>
        </el-card>

        <!-- ④ 责任护士分配 -->
        <el-card class="ward-board-mid-card">
          <template #header>
            <div class="ward-board-card-head">
              <span>责任护士分配</span>
              <el-select v-model="shiftCode" class="ward-board-shift" @change="onShiftChange">
                <el-option
                  v-for="shift in SHIFT_OPTIONS"
                  :key="shift.code"
                  :label="shift.label"
                  :value="shift.code"
                />
              </el-select>
            </div>
          </template>
          <div v-loading="assignmentLoading" class="ward-assign-list">
            <div v-for="row in assignmentList" :key="String(row.id)" class="ward-assign-row">
              <span class="ward-assign-nurse">{{ row.nurseId }}</span>
              <span class="ward-assign-scope fuy-num">{{
                row.assignmentType === 'BED' ? `管床 ${row.bedNo ?? '—'}` : '责任患者'
              }}</span>
              <el-button link type="danger" size="small" @click="onUnassign(row)">移除</el-button>
            </div>
            <el-empty
              v-if="assignmentList.length === 0"
              :image-size="56"
              description="本班次暂无分配，请新增"
            />
          </div>
          <div v-if="!assignFormVisible" class="ward-assign-add">
            <el-button size="small" @click="assignFormVisible = true">新增分配</el-button>
          </div>
          <div v-else class="ward-assign-form">
            <el-input v-model="assignForm.nurseId" placeholder="护士工号" />
            <el-select v-model="assignForm.assignmentType">
              <el-option
                v-for="item in ASSIGNMENT_TYPE_OPTIONS"
                :key="item.code"
                :label="item.label"
                :value="item.code"
              />
            </el-select>
            <el-input
              v-if="assignForm.assignmentType === 'BED'"
              v-model="assignForm.bedNo"
              placeholder="床位号"
            />
            <el-input v-else v-model="assignForm.patientId" placeholder="患者 ID（责任分配）" />
            <div class="ward-assign-form-actions">
              <el-button
                type="primary"
                size="small"
                :loading="assigning"
                :disabled="assigning"
                @click="onAssign"
                >确认</el-button
              >
              <el-button size="small" @click="assignFormVisible = false">取消</el-button>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :md="24" :lg="12" :style="{ '--fuy-stagger-index': 2 }">
        <!-- ⑤ 体征录入 + 待复核列表 -->
        <el-card id="ward-pending-review" class="ward-board-mid-card">
          <template #header>
            <div class="ward-board-card-head">
              <span>体征录入（{{ selectedDetail?.bedNo ?? '未选患者' }}）</span>
              <el-button link type="primary" size="small" @click="ioVisible = !ioVisible">
                {{ ioVisible ? '收起出入量' : '出入量快录' }}
              </el-button>
            </div>
          </template>
          <el-form
            label-position="top"
            size="small"
            class="ward-vital-form"
            :disabled="selectedDetail === undefined"
          >
            <div class="ward-vital-grid">
              <div class="ward-vital-field">
                <label class="ward-vital-label">体温（℃）</label>
                <div class="ward-vital-pair">
                  <input
                    v-model="vitalForm.temperature"
                    class="ward-vital-input ward-vital-input-num"
                    inputmode="decimal"
                    placeholder="36.5"
                    :disabled="selectedDetail === undefined"
                    @change="onVitalFieldChange('temperature')"
                  />
                  <select
                    v-model="vitalForm.tempSite"
                    class="ward-vital-input"
                    :disabled="selectedDetail === undefined"
                  >
                    <option v-for="site in TEMP_SITE_OPTIONS" :key="site.code" :value="site.code">
                      {{ site.label }}
                    </option>
                  </select>
                </div>
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">脉搏（次/分）</label>
                <input
                  v-model="vitalForm.pulse"
                  class="ward-vital-input ward-vital-input-num"
                  inputmode="numeric"
                  placeholder="80"
                  :disabled="selectedDetail === undefined"
                  @change="onVitalFieldChange('pulse')"
                />
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">呼吸（次/分）</label>
                <input
                  v-model="vitalForm.respiration"
                  class="ward-vital-input ward-vital-input-num"
                  inputmode="numeric"
                  placeholder="18"
                  :disabled="selectedDetail === undefined"
                  @change="onVitalFieldChange('respiration')"
                />
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">血压（mmHg）</label>
                <div class="ward-vital-pair">
                  <input
                    v-model="vitalForm.systolicBp"
                    class="ward-vital-input ward-vital-input-num"
                    inputmode="numeric"
                    placeholder="120"
                    :disabled="selectedDetail === undefined"
                    @change="onVitalFieldChange('systolicBp')"
                  />
                  <input
                    v-model="vitalForm.diastolicBp"
                    class="ward-vital-input ward-vital-input-num"
                    inputmode="numeric"
                    placeholder="80"
                    :disabled="selectedDetail === undefined"
                    @change="onVitalFieldChange('diastolicBp')"
                  />
                </div>
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">血氧（%）</label>
                <input
                  v-model="vitalForm.spo2"
                  class="ward-vital-input ward-vital-input-num"
                  inputmode="numeric"
                  placeholder="98"
                  :disabled="selectedDetail === undefined"
                  @change="onVitalFieldChange('spo2')"
                />
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">体重（kg）</label>
                <input
                  v-model="vitalForm.weight"
                  class="ward-vital-input ward-vital-input-num"
                  inputmode="decimal"
                  placeholder="60"
                  :disabled="selectedDetail === undefined"
                  @change="onVitalFieldChange('weight')"
                />
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">身高（cm）</label>
                <input
                  v-model="vitalForm.height"
                  class="ward-vital-input ward-vital-input-num"
                  inputmode="numeric"
                  placeholder="170"
                  :disabled="selectedDetail === undefined"
                  @change="onVitalFieldChange('height')"
                />
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">疼痛评分（NRS）</label>
                <input
                  v-model="vitalForm.painScore"
                  class="ward-vital-input ward-vital-input-num"
                  inputmode="numeric"
                  placeholder="0"
                  :disabled="selectedDetail === undefined"
                  @change="onVitalFieldChange('painScore')"
                />
              </div>
            </div>
            <el-button
              type="primary"
              class="ward-vital-submit"
              :loading="recording"
              :disabled="recording || selectedDetail === undefined"
              @click="onRecordVitals"
              >录入体征</el-button
            >
            <p v-if="selectedDetail === undefined" class="ward-vital-hint">先选择患者</p>
          </el-form>
          <!-- 出入量快录（收起形态默认） -->
          <div v-if="ioVisible" class="ward-io-form">
            <select v-model="ioForm.ioType" class="ward-vital-input">
              <option v-for="item in IO_TYPE_OPTIONS" :key="item.code" :value="item.code">
                {{ item.label }}
              </option>
            </select>
            <input v-model="ioForm.itemCode" class="ward-vital-input" placeholder="项目编码" />
            <input
              v-model="ioForm.quantity"
              class="ward-vital-input ward-vital-input-num"
              inputmode="decimal"
              placeholder="数量"
            />
            <input
              v-model="ioForm.unit"
              class="ward-vital-input ward-vital-unit"
              placeholder="单位"
            />
            <el-button
              size="small"
              :loading="ioRecording"
              :disabled="ioRecording"
              @click="onCreateIoRecord"
              >记录出入量</el-button
            >
          </div>
          <!-- 待复核列表（确认成功该行移除，spec 冻结语义） -->
          <h4 class="fuy-section-title">待复核体征</h4>
          <el-table
            v-if="pendingList.length > 0"
            :data="pendingList"
            class="fuy-dense"
            size="small"
          >
            <el-table-column label="时点" width="120">
              <template #default="{ row }">
                <span class="fuy-num">{{ formatTime(row.measuredAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="体温" width="80">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.temperature ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="脉搏" width="70">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.pulse ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="呼吸" width="70">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.respiration ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="血压" width="100">
              <template #default="{ row }">
                <span class="fuy-num"
                  >{{ row.systolicBp ?? '—' }}/{{ row.diastolicBp ?? '—' }}</span
                >
              </template>
            </el-table-column>
            <el-table-column label="来源" width="80">
              <template #default="{ row }">
                {{ row.source === 'IOT' ? 'IoT' : row.source === 'PDA' ? 'PDA' : '一体机' }}
              </template>
            </el-table-column>
            <el-table-column label="质量" width="90">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.iotQuality ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" class-name="fuy-ops-8">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  size="small"
                  :disabled="confirmingId !== null"
                  @click="onConfirmVital(row)"
                  >确认</el-button
                >
                <el-button
                  link
                  type="danger"
                  size="small"
                  :disabled="confirmingId !== null"
                  @click="onRejectVital(row)"
                  >驳回</el-button
                >
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else :image-size="56" description="暂无待复核体征" />
        </el-card>

        <!-- ⑦ 护理评估 -->
        <el-card class="ward-board-mid-card">
          <template #header>
            <div class="ward-board-card-head">
              <span>护理评估（{{ selectedDetail?.patientName ?? '未选患者' }}）</span>
              <el-select v-model="scaleType" class="ward-board-scale" @change="onScaleChange">
                <el-option
                  v-for="scale in scaleList"
                  :key="scale.scaleType"
                  :label="SCALE_TYPE_LABELS[scale.scaleType ?? ''] ?? scale.scaleType"
                  :value="scale.scaleType ?? ''"
                />
              </el-select>
            </div>
          </template>
          <template v-if="currentScale !== undefined">
            <div
              v-for="(code, index) in currentScale.itemCodes ?? []"
              :key="code"
              class="ward-scale-item"
            >
              <span class="ward-scale-item-name">{{
                currentScale.itemLabels?.[index] ?? code
              }}</span>
              <el-radio-group v-model="scaleAnswers[code]" size="small">
                <el-radio-button
                  v-for="score in currentScale.choices?.[code] ?? []"
                  :key="score"
                  :value="score"
                >
                  {{ score }}
                </el-radio-button>
              </el-radio-group>
            </div>
            <el-button
              type="primary"
              class="ward-scale-submit"
              :loading="assessing"
              :disabled="assessing || selectedDetail === undefined"
              @click="onAssess"
              >提交评估</el-button
            >
            <!-- 结果条（§3.9 契约：HIGH 挂 .fuy-assess-result--high + 「高风险」文案） -->
            <div
              v-if="assessResult !== null"
              class="ward-assess-result"
              :class="{ 'fuy-assess-result--high': assessResult.riskLevel === 'HIGH' }"
            >
              <span class="fuy-num ward-assess-total">{{ assessResult.totalScore }}</span>
              <el-tag
                v-if="RISK_LEVEL_META[assessResult.riskLevel ?? ''] !== undefined"
                :type="RISK_LEVEL_META[assessResult.riskLevel ?? '']?.type"
                class="fuy-tag-aa"
                >{{ RISK_LEVEL_META[assessResult.riskLevel ?? '']?.text }}</el-tag
              >
              <span class="ward-assess-scale">{{
                SCALE_TYPE_LABELS[assessResult.scaleType ?? ''] ?? assessResult.scaleType
              }}</span>
              <span class="ward-assess-time">{{ formatTime(assessResult.assessedAt) }}</span>
            </div>
          </template>
          <el-empty v-else :image-size="56" description="量表定义加载中或不可用" />
          <!-- 最近评估历史 -->
          <div v-if="assessHistory.length > 0" class="ward-assess-history">
            <span
              v-for="row in assessHistory.slice(-3).reverse()"
              :key="String(row.id)"
              class="ward-assess-history-row"
            >
              {{ SCALE_TYPE_LABELS[row.scaleType ?? ''] ?? row.scaleType }} · 总分
              <span class="fuy-num">{{ row.totalScore ?? '—' }}</span>
              · {{ RISK_LEVEL_META[row.riskLevel ?? '']?.text ?? row.riskLevel ?? '—' }} ·
              {{ formatTime(row.assessedAt) }}
            </span>
          </div>
        </el-card>
      </el-col>

      <el-col :md="24" :lg="6" :style="{ '--fuy-stagger-index': 3 }">
        <!-- ⑧ 任务列表 + 交接班双签 -->
        <el-card class="ward-board-mid-card">
          <template #header>
            <div class="ward-board-card-head">
              <span>护理任务</span>
              <el-select
                v-model="taskStatusFilter"
                class="ward-board-task-filter"
                size="small"
                @change="loadTasks"
              >
                <el-option label="全部状态" value="" />
                <el-option label="待执行" value="PENDING" />
                <el-option label="执行中" value="IN_PROGRESS" />
                <el-option label="已完成" value="COMPLETED" />
                <el-option label="已取消" value="CANCELLED" />
              </el-select>
            </div>
          </template>
          <div v-loading="taskLoading">
            <el-table
              v-if="taskList.length > 0"
              :data="taskList"
              class="fuy-dense"
              size="small"
              :row-class-name="taskRowClass"
            >
              <el-table-column label="任务号" min-width="110">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.taskNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="80">
                <template #default="{ row }">
                  {{ TASK_TYPE_LABELS[row.taskType ?? ''] ?? row.taskType }}
                </template>
              </el-table-column>
              <el-table-column label="患者" min-width="110">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.bedNo }}</span> {{ taskPatientLabel(row) }}
                </template>
              </el-table-column>
              <el-table-column label="计划时间" width="110">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.planTime) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="责任护士" width="90">
                <template #default="{ row }">
                  {{ row.assignedNurse ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag
                    size="small"
                    :type="taskStatusMeta(row.status).type"
                    class="fuy-tag-aa"
                    :class="{ 'fuy-tag-strike': taskStatusMeta(row.status).strike }"
                    >{{ taskStatusMeta(row.status).text }}</el-tag
                  >
                  <!-- 逾期动作标记（不改状态，仍可完成 §3.10） -->
                  <el-tag
                    v-if="row.overdueFlag === true"
                    size="small"
                    type="danger"
                    class="fuy-tag-aa"
                    >逾期</el-tag
                  >
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" class-name="fuy-ops-8">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    size="small"
                    :disabled="actingTaskNo !== null"
                    @click="onCompleteTask(row)"
                    >完成</el-button
                  >
                  <el-button
                    link
                    type="danger"
                    size="small"
                    :disabled="actingTaskNo !== null"
                    @click="onCancelTask(row)"
                    >取消</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="56" description="当前病区暂无护理任务" />
          </div>
        </el-card>

        <!-- 交接班双签 -->
        <el-card class="ward-board-mid-card">
          <template #header>
            <div class="ward-board-card-head">
              <span>交接班双签</span>
              <el-button
                v-if="handover === null"
                type="primary"
                size="small"
                :loading="generating"
                :disabled="generating"
                @click="onGenerateHandover"
                >生成交接班</el-button
              >
            </div>
          </template>
          <div v-loading="handoverLoading">
            <template v-if="handover !== null">
              <!-- 患者摘要行 -->
              <div class="ward-handover-summary fuy-num">
                <span>总数 {{ handover.patientSummary?.total ?? 0 }}</span>
                <span>病危 {{ handover.patientSummary?.criticalCount ?? 0 }}</span>
                <span>病重 {{ handover.patientSummary?.specialCount ?? 0 }}</span>
                <span>新入 {{ handover.patientSummary?.newAdmissionCount ?? 0 }}</span>
                <span>手术 {{ handover.patientSummary?.surgeryCount ?? 0 }}</span>
                <span>转出 {{ handover.patientSummary?.transferOutCount ?? 0 }}</span>
                <span>今日出院 {{ handover.patientSummary?.todayDischargeCount ?? 0 }}</span>
              </div>
              <!-- SBAR 四段 -->
              <div class="ward-handover-sbar">
                <h4 class="fuy-section-title">现状 S</h4>
                <p class="ward-handover-text">{{ handover.sbarSituation ?? '—' }}</p>
                <h4 class="fuy-section-title">背景 B</h4>
                <p class="ward-handover-text">{{ handover.sbarBackground ?? '—' }}</p>
                <h4 class="fuy-section-title">评估 A</h4>
                <p class="ward-handover-text">{{ handover.sbarAssessment ?? '—' }}</p>
                <h4 class="fuy-section-title">建议 R</h4>
                <p class="ward-handover-text">{{ handover.sbarRecommendation ?? '—' }}</p>
              </div>
              <p class="ward-handover-pending">
                待续事项 <span class="fuy-num">{{ handover.pendingItems?.length ?? 0 }}</span> 条
              </p>
              <!-- 双签按钮契约（§3.10：DRAFT 可点 / COMPLETED disabled「已完成交接」） -->
              <div class="ward-handover-sign">
                <el-input
                  v-model="incomingNurseId"
                  placeholder="接班护士工号"
                  size="small"
                  class="ward-handover-incoming"
                  :disabled="handover.status === 'COMPLETED'"
                />
                <el-button
                  type="primary"
                  size="small"
                  :loading="completingHandover"
                  :disabled="completingHandover || handover.status === 'COMPLETED'"
                  @click="onCompleteHandover"
                  >{{ handover.status === 'COMPLETED' ? '已完成交接' : '完成交接' }}</el-button
                >
              </div>
            </template>
            <el-empty
              v-else
              :image-size="56"
              description="点击生成本班交接班材料（SBAR 自动汇总）"
            />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ⑥ 体温单渲染区（全宽置底） -->
    <el-card class="ward-board-chart-card" :style="{ '--fuy-stagger-index': 3 }">
      <template #header>
        <div class="ward-board-card-head">
          <span>体温单（{{ selectedDetail?.patientName ?? '未选患者' }}）</span>
          <!-- 图例（静态常驻，符号语义自助解读 §3.8） -->
          <span class="ward-chart-legend">
            <i class="ward-chart-legend-x" />腋温 <i class="ward-chart-legend-dot" />口温
            <i class="ward-chart-legend-circle" />肛温 <i class="ward-chart-legend-pulse" />脉率
          </span>
          <div class="ward-chart-month">
            <el-button size="small" :disabled="prevMonthDisabled" @click="onMonthChange(-1)"
              >上月</el-button
            >
            <span class="fuy-num ward-chart-month-text">{{ chartMonth }}</span>
            <el-button size="small" @click="onMonthChange(1)">下月</el-button>
          </div>
        </div>
      </template>
      <template v-if="selectedDetail !== undefined">
        <!-- 月页/患者切换 content-fade 200ms 显隐（曲线不做位移动画，防趋势误读 §3.8） -->
        <Transition name="fuy-content-fade" mode="out-in">
          <div
            v-loading="chartLoading"
            :key="`${chartMonth}-${selectedVisitId ?? 'none'}`"
            class="ward-chart-scroll"
          >
            <div class="ward-chart-inner">
              <svg
                class="ward-chart-svg"
                :width="chartModel.width"
                :height="chartModel.height + 20"
                :viewBox="`0 0 ${chartModel.width} ${chartModel.height + 20}`"
                role="img"
                aria-label="体温单曲线区"
              >
                <!-- 网格（普通 0.5px / major 1px） -->
                <line
                  v-for="grid in chartModel.grids"
                  :key="grid.key"
                  class="fuy-chart-grid"
                  :class="{ 'fuy-chart-grid--major': grid.major }"
                  :x1="grid.vertical ? grid.pos : AXIS_WIDTH"
                  :y1="grid.vertical ? 0 : grid.pos"
                  :x2="grid.vertical ? grid.pos : chartModel.width - AXIS_WIDTH"
                  :y2="grid.vertical ? chartModel.height : grid.pos"
                />
                <!-- 左轴体温刻度 / 右轴脉搏刻度 / X 轴日号 -->
                <text
                  v-for="tick in chartModel.tempTicks"
                  :key="`t-${tick.value}`"
                  class="fuy-chart-tick"
                  :x="AXIS_WIDTH - 6"
                  :y="tick.y + 4"
                  text-anchor="end"
                >
                  {{ tick.value }}
                </text>
                <text
                  v-for="tick in chartModel.pulseTicks"
                  :key="`p-${tick.value}`"
                  class="fuy-chart-tick"
                  :x="chartModel.width - AXIS_WIDTH + 6"
                  :y="tick.y + 4"
                  text-anchor="start"
                >
                  {{ tick.value }}
                </text>
                <text
                  v-for="tick in chartModel.dayTicks"
                  :key="`d-${tick.day}`"
                  class="fuy-chart-tick"
                  :x="tick.x"
                  :y="chartModel.height + 16"
                  text-anchor="middle"
                >
                  {{ tick.day }}
                </text>
                <!-- 曲线连线（体温蓝实线/脉率红线/降温红虚线/短绌填充线） -->
                <line
                  v-for="lineNode in chartModel.lines"
                  :key="lineNode.key"
                  :class="lineNode.className"
                  :x1="lineNode.x1"
                  :y1="lineNode.y1"
                  :x2="lineNode.x2"
                  :y2="lineNode.y2"
                />
                <!-- 特殊事件竖线（贯穿曲线区；呼吸心跳停止双竖线） -->
                <template v-for="eventNode in chartModel.events" :key="eventNode.key">
                  <line
                    :class="eventNode.className"
                    :x1="eventNode.x"
                    :y1="0"
                    :x2="eventNode.x"
                    :y2="chartModel.height"
                  />
                  <line
                    v-if="eventNode.double"
                    :class="eventNode.className"
                    :x1="eventNode.x + 2"
                    :y1="0"
                    :x2="eventNode.x + 2"
                    :y2="chartModel.height"
                  />
                  <text class="fuy-event-label" :x="eventNode.x + 4" :y="12">
                    {{ eventNode.label }}
                  </text>
                </template>
                <!-- 数据点符号（×/●/〇/脉率点/降温红圈/重叠外圈，title 无障碍读法） -->
                <template v-for="symbol in chartModel.symbols" :key="symbol.key">
                  <g v-if="symbol.className === 'fuy-temp-x'" :class="symbol.className">
                    <line
                      :x1="symbol.x - 3.5"
                      :y1="symbol.y - 3.5"
                      :x2="symbol.x + 3.5"
                      :y2="symbol.y + 3.5"
                    />
                    <line
                      :x1="symbol.x - 3.5"
                      :y1="symbol.y + 3.5"
                      :x2="symbol.x + 3.5"
                      :y2="symbol.y - 3.5"
                    />
                    <title>{{ symbol.title }}</title>
                  </g>
                  <circle
                    v-else
                    :class="symbol.className"
                    :cx="symbol.x"
                    :cy="symbol.y"
                    :r="symbol.className === 'fuy-temp-overlap-ring' ? 6 : 4"
                  >
                    <title>{{ symbol.title }}</title>
                  </circle>
                </template>
              </svg>
              <!-- 日行值底栏（与 X 轴日列对齐：label 列 88px + SVG 左移 88px 后日列同起点） -->
              <table class="ward-chart-daily">
                <tbody>
                  <tr v-for="row in DAILY_ROWS" :key="row.key">
                    <th class="ward-chart-daily-label">{{ row.label }}</th>
                    <td
                      v-for="day in chartModel.days"
                      :key="`${row.key}-${day}`"
                      class="fuy-num ward-chart-daily-cell"
                      :class="dailyCellValue(day, row.key)?.ruleClass"
                    >
                      {{ row.key === 'DATE' ? day : (dailyCellValue(day, row.key)?.text ?? '') }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </Transition>
        <!-- 特殊事件录入行（事件时点由后端服务器时间承载） -->
        <div class="ward-chart-event-row">
          <el-select v-model="specialEventType" class="ward-chart-event-type">
            <el-option
              v-for="item in SPECIAL_EVENT_OPTIONS"
              :key="item.code"
              :label="item.label"
              :value="item.code"
            />
          </el-select>
          <el-input
            v-model="specialEventRemark"
            placeholder="备注（选填）"
            class="ward-chart-event-remark"
          />
          <el-button
            type="primary"
            :loading="specialEventRecording"
            :disabled="specialEventRecording"
            @click="onAddSpecialEvent"
            >记录</el-button
          >
          <el-button
            v-if="selectedDetail !== undefined"
            link
            type="danger"
            size="small"
            class="ward-chart-exit"
            @click="onRemovePatient(selectedDetail)"
            >出区</el-button
          >
        </div>
      </template>
      <el-empty v-else :image-size="72" description="从床位卡墙选择患者查看体温单" />
    </el-card>

    <!-- 入区登记弹窗（520px 固定宽，§3.3） -->
    <el-dialog
      v-model="registerVisible"
      title="入区登记"
      width="520px"
      destroy-on-close
      class="ward-register-dialog"
    >
      <el-form label-width="90px">
        <el-form-item label="患者 ID" required>
          <el-input
            v-model="registerForm.patientId"
            placeholder="数字编号"
            class="ward-register-input"
          />
        </el-form-item>
        <el-form-item label="visit 号" required>
          <el-input
            v-model="registerForm.visitId"
            placeholder="I + 13 位数字"
            class="ward-register-input"
          />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input
            v-model="registerForm.patientName"
            placeholder="患者姓名"
            class="ward-register-name"
          />
        </el-form-item>
        <el-form-item label="床位" required>
          <el-input v-model="registerForm.bedNo" placeholder="如 03-01" class="ward-register-bed" />
        </el-form-item>
        <el-form-item label="护理级别" required>
          <el-select v-model="registerForm.nursingLevel" class="ward-register-bed">
            <el-option
              v-for="level in NURSING_LEVEL_OPTIONS"
              :key="level.code"
              :label="level.label"
              :value="level.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="病情标记">
          <el-select v-model="registerForm.conditionTags" multiple class="ward-register-input">
            <el-option
              v-for="tag in CONDITION_TAG_OPTIONS"
              :key="tag.code"
              :label="tag.label"
              :value="tag.code"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" :loading="registering" :disabled="registering" @click="onRegister"
          >确认登记</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* ① 操作条 */
.ward-board-ward {
  width: 180px;
}
.ward-board-counts {
  display: flex;
  align-items: baseline;
  gap: var(--fuy-space-3);
}
.ward-board-count-total {
  font-size: var(--fuy-font-size-3xl);
  font-weight: 700;
  line-height: 1.2;
}
.ward-board-count-critical {
  color: var(--fuy-color-nursing-critical);
  font-size: var(--fuy-font-size-sm);
  font-weight: 600;
}
.ward-board-count-severe {
  color: var(--fuy-color-nursing-serious);
  font-size: var(--fuy-font-size-sm);
  font-weight: 600;
}
.ward-board-review-anchor {
  position: relative;
  margin-left: auto;
  color: var(--fuy-color-danger-text);
  font-size: var(--fuy-font-size-md);
  text-decoration: none;
}
/* 待复核徽标（红底白字圆形 18px，§3.1） */
.ward-board-review-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  margin-left: 4px;
  padding: 0 4px;
  border-radius: var(--fuy-radius-full);
  background: var(--fuy-color-nursing-critical);
  color: var(--el-color-white);
  font-size: var(--fuy-font-size-xs);
  font-weight: 700;
}
.ward-board-register-btn {
  width: 96px;
}

/* ② 卡墙（lg 8 列 / xl 10 列，min-height 240px CLS 锁） */
.ward-board-wall {
  min-height: 240px;
}
.ward-board-wall-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 8px;
}
.ward-bed-card {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 148px;
  min-height: 84px;
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: 1px solid #e5e7eb;
  border-radius: var(--fuy-radius-lg);
  background: var(--el-bg-color);
  text-align: left;
  cursor: pointer;
  /* 选中态 120ms 描边/底色过渡（PR-5 号源卡选中同形态） */
  transition:
    border-color var(--fuy-motion-fast) linear,
    background-color var(--fuy-motion-fast) linear;
}
.ward-bed-card.is-selected {
  border: 2px solid var(--fuy-color-brand);
  background: var(--fuy-palette-brand-100);
}
.ward-bed-card.is-selected::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  border-radius: var(--fuy-radius-full);
  background: var(--fuy-color-brand);
}
.ward-bed-card-head {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}
.ward-bed-no {
  font-size: var(--fuy-font-size-lg);
  font-weight: 700;
}
.ward-bed-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
}
.ward-bed-flags {
  display: flex;
  gap: 4px;
}
.ward-bed-overflow {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.ward-bed-foot {
  display: flex;
  justify-content: space-between;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}

/* ③④⑤⑦⑧ 三列卡片 */
.ward-board-mid-card {
  margin-bottom: var(--fuy-space-3);
}
.ward-board-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-2);
}
.ward-board-shift {
  width: 100px;
}
.ward-board-scale {
  width: 200px;
}
.ward-board-task-filter {
  width: 110px;
}

/* ③ 详情面板 */
.ward-detail-head {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}
.ward-detail-name {
  font-size: var(--fuy-font-size-lg);
  font-weight: 600;
}
.ward-detail-base {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.ward-detail-allergy {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  margin: var(--fuy-space-2) 0;
}
.ward-detail-allergy-list {
  color: var(--fuy-color-danger-text);
  font-size: var(--fuy-font-size-sm);
}
.ward-detail-allergy-none {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.ward-detail-risk {
  display: flex;
  gap: 4px;
  margin-bottom: var(--fuy-space-2);
}
.ward-detail-desc {
  margin-bottom: var(--fuy-space-2);
}
.ward-detail-vitals {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-2) var(--fuy-space-3);
  font-size: var(--fuy-font-size-md);
}
.ward-detail-vitals-time {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}

/* ④ 分配 */
.ward-assign-list {
  min-height: 80px;
}
.ward-assign-row {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  height: 36px;
}
.ward-assign-nurse {
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
}
.ward-assign-scope {
  flex: 1;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.ward-assign-add {
  margin-top: var(--fuy-space-2);
}
.ward-assign-form {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-2);
  margin-top: var(--fuy-space-2);
}
.ward-assign-form .ward-vital-input,
.ward-assign-form .el-input,
.ward-assign-form .el-select {
  width: 100%;
}
.ward-assign-form-actions {
  display: flex;
  gap: var(--fuy-space-2);
  width: 100%;
}

/* ⑤ 体征录入（两行 × 四列 grid，min-height 240px CLS 锁） */
.ward-vital-form {
  min-height: 240px;
}
.ward-vital-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(170px, 1fr));
  gap: var(--fuy-space-3);
}
.ward-vital-field {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
}
.ward-vital-label {
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
}
.ward-vital-pair {
  display: flex;
  gap: var(--fuy-space-1);
}
/* 原生数值输入（数值字段文本承载显式校验；EP 输入组件经禁用态联动承载未选患者灰化） */
.ward-vital-input {
  height: 24px;
  padding: 0 6px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--el-bg-color);
  min-width: 0;
  flex: 1;
}
.ward-vital-input:disabled {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-placeholder);
  cursor: not-allowed;
}
.ward-vital-input:focus-visible {
  outline: 2px solid var(--fuy-color-brand);
  outline-offset: 0;
}
.ward-vital-input-num {
  width: 80px;
  flex: none;
}
.ward-vital-pair .ward-vital-input:not(.ward-vital-input-num) {
  width: 88px;
  flex: none;
}
.ward-vital-submit {
  margin-top: var(--fuy-space-3);
  width: 96px;
}
.ward-vital-hint {
  margin: var(--fuy-space-2) 0 0;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.ward-io-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
  margin-top: var(--fuy-space-3);
}
.ward-vital-unit {
  width: 56px;
  flex: none;
}

/* ⑦ 评估 */
.ward-scale-item {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-3);
  min-height: 32px;
}
.ward-scale-item-name {
  flex: 1;
  font-size: var(--fuy-font-size-md);
}
.ward-scale-submit {
  margin-top: var(--fuy-space-3);
  width: 96px;
}
/* 结果条（content-fade 出现；HIGH 高危红描边 + 8% 红底，§3.9 契约） */
.ward-assess-result {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-3);
  margin-top: var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: 1px solid var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-md);
}
.ward-assess-result.fuy-assess-result--high {
  border: 1px solid var(--fuy-color-danger-text);
  background: rgba(185, 28, 28, 0.08);
}
.ward-assess-total {
  font-size: var(--fuy-font-size-3xl);
  font-weight: 700;
  line-height: 1.2;
}
.ward-assess-scale,
.ward-assess-time {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.ward-assess-history {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
  margin-top: var(--fuy-space-3);
}
.ward-assess-history-row {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}

/* ⑧ 任务与交接班 */
.ward-handover-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-2) var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
}
.ward-handover-sbar .fuy-section-title {
  margin: var(--fuy-space-2) 0 var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
}
.ward-handover-text {
  margin: 0;
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
}
.ward-handover-pending {
  margin: var(--fuy-space-2) 0 0;
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}
.ward-handover-sign {
  display: flex;
  gap: var(--fuy-space-2);
  margin-top: var(--fuy-space-3);
}
.ward-handover-incoming {
  flex: 1;
}
/* 任务逾期行（左缘 3px 红条 + 计划时间红字，§3.10 契约；表格行类经 :row-class-name 挂载，
   scoped 零哈希匹配——全局层 deep 承载） */
:deep(.fuy-task-overdue) td {
  position: relative;
}
:deep(.fuy-task-overdue) td:first-child::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--fuy-color-danger-text);
}
:deep(.fuy-task-overdue) td .cell {
  color: var(--fuy-color-danger-text);
}

/* ⑥ 体温单（全宽，min-height 420px CLS 锁） */
.ward-board-chart-card {
  min-height: 420px;
}
.ward-chart-legend {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.ward-chart-legend i {
  display: inline-block;
  width: 10px;
  height: 10px;
}
.ward-chart-legend-x {
  position: relative;
}
.ward-chart-legend-x::before,
.ward-chart-legend-x::after {
  content: '';
  position: absolute;
  left: 4px;
  top: 0;
  width: 1.5px;
  height: 10px;
  background: var(--fuy-chart-temp-color);
}
.ward-chart-legend-x::before {
  transform: rotate(45deg);
}
.ward-chart-legend-x::after {
  transform: rotate(-45deg);
}
.ward-chart-legend-dot {
  border-radius: 50%;
  background: var(--fuy-chart-temp-color);
}
.ward-chart-legend-circle {
  border-radius: 50%;
  border: 1.5px solid var(--fuy-chart-temp-color);
}
.ward-chart-legend-pulse {
  border-radius: 50%;
  background: var(--fuy-chart-pulse-color);
}
.ward-chart-month {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}
.ward-chart-month-text {
  font-size: var(--fuy-font-size-md);
  font-weight: 700;
}
.ward-chart-scroll {
  overflow-x: auto;
  min-height: 320px;
}
.ward-chart-inner {
  display: inline-block;
  min-width: max-content;
}
/* SVG 左移 88px 与底栏 label 列对齐（日列同起点 120px） */
.ward-chart-svg {
  margin-left: 88px;
  display: block;
}
.ward-chart-daily {
  border-collapse: collapse;
  table-layout: fixed;
  margin-top: var(--fuy-space-2);
}
.ward-chart-daily-label {
  width: 88px;
  padding: 2px 6px;
  text-align: left;
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  color: var(--fuy-chart-text-color);
  white-space: nowrap;
}
.ward-chart-daily-cell {
  width: 192px;
  min-width: 192px;
  max-width: 192px;
  padding: 2px 4px;
  text-align: center;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-chart-text-color);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 出入量班次小结/24h 总结红双线（§5.3 S14/S15；<3px 退化单线，3px/4px 保证双线可见） */
.ward-chart-daily-cell.fuy-io-summary-rule--shift {
  border-top: 3px double var(--fuy-chart-pulse-color);
}
.ward-chart-daily-cell.fuy-io-summary-rule--24h {
  border-top: 4px double var(--fuy-chart-pulse-color);
  font-weight: 700;
}
.ward-chart-event-row {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  margin-top: var(--fuy-space-3);
  min-height: 40px;
}
.ward-chart-event-type {
  width: 160px;
}
.ward-chart-event-remark {
  flex: 1;
}
.ward-chart-exit {
  margin-left: auto;
}

/* 体温单 SVG 符号（§5.4 冻结样式：类只控色与线宽，几何在节点属性） */
.fuy-chart-grid {
  stroke: var(--fuy-chart-grid-color);
  stroke-width: 0.5;
}
.fuy-chart-grid--major {
  stroke-width: 1;
}
.fuy-chart-tick {
  fill: var(--fuy-chart-text-color);
  font-size: var(--fuy-font-size-xs);
}
.fuy-temp-x {
  stroke: var(--fuy-chart-temp-color);
  stroke-width: var(--fuy-chart-line-width);
}
.fuy-temp-dot {
  fill: var(--fuy-chart-temp-color);
}
.fuy-temp-circle {
  fill: none;
  stroke: var(--fuy-chart-temp-color);
  stroke-width: var(--fuy-chart-line-width);
}
.fuy-temp-line {
  stroke: var(--fuy-chart-temp-color);
  stroke-width: var(--fuy-chart-line-width);
  fill: none;
}
.fuy-pulse-dot {
  fill: var(--fuy-chart-pulse-color);
}
.fuy-pulse-heart-ring {
  fill: none;
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width);
}
.fuy-pulse-line {
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width);
  fill: none;
}
.fuy-temp-overlap-ring {
  fill: none;
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width);
}
.fuy-temp-cooling-ring {
  fill: none;
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width);
}
.fuy-temp-cooling-line {
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width);
  stroke-dasharray: 4 3;
  fill: none;
}
.fuy-temp-deficit-line {
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width);
}
.fuy-event-line {
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: 1;
}
.fuy-event-line--arrest {
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: 1;
}
.fuy-event-label {
  fill: var(--fuy-chart-pulse-color);
  font-size: var(--fuy-font-size-xs);
}

/* 月页/患者切换 fade leave 段（motion.css 仅定义 enter 段，DoctorStation 同款页内补齐） */
.fuy-content-fade-leave-active {
  transition: opacity var(--fuy-motion-fast) var(--fuy-ease-exit);
}
.fuy-content-fade-leave-to {
  opacity: 0;
}

/* 入区登记弹窗表单宽度（§3.3 冻结值） */
.ward-register-input {
  width: 220px;
}
.ward-register-name {
  width: 120px;
}
.ward-register-bed {
  width: 160px;
}
</style>

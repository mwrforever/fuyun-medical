<script setup lang="ts">
// PDA 移动护理页（顶层路由 /pda，设计文档 §4）：床旁单手操作面——扫腕带/卡号 → 核对
// 脱敏患者卡 → 在途执行单清单 → 三向扫码核对 → 执行（给药/输液拔针）→ 录体征 → 巡视
// 打卡七段卡流（brief 六段=识别/患者卡/清单/核对/执行/巡视；体征段为 PR-6 既有冻结面
// 保留，插入执行三段与巡视打卡之间）。全部原生控件（EP 默认控件高不满足 48px 触控基线，
// 覆盖面大得不偿失），触控目标 ≥48px、字号 16px 基线、Enter 即提交（扫码枪回车形态）。
// 超敏字段零渲染：数据源为脱敏摘要（无证件/手机号字段，spec 冻结断言）；各动作各自独立
// 在途守卫 + 按钮 disabled，双击零出网。失败弹错：AxiosError 归拦截器，其余形态兜底展示。
import { computed, nextTick, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（portal 原生基线同款口径）
import 'element-plus/es/components/message/style/css';
import axios from 'axios';
import {
  EXECUTION_STATUS_LABELS,
  EXECUTION_TYPE_LABELS,
  TEMP_SITE_OPTIONS,
  executions,
  pda,
  vitalSigns,
} from '@/api/nursing';
import type {
  ExecutionStartRequest,
  NursingTaskVO,
  OrderExecutionVO,
  PdaPatientSummaryVO,
} from '@/api/nursing';
import { useAuthStore } from '@/stores/auth';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';
import { validateScanCode } from './composables/useExecutions';
import { todayString } from './wardBoardShared';

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
    // 换患者识别：体征表单复位同时轮换幂等键（D-22，防前患者未落卡键串用到新患者提交）
    vitalClientMsgId = newIdempotencyKey();
    patrolTask.value = null;
    // 换患者执行面复位：清旧清单/选中单与核对、拔针表单（防前患者扫码残留串台到新患者）
    wardExecutions.value = [];
    selectedExecution.value = null;
    resetCheckFace();
    resetExecuteFace();
    // 在途清单随识别异步加载（不阻塞识别反馈；病区+当日自摘要派生）
    void loadExecutions();
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

/* ==================== 第 3 段：在途执行单清单（当前患者前端侧过滤） ==================== */
const auth = useAuthStore();
const listLoading = ref(false);
/** 病区当日执行单全集（GET /executions?wardId=&date= 拉取；单患者过滤前端侧组合） */
const wardExecutions = ref<OrderExecutionVO[]>([]);
/** 当前操作执行单（清单点选锚定核对/执行两段；动作后随清单重拉重同步） */
const selectedExecution = ref<OrderExecutionVO | null>(null);

/** 在途执行单 = 当前患者行 + 非终态（COMPLETED/CANCELLED 离场——「在途」语义前端承载）。 */
const patientExecutions = computed(() => {
  const patientId = summary.value?.patientId ?? '';
  return wardExecutions.value.filter(
    (row) =>
      row.patientId === patientId && row.status !== 'COMPLETED' && row.status !== 'CANCELLED',
  );
});

/**
 * 拉取病区当日执行单清单（复用工作台清单端点；识别成功与各动作收尾统一刷新入口）。
 * 选中行按执行单号重同步：动作后状态迁移回写选中态；离场（终态被过滤）即清空选择。
 */
async function loadExecutions(): Promise<void> {
  const wardId = summary.value?.wardId;
  if (wardId === undefined || wardId === '') {
    return;
  }
  listLoading.value = true;
  try {
    const page = await executions.list({ wardId, date: todayString(), page: 0, size: 200 });
    // 过期回包丢弃：识别换患者后旧病区回包不得落值清单（EX-45/FE-A1-04 同族竞态纪律，
    // Task 14 P1-1 同款——回包比对当前上下文后再写）
    if (summary.value?.wardId !== wardId) {
      return;
    }
    wardExecutions.value = page.content ?? [];
    const no = selectedExecution.value?.executionNo;
    selectedExecution.value =
      no === undefined
        ? null
        : (patientExecutions.value.find((row) => row.executionNo === no) ?? null);
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    listLoading.value = false;
  }
}

/** 清单点选（换单复位核对与执行表单：防前单扫码/拔针输入残留串用到新单）。 */
function onSelectExecution(row: OrderExecutionVO): void {
  selectedExecution.value = row;
  resetCheckFace();
  resetExecuteFace();
}

/**
 * 在途动作回包是否已过期（提交期间换行/换患者后选中执行单号已变）。
 * 过期回包不回写新单表单与破码弹层（EX-45/FE-A1-04「过期回包丢弃」纪律，
 * Task 14 P1-1 同族缺陷防线）；清单重拉仍执行（动作确已落库，状态需同步）。
 */
function isStaleAction(no: string): boolean {
  return selectedExecution.value?.executionNo !== no;
}

/* ==================== 第 4 段：扫码核对（三向 codeType 分段） ==================== */
/** 三向核对扫码维（后端 CheckType 词表三扫码值实况；OVERRIDE 为放行留痕维不进扫码输入） */
type CheckCodeType = 'WRISTBAND' | 'BAG_LABEL' | 'DEVICE';

/** 核对输入组（codeType 分段提交：腕带=visitId 匹配/瓶签=袋签码/设备码=执行单条码） */
const CHECK_INPUTS: ReadonlyArray<{
  codeType: CheckCodeType;
  label: string;
  placeholder: string;
}> = [
  { codeType: 'WRISTBAND', label: '腕带', placeholder: '扫描患者腕带（I 开头 14 位）' },
  { codeType: 'BAG_LABEL', label: '瓶签', placeholder: '扫描输液袋签' },
  { codeType: 'DEVICE', label: '设备码', placeholder: '扫描执行单条码（EX 开头）' },
];

const checkForm = ref<Record<CheckCodeType, string>>({ WRISTBAND: '', BAG_LABEL: '', DEVICE: '' });
const checkErrors = ref<Record<CheckCodeType, string>>({
  WRISTBAND: '',
  BAG_LABEL: '',
  DEVICE: '',
});
const checking = ref(false);

/** 破码放行双授权表单（核对 FAIL 后弹出：主授权人=当前登录人会话身份展示回显无录入面，
 * 副授权人工号+原因必填，副授权人与登录人不同——W-72 主授权人服务端一律以令牌身份落值）。 */
const overrideVisible = ref(false);
const overrideForm = ref({ secondaryAuthorizerId: '', reason: '' });
const overriding = ref(false);

/** 核对面复位（换患者/换单共用：扫码原文、贴字段错误与破码表单一并清空）。 */
function resetCheckFace(): void {
  checkForm.value = { WRISTBAND: '', BAG_LABEL: '', DEVICE: '' };
  checkErrors.value = { WRISTBAND: '', BAG_LABEL: '', DEVICE: '' };
  overrideVisible.value = false;
  overrideForm.value = { secondaryAuthorizerId: '', reason: '' };
}

/** 扫码核对 FAIL 判定：409 冲突=后端 NS-1022 核对不匹配；其余 4xx 为格式/状态错误不弹破码。 */
function isCheckFail(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 409;
}

/**
 * 扫码核对提交（三输入各自回车触发；正则校验前置零出网）。
 * PASS 清输入续扫下一维并重拉清单；FAIL 保留扫码原文供重试且弹双授权破码表单
 * （AxiosError 弹错归拦截器；重试可直接改扫正确码——原文非只读）。
 */
async function onCheck(codeType: CheckCodeType): Promise<void> {
  if (checking.value || selectedExecution.value === null) {
    return;
  }
  const code = checkForm.value[codeType].trim();
  const validation = validateScanCode(code, codeType);
  if (validation !== null) {
    checkErrors.value[codeType] = validation;
    return;
  }
  checkErrors.value[codeType] = '';
  const no = selectedExecution.value.executionNo ?? '';
  checking.value = true;
  try {
    await executions.check(no, { code, codeType });
    void ElMessage.success(`核对通过：${no}`);
    // 过期回包丢弃：核对在途换行后不清新行扫码输入、不收新行破码弹层
    if (!isStaleAction(no)) {
      checkForm.value[codeType] = '';
      overrideVisible.value = false;
    }
    await loadExecutions();
  } catch (error) {
    if (isCheckFail(error)) {
      // FAIL：弹错已归拦截器；保留原文重试 + 破码放行双授权表单出场（过期回包不弹新行）
      if (!isStaleAction(no)) {
        overrideVisible.value = true;
      }
    } else {
      surfaceBizError(error);
    }
  } finally {
    checking.value = false;
  }
}

/**
 * 破码放行提交（双授权校验前置零出网：主授权人=当前登录人会话身份无录入面——W-72 服务端
 * 一律以令牌身份落值；副授权人必填且不得与登录人相同（前端比对会话工号）、原因必填——
 * 后端同口径兜底）。放行置位 override_flag 供后续开始执行越过时间窗；扫码原文保留供重新核对。
 */
async function onSubmitOverride(): Promise<void> {
  if (overriding.value || selectedExecution.value === null) {
    return;
  }
  const secondary = overrideForm.value.secondaryAuthorizerId.trim();
  const reason = overrideForm.value.reason.trim();
  if (secondary === '') {
    void ElMessage.warning('副授权人工号不能为空');
    return;
  }
  if (secondary === (auth.user?.userId ?? '')) {
    void ElMessage.warning('破码放行双授权两人不得相同（主授权人=当前登录人）');
    return;
  }
  if (reason === '') {
    void ElMessage.warning('放行原因不能为空');
    return;
  }
  const no = selectedExecution.value.executionNo ?? '';
  overriding.value = true;
  try {
    await pda.overrideCheck({
      executionNo: no,
      // 主授权人携会话 userId 出网（兼容保留——服务端一律以令牌身份落值，W-72）
      primaryAuthorizerId: auth.user?.userId ?? '',
      secondaryAuthorizerId: secondary,
      reason,
    });
    void ElMessage.success(`已破码放行：${no}`);
    // 过期回包丢弃：放行在途换行后不收新行破码弹层
    if (!isStaleAction(no)) {
      overrideVisible.value = false;
    }
    await loadExecutions();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    overriding.value = false;
  }
}

/* ==================== 第 5 段：执行（给药 start→finish / 输液 start→拔针） ==================== */
const executing = ref(false);
/** 输液开始设备码（可扫可空——Task 6 挂接语义：deviceId 随 start 请求入、不进事件） */
const startDeviceCode = ref('');
/** 拔针表单：实际输注量（ml 数字键盘）+ 腕带复扫（Enter 即提交） */
const needleVolume = ref('');
const needleWristband = ref('');

/** 执行面复位（换患者/换单共用：设备码与拔针两字段清空）。 */
function resetExecuteFace(): void {
  startDeviceCode.value = '';
  needleVolume.value = '';
  needleWristband.value = '';
}

/** 执行段可动作态（状态机子集：CHECKED=开始 / EXECUTING=完成或拔针；前置态引导回核对段）。 */
const executionReady = computed(() => {
  const status = selectedExecution.value?.status;
  return status === 'CHECKED' || status === 'EXECUTING';
});

/** 输液型判定（型别分叉：给药走 finish、输液走拔针——后端型守卫同口径 fail-closed）。 */
const isInfusionExecution = computed(() => selectedExecution.value?.executionType === 'INFUSION');

/** 操作人判空（start/finish/拔针留痕必填；缺会话身份零出网显式拦截——工作台同款口径）。 */
function requireExecutorId(): string | null {
  const executorId = auth.user?.userId ?? '';
  if (executorId === '') {
    void ElMessage.warning('会话缺少操作人身份，无法执行该操作（请重新登录后再试）');
    return null;
  }
  return executorId;
}

/** 开始执行（给药/输液共入口；输液携可扫设备码；时间窗外未破码拒绝归后端把守）。 */
async function onStartExecution(): Promise<void> {
  if (executing.value || selectedExecution.value === null) {
    return;
  }
  const executorId = requireExecutorId();
  if (executorId === null) {
    return;
  }
  const payload: ExecutionStartRequest = { executorId, overrideTimeWindow: false };
  const deviceId = startDeviceCode.value.trim();
  if (isInfusionExecution.value && deviceId !== '') {
    payload.deviceId = deviceId;
  }
  const no = selectedExecution.value.executionNo ?? '';
  executing.value = true;
  try {
    await executions.start(no, payload);
    void ElMessage.success(`已开始执行：${no}`);
    // 过期回包丢弃：开始在途换行后不清新行设备码输入
    if (!isStaleAction(no)) {
      startDeviceCode.value = '';
    }
    await loadExecutions();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    executing.value = false;
  }
}

/** 给药执行完成（EXECUTING→COMPLETED+双路回签 M04；输注类完成归拔针面）。 */
async function onFinishExecution(): Promise<void> {
  if (executing.value || selectedExecution.value === null) {
    return;
  }
  const executorId = requireExecutorId();
  if (executorId === null) {
    return;
  }
  const no = selectedExecution.value.executionNo ?? '';
  executing.value = true;
  try {
    await executions.finish(no, { executorId });
    void ElMessage.success(`已完成执行：${no}`);
    await loadExecutions();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    executing.value = false;
  }
}

/** 拔针量显式校验（纯整数 0~5000，brief 冻结边界；后端服务层同口径兜底）。 */
function isValidNeedleVolume(raw: string): boolean {
  if (!/^\d+$/.test(raw)) {
    return false;
  }
  const value = Number(raw);
  return value >= 0 && value <= 5000;
}

/** 输液拔针（实际输注量数字键盘+腕带复扫双前置校验；挂接收口+自动入量+双路回签归后端编排）。 */
async function onNeedleOut(): Promise<void> {
  if (executing.value || selectedExecution.value === null) {
    return;
  }
  const volume = needleVolume.value.trim();
  if (!isValidNeedleVolume(volume)) {
    void ElMessage.warning('实际输注量应为 0–5000 的整数（ml），请重新输入');
    return;
  }
  const wristband = needleWristband.value.trim();
  const wristbandValidation = validateScanCode(wristband, 'WRISTBAND');
  if (wristbandValidation !== null) {
    void ElMessage.warning(wristbandValidation);
    return;
  }
  const executorId = requireExecutorId();
  if (executorId === null) {
    return;
  }
  const no = selectedExecution.value.executionNo ?? '';
  executing.value = true;
  try {
    await pda.needleOut(no, {
      executorId,
      actualVolumeMl: Number(volume),
      wristbandCode: wristband,
    });
    void ElMessage.success(`已完成拔针：${no}`);
    // 过期回包丢弃：拔针在途换行后不清新行输注量与腕带复扫输入
    if (!isStaleAction(no)) {
      needleVolume.value = '';
      needleWristband.value = '';
    }
    await loadExecutions();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    executing.value = false;
  }
}

/* ==================== 第 6 段：体征录入（五字段子集） ==================== */
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

/**
 * 幂等键生成（D-22）：优先 crypto.randomUUID；该 API 仅安全上下文（HTTPS/localhost）可用，
 * 院内 PDA 常经 HTTP 内网访问（nginx :80），此时回退自拼 v4 形态 UUID——键生成不可失败
 * （setup 期即取键，无 UUID 会让整页崩死）。形态仍为 36 位 8-4-4-4-12 标准串（≤64 列宽）。
 */
function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const rand = (Math.random() * 16) | 0;
    return (c === 'x' ? rand : (rand & 0x3) | 0x8).toString(16);
  });
}

/**
 * 体征提交幂等键（D-22）：一次「录入-提交-确认」组合的稳定标识——生成后保持不变，成功落卡
 * 才轮换（弱网下请求在途失败的重试复用同一键，服务端按 client_msg_id 识别同一次点测并重放
 * 返回原记录，杜绝补传重复落卡）；换患者识别随表单复位一并轮换，防前患者未落卡键串用。
 */
let vitalClientMsgId = newIdempotencyKey();

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
  // D-22：幂等键置于 await 之前捕获——弱网在途失败后的重试复用同一键，成功落卡才轮换
  const clientMsgId = vitalClientMsgId;
  try {
    await vitalSigns.record({
      visitId,
      source: 'PDA',
      clientMsgId,
      temperature: form.temperature === '' ? undefined : Number(form.temperature),
      tempSite: form.temperature === '' ? undefined : form.tempSite,
      pulse: form.pulse === '' ? undefined : Number(form.pulse),
      respiration: form.respiration === '' ? undefined : Number(form.respiration),
      systolicBp: form.systolicBp === '' ? undefined : Number(form.systolicBp),
      diastolicBp: form.diastolicBp === '' ? undefined : Number(form.diastolicBp),
      spo2: form.spo2 === '' ? undefined : Number(form.spo2),
    });
    void ElMessage.success('体征已录入');
    // 提交成功才清表单，并轮换幂等键（失败保留表单与原键，重试仍复用同键）
    vitalClientMsgId = newIdempotencyKey();
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

/* ==================== 第 7 段：巡视打卡 ==================== */
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

    <!-- 第 3 段：在途执行单清单（当前患者前端侧过滤；行卡即触控目标，点选锚定后续两段） -->
    <section
      class="pda-card"
      :class="{ 'pda-card-locked': !identified }"
      aria-label="在途执行单清单"
    >
      <h2 class="pda-card-title">在途执行单清单</h2>
      <Transition name="fuy-content-fade">
        <div v-if="identified">
          <p v-if="listLoading" class="pda-card-hint">清单加载中…</p>
          <p v-else-if="patientExecutions.length === 0" class="pda-card-hint">
            当前患者无在途执行单
          </p>
          <div v-else class="pda-exec-list">
            <button
              v-for="row in patientExecutions"
              :key="row.executionNo"
              type="button"
              class="pda-exec-item"
              :class="{
                'pda-exec-item--active': row.executionNo === selectedExecution?.executionNo,
              }"
              :aria-pressed="row.executionNo === selectedExecution?.executionNo"
              @click="onSelectExecution(row)"
            >
              <span class="pda-exec-name">
                {{ row.execItemName ?? '—' }}
                <span v-if="(row.dosageText ?? '') !== ''">{{ row.dosageText }}</span>
              </span>
              <span class="pda-exec-meta">
                <span>{{ EXECUTION_TYPE_LABELS[row.executionType ?? ''] ?? '' }}</span>
                <span>{{ EXECUTION_STATUS_LABELS[row.status ?? ''] ?? '' }}</span>
                <span class="fuy-num">{{ formatTime(row.planTime) }}</span>
              </span>
            </button>
          </div>
        </div>
      </Transition>
      <p v-if="!identified" class="pda-card-hint">先完成患者识别</p>
    </section>

    <!-- 第 4 段：扫码核对（腕带/瓶签/设备码三输入回车提交；FAIL 后弹双授权破码表单） -->
    <section class="pda-card" :class="{ 'pda-card-locked': !identified }" aria-label="扫码核对">
      <h2 class="pda-card-title">扫码核对</h2>
      <p v-if="identified && selectedExecution === null" class="pda-card-hint">
        先从清单选择执行单
      </p>
      <Transition name="fuy-content-fade">
        <div v-if="selectedExecution !== null">
          <p class="pda-exec-target fuy-num">
            {{ selectedExecution.execItemName ?? '' }} {{ selectedExecution.executionNo ?? '' }}
          </p>
          <label v-for="input in CHECK_INPUTS" :key="input.codeType" class="pda-field">
            <span class="pda-field-label">{{ input.label }}</span>
            <input
              v-model="checkForm[input.codeType]"
              class="pda-input"
              type="text"
              autocomplete="off"
              :placeholder="input.placeholder"
              :aria-describedby="`pda-check-error-${input.codeType}`"
              @keyup.enter="onCheck(input.codeType)"
            />
            <p
              v-if="checkErrors[input.codeType] !== ''"
              :id="`pda-check-error-${input.codeType}`"
              class="pda-field-error pda-check-error"
              role="alert"
            >
              {{ checkErrors[input.codeType] }}
            </p>
          </label>
          <!-- 破码放行双授权表单（FAIL 后出现：主授权人=当前登录人展示回显，副授权人+原因录入） -->
          <div v-if="overrideVisible" class="pda-override" aria-label="破码放行双授权">
            <h3 class="pda-override-title">破码放行（双授权）</h3>
            <p class="pda-override-primary fuy-num">
              主授权人：{{ auth.user?.displayName ?? auth.user?.userId ?? '—' }}（{{
                auth.user?.userId ?? '—'
              }}）——当前登录人，服务端留痕
            </p>
            <label class="pda-field">
              <span class="pda-field-label">副授权人工号</span>
              <input
                v-model="overrideForm.secondaryAuthorizerId"
                class="pda-input"
                type="text"
                autocomplete="off"
                placeholder="副授权人工号"
              />
            </label>
            <label class="pda-field">
              <span class="pda-field-label">放行原因</span>
              <input
                v-model="overrideForm.reason"
                class="pda-input"
                type="text"
                autocomplete="off"
                placeholder="放行原因"
              />
            </label>
            <button
              type="button"
              class="pda-button pda-button-primary"
              :disabled="overriding"
              @click="onSubmitOverride"
            >
              {{ overriding ? '放行中…' : '提交放行' }}
            </button>
            <button
              type="button"
              class="pda-button pda-button-plain"
              :disabled="overriding"
              @click="overrideVisible = false"
            >
              取消
            </button>
          </div>
        </div>
      </Transition>
      <p v-if="!identified" class="pda-card-hint">先完成患者识别</p>
    </section>

    <!-- 第 5 段：执行（给药 start→finish / 输液 start[设备码可扫]→拔针[输注量+腕带复扫]） -->
    <section class="pda-card" :class="{ 'pda-card-locked': !identified }" aria-label="执行">
      <h2 class="pda-card-title">执行</h2>
      <p v-if="identified && selectedExecution === null" class="pda-card-hint">
        先从清单选择执行单
      </p>
      <Transition name="fuy-content-fade">
        <div v-if="selectedExecution !== null">
          <p class="pda-exec-target fuy-num">
            {{ selectedExecution.execItemName ?? '' }} {{ selectedExecution.executionNo ?? '' }}
          </p>
          <p v-if="!executionReady" class="pda-card-hint">该执行单尚未核对通过，请先完成扫码核对</p>
          <!-- 给药面：按状态切换开始/完成两按钮 -->
          <template v-else-if="!isInfusionExecution">
            <button
              v-if="selectedExecution.status === 'CHECKED'"
              type="button"
              class="pda-button pda-button-primary"
              :disabled="executing"
              @click="onStartExecution"
            >
              {{ executing ? '开始中…' : '开始执行' }}
            </button>
            <button
              v-else
              type="button"
              class="pda-button pda-button-primary"
              :disabled="executing"
              @click="onFinishExecution"
            >
              {{ executing ? '完成中…' : '执行完成' }}
            </button>
          </template>
          <!-- 输液面：开始（设备码可扫）→ 拔针（实际输注量数字键盘+腕带复扫） -->
          <template v-else>
            <template v-if="selectedExecution.status === 'CHECKED'">
              <label class="pda-field">
                <span class="pda-field-label">设备码（可扫，选填）</span>
                <input
                  v-model="startDeviceCode"
                  class="pda-input"
                  type="text"
                  autocomplete="off"
                  placeholder="扫描输液设备码（选填）"
                />
              </label>
              <button
                type="button"
                class="pda-button pda-button-primary"
                :disabled="executing"
                @click="onStartExecution"
              >
                {{ executing ? '开始中…' : '开始输液' }}
              </button>
            </template>
            <template v-else>
              <label class="pda-field">
                <span class="pda-field-label">实际输注量（ml）</span>
                <input
                  v-model="needleVolume"
                  class="pda-input"
                  type="text"
                  inputmode="numeric"
                  autocomplete="off"
                  placeholder="0–5000"
                />
              </label>
              <label class="pda-field">
                <span class="pda-field-label">腕带复扫</span>
                <input
                  v-model="needleWristband"
                  class="pda-input"
                  type="text"
                  autocomplete="off"
                  placeholder="扫描患者腕带复扫"
                  @keyup.enter="onNeedleOut"
                />
              </label>
              <button
                type="button"
                class="pda-button pda-button-primary"
                :disabled="executing"
                @click="onNeedleOut"
              >
                {{ executing ? '拔针中…' : '拔针完成' }}
              </button>
            </template>
          </template>
        </div>
      </Transition>
      <p v-if="!identified" class="pda-card-hint">先完成患者识别</p>
    </section>

    <!-- 第 6 段：体征录入（五字段巡床高频子集） -->
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

    <!-- 第 7 段：巡视打卡（成功后转已完成态 + taskNo 回显） -->
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

/* 在途执行单清单（行卡=触控目标 ≥48px，段内 8px 间距基线；选中态品牌描边+焦点环） */
.pda-exec-list {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
}
.pda-exec-item {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
  min-height: var(--fuy-pda-touch);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-xl);
  background: #fff;
  text-align: left;
  cursor: pointer;
}
.pda-exec-item:active {
  transform: scale(0.98);
}
.pda-exec-item--active {
  border-color: var(--fuy-color-brand);
  box-shadow: 0 0 0 3px var(--fuy-color-focus-ring);
}
.pda-exec-name {
  font-weight: 600;
}
.pda-exec-meta {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}

/* 核对/执行段当前单回显行（13px 只读辅助信息） */
.pda-exec-target {
  margin: 0 0 var(--fuy-space-2);
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
/* 核对贴字段错误（§4.3：置字段正下方，label 纵排内收边距） */
.pda-check-error {
  margin: 0;
}

/* 破码放行双授权表单（FAIL 后内嵌子区块；主按钮下白底描边次按钮收尾） */
.pda-override {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
  margin-top: var(--fuy-space-3);
  padding: var(--fuy-space-3);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-xl);
  background: var(--fuy-palette-gray-50);
}
.pda-override-title {
  margin: 0;
  color: var(--fuy-color-danger-text);
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
}
/* 主授权人展示行（零手输：会话身份回显只读态，白底描边示不可编辑） */
.pda-override-primary {
  margin: 0;
  padding: 8px var(--fuy-space-2);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-md);
  background: #fff;
  font-size: 16px;
  color: var(--fuy-color-text-emphasis);
}
.pda-button-plain {
  border: var(--fuy-border-hairline);
  background: #fff;
  color: var(--fuy-color-text-emphasis);
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

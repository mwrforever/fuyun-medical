/**
 * 护理执行工作台 composable（PR-3 Task 14，ExecutionWorkbenchView 业务面）：四列分组
 * 看板（待签收/待核对/待执行/执行中——CREATED/SIGNED/CHECKED/EXECUTING 四态 1:1 映射，
 * COMPLETED/CANCELLED 终态不进列）+ 详情抽屉（闭环追溯 trace + 五动作组 sign-receive/
 * check/start/finish/cancel 在途互斥）+ 扫码核对（PDA 同款正则校验）+ 输液遥测条。
 *
 * 锚行区分（Task 4 派发义务④）：LONG 类型锚行 m04PlanNo 为空（STAT 快照行=唯一执行
 * 载体）——行卡以类型标签区分两种快照语义，锚行 availableActions 恒空不进操作流。
 *
 * 输液遥测条为前端组合面（IoT 面前端组合降级注记，UI 不暴露降级态）：护理侧
 * infusions/active 按 patientId 对齐在途输注行，其 iotDeviceId 再对齐 ward 侧
 * infusion-board 设备遥测（余量/滴速/档位）——nursing→iot 禁反向依赖（后端架构约束），
 * 组合逻辑收口在本 composable，注释注记即可、UI 内不体现。
 */
import { computed, ref } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { executions, infusions } from '@/api/nursing';
import type { ActiveInfusionVO, OrderExecutionTraceVO, OrderExecutionVO } from '@/api/nursing';
import type { InfusionBoardDeviceVO } from '@/api/ward';
import { infusionBoard } from '@/api/ward';
import { surfaceBizError } from '@/utils/bizError';
import { todayString } from '../wardBoardShared';

/** 四列看板列定义（状态码 → 列名 1:1 映射；列序即渲染序，spec 冻结断言） */
export const EXECUTION_COLUMNS: ReadonlyArray<{ status: string; label: string }> = [
  { status: 'CREATED', label: '待签收' },
  { status: 'SIGNED', label: '待核对' },
  { status: 'CHECKED', label: '待执行' },
  { status: 'EXECUTING', label: '执行中' },
];

/** 腕带格式（PdaView 同款冻结口径：I 开头 14 位=I+13 位数字） */
const WRISTBAND_PATTERN = /^I\d{13}$/;
/** 执行单条码格式（后端 NursingSeqGate：EX+当日 8 位+5 位序号共 15 位） */
const EXECUTION_NO_PATTERN = /^EX\d{13}$/;
/** 扫码原文长度上限（后端 CheckRequest @Size(max=64) 同口径） */
const SCAN_CODE_MAX_LENGTH = 64;

/** 动作编码（详情抽屉操作按钮组，锚行/终态行为空集） */
export type ExecutionAction = 'signReceive' | 'check' | 'start' | 'finish' | 'cancel';

/** LONG 锚行判定（Task 4 裁决：锚行 m04_plan_no 为空——STAT 快照行才是执行载体） */
export function isAnchorRow(row: OrderExecutionVO): boolean {
  return (row.m04PlanNo ?? '') === '';
}

/**
 * 扫码核对校验（PDA 同款正则，纯函数供 spec 直测）。
 *
 * @param code 扫码原文（腕带就诊编码/输液袋签码/执行单条码）
 * @param codeType 核对方式（WRISTBAND/BAG_LABEL/DEVICE）
 * @return null=合法；否则为中文错误文案（提示后零出网）
 */
export function validateScanCode(code: string, codeType: string): string | null {
  const trimmed = code.trim();
  if (codeType === 'WRISTBAND') {
    return WRISTBAND_PATTERN.test(trimmed)
      ? null
      : '腕带号应为 I 开头共 14 位（I+13 位数字），请重新扫描';
  }
  if (codeType === 'DEVICE') {
    return EXECUTION_NO_PATTERN.test(trimmed)
      ? null
      : '执行单条码应为 EX 开头共 15 位（EX+日期+序号），请重新扫描';
  }
  if (trimmed.length === 0 || trimmed.length > SCAN_CODE_MAX_LENGTH) {
    return '瓶签码不能为空且不超过 64 位，请重新扫描';
  }
  return null;
}

/** 执行工作台参数对象（病区上下文与操作人取值器经视图注入） */
export interface UseExecutionsOptions {
  /** 当前病区代码（看板清单范围），来自视图筛选条 */
  wardId: Ref<string>;
  /** 执行人 userId 取值器（start/finish 留痕锚点，会话惰性取值）；来源：auth store */
  getExecutorId: () => string;
}

/** 单列看板行集（列定义 + 行集 + 计数） */
export interface ExecutionColumn {
  status: string;
  label: string;
  rows: OrderExecutionVO[];
}

/** 输液遥测条组合结果（护理侧在途输注行 + ward 侧设备遥测对齐；null=无在途输注） */
export interface InfusionStrip {
  infusion: ActiveInfusionVO;
  telemetry: InfusionBoardDeviceVO | undefined;
}

/** 初始化执行工作台状态面（每组件实例独立状态，仅 setup 同步调用） */
export function useExecutions(options: UseExecutionsOptions) {
  /* ==================== 筛选与看板加载 ==================== */
  /** 日期过滤（默认当日，yyyy-MM-dd；空串=不过滤） */
  const filterDate = ref(todayString());
  /** 班次过滤（默认空=全部班次） */
  const filterShift = ref('');

  const boardRows = ref<OrderExecutionVO[]>([]);
  const boardLoading = ref(false);

  /** 四列看板（终态行不进列；列内按后端返回序——计划时间升序由后端承载） */
  const columns: ComputedRef<ExecutionColumn[]> = computed(() =>
    EXECUTION_COLUMNS.map((column) => ({
      ...column,
      rows: boardRows.value.filter((row) => row.status === column.status),
    })),
  );

  /* ==================== 输液遥测条组合源（前端组合降级注记） ==================== */
  const activeInfusions = ref<ActiveInfusionVO[]>([]);
  /** ward 侧设备遥测按 deviceId 索引（余量/滴速/档位） */
  const boardDeviceMap = ref<Map<string, InfusionBoardDeviceVO>>(new Map());

  /**
   * 拉取输液遥测条两路组合源（护理侧在途输注 + ward 侧设备遥测）。失败静默——遥测条为
   * 展示增强面，缺失不阻塞执行看板主链路（组合降级 UI 内不体现，仅数据缺位不渲染）。
   */
  async function loadInfusionSources(): Promise<void> {
    const [activeResult, boardResult] = await Promise.allSettled([
      infusions.active(options.wardId.value),
      infusionBoard.byWard(options.wardId.value),
    ]);
    if (activeResult.status === 'fulfilled') {
      activeInfusions.value = activeResult.value;
    }
    if (boardResult.status === 'fulfilled') {
      boardDeviceMap.value = new Map(
        (boardResult.value.devices ?? []).map((device) => [device.deviceId ?? '', device]),
      );
    }
  }

  /** 看板加载：执行单清单 + 遥测组合源并行刷新（遥测失败不阻塞清单） */
  async function loadBoard(): Promise<void> {
    boardLoading.value = true;
    try {
      const page = await executions.list({
        wardId: options.wardId.value,
        date: filterDate.value === '' ? undefined : filterDate.value,
        shift: filterShift.value === '' ? undefined : filterShift.value,
        page: 0,
        size: 200,
      });
      boardRows.value = page.content ?? [];
      await loadInfusionSources();
    } catch {
      // 失败弹错归响应拦截器；驻留旧看板
    } finally {
      boardLoading.value = false;
    }
  }

  /* ==================== 详情抽屉 ==================== */
  const drawerVisible = ref(false);
  const selected = ref<OrderExecutionVO | null>(null);
  const trace = ref<OrderExecutionTraceVO | null>(null);
  const traceLoading = ref(false);

  /** 打开详情抽屉：锚点换行 → 闭环追溯拉取（时间线数据源） */
  async function openDetail(row: OrderExecutionVO): Promise<void> {
    selected.value = row;
    // 换行先清旧时间线：B 行 trace 未回包前抽屉数据源降级 selected（行内快照兜底），
    // 防 A 行闭环数据驻留串台到 B 行单号名下
    trace.value = null;
    drawerVisible.value = true;
    await loadTrace();
  }

  /**
   * 拉取闭环追溯（五环节时点+核对流水；失败弹错归拦截器，驻留旧时间线）。
   * 回包先比对当前选中执行单号再落值：回包期间换行即在途回包过期，直接丢弃，
   * 防 A 行慢回包覆盖 B 行时间线（跨执行单串台，同 WardBoardView EX-45/FE-A1-04 纪律）。
   */
  async function loadTrace(): Promise<void> {
    const no = selected.value?.executionNo;
    if (no === undefined || no === '') {
      return;
    }
    traceLoading.value = true;
    try {
      const result = await executions.trace(no);
      // 过期回包丢弃：换行后选中执行单号已变，旧单回包不得落值
      if (selected.value?.executionNo === no) {
        trace.value = result;
      }
    } catch {
      // 失败弹错归响应拦截器
    } finally {
      traceLoading.value = false;
    }
  }

  /** 选中患者在途输注遥测条（patientId 对齐护理侧行 → iotDeviceId 对齐 ward 侧遥测） */
  const infusionStrip: ComputedRef<InfusionStrip | null> = computed(() => {
    const patientId = selected.value?.patientId;
    if (patientId === undefined || patientId === '') {
      return null;
    }
    const infusion = activeInfusions.value.find((row) => row.patientId === patientId);
    if (infusion === undefined) {
      return null;
    }
    return {
      infusion,
      telemetry: boardDeviceMap.value.get(infusion.iotDeviceId ?? ''),
    };
  });

  /* ==================== 操作组（在途互斥） ==================== */
  /** 动作在途标志（抽屉内五动作互斥：同执行单同时至多一个动作在途，双击零二次出网） */
  const acting = ref(false);

  /**
   * 状态-动作矩阵（spec 冻结断言）：CREATED=补签收/核对/撤销；SIGNED=核对/撤销；
   * CHECKED=开始/撤销；EXECUTING=完成（中断撤单归输液面）；锚行恒空集不进操作流。
   */
  function availableActions(row: OrderExecutionVO): ExecutionAction[] {
    if (isAnchorRow(row)) {
      return [];
    }
    switch (row.status) {
      case 'CREATED':
        return ['signReceive', 'check', 'cancel'];
      case 'SIGNED':
        return ['check', 'cancel'];
      case 'CHECKED':
        return ['start', 'cancel'];
      case 'EXECUTING':
        return ['finish'];
      default:
        return [];
    }
  }

  /** 操作人判空（start/finish 留痕必填；缺会话身份零出网显式拦截） */
  function requireExecutorId(): string | null {
    const executorId = options.getExecutorId();
    if (executorId === '') {
      void ElMessage.warning('会话缺少操作人身份，无法执行该操作（请重新登录后再试）');
      return null;
    }
    return executorId;
  }

  /** 动作成功后的统一收尾：闭环追溯与看板（含遥测组合源）重拉 */
  async function refreshAfterAction(): Promise<void> {
    await Promise.all([loadTrace(), loadBoard()]);
  }

  /** 人工补签收（非药品类；药品类经摆药签收衔接自动签收） */
  async function onSignReceive(): Promise<void> {
    if (acting.value || selected.value === null) {
      return;
    }
    acting.value = true;
    try {
      const updated = await executions.signReceive(selected.value.executionNo ?? '', {});
      void ElMessage.success(`已签收：${updated.executionNo ?? ''}`);
      await refreshAfterAction();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      acting.value = false;
    }
  }

  /* ==================== 扫码核对（输入框回车提交） ==================== */
  const scanCode = ref('');
  const scanType = ref('WRISTBAND');

  /** 扫码核对提交（PDA 同款正则校验前置零出网；PASS 后清输入框续扫下一维） */
  async function onScanCheck(): Promise<void> {
    if (acting.value || selected.value === null) {
      return;
    }
    const error = validateScanCode(scanCode.value, scanType.value);
    if (error !== null) {
      void ElMessage.warning(error);
      return;
    }
    const no = selected.value.executionNo ?? '';
    acting.value = true;
    try {
      const updated = await executions.check(no, {
        code: scanCode.value.trim(),
        codeType: scanType.value,
      });
      void ElMessage.success(`核对通过：${updated.executionNo ?? ''}`);
      scanCode.value = '';
      await refreshAfterAction();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      acting.value = false;
    }
  }

  /** 开始执行（CHECKED→EXECUTING；时间窗外未破码拒绝归后端把守） */
  async function onStart(): Promise<void> {
    if (acting.value || selected.value === null) {
      return;
    }
    const executorId = requireExecutorId();
    if (executorId === null) {
      return;
    }
    acting.value = true;
    try {
      const updated = await executions.start(selected.value.executionNo ?? '', {
        executorId,
        overrideTimeWindow: false,
      });
      void ElMessage.success(`已开始执行：${updated.executionNo ?? ''}`);
      await refreshAfterAction();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      acting.value = false;
    }
  }

  /** 执行完成（EXECUTING→COMPLETED+双路回签 M04；输注类完成归 PDA 拔针面） */
  async function onFinish(): Promise<void> {
    if (acting.value || selected.value === null) {
      return;
    }
    const executorId = requireExecutorId();
    if (executorId === null) {
      return;
    }
    acting.value = true;
    try {
      const updated = await executions.finish(selected.value.executionNo ?? '', { executorId });
      void ElMessage.success(`已完成执行：${updated.executionNo ?? ''}`);
      await refreshAfterAction();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      acting.value = false;
    }
  }

  /** 撤销执行单（未执行三态常规撤销，必填原因留痕；EXECUTING 中断归输液面不在本入口） */
  async function onCancelExecution(): Promise<void> {
    if (acting.value || selected.value === null) {
      return;
    }
    try {
      const { value } = await ElMessageBox.prompt(
        `撤销执行单 ${selected.value.executionNo ?? ''}？撤销原因必填（停嘱/作废/撤单）`,
        '执行单撤销确认',
        {
          confirmButtonText: '确认撤销',
          cancelButtonText: '返回',
          inputPlaceholder: '撤销原因（必填）',
          inputValidator: (input: string) => (input.trim() === '' ? '撤销原因不能为空' : true),
        },
      );
      acting.value = true;
      const updated = await executions.cancel(selected.value.executionNo ?? '', {
        reason: value.trim(),
      });
      void ElMessage.success(`已撤销：${updated.executionNo ?? ''}`);
      await refreshAfterAction();
    } catch (error) {
      // 用户取消静默；出网失败经 surfaceBizError 兜底
      surfaceBizError(error);
    } finally {
      acting.value = false;
    }
  }

  return {
    // 筛选与看板
    filterDate,
    filterShift,
    columns,
    boardLoading,
    loadBoard,
    // 详情抽屉
    drawerVisible,
    selected,
    trace,
    traceLoading,
    openDetail,
    infusionStrip,
    // 操作组
    acting,
    availableActions,
    onSignReceive,
    onScanCheck,
    onStart,
    onFinish,
    onCancelExecution,
    // 扫码核对
    scanCode,
    scanType,
  };
}

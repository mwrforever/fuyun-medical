// 执行工作台 composable 单测（PR-3 Task 14，四组用例）：①分组过滤——四列看板按状态
// 分组、终态行不进列、LONG 锚行（m04PlanNo 为空）标记；②操作在途守卫——acting 互斥双击
// 零二次出网、动作成功后 trace+看板重拉；③扫码校验——PDA 同款正则（腕带 I+13 位/执行单
// EX+13 位）非法拦截零出网、合法出网核对；④换行不串台——换行清空旧 trace 数据源降级
// selected、慢回包竞态过期回包丢弃（EX-45 形态）；⑤看板慢回包守卫（D-3）——病区切换
// 旧清单回包后到丢弃、新请求在途时 loading 不被旧请求收尾误复位。api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { executions, infusions } from '@/api/nursing';
import { infusionBoard } from '@/api/ward';
import type { OrderExecutionTraceVO, OrderExecutionVO } from '@/api/nursing';
import { isAnchorRow, useExecutions, validateScanCode } from './useExecutions';

vi.mock('@/api/nursing', () => ({
  executions: {
    list: vi.fn(),
    trace: vi.fn(),
    signReceive: vi.fn(),
    check: vi.fn(),
    start: vi.fn(),
    finish: vi.fn(),
    cancel: vi.fn(),
  },
  infusions: { active: vi.fn() },
}));

vi.mock('@/api/ward', () => ({
  infusionBoard: { byWard: vi.fn() },
}));

// 仅替身弹层件（提示与确认断言用），其余导出原样保留
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: {
      ...mod.ElMessageBox,
      confirm: vi.fn().mockResolvedValue('confirm'),
      prompt: vi.fn().mockResolvedValue({ value: '医嘱作废' }),
    },
  };
});

/** 执行单行（状态/锚行可覆写） */
function rowMock(partial: Partial<OrderExecutionVO> = {}): OrderExecutionVO {
  return {
    executionNo: 'EX2026100100001',
    m04OrderNo: 'M04202610010001',
    m04PlanNo: 'M04P2026100100001',
    visitId: 'I20260923000000001',
    patientId: '1932000000000000001',
    wardId: 'W01',
    bedNo: '01',
    executionType: 'GENERIC',
    execItemName: '0.9% 氯化钠注射液',
    dosageText: '250ml qd',
    planTime: '2026-10-01T08:00:00',
    status: 'CREATED',
    ...partial,
  };
}

describe('useExecutions', () => {
  const wardId = ref('W01');

  beforeEach(() => {
    vi.mocked(executions.list).mockReset().mockResolvedValue({ content: [], total: '0' });
    vi.mocked(executions.trace).mockReset().mockResolvedValue({});
    vi.mocked(executions.signReceive).mockReset().mockResolvedValue(rowMock());
    vi.mocked(executions.check).mockReset().mockResolvedValue(rowMock());
    vi.mocked(executions.start).mockReset().mockResolvedValue(rowMock());
    vi.mocked(executions.finish).mockReset().mockResolvedValue(rowMock());
    vi.mocked(executions.cancel).mockReset().mockResolvedValue(rowMock());
    vi.mocked(infusions.active).mockReset().mockResolvedValue([]);
    vi.mocked(infusionBoard.byWard).mockReset().mockResolvedValue({ devices: [] });
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.prompt).mockClear();
  });

  /* ==================== 第①组：分组过滤 ==================== */

  it('四列看板按状态分组且终态行不进列（COMPLETED/CANCELLED 排除）', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [
        rowMock({ executionNo: 'E1', status: 'CREATED' }),
        rowMock({ executionNo: 'E2', status: 'SIGNED' }),
        rowMock({ executionNo: 'E3', status: 'CHECKED' }),
        rowMock({ executionNo: 'E4', status: 'EXECUTING' }),
        rowMock({ executionNo: 'E5', status: 'COMPLETED' }),
        rowMock({ executionNo: 'E6', status: 'CANCELLED' }),
      ],
      total: '6',
    });
    const state = useExecutions({ wardId, getExecutorId: () => 'u1' });
    await state.loadBoard();
    expect(state.columns.value.map((column) => column.label)).toEqual([
      '待签收',
      '待核对',
      '待执行',
      '执行中',
    ]);
    expect(state.columns.value.map((column) => column.rows.map((row) => row.executionNo))).toEqual([
      ['E1'],
      ['E2'],
      ['E3'],
      ['E4'],
    ]);
    // 出网参数：病区+日期默认当日、班次空不过滤、固定首页 200 条
    expect(executions.list).toHaveBeenCalledWith({
      wardId: 'W01',
      date: expect.any(String),
      shift: undefined,
      page: 0,
      size: 200,
    });
  });

  it('LONG 锚行（m04PlanNo 为空）标记且不进操作流（availableActions 为空）', () => {
    const anchor = rowMock({ executionNo: 'E7', m04PlanNo: undefined, status: 'CREATED' });
    const snapshot = rowMock({ executionNo: 'E8', status: 'CREATED' });
    expect(isAnchorRow(anchor)).toBe(true);
    expect(isAnchorRow(snapshot)).toBe(false);
    const state = useExecutions({ wardId, getExecutorId: () => 'u1' });
    expect(state.availableActions(anchor)).toEqual([]);
    expect(state.availableActions(snapshot)).toContain('signReceive');
    // 状态-动作矩阵：SIGNED=核对+撤销；CHECKED=开始+撤销；EXECUTING=完成（中断归输液面）
    expect(state.availableActions(rowMock({ status: 'SIGNED' }))).toEqual(['check', 'cancel']);
    expect(state.availableActions(rowMock({ status: 'CHECKED' }))).toEqual(['start', 'cancel']);
    expect(state.availableActions(rowMock({ status: 'EXECUTING' }))).toEqual(['finish']);
  });

  /* ==================== 第②组：操作在途守卫 ==================== */

  it('动作在途互斥：acting 置位期间二次动作零二次出网，成功后 trace+看板重拉', async () => {
    let releaseSign: (vo: OrderExecutionVO) => void = () => {};
    vi.mocked(executions.signReceive).mockImplementation(
      () =>
        new Promise((resolve) => {
          releaseSign = resolve;
        }),
    );
    const state = useExecutions({ wardId, getExecutorId: () => 'u1' });
    state.selected.value = rowMock({ status: 'CREATED' });
    state.drawerVisible.value = true;
    const first = state.onSignReceive();
    // 在途窗口：acting=true，二次动作（开始执行）经入口守卫零出网
    expect(state.acting.value).toBe(true);
    await state.onStart();
    expect(executions.start).not.toHaveBeenCalled();
    releaseSign(rowMock());
    await first;
    expect(state.acting.value).toBe(false);
    // 成功后重拉：trace 与看板各一次（动作前未初拉，无历史调用）
    expect(executions.trace).toHaveBeenCalledWith('EX2026100100001');
    expect(executions.list).toHaveBeenCalledTimes(1);
  });

  it('开始执行携带当班执行人；会话无操作人身份时显式拦截零出网', async () => {
    const state = useExecutions({ wardId, getExecutorId: () => '' });
    state.selected.value = rowMock({ status: 'CHECKED' });
    await state.onStart();
    expect(executions.start).not.toHaveBeenCalled();
    expect(ElMessage.warning).toHaveBeenCalledWith(
      '会话缺少操作人身份，无法执行该操作（请重新登录后再试）',
    );
    const withOperator = useExecutions({ wardId, getExecutorId: () => 'u1' });
    withOperator.selected.value = rowMock({ status: 'CHECKED' });
    await withOperator.onStart();
    expect(executions.start).toHaveBeenCalledWith('EX2026100100001', {
      executorId: 'u1',
      overrideTimeWindow: false,
    });
  });

  it('撤销必填原因（prompt 携医嘱作废）出网一次', async () => {
    const state = useExecutions({ wardId, getExecutorId: () => 'u1' });
    state.selected.value = rowMock({ status: 'CHECKED' });
    await state.onCancelExecution();
    expect(executions.cancel).toHaveBeenCalledTimes(1);
    expect(executions.cancel).toHaveBeenCalledWith('EX2026100100001', { reason: '医嘱作废' });
  });

  /* ==================== 第③组：扫码校验 ==================== */

  it('扫码校验纯函数：腕带 I+13 位 / 执行单 EX+13 位 / 瓶签非空 ≤64', () => {
    expect(validateScanCode('I2026092300001', 'WRISTBAND')).toBeNull();
    expect(validateScanCode('I2026', 'WRISTBAND')).toContain('腕带号');
    expect(validateScanCode('EX2026100100001', 'DEVICE')).toBeNull();
    expect(validateScanCode('E1', 'DEVICE')).toContain('执行单条码');
    expect(validateScanCode('BAG-001', 'BAG_LABEL')).toBeNull();
    expect(validateScanCode('', 'BAG_LABEL')).toContain('瓶签码');
    expect(validateScanCode('x'.repeat(65), 'BAG_LABEL')).toContain('瓶签码');
  });

  it('扫码核对：非法腕带提示且零出网；合法腕带回车出网核对一次', async () => {
    const state = useExecutions({ wardId, getExecutorId: () => 'u1' });
    state.selected.value = rowMock({ status: 'SIGNED' });
    state.scanCode.value = 'I2026';
    state.scanType.value = 'WRISTBAND';
    await state.onScanCheck();
    expect(executions.check).not.toHaveBeenCalled();
    expect(ElMessage.warning).toHaveBeenCalledWith(
      '腕带号应为 I 开头共 14 位（I+13 位数字），请重新扫描',
    );
    state.scanCode.value = 'I2026092300001';
    await state.onScanCheck();
    expect(executions.check).toHaveBeenCalledTimes(1);
    expect(executions.check).toHaveBeenCalledWith('EX2026100100001', {
      code: 'I2026092300001',
      codeType: 'WRISTBAND',
    });
  });

  /* ==================== 第④组：详情抽屉换行不串台（EX-45 形态） ==================== */

  it('换行打开 B 行先清空旧时间线：trace 未回包前抽屉数据源降级 selected', async () => {
    // 首次打开 A 行 trace 即回；换行 B 行的 trace 挂起制造「未回包窗口」
    vi.mocked(executions.trace)
      .mockResolvedValueOnce({ executionNo: 'EA', signedAt: '2026-10-01T08:05:00' })
      .mockImplementation(() => new Promise<OrderExecutionTraceVO>(() => {}));
    const state = useExecutions({ wardId, getExecutorId: () => 'u1' });
    await state.openDetail(rowMock({ executionNo: 'EA' }));
    expect(state.trace.value?.executionNo).toBe('EA');
    // B 行 trace 在途未回包：换行瞬间旧时间线必须清空，抽屉数据源降级 B 行快照
    // （trace=null 时视图 timeline 取 selected 兜底），不得残留 A 行闭环数据
    void state.openDetail(rowMock({ executionNo: 'EB', signedAt: '2026-10-01T08:10:00' }));
    expect(state.selected.value?.executionNo).toBe('EB');
    expect(state.trace.value).toBeNull();
  });

  it('慢回包竞态：A 行 trace 回包在换行 B 后晚到，过期回包丢弃不覆盖 B 时间线', async () => {
    // A 行 trace 挂起手动放行，制造「A 慢回包晚到」竞态窗口；B 行回包即达
    let releaseA: (vo: OrderExecutionTraceVO) => void = () => {};
    vi.mocked(executions.trace).mockImplementation(
      (no) =>
        new Promise((resolve) => {
          if (no === 'EA') {
            releaseA = resolve;
            return;
          }
          resolve({ executionNo: no, signedAt: '2026-10-01T08:10:00' });
        }),
    );
    const state = useExecutions({ wardId, getExecutorId: () => 'u1' });
    const openA = state.openDetail(rowMock({ executionNo: 'EA' }));
    // A 在途时换行 B：B 回包先到，时间线为 B 单闭环数据
    const openB = state.openDetail(rowMock({ executionNo: 'EB' }));
    await openB;
    expect(state.trace.value?.executionNo).toBe('EB');
    // A 慢回包后到：过期回包必须丢弃，不得把 A 行闭环数据串台到 B 行抽屉
    releaseA({ executionNo: 'EA', signedAt: '2026-10-01T08:05:00' });
    await openA;
    expect(state.trace.value?.executionNo).toBe('EB');
  });

  /* ==================== 第⑤组：看板慢回包守卫（D-3） ==================== */

  it('D-3 loadBoard 慢回包守卫：病区切换后旧回包丢弃且 loading 不误复位', async () => {
    // 手动闸门两段式：按调用序捕获各次清单回包 resolve 器，测试侧控制回包先后（模拟慢回包晚到）
    const gates: Array<(page: Awaited<ReturnType<typeof executions.list>>) => void> = [];
    vi.mocked(executions.list).mockImplementation(
      () =>
        new Promise((resolve) => {
          gates.push(resolve);
        }),
    );
    // 独立病区 ref：切换病区触发第二次 loadBoard，不污染共享用例病区
    const wardRef = ref('W01');
    const state = useExecutions({ wardId: wardRef, getExecutorId: () => 'u1' });
    const boardOld = state.loadBoard(); // 旧病区 W01 清单在途
    wardRef.value = 'W02';
    const boardNew = state.loadBoard(); // 病区已切 W02，更新请求在途
    // 旧病区回包后到：过期丢弃，看板不得落 W01 行
    gates[0]?.({ content: [rowMock({ executionNo: 'OLD', status: 'CREATED' })], total: '1' });
    await boardOld;
    expect(
      state.columns.value.flatMap((column) => column.rows.map((row) => row.executionNo)),
    ).toEqual([]);
    // 新请求仍在途：旧请求收尾不得提前撤掉新请求的加载态
    expect(state.boardLoading.value).toBe(true);
    // 新病区回包：最新请求正常落值并复位加载态
    gates[1]?.({ content: [rowMock({ executionNo: 'NEW', status: 'CREATED' })], total: '1' });
    await boardNew;
    expect(
      state.columns.value.flatMap((column) => column.rows.map((row) => row.executionNo)),
    ).toEqual(['NEW']);
    expect(state.boardLoading.value).toBe(false);
  });
});

// PDA 移动护理页单测（M05 前端面，设计文档 §4）：腕带/卡号格式校验（非 I 型 14 位且非
// 卡号格式时提示且零出网，spec 冻结）、患者卡超敏字段零渲染（DOM 不含「手机」「证件」
// 文案——脱敏摘要数据源）、巡视打卡成功后按钮转已完成态并回显 taskNo（patrol 调用一次）、
// 打卡在途守卫拦截重复点击（双击零二次出网）、卡号路径 visitId 判空（体征录入与巡视打卡
// 均 warning 明确提示且零出网，R1 finding ③）、体征幂等键两态（D-22）。
// PR-3 Task 15 扩（住院执行六段流）：在途执行单清单按当前患者前端侧过滤（他患者与终态行
// 剔除）、扫码核对三输入 codeType 分段提交与 FAIL（409）分支（破码双授权表单弹出、扫码
// 原文保留重试）、给药 start→finish 按状态切换、输液设备码携 start 与拔针量 0~5000 校验、
// 双授权表单必填/两人不同校验、换单竞态过期回包丢弃（EX-45/FE-A1-04 同族纪律）。
// api mock 承载，不打真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { executions, pda, vitalSigns } from '@/api/nursing';
import type { OrderExecutionVO, PdaPatientSummaryVO } from '@/api/nursing';
import PdaView from './PdaView.vue';
import { todayString } from './wardBoardShared';

vi.mock('@/api/nursing', () => ({
  TEMP_SITE_SYMBOL: { AXILLARY: 'fuy-temp-x', ORAL: 'fuy-temp-dot', RECTAL: 'fuy-temp-circle' },
  TEMP_SITE_OPTIONS: [
    { code: 'AXILLARY', label: '腋下' },
    { code: 'ORAL', label: '口腔' },
    { code: 'RECTAL', label: '直肠' },
  ],
  NURSING_LEVEL_OPTIONS: [
    { code: 'SPECIAL', label: '特级护理' },
    { code: 'CRITICAL', label: '病重护理' },
    { code: 'NORMAL', label: '普通护理' },
  ],
  SPECIAL_EVENT_OPTIONS: [],
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
  SHIFT_OPTIONS: [{ code: 'DAY', label: '白班' }],
  EXECUTION_STATUS_LABELS: {
    CREATED: '待签收',
    SIGNED: '已签收',
    CHECKED: '已核对',
    EXECUTING: '执行中',
    COMPLETED: '已完成',
    CANCELLED: '已撤销',
  },
  EXECUTION_TYPE_LABELS: { GENERIC: '通用给药', INFUSION: '输液' },
  // W-34：register/remove 已随端点退役，mock 面同步收敛
  wardPatients: { list: vi.fn(), detail: vi.fn() },
  assignments: { list: vi.fn(), create: vi.fn(), remove: vi.fn() },
  vitalSigns: {
    record: vi.fn(),
    list: vi.fn(),
    pendingReview: vi.fn(),
    confirm: vi.fn(),
    reject: vi.fn(),
  },
  chart: { query: vi.fn(), addSpecialEvent: vi.fn() },
  ioRecords: { create: vi.fn(), list: vi.fn() },
  records: { create: vi.fn(), submit: vi.fn(), revise: vi.fn(), list: vi.fn() },
  assessments: { scales: vi.fn(), create: vi.fn(), list: vi.fn() },
  tasks: { list: vi.fn(), complete: vi.fn(), cancel: vi.fn() },
  handovers: { generate: vi.fn(), complete: vi.fn(), list: vi.fn() },
  adverseEvents: { list: vi.fn(), report: vi.fn() },
  infusions: { active: vi.fn() },
  // Task 15 消费面：清单（GET /executions）+ check/start/finish 复用执行单资源组
  executions: { list: vi.fn(), check: vi.fn(), start: vi.fn(), finish: vi.fn() },
  pda: { patientSummary: vi.fn(), patrol: vi.fn(), needleOut: vi.fn(), overrideCheck: vi.fn() },
}));

// 仅替身 ElMessage（提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

/** 脱敏患者摘要（无证件/手机号字段——超敏字段零渲染的数据源形态） */
function summaryMock(partial: Partial<PdaPatientSummaryVO> = {}): PdaPatientSummaryVO {
  return {
    patientId: '1932000000000000001',
    patientName: '张*',
    wardId: 'W01',
    bedNo: '03-01',
    nursingLevel: 'NORMAL',
    allergies: [{ itemCode: 'PAVE', itemName: '青霉素', severity: 'SEVERE' }],
    inFlightTaskCount: 2,
    ...partial,
  };
}

/** 执行单行（状态/型别/患者可覆写；visitId 一律 I+13 位 mock 口径） */
function executionRowMock(partial: Partial<OrderExecutionVO> = {}): OrderExecutionVO {
  return {
    executionNo: 'EX2026100200001',
    m04OrderNo: 'M04202610020001',
    m04PlanNo: 'M04P2026100200001',
    visitId: 'I2026092300001',
    patientId: '1932000000000000001',
    wardId: 'W01',
    bedNo: '03-01',
    executionType: 'GENERIC',
    execItemName: '0.9% 氯化钠注射液',
    dosageText: '250ml qd',
    planTime: '2026-10-02T08:00:00',
    status: 'SIGNED',
    ...partial,
  };
}

/**
 * 扫码核对 FAIL 形态（后端 409 冲突=NS-1022；AxiosError 鸭子形态——经 isAxiosError +
 * response.status 判定路径，真实运行时错误提示归响应拦截器，单测只驱动分支不重复断言弹错）。
 */
function checkFailError(): Error {
  return Object.assign(new Error('扫码核对不匹配'), {
    isAxiosError: true,
    response: {
      status: 409,
      data: { detail: '扫码核对不匹配（WRISTBAND，fail_type=WRISTBAND_MISMATCH）' },
    },
  });
}

// 清单数据源（describe 间共享的可变行集：动作后重拉清单的状态迁移由测试内改写本变量承载）
let execListRows: OrderExecutionVO[] = [];
// 会话级 pinia（PdaView 消费 auth store 取执行人 userId）
let pinia: Pinia;

/** 会话种子（auth store 从 sessionStorage 恢复：执行人=登录用户 u1/李护士） */
function seedAuthSession(): void {
  sessionStorage.setItem(
    'fy:workstation:auth',
    JSON.stringify({
      token: 'test-token',
      refreshToken: 'test-refresh',
      user: {
        userId: 'u1',
        loginName: 'nursedemo',
        displayName: '李护士',
        orgId: null,
        roles: ['nurse'],
      },
    }),
  );
}

function mountView(): VueWrapper {
  return mount(PdaView, { global: { plugins: [pinia] } });
}

/** 完成患者识别前置（合法腕带 → 摘要返回 → 段解锁 + 在途清单加载） */
async function identify(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('input[placeholder="扫描腕带或输入患者卡号"]').setValue('I2026092300001');
  const buttons = wrapper.findAll('button');
  await buttons.find((b) => b.text() === '查询')?.trigger('click');
  await flushPromises();
}

describe('PDA 移动护理页', () => {
  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    seedAuthSession();
    execListRows = [];
    vi.mocked(pda.patientSummary).mockReset();
    vi.mocked(pda.patrol).mockReset();
    vi.mocked(pda.needleOut).mockReset();
    vi.mocked(pda.overrideCheck).mockReset();
    vi.mocked(vitalSigns.record).mockReset();
    // 清单回包读可变行集（动作后状态迁移由测试内改写承载；Promise 包装避开无 await 异步箭头）
    vi.mocked(executions.list)
      .mockReset()
      .mockImplementation(() => Promise.resolve({ content: execListRows }));
    vi.mocked(executions.check).mockReset();
    vi.mocked(executions.start).mockReset();
    vi.mocked(executions.finish).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
  });

  it('腕带标识校验：非 I 型 14 位且非卡号格式时提示且零出网', async () => {
    const wrapper = mountView();
    const input = wrapper.find('input[placeholder="扫描腕带或输入患者卡号"]');
    // 非法形态：短数字（不足 8 位卡号下限）
    await input.setValue('1234567');
    const buttons = wrapper.findAll('button');
    await buttons.find((b) => b.text() === '查询')?.trigger('click');
    await flushPromises();
    expect(wrapper.find('.pda-field-error').text()).toBe(
      '腕带号应为 I 开头 14 位，或 8 位以上数字卡号，请重新扫描',
    );
    expect(vi.mocked(pda.patientSummary)).not.toHaveBeenCalled();
    // 合法形态放行对照：I 型 14 位
    await input.setValue('I2026092300001');
    await buttons.find((b) => b.text() === '查询')?.trigger('click');
    await flushPromises();
    expect(vi.mocked(pda.patientSummary)).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('患者卡展示过敏与在区床位，超敏字段不渲染（DOM 不含「手机」「证件」文案）', async () => {
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    const wrapper = mountView();
    await identify(wrapper);
    expect(wrapper.text()).toContain('张*');
    expect(wrapper.text()).toContain('03-01');
    expect(wrapper.text()).toContain('青霉素');
    // 超敏字段零渲染（spec 冻结断言：脱敏摘要无证件/手机号字段）
    expect(wrapper.text()).not.toContain('手机');
    expect(wrapper.text()).not.toContain('证件');
    wrapper.unmount();
  });

  it('巡视打卡成功后按钮进入已完成态并回显 taskNo（patrol 调用一次）', async () => {
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    vi.mocked(pda.patrol).mockResolvedValue({
      taskNo: 'T20260923002',
      status: 'COMPLETED',
      taskType: 'PATROL',
    });
    const wrapper = mountView();
    await identify(wrapper);
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '巡视打卡')
      ?.trigger('click');
    await flushPromises();
    expect(vi.mocked(pda.patrol)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(pda.patrol).mock.calls[0]?.[0]).toEqual({
      identifier: 'I2026092300001',
      visitId: 'I2026092300001',
    });
    // 已完成态：绿底按钮文案 + taskNo 回显
    expect(wrapper.text()).toContain('已巡视 ✓');
    expect(wrapper.text()).toContain('T20260923002');
    wrapper.unmount();
  });

  it('打卡在途守卫拦截重复点击（双击零二次出网，按钮 disabled）', async () => {
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    let releasePatrol: () => void = () => {};
    vi.mocked(pda.patrol).mockImplementation(
      () =>
        new Promise((resolve) => {
          releasePatrol = () => resolve({ taskNo: 'T20260923003', status: 'COMPLETED' });
        }),
    );
    const wrapper = mountView();
    await identify(wrapper);
    const patrolButton = wrapper.findAll('button').find((b) => b.text() === '巡视打卡');
    await patrolButton?.trigger('click');
    await flushPromises();
    // 在途窗口：按钮禁用且二次点击经入口守卫零二次出网
    const inFlight = wrapper.findAll('button').find((b) => b.text() === '打卡中…');
    expect(inFlight?.attributes('disabled')).toBeDefined();
    await inFlight?.trigger('click');
    await flushPromises();
    expect(vi.mocked(pda.patrol)).toHaveBeenCalledTimes(1);
    releasePatrol();
    await flushPromises();
    wrapper.unmount();
  });

  it('卡号路径 visitId 判空：体征录入与巡视打卡均 warning 提示且零出网', async () => {
    // 卡号识别（8 位数字合法卡号）成功解锁段卡，但脱敏摘要面无 visitId 可回溯
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    const wrapper = mountView();
    await wrapper.find('input[placeholder="扫描腕带或输入患者卡号"]').setValue('12345678');
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '查询')
      ?.trigger('click');
    await flushPromises();
    expect(vi.mocked(pda.patientSummary)).toHaveBeenCalledWith('12345678');
    // 体征录入：visitId 为 required 必填、空串出网必 4xx——前端判空拦截（R1 finding ③）
    await wrapper.find('input[placeholder="36.5"]').setValue('36.5');
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '提交体征')
      ?.trigger('click');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '卡号识别无法录入体征，请改用腕带扫描（I 开头 14 位）后重试',
    );
    expect(vi.mocked(vitalSigns.record)).not.toHaveBeenCalled();
    // 巡视打卡：同口径判空拦截，零出网
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '巡视打卡')
      ?.trigger('click');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '卡号识别无法巡视打卡，请改用腕带扫描（I 开头 14 位）后重试',
    );
    expect(vi.mocked(pda.patrol)).not.toHaveBeenCalled();
    // 拦截后按钮仍处初始态（未进入在途/已完成形态）
    expect(wrapper.text()).toContain('巡视打卡');
    wrapper.unmount();
  });

  it('体征提交载荷携带幂等键，成功后清空表单并轮换键（D-22）', async () => {
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    vi.mocked(vitalSigns.record).mockResolvedValue({});
    const wrapper = mountView();
    await identify(wrapper);
    await wrapper.find('input[placeholder="36.5"]').setValue('36.5');
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '提交体征')
      ?.trigger('click');
    await flushPromises();
    expect(vi.mocked(vitalSigns.record)).toHaveBeenCalledTimes(1);
    const firstPayload = vi.mocked(vitalSigns.record).mock.calls[0]?.[0];
    // 载荷锚：幂等键随提交出网，source=PDA、数值转换不变形
    expect(typeof firstPayload.clientMsgId).toBe('string');
    expect(firstPayload.source).toBe('PDA');
    expect(firstPayload.temperature).toBe(36.5);
    // 成功后清表单 + 幂等键轮换：下次提交为新幂等单元（不与本次同键）
    expect((wrapper.find('input[placeholder="36.5"]').element as HTMLInputElement).value).toBe('');
    await wrapper.find('input[placeholder="36.5"]').setValue('36.8');
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '提交体征')
      ?.trigger('click');
    await flushPromises();
    expect(vi.mocked(vitalSigns.record).mock.calls[1]?.[0].clientMsgId).not.toBe(
      firstPayload.clientMsgId,
    );
    wrapper.unmount();
  });

  it('弱网失败重试复用同一幂等键，成功才清表单（D-22）', async () => {
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    vi.mocked(vitalSigns.record)
      .mockRejectedValueOnce(Object.assign(new Error('mock'), { detail: '网络超时' }))
      .mockResolvedValueOnce({});
    const wrapper = mountView();
    await identify(wrapper);
    await wrapper.find('input[placeholder="36.5"]').setValue('36.5');
    const submitButton = () => wrapper.findAll('button').find((b) => b.text() === '提交体征');
    await submitButton()?.trigger('click');
    await flushPromises();
    // 失败：错误提示透出且表单保留（重试数据不丢）
    expect(vi.mocked(ElMessage.error)).toHaveBeenCalledWith('网络超时');
    expect((wrapper.find('input[placeholder="36.5"]').element as HTMLInputElement).value).toBe(
      '36.5',
    );
    // D-22 核心：重试复用同一键——服务端按 client_msg_id 重放返回原记录，补传零重复落卡
    await submitButton()?.trigger('click');
    await flushPromises();
    const calls = vi.mocked(vitalSigns.record).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[0]?.[0].clientMsgId).toBe(calls[1]?.[0].clientMsgId);
    // 成功后清表单
    expect((wrapper.find('input[placeholder="36.5"]').element as HTMLInputElement).value).toBe('');
    wrapper.unmount();
  });

  /* ==================== Task 15：住院执行六段流扩展 ==================== */

  it('在途执行单清单按当前患者前端侧过滤（他患者与终态行剔除），点选解锁核对/执行段', async () => {
    execListRows = [
      executionRowMock({ executionNo: 'EX2026100200001', execItemName: '头孢呋辛钠片' }),
      executionRowMock({
        executionNo: 'EX2026100200002',
        execItemName: '硝苯地平片',
        patientId: '1932000000000000999',
      }),
      executionRowMock({
        executionNo: 'EX2026100200003',
        execItemName: '左氧氟沙星',
        status: 'COMPLETED',
      }),
      executionRowMock({
        executionNo: 'EX2026100200004',
        execItemName: '氯化钾缓释片',
        status: 'CANCELLED',
      }),
    ];
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    const wrapper = mountView();
    await identify(wrapper);
    // 清单拉取锚：病区 + 当日 + 分页参数（工作台清单端点复用）
    expect(vi.mocked(executions.list)).toHaveBeenCalledWith({
      wardId: 'W01',
      date: todayString(),
      page: 0,
      size: 200,
    });
    // 当前患者在途行独占渲染：他患者/已完成/已撤销行不出现
    const items = wrapper.findAll('.pda-exec-item');
    expect(items).toHaveLength(1);
    expect(items[0]?.text()).toContain('头孢呋辛钠片');
    expect(wrapper.text()).not.toContain('硝苯地平片');
    expect(wrapper.text()).not.toContain('左氧氟沙星');
    expect(wrapper.text()).not.toContain('氯化钾缓释片');
    // 点选行：核对/执行段换入当前单（未选时引导文案消失）
    expect(wrapper.text()).toContain('先从清单选择执行单');
    await items[0]?.trigger('click');
    expect(wrapper.findAll('.pda-exec-target')).toHaveLength(2);
    wrapper.unmount();
  });

  it('扫码核对 FAIL 分支：非法格式贴字段错误零出网，409 弹破码表单且原文保留，重试通过清输入', async () => {
    execListRows = [executionRowMock({ executionNo: 'EX2026100200011', status: 'SIGNED' })];
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    vi.mocked(executions.check)
      .mockRejectedValueOnce(checkFailError())
      .mockResolvedValueOnce(
        executionRowMock({ executionNo: 'EX2026100200011', status: 'CHECKED' }),
      );
    const wrapper = mountView();
    await identify(wrapper);
    await wrapper.find('.pda-exec-item').trigger('click');
    // 非法格式：贴字段错误 + 零出网（§4.3 错误贴字段口径）
    const wristbandInput = wrapper.find('input[placeholder="扫描患者腕带（I 开头 14 位）"]');
    await wristbandInput.setValue('12345');
    await wristbandInput.trigger('keyup.enter');
    await flushPromises();
    expect(wrapper.find('.pda-check-error').text()).toBe(
      '腕带号应为 I 开头共 14 位（I+13 位数字），请重新扫描',
    );
    expect(vi.mocked(executions.check)).not.toHaveBeenCalled();
    // 合法形态提交后 FAIL（409）：破码放行表单弹出、扫码原文保留（重试不改写输入）
    await wristbandInput.setValue('I2026092399999');
    await wristbandInput.trigger('keyup.enter');
    await flushPromises();
    expect(vi.mocked(executions.check)).toHaveBeenCalledWith('EX2026100200011', {
      code: 'I2026092399999',
      codeType: 'WRISTBAND',
    });
    expect(wrapper.find('.pda-override').exists()).toBe(true);
    expect((wristbandInput.element as HTMLInputElement).value).toBe('I2026092399999');
    // 重试通过：成功提示 + 输入清空（连续扫下一维节奏）+ 破码表单收起
    await wristbandInput.trigger('keyup.enter');
    await flushPromises();
    expect(vi.mocked(executions.check)).toHaveBeenCalledTimes(2);
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalledWith('核对通过：EX2026100200011');
    expect((wristbandInput.element as HTMLInputElement).value).toBe('');
    expect(wrapper.find('.pda-override').exists()).toBe(false);
    wrapper.unmount();
  });

  it('扫码核对 codeType 分段：瓶签与设备码输入各自按维提交', async () => {
    execListRows = [executionRowMock({ executionNo: 'EX2026100200012' })];
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    vi.mocked(executions.check).mockResolvedValue(
      executionRowMock({ executionNo: 'EX2026100200012' }),
    );
    const wrapper = mountView();
    await identify(wrapper);
    await wrapper.find('.pda-exec-item').trigger('click');
    // 瓶签维：非空且 ≤64 位即合法（袋签码形态归后端核对）
    const bagInput = wrapper.find('input[placeholder="扫描输液袋签"]');
    await bagInput.setValue('BAG20261002001');
    await bagInput.trigger('keyup.enter');
    await flushPromises();
    expect(vi.mocked(executions.check)).toHaveBeenLastCalledWith('EX2026100200012', {
      code: 'BAG20261002001',
      codeType: 'BAG_LABEL',
    });
    // 设备码维：执行单条码 EX 形态
    const deviceInput = wrapper.find('input[placeholder="扫描执行单条码（EX 开头）"]');
    await deviceInput.setValue('EX2026100200012');
    await deviceInput.trigger('keyup.enter');
    await flushPromises();
    expect(vi.mocked(executions.check)).toHaveBeenLastCalledWith('EX2026100200012', {
      code: 'EX2026100200012',
      codeType: 'DEVICE',
    });
    wrapper.unmount();
  });

  it('给药执行段按状态切换：开始携会话执行人，完成后离场在途清单', async () => {
    execListRows = [executionRowMock({ executionNo: 'EX2026100200021', status: 'CHECKED' })];
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    vi.mocked(executions.start).mockResolvedValue(
      executionRowMock({ executionNo: 'EX2026100200021', status: 'EXECUTING' }),
    );
    vi.mocked(executions.finish).mockResolvedValue(
      executionRowMock({ executionNo: 'EX2026100200021', status: 'COMPLETED' }),
    );
    const wrapper = mountView();
    await identify(wrapper);
    await wrapper.find('.pda-exec-item').trigger('click');
    // 预置动作后清单回包新态（list mock 在 start 收尾重拉时读取——回包即状态迁移）
    execListRows = [executionRowMock({ executionNo: 'EX2026100200021', status: 'EXECUTING' })];
    // CHECKED 态：开始执行（executorId 取会话身份留痕）
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '开始执行')
      ?.trigger('click');
    await flushPromises();
    expect(vi.mocked(executions.start)).toHaveBeenCalledWith('EX2026100200021', {
      executorId: 'u1',
      overrideTimeWindow: false,
    });
    // EXECUTING 态：按钮切换为执行完成（完成后终态离场在途清单）
    execListRows = [];
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '执行完成')
      ?.trigger('click');
    await flushPromises();
    expect(vi.mocked(executions.finish)).toHaveBeenCalledWith('EX2026100200021', {
      executorId: 'u1',
    });
    // 终态离场：在途清单空态（COMPLETED 行不进清单）
    expect(wrapper.text()).toContain('当前患者无在途执行单');
    wrapper.unmount();
  });

  it('输液执行段：开始携可扫设备码，拔针量 0~5000 越界与腕带复扫校验零出网，合法载荷提交', async () => {
    execListRows = [
      executionRowMock({
        executionNo: 'EX2026100200031',
        status: 'CHECKED',
        executionType: 'INFUSION',
      }),
    ];
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    vi.mocked(executions.start).mockResolvedValue(
      executionRowMock({
        executionNo: 'EX2026100200031',
        status: 'EXECUTING',
        executionType: 'INFUSION',
      }),
    );
    vi.mocked(pda.needleOut).mockResolvedValue(
      executionRowMock({
        executionNo: 'EX2026100200031',
        status: 'COMPLETED',
        executionType: 'INFUSION',
      }),
    );
    const wrapper = mountView();
    await identify(wrapper);
    await wrapper.find('.pda-exec-item').trigger('click');
    // 预置动作后清单回包新态（list mock 在 start 收尾重拉时读取——回包即状态迁移）
    execListRows = [
      executionRowMock({
        executionNo: 'EX2026100200031',
        status: 'EXECUTING',
        executionType: 'INFUSION',
      }),
    ];
    // 输液 CHECKED 态：设备码可扫入 start 载荷（Task 6 infusion.started 挂接语义）
    await wrapper.find('input[placeholder="扫描输液设备码（选填）"]').setValue('DEV-ICU-07');
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '开始输液')
      ?.trigger('click');
    await flushPromises();
    expect(vi.mocked(executions.start)).toHaveBeenCalledWith('EX2026100200031', {
      executorId: 'u1',
      overrideTimeWindow: false,
      deviceId: 'DEV-ICU-07',
    });
    // 拔针量越界（>5000）：warning 且零出网
    const volumeInput = wrapper.find('input[placeholder="0–5000"]');
    await volumeInput.setValue('5001');
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '拔针完成')
      ?.trigger('click');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '实际输注量应为 0–5000 的整数（ml），请重新输入',
    );
    expect(vi.mocked(pda.needleOut)).not.toHaveBeenCalled();
    // 非数字形态：同口径拦截
    await volumeInput.setValue('abc');
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '拔针完成')
      ?.trigger('click');
    await flushPromises();
    expect(vi.mocked(pda.needleOut)).not.toHaveBeenCalled();
    // 量合法但腕带复扫非法：拦截零出网（边界上界 5000 本身合法）
    await volumeInput.setValue('5000');
    const rescanInput = wrapper.find('input[placeholder="扫描患者腕带复扫"]');
    await rescanInput.setValue('123');
    await rescanInput.trigger('keyup.enter');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '腕带号应为 I 开头共 14 位（I+13 位数字），请重新扫描',
    );
    expect(vi.mocked(pda.needleOut)).not.toHaveBeenCalled();
    // 合法组合（回车提交路径）：实际输注量数字键盘值 + 腕带复扫码随载荷出网
    await volumeInput.setValue('250');
    await rescanInput.setValue('I2026092300001');
    await rescanInput.trigger('keyup.enter');
    await flushPromises();
    expect(vi.mocked(pda.needleOut)).toHaveBeenCalledWith('EX2026100200031', {
      executorId: 'u1',
      actualVolumeMl: 250,
      wristbandCode: 'I2026092300001',
    });
    wrapper.unmount();
  });

  it('破码放行双授权表单校验：必填缺失/两人相同零出网，合法载荷提交一次', async () => {
    execListRows = [executionRowMock({ executionNo: 'EX2026100200041', status: 'SIGNED' })];
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    vi.mocked(executions.check).mockRejectedValue(checkFailError());
    vi.mocked(pda.overrideCheck).mockResolvedValue(
      executionRowMock({ executionNo: 'EX2026100200041', overrideFlag: true }),
    );
    const wrapper = mountView();
    await identify(wrapper);
    await wrapper.find('.pda-exec-item').trigger('click');
    // 触发 FAIL 分支弹出双授权表单
    const wristbandInput = wrapper.find('input[placeholder="扫描患者腕带（I 开头 14 位）"]');
    await wristbandInput.setValue('I2026092399999');
    await wristbandInput.trigger('keyup.enter');
    await flushPromises();
    expect(wrapper.find('.pda-override').exists()).toBe(true);
    const submitOverride = async (): Promise<void> => {
      await wrapper
        .findAll('button')
        .find((b) => b.text() === '提交放行')
        ?.trigger('click');
      await flushPromises();
    };
    // 工号必填：全空提交拦截零出网
    await submitOverride();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('两位授权人工号均不能为空');
    expect(vi.mocked(pda.overrideCheck)).not.toHaveBeenCalled();
    // 两人不同：同工号提交拦截零出网
    await wrapper.find('input[placeholder="主授权人工号"]').setValue('1001');
    await wrapper.find('input[placeholder="副授权人工号"]').setValue('1001');
    await submitOverride();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('破码放行双授权两人不得相同');
    expect(vi.mocked(pda.overrideCheck)).not.toHaveBeenCalled();
    // 原因必填：缺原因拦截零出网
    await wrapper.find('input[placeholder="副授权人工号"]').setValue('1002');
    await submitOverride();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('放行原因不能为空');
    expect(vi.mocked(pda.overrideCheck)).not.toHaveBeenCalled();
    // 合法载荷：执行单号 + 两授权人 + 原因一次出网，成功后表单收起
    await wrapper.find('input[placeholder="放行原因"]').setValue('腕带破损无法扫码');
    await submitOverride();
    expect(vi.mocked(pda.overrideCheck)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(pda.overrideCheck).mock.calls[0]?.[0]).toEqual({
      executionNo: 'EX2026100200041',
      primaryAuthorizerId: '1001',
      secondaryAuthorizerId: '1002',
      reason: '腕带破损无法扫码',
    });
    expect(wrapper.find('.pda-override').exists()).toBe(false);
    wrapper.unmount();
  });

  it('换单竞态：核对在途过期回包不清新单输入、不弹新单破码表单（EX-45 同族纪律）', async () => {
    execListRows = [
      executionRowMock({ executionNo: 'EX2026100200051', status: 'SIGNED' }),
      executionRowMock({ executionNo: 'EX2026100200052', status: 'SIGNED' }),
    ];
    vi.mocked(pda.patientSummary).mockResolvedValue(summaryMock());
    // A 单核对挂起回包：提交后换选 B 单再放行——模拟慢回包竞态（照 EX-45 挂起形态）
    let releaseA: (row: OrderExecutionVO) => void = () => {};
    vi.mocked(executions.check).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          releaseA = resolve;
        }),
    );
    let rejectB: (reason: Error) => void = () => {};
    vi.mocked(executions.check).mockImplementationOnce(
      () =>
        new Promise((_resolve, reject) => {
          rejectB = reject;
        }),
    );
    const wrapper = mountView();
    await identify(wrapper);
    await wrapper.findAll('.pda-exec-item')[0]?.trigger('click');
    // 核对输入随选中单渲染；换行不卸载（v-if 锚定选中非空），同一 DOM 元素全程复用
    const wristbandInput = wrapper.find('input[placeholder="扫描患者腕带（I 开头 14 位）"]');
    await wristbandInput.setValue('I2026092399999');
    await wristbandInput.trigger('keyup.enter');
    await flushPromises();
    // A 单提交在途换选 B 单并录入新码：A 单回包即过期
    await wrapper.findAll('.pda-exec-item')[1]?.trigger('click');
    await wristbandInput.setValue('I2026092300052');
    releaseA(executionRowMock({ executionNo: 'EX2026100200051', status: 'CHECKED' }));
    await flushPromises();
    // 过期成功回包：A 单动作提示照常（确已落库），但 B 单扫码原文不被清空
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalledWith('核对通过：EX2026100200051');
    expect((wristbandInput.element as HTMLInputElement).value).toBe('I2026092300052');
    // B 单提交 FAIL 在途换回 A 单：过期 FAIL 回包不得弹 A 单名下破码表单
    await wristbandInput.trigger('keyup.enter');
    await flushPromises();
    await wrapper.findAll('.pda-exec-item')[0]?.trigger('click');
    rejectB(checkFailError());
    await flushPromises();
    expect(wrapper.find('.pda-override').exists()).toBe(false);
    wrapper.unmount();
  });
});

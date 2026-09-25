// 出院管理单测（/inpatient/discharge，M04 FU-M04-07 前端面）：四态 tab 渲染、出院申请
// 出网（携预出院时间/离院方式）、BLOCKED 清理预审面板欠费额渲染与挂账引导、离院确认
// 双条件禁用（预审 READY+结算完成，后端 GC19 前置同语义）与放行出网。
// 说明：冻结 REST 面无出院申请列表 GET 端点，列表由本会话发起的申请单承载（Concern 已登记）。
// api mock 承载零出网（vi.mock('@/api/inpatient') 整模块替身），不打真实网络；
// 断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { discharge } from '@/api/inpatient';
import type { ClearanceVO, DischargeRequestVO } from '@/api/inpatient';
import DischargeManageView from './DischargeManageView.vue';

vi.mock('@/api/inpatient', () => ({
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
  DISCHARGE_WAY_OPTIONS: [
    { code: '1', label: '医嘱离院' },
    { code: '2', label: '医嘱转院' },
    { code: '3', label: '转社区/乡镇卫生院' },
    { code: '4', label: '非医嘱离院' },
    { code: '5', label: '死亡' },
    { code: '9', label: '其他' },
  ],
  DISCHARGE_STATUS_LABELS: {
    REQUESTED: '预审中',
    READY: '待离院确认',
    BLOCKED: '挂账审批中',
    COMPLETED: '已离院',
    CANCELLED: '已取消',
  },
  admissions: {
    create: vi.fn(),
    list: vi.fn(),
    schedule: vi.fn(),
    cancel: vi.fn(),
    register: vi.fn(),
  },
  visits: { admitWard: vi.fn(), arrears: vi.fn() },
  beds: {
    map: vi.fn(),
    reserve: vi.fn(),
    assign: vi.fn(),
    release: vi.fn(),
    disinfectDone: vi.fn(),
    maintain: vi.fn(),
    maintainDone: vi.fn(),
  },
  transfer: { execute: vi.fn(), changeBed: vi.fn() },
  orders: { create: vi.fn(), list: vi.fn(), trace: vi.fn() },
  transferWorklist: { list: vi.fn(), check: vi.fn() },
  discharge: {
    create: vi.fn(),
    cancel: vi.fn(),
    clearance: vi.fn(),
    confirm: vi.fn(),
  },
}));

// 仅替身 ElMessage/ElMessageBox（提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: {
      ...mod.ElMessageBox,
      confirm: vi.fn().mockResolvedValue('confirm'),
    },
  };
});

// jsdom 未实现 ResizeObserver：el-table 布局测量依赖（存量 spec 同款空壳）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 出院申请单行（状态/欠费额可覆写） */
function requestMock(partial: Partial<DischargeRequestVO> = {}): DischargeRequestVO {
  return {
    requestNo: 'DC20260925001',
    visitId: 'I2026092500001',
    dischargeWay: '1',
    expectDischargeAt: '2026-09-26T10:00:00+08:00',
    requestedAt: '2026-09-25T09:00:00+08:00',
    requesterId: 'D001',
    status: 'REQUESTED',
    arrearsAmount: undefined,
    settlementCompletedAt: undefined,
    approvalNo: undefined,
    ...partial,
  };
}

/** 清理与预审结果行（状态/欠费额/结算标记可覆写） */
function clearanceMock(partial: Partial<ClearanceVO> = {}): ClearanceVO {
  return {
    requestNo: 'DC20260925001',
    visitId: 'I2026092500001',
    status: 'REQUESTED',
    stoppedLongCount: 2,
    trackedOrders: [
      { orderNo: 'MO2026092500001', orderClass: 'LONG', status: 'STOPPED' },
      { orderNo: 'MO2026092500002', orderClass: 'STAT', status: 'TRANSFERRED' },
    ],
    cancelledPlanCount: 3,
    arrearsAmount: '0',
    settlementCompletedAt: undefined,
    approvalNo: undefined,
    ...partial,
  };
}

/** 按按钮文案点击（el-button 通用） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('出院管理', () => {
  beforeEach(() => {
    for (const fn of [discharge.create, discharge.cancel, discharge.clearance, discharge.confirm]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.confirm).mockClear();
  });

  it('四态 tab 渲染与空态提示', async () => {
    const wrapper = mount(DischargeManageView);
    await flushPromises();
    const text = wrapper.text();
    // 四态 tab（后端 DischargeRequestStatus 主链四态）
    expect(text).toContain('预审中');
    expect(text).toContain('待离院确认');
    expect(text).toContain('挂账审批中');
    expect(text).toContain('已离院');
    // 会话空态
    expect(text).toContain('暂无出院申请');
  });

  it('出院申请出网携预出院时间与离院方式并落列表', async () => {
    vi.mocked(discharge.create).mockResolvedValue(requestMock({ status: 'REQUESTED' }));
    const wrapper = mount(DischargeManageView);
    await flushPromises();
    // 打开申请弹窗 → 填就诊号/预出院时间/离院方式 → 提交
    await clickButton(wrapper, '发起出院申请');
    await wrapper.find('input[aria-label="在院就诊号"]').setValue('I2026092500001');
    await wrapper.find('input[aria-label="预出院时间"]').setValue('2026-09-26T10:00');
    await wrapper.find('select[aria-label="离院方式"]').setValue('1');
    await clickButton(wrapper, '提交申请');
    await flushPromises();
    // 出网携 date-time 契约形态（OffsetDateTime 可解析，时点日期断言不绑时区）
    expect(discharge.create).toHaveBeenCalledWith(
      'I2026092500001',
      expect.objectContaining({
        expectDischargeAt: expect.stringContaining('2026-09-26T'),
        dischargeWay: '1',
      }),
    );
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 申请单落会话列表（自动切至返回态 tab 可见）
    expect(wrapper.text()).toContain('DC20260925001');
  });

  it('BLOCKED 清理预审面板渲染欠费额与挂账审批引导', async () => {
    vi.mocked(discharge.create).mockResolvedValue(
      requestMock({ status: 'BLOCKED', arrearsAmount: '120500' }),
    );
    vi.mocked(discharge.clearance).mockResolvedValue(
      clearanceMock({ status: 'BLOCKED', arrearsAmount: '120500' }),
    );
    const wrapper = mount(DischargeManageView);
    await flushPromises();
    // 创建 BLOCKED 申请（自动切至挂账审批中 tab）→ 行内清理预审
    await clickButton(wrapper, '发起出院申请');
    await wrapper.find('input[aria-label="在院就诊号"]').setValue('I2026092500001');
    await wrapper.find('input[aria-label="预出院时间"]').setValue('2026-09-26T10:00');
    await wrapper.find('select[aria-label="离院方式"]').setValue('1');
    await clickButton(wrapper, '提交申请');
    await flushPromises();
    await clickButton(wrapper, '清理预审');
    await flushPromises();
    const text = wrapper.text();
    // 欠费额渲染（分→元全站唯一件换算：120500 分=1205.00 元）
    expect(text).toContain('1205.00');
    // 挂账审批引导（BLOCKED 走 M13 收费面，凭 billing.arrears.approved 转 READY）
    expect(wrapper.find('.discharge-blocked-guide').exists()).toBe(true);
    expect(text).toContain('挂账审批');
    // 清理三清单计数渲染（长期停止 2/计划作废 3）
    expect(text).toContain('2');
    expect(discharge.clearance).toHaveBeenCalledWith('DC20260925001');
  });

  it('离院确认双条件：结算未完成禁用并提示原因，满足后放行出网', async () => {
    vi.mocked(discharge.create).mockResolvedValue(requestMock({ status: 'READY' }));
    vi.mocked(discharge.clearance)
      .mockResolvedValueOnce(
        clearanceMock({
          status: 'READY',
          settlementCompletedAt: undefined,
          trackedOrders: [],
        }),
      )
      .mockResolvedValueOnce(
        clearanceMock({
          status: 'READY',
          settlementCompletedAt: '2026-09-26T09:00:00+08:00',
          trackedOrders: [],
        }),
      );
    vi.mocked(discharge.confirm).mockResolvedValue(requestMock({ status: 'COMPLETED' }));
    const wrapper = mount(DischargeManageView);
    await flushPromises();
    // 创建 READY 申请 → 清理预审（结算未完成）
    await clickButton(wrapper, '发起出院申请');
    await wrapper.find('input[aria-label="在院就诊号"]').setValue('I2026092500001');
    await wrapper.find('input[aria-label="预出院时间"]').setValue('2026-09-26T10:00');
    await wrapper.find('select[aria-label="离院方式"]').setValue('1');
    await clickButton(wrapper, '提交申请');
    await flushPromises();
    await clickButton(wrapper, '清理预审');
    await flushPromises();
    // 双条件之一未满足（结算未完成）：确认按钮禁用 + 原因提示
    const confirmBtn = () => wrapper.findAll('button').find((b) => b.text() === '确认离院');
    expect(confirmBtn()?.attributes('disabled')).toBeDefined();
    expect(wrapper.find('.discharge-confirm-reason').text()).toContain('结算未完成');
    expect(discharge.confirm).not.toHaveBeenCalled();
    // 重拉清理预审（结算完成后）→ 双条件满足 → 确认按钮启用 → 放行出网
    await clickButton(wrapper, '清理预审');
    await flushPromises();
    expect(confirmBtn()?.attributes('disabled')).toBeUndefined();
    await clickButton(wrapper, '确认离院');
    await flushPromises();
    expect(discharge.confirm).toHaveBeenCalledWith('DC20260925001', {});
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
  });
});

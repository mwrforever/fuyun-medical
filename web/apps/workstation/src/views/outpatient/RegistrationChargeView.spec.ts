// 挂号收费联动页单测（FU-M03-07 前端面）：渲染断言（三步卡与收费面板空态）、空词检索前置
// 拦截零出网、挂号→收费联动主链（WINDOW 渠道 + TAKEN 直出 visitId 拉起费用 + 全现金结算金额
// 原样透传零运算）、动作在途守卫（W-22⑥：慢响应窗口按钮禁用且二次点击零出网）。
// api mock 承载，不打真实网络；失败弹错归响应拦截器不在前端断言。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElDatePicker, ElMessage } from 'element-plus';
import { listFees, previewSettlement, settle } from '@/api/billing';
import type { SettlementPreviewVO } from '@/api/billing';
import { createAppointment, listAvailablePools } from '@/api/outpatient';
import type { AppointmentVO, NumberPoolVO } from '@/api/outpatient';
import { searchPatients } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import RegistrationChargeView from './RegistrationChargeView.vue';

vi.mock('@/api/patient', () => ({
  searchPatients: vi.fn(),
}));
vi.mock('@/api/outpatient', () => ({
  createAppointment: vi.fn(),
  listAvailablePools: vi.fn(),
}));
vi.mock('@/api/billing', () => ({
  listFees: vi.fn(),
  previewSettlement: vi.fn(),
  settle: vi.fn(),
}));

// 仅替身 ElMessage（提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

// jsdom 未实现 ResizeObserver：el-table 布局测量依赖，补空壳避免挂载即抛（存量 spec 同款）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 按按钮文案点击 el-button（避免 DOM 结构序号耦合，存量 spec 同款） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

/** 按按钮文案定位 el-button 包装（在途断言用；非点击入口） */
function findButton(wrapper: VueWrapper, text: string): DOMWrapper<Element> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  return button;
}

function patientMock(): PatientVO {
  return {
    patientId: '1932000000000000001',
    name: '张*',
    sex: 'F',
    idCardNo: '110***********0022',
    status: 'ACTIVE',
  };
}

function poolMock(remaining: number): NumberPoolVO {
  return {
    id: '501',
    scheduleId: '301',
    apptType: 'GENERAL',
    slotStart: '08:00',
    slotEnd: '11:30',
    totalQuota: 20,
    usedCount: 20 - remaining,
    remaining,
  };
}

function appointmentTakenMock(): AppointmentVO {
  return {
    id: '601',
    apptNo: 'OAPPT-20260921-0001',
    patientId: '1932000000000000001',
    scheduleId: '301',
    poolId: '501',
    apptType: 'GENERAL',
    schedDate: '2026-09-21',
    slotStart: '08:00',
    slotEnd: '11:30',
    channel: 'WINDOW',
    feeStatus: 'UNPAID',
    visitId: 'O2026092100001',
    status: 'TAKEN',
  };
}

describe('挂号收费联动页', () => {
  beforeEach(() => {
    vi.mocked(searchPatients).mockReset();
    vi.mocked(createAppointment).mockReset();
    vi.mocked(listAvailablePools).mockReset();
    vi.mocked(listFees).mockReset();
    vi.mocked(previewSettlement).mockReset();
    vi.mocked(settle).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
  });

  it('渲染断言：三步卡、检索/号源/确认按钮与收费面板空态齐备', () => {
    const wrapper = mount(RegistrationChargeView);
    expect(wrapper.text()).toContain('选择患者');
    expect(wrapper.text()).toContain('选择排班/号别');
    expect(wrapper.text()).toContain('确认挂号');
    expect(wrapper.text()).toContain('挂号费收费');
    expect(wrapper.text()).toContain('完成当日挂号后自动带出挂号费待缴行');
    expect(wrapper.text()).toContain('今日尚无挂号记录');
    expect(findButton(wrapper, '检索患者').exists()).toBe(true);
    expect(findButton(wrapper, '查询号源').exists()).toBe(true);
    wrapper.unmount();
  });

  it('空关键词检索被前置拦截不出网（零出网断言）', async () => {
    const wrapper = mount(RegistrationChargeView);
    await flushPromises();
    await clickButton(wrapper, '检索患者');
    await flushPromises();
    expect(vi.mocked(searchPatients)).not.toHaveBeenCalled();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('挂号收费联动主链：TAKEN 直出 visitId 拉起费用并全额现金结算（金额原样透传）', async () => {
    vi.mocked(searchPatients).mockResolvedValue({
      content: [patientMock()],
      page: 0,
      size: 10,
      total: 1 /* patient.ts 存量分页手写壳 total 为 number 旧型（D-18 前形态），mock 对齐其声明 */,
    });
    vi.mocked(listAvailablePools).mockResolvedValue([poolMock(8)]);
    vi.mocked(createAppointment).mockResolvedValue(appointmentTakenMock());
    vi.mocked(listFees).mockResolvedValue({
      content: [
        {
          id: '701',
          feeNo: 'FEE-1',
          visitId: 'O2026092100001',
          itemNameSnapshot: '普通挂号费',
          quantity: 1,
          amount: '1050',
          status: 'UNPAID',
        },
      ],
      page: 0,
      size: 20,
      total: '1',
    });
    vi.mocked(previewSettlement).mockResolvedValue({
      settleNo: 'STL-1',
      patientId: '1932000000000000001',
      visitId: 'O2026092100001',
      payerType: 'SELF_PAY',
      totalAmount: '1050',
    } satisfies SettlementPreviewVO);
    vi.mocked(settle).mockResolvedValue({
      id: '801',
      settleNo: 'STL-1',
      status: 'SETTLED',
      totalAmount: '1050',
    });
    const wrapper = mount(RegistrationChargeView);
    await flushPromises();

    // 步骤 1：检索并点选患者
    await wrapper.find('input[placeholder="姓名/证件号/手机号"]').setValue('张');
    await clickButton(wrapper, '检索患者');
    await flushPromises();
    expect(vi.mocked(searchPatients)).toHaveBeenCalledWith({
      keyword: '张',
      page: 0,
      size: 10,
    });
    await wrapper.find('.fuy-patient-row').trigger('click');

    // 步骤 2：填诊区/日期（选择器以 emit 回填 v-model，口径同存量 spec）→ 查询并点选号源
    await wrapper.find('input[placeholder="诊区编码，如 DEPT-INT"]').setValue('DEPT-INT');
    wrapper.findComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-09-21');
    await flushPromises();
    await clickButton(wrapper, '查询号源');
    await flushPromises();
    expect(vi.mocked(listAvailablePools)).toHaveBeenCalledWith({
      deptCode: 'DEPT-INT',
      date: '2026-09-21',
    });
    await wrapper.find('button.registration-charge-pool').trigger('click');

    // 步骤 3：确认挂号（WINDOW 渠道）→ TAKEN 自动拉起费用
    await clickButton(wrapper, '确认挂号');
    await flushPromises();
    expect(vi.mocked(createAppointment)).toHaveBeenCalledWith({
      patientId: '1932000000000000001',
      poolId: '501',
      channel: 'WINDOW',
    });
    await vi.waitFor(() => {
      expect(vi.mocked(listFees)).toHaveBeenCalledWith({
        visitId: 'O2026092100001',
        page: 0,
        size: 20,
      });
    });
    // 渲染断言：待缴行与分→元展示串（金额零运算，展示层换算）
    expect(wrapper.text()).toContain('普通挂号费');
    expect(wrapper.text()).toContain('10.50');

    // 收费联动：预结算 → 确认收费（amount 取预结算回传值原样字符串透传）
    await clickButton(wrapper, '预结算');
    await flushPromises();
    await clickButton(wrapper, '确认收费');
    await flushPromises();
    expect(vi.mocked(previewSettlement)).toHaveBeenCalledWith({
      patientId: '1932000000000000001',
      visitId: 'O2026092100001',
      payerType: 'SELF_PAY',
    });
    expect(vi.mocked(settle)).toHaveBeenCalledWith({
      settleNo: 'STL-1',
      payments: [{ method: 'CASH', amount: '1050' }],
    });
    // 缴费完成态绿色对勾区 + 记录表登记一行
    expect(wrapper.text()).toContain('收费完成');
    expect(wrapper.text()).toContain('OAPPT-20260921-0001');
    wrapper.unmount();
  });

  it('挂号在途：按钮禁用且二次点击零出网，结束后复位可再点（W-22⑥ 防抖）', async () => {
    vi.mocked(searchPatients).mockResolvedValue({
      content: [patientMock()],
      page: 0,
      size: 10,
      total: 1,
    });
    vi.mocked(listAvailablePools).mockResolvedValue([poolMock(8)]);
    // 慢响应：挂号挂起至用例放行，稳定复现在途窗口
    let releaseRegister: () => void = () => {};
    vi.mocked(createAppointment).mockImplementation(
      () =>
        new Promise<AppointmentVO>((resolve) => {
          releaseRegister = () => resolve(appointmentTakenMock());
        }),
    );
    const wrapper = mount(RegistrationChargeView);
    await flushPromises();

    await wrapper.find('input[placeholder="姓名/证件号/手机号"]').setValue('张');
    await clickButton(wrapper, '检索患者');
    await flushPromises();
    await wrapper.find('.fuy-patient-row').trigger('click');
    await wrapper.find('input[placeholder="诊区编码，如 DEPT-INT"]').setValue('DEPT-INT');
    wrapper.findComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-09-21');
    await flushPromises();
    await clickButton(wrapper, '查询号源');
    await flushPromises();
    await wrapper.find('button.registration-charge-pool').trigger('click');

    await clickButton(wrapper, '确认挂号');
    await flushPromises();
    expect(findButton(wrapper, '确认挂号').attributes('disabled')).toBeDefined();
    // 在途窗口内二次点击：入口守卫 + loading 双保险，零第二次出网
    await clickButton(wrapper, '确认挂号');
    await flushPromises();
    expect(vi.mocked(createAppointment)).toHaveBeenCalledTimes(1);

    releaseRegister();
    await flushPromises();
    expect(findButton(wrapper, '确认挂号').attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });
});

// 转抄工作台单测（/inpatient/transfer，M04 FU-M04-06 上前端面）：待转抄列表加载渲染
// （高危药红色标识透出，病区维度）、高危缺第二核对人后端 IP-1016 4xx detail 原文透出
// （业务规则单点归后端把守，前端红*仅承载视觉必填指示）、批量提交出网携勾选医嘱号集/
// 转抄人/核对结论并刷新列表。
// api mock 承载零出网（vi.mock('@/api/inpatient') 整模块替身），不打真实网络；
// 断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { transferWorklist } from '@/api/inpatient';
import type { TransferWorklistVO } from '@/api/inpatient';
import TransferWorklistView from './TransferWorklistView.vue';

vi.mock('@/api/inpatient', () => ({
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
  ORDER_TYPE_OPTIONS: [
    { code: 'DRUG', label: '药品' },
    { code: 'LAB', label: '检验' },
    { code: 'EXAM', label: '检查' },
    { code: 'SURGERY', label: '手术' },
    { code: 'BLOOD', label: '用血' },
    { code: 'NURSING', label: '护理' },
    { code: 'DIET', label: '膳食' },
    { code: 'CONSULT', label: '会诊' },
    { code: 'DISCHARGE_MED', label: '出院带药' },
  ],
  ORDER_CLASS_OPTIONS: [
    { code: 'LONG', label: '长期' },
    { code: 'STAT', label: '临时' },
  ],
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

// 仅替身 ElMessage（提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
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

/** 待转抄行（AUDITED 医嘱聚合；highRisk 可覆写） */
function worklistMock(partial: Partial<TransferWorklistVO> = {}): TransferWorklistVO {
  return {
    orderNo: 'MO2026092500001',
    visitId: 'I2026092500001',
    patientId: '1932000000000000001',
    orderType: 'DRUG',
    orderClass: 'STAT',
    standbyFlag: false,
    freqCode: undefined,
    doctorId: 'D001',
    orderedAt: '2026-09-25T10:00:00+08:00',
    highRisk: false,
    ...partial,
  };
}

/** 空待转抄分页出参（page/size/total 由后端 long→string 全局口径） */
function emptyWorklist() {
  return { content: [], page: '0', size: '20', total: '0' };
}

/** 按 aria-label 勾选行复选框 */
async function checkRow(wrapper: VueWrapper, orderNo: string): Promise<void> {
  const checkbox = wrapper.find(`input[aria-label="勾选 ${orderNo}"]`);
  await checkbox.setValue(true);
}

describe('转抄工作台', () => {
  beforeEach(() => {
    for (const fn of [transferWorklist.list, transferWorklist.check]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(transferWorklist.list).mockResolvedValue(emptyWorklist());
  });

  it('待转抄列表加载渲染并透出高危红色标识（病区维度）', async () => {
    vi.mocked(transferWorklist.list).mockResolvedValue({
      content: [
        worklistMock({ orderNo: 'MO2026092500001', highRisk: true }),
        worklistMock({ orderNo: 'MO2026092500002', orderType: 'LAB', highRisk: false }),
      ],
      page: '0',
      size: '20',
      total: '2',
    });
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('MO2026092500001');
    expect(text).toContain('MO2026092500002');
    // 高危标识仅命中高危行（机器判据类）
    expect(wrapper.findAll('.transfer-highrisk-flag').length).toBe(1);
    // 出网携病区维度
    expect(transferWorklist.list).toHaveBeenCalledWith(expect.objectContaining({ wardId: 'W01' }));
  });

  it('高危行缺第二核对人透出 IP-1016 4xx detail 原文（业务规则后端把守）', async () => {
    vi.mocked(transferWorklist.list).mockResolvedValue({
      content: [worklistMock({ orderNo: 'MO2026092500001', highRisk: true })],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(transferWorklist.check).mockRejectedValue({
      errorCode: 'IP-1016',
      detail: '高危或输血类医嘱转抄须第二核对人复核',
    });
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    // 勾选高危行 + 填转抄人，第二核对人留空提交（红*为视觉指示，强制面由后端 IP-1016 把守）
    await checkRow(wrapper, 'MO2026092500001');
    await wrapper.find('input[aria-label="转抄人工号"]').setValue('N001');
    const button = wrapper.findAll('button').find((b) => b.text() === '提交批量核对');
    await button?.trigger('click');
    await flushPromises();
    expect(transferWorklist.check).toHaveBeenCalledTimes(1);
    expect(vi.mocked(ElMessage.error)).toHaveBeenCalledWith('高危或输血类医嘱转抄须第二核对人复核');
    // 失败不刷新列表（list 仍只 1 次）
    expect(transferWorklist.list).toHaveBeenCalledTimes(1);
  });

  it('批量提交出网携勾选医嘱号集/转抄人/核对结论并刷新列表', async () => {
    vi.mocked(transferWorklist.list).mockResolvedValue({
      content: [
        worklistMock({ orderNo: 'MO2026092500001', highRisk: true }),
        worklistMock({ orderNo: 'MO2026092500002', orderType: 'LAB' }),
      ],
      page: '0',
      size: '20',
      total: '2',
    });
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    await checkRow(wrapper, 'MO2026092500001');
    await checkRow(wrapper, 'MO2026092500002');
    await wrapper.find('input[aria-label="转抄人工号"]').setValue('N001');
    await wrapper.find('input[aria-label="第二核对人"]').setValue('N002');
    const button = wrapper.findAll('button').find((b) => b.text() === '提交批量核对');
    await button?.trigger('click');
    await flushPromises();
    expect(transferWorklist.check).toHaveBeenCalledWith({
      orderNos: ['MO2026092500001', 'MO2026092500002'],
      transferNurseId: 'N001',
      conclusion: 'PASSED',
      secondCheckerId: 'N002',
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalledWith(
      expect.stringContaining('2 条已转抄'),
    );
    // 提交成功后列表重载（初载+刷新=2 次）
    expect(transferWorklist.list).toHaveBeenCalledTimes(2);
  });
});

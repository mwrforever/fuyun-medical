// PDA 移动护理页单测（M05 前端面，设计文档 §4）：腕带/卡号格式校验（非 I 型 14 位且非
// 卡号格式时提示且零出网，spec 冻结）、患者卡超敏字段零渲染（DOM 不含「手机」「证件」
// 文案——脱敏摘要数据源）、巡视打卡成功后按钮转已完成态并回显 taskNo（patrol 调用一次）、
// 打卡在途守卫拦截重复点击（双击零二次出网）、卡号路径 visitId 判空（体征录入与巡视打卡
// 均 warning 明确提示且零出网，R1 finding ③）。api mock 承载，不打真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { pda, vitalSigns } from '@/api/nursing';
import type { PdaPatientSummaryVO } from '@/api/nursing';
import PdaView from './PdaView.vue';

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
  CONDITION_TAG_OPTIONS: [],
  SPECIAL_EVENT_OPTIONS: [],
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
  SHIFT_OPTIONS: [{ code: 'DAY', label: '白班' }],
  wardPatients: { register: vi.fn(), list: vi.fn(), detail: vi.fn(), remove: vi.fn() },
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
  pda: { patientSummary: vi.fn(), patrol: vi.fn() },
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

/** 完成患者识别前置（合法腕带 → 摘要返回 → 段解锁） */
async function identify(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('input[placeholder="扫描腕带或输入患者卡号"]').setValue('I2026092300001');
  const buttons = wrapper.findAll('button');
  await buttons.find((b) => b.text() === '查询')?.trigger('click');
  await flushPromises();
}

describe('PDA 移动护理页', () => {
  beforeEach(() => {
    vi.mocked(pda.patientSummary).mockReset();
    vi.mocked(pda.patrol).mockReset();
    vi.mocked(vitalSigns.record).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
  });

  it('腕带标识校验：非 I 型 14 位且非卡号格式时提示且零出网', async () => {
    const wrapper = mount(PdaView);
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
    const wrapper = mount(PdaView);
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
    const wrapper = mount(PdaView);
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
    const wrapper = mount(PdaView);
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
    const wrapper = mount(PdaView);
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
});

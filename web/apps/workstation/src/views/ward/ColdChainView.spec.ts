// 冷链台账单测（/ward/cold-chain，M16 冷链治理面前端面）：档案列表加载与用途/温区词表徽标
// （fuy-archive-tag--{purpose} 机器判据）与校验到期 overdue 标记、新建档案必填校验零出网与
// 合法出网携用途/设备/温区并刷新、档案详情出网 detail+records 渲染记录列表（记录类型词表）、
// 巡检登记出网携 recordType=INSPECTION+content 并刷新、告警处置双人复核字段缺一零出网与齐
// 全出网携 alarmRef+secondOperator、温度曲线出网 series 携 scope=device/metricCode/from/to
// 且 SVG 折线点数机器判据（轻量渲染不引 echarts）。
// vi.mock('@/api/ward') 与 vi.mock('@/api/iot') 双替身承载零出网，断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { telemetry } from '@/api/iot';
import { coldChain } from '@/api/ward';
import type { ColdChainArchiveVO, ColdChainRecordVO } from '@/api/ward';
import ColdChainView from './ColdChainView.vue';

vi.mock('@/api/iot', () => ({
  telemetry: { series: vi.fn() },
}));

vi.mock('@/api/ward', () => ({
  COLD_PURPOSE_LABELS: { VACCINE: '疫苗', BLOOD: '血液', REAGENT: '试剂', PHARMA: '药品' },
  TEMP_RANGE_LABELS: { FREEZE: '冷冻', COOL: '冷藏', SHELDED: '阴凉', NORMAL: '常温' },
  COLD_RECORD_TYPE_LABELS: { INSPECTION: '巡检', ALARM_HANDLE: '告警处置', DEVIATION: '偏差记录' },
  WARD_OPTIONS: [{ code: '1001', label: '1001 演示病区' }],
  coldChain: {
    page: vi.fn(),
    create: vi.fn(),
    detail: vi.fn(),
    records: vi.fn(),
    registerRecord: vi.fn(),
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

/** 档案行（可覆写用途/温区/到期标记） */
function archiveMock(partial: Partial<ColdChainArchiveVO> = {}): ColdChainArchiveVO {
  return {
    id: '301',
    archiveNo: 'CC20260926001',
    purpose: 'VACCINE',
    deviceId: 'dev-fridge-1',
    tempRangeType: 'COOL',
    verifyDueAt: '2026-12-31T23:59:59+08:00',
    inventoryDigest: '疫苗批次 2026-09 批 12 件',
    overdue: false,
    createdAt: '2026-09-25T10:00:00+08:00',
    ...partial,
  };
}

/** 记录行（可覆写类型/双人字段） */
function recordMock(partial: Partial<ColdChainRecordVO> = {}): ColdChainRecordVO {
  return {
    id: '311',
    recordNo: 'CR20260926001',
    archiveNo: 'CC20260926001',
    recordType: 'INSPECTION',
    alarmRef: undefined,
    secondOperator: 'N002',
    content: '温度正常，门封完好',
    recordedBy: 'N001',
    recordedAt: '2026-09-26T10:00:00+08:00',
    ...partial,
  };
}

/** 按文本定位表格行 */
function findRow(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('tr').find((row) => row.text().includes(text));
}

/** 在指定行内按按钮文案点击（行作用域操作，防跨行误中） */
async function clickRowButton(
  wrapper: VueWrapper,
  rowText: string,
  buttonText: string,
): Promise<void> {
  const row = findRow(wrapper, rowText);
  const button = row?.findAll('button').find((b) => b.text() === buttonText);
  if (!button) {
    throw new Error(`未找到行内按钮：${buttonText}`);
  }
  await button.trigger('click');
}

/** 按按钮文案点击 */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('冷链台账页', () => {
  beforeEach(() => {
    for (const fn of [
      coldChain.page,
      coldChain.create,
      coldChain.detail,
      coldChain.records,
      coldChain.registerRecord,
      telemetry.series,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(coldChain.page).mockResolvedValue({
      content: [archiveMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(coldChain.detail).mockResolvedValue(archiveMock());
    vi.mocked(coldChain.records).mockResolvedValue([]);
  });

  it('台账列表加载渲染用途/温区词表徽标与 overdue 到期标记（机器判据）', async () => {
    vi.mocked(coldChain.page).mockResolvedValue({
      content: [
        archiveMock(),
        archiveMock({
          archiveNo: 'CC20260926002',
          purpose: 'BLOOD',
          tempRangeType: 'FREEZE',
          overdue: true,
        }),
      ],
      page: '0',
      size: '20',
      total: '2',
    });
    const wrapper = mount(ColdChainView);
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('CC20260926001');
    expect(text).toContain('疫苗');
    expect(text).toContain('血液');
    expect(text).toContain('冷藏');
    expect(text).toContain('冷冻');
    // 用途徽标状态类契约（机器判据；色值经语义 token 承载）
    expect(wrapper.find('.fuy-archive-tag--vaccine').exists()).toBe(true);
    expect(wrapper.find('.fuy-archive-tag--blood').exists()).toBe(true);
    // 校验到期标记（overdue 由后端承载）
    expect(wrapper.find('.fuy-archive-overdue').exists()).toBe(true);
  });

  it('新建档案设备必填零出网拦截，合法出网携用途/设备/温区并刷新列表', async () => {
    vi.mocked(coldChain.create).mockResolvedValue(archiveMock());
    const wrapper = mount(ColdChainView);
    await flushPromises();
    await clickButton(wrapper, '新建档案');
    // 设备必填拦截
    await clickButton(wrapper, '保存档案');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写设备 ID');
    expect(coldChain.create).not.toHaveBeenCalled();
    // 合法出网
    await wrapper.find('select[aria-label="冷链用途"]').setValue('PHARMA');
    await wrapper.find('input[aria-label="设备 ID"]').setValue('dev-fridge-9');
    await wrapper.find('select[aria-label="温区"]').setValue('SHELDED');
    await clickButton(wrapper, '保存档案');
    await flushPromises();
    expect(coldChain.create).toHaveBeenCalledWith({
      purpose: 'PHARMA',
      deviceId: 'dev-fridge-9',
      tempRangeType: 'SHELDED',
      verifyDueAt: undefined,
      inventoryDigest: undefined,
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    expect(coldChain.page).toHaveBeenCalledTimes(2);
  });

  it('档案详情出网 detail+records 渲染记录列表（记录类型词表与双人字段可见）', async () => {
    vi.mocked(coldChain.records).mockResolvedValue([
      recordMock(),
      recordMock({
        recordNo: 'CR20260926002',
        recordType: 'ALARM_HANDLE',
        alarmRef: 'AL20260926009',
        secondOperator: 'N003',
        content: '温度越限处置复核',
      }),
    ]);
    const wrapper = mount(ColdChainView);
    await flushPromises();
    await clickRowButton(wrapper, 'CC20260926001', '详情');
    await flushPromises();
    expect(coldChain.detail).toHaveBeenCalledWith('CC20260926001');
    expect(coldChain.records).toHaveBeenCalledWith('CC20260926001');
    const text = wrapper.text();
    expect(text).toContain('巡检');
    expect(text).toContain('告警处置');
    expect(text).toContain('N002');
    expect(text).toContain('AL20260926009');
  });

  it('巡检登记出网携 recordType=INSPECTION+content 并刷新记录', async () => {
    const wrapper = mount(ColdChainView);
    await flushPromises();
    await clickRowButton(wrapper, 'CC20260926001', '详情');
    await flushPromises();
    await wrapper.find('textarea[aria-label="登记内容"]').setValue('日检温度正常');
    await clickButton(wrapper, '提交登记');
    await flushPromises();
    expect(coldChain.registerRecord).toHaveBeenCalledWith('CC20260926001', {
      recordType: 'INSPECTION',
      alarmRef: undefined,
      secondOperator: undefined,
      content: '日检温度正常',
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 记录重载（records 第二次调用）
    expect(coldChain.records).toHaveBeenCalledTimes(2);
  });

  it('告警处置双人复核字段缺一零出网拦截，齐全出网携 alarmRef+secondOperator', async () => {
    const wrapper = mount(ColdChainView);
    await flushPromises();
    await clickRowButton(wrapper, 'CC20260926001', '详情');
    await flushPromises();
    // 切换登记类型为告警处置
    await wrapper.find('select[aria-label="登记类型"]').setValue('ALARM_HANDLE');
    await wrapper.find('textarea[aria-label="登记内容"]').setValue('越限处置');
    // 缺双人复核字段：零出网
    await clickButton(wrapper, '提交登记');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('告警处置须填写告警号与双人复核人');
    expect(coldChain.registerRecord).not.toHaveBeenCalled();
    // 齐全出网
    await wrapper.find('input[aria-label="告警号"]').setValue('AL20260926009');
    await wrapper.find('input[aria-label="复核人"]').setValue('N003');
    await clickButton(wrapper, '提交登记');
    await flushPromises();
    expect(coldChain.registerRecord).toHaveBeenCalledWith('CC20260926001', {
      recordType: 'ALARM_HANDLE',
      alarmRef: 'AL20260926009',
      secondOperator: 'N003',
      content: '越限处置',
    });
    expect(coldChain.records).toHaveBeenCalledTimes(2);
  });

  it('温度曲线出网 series 携 scope=device/metricCode/from/to，SVG 折线点数机器判据', async () => {
    vi.mocked(telemetry.series).mockResolvedValue([
      {
        deviceId: 'dev-fridge-1',
        metricCode: 'COLDCHAIN_TEMP',
        time: '2026-09-26T00:00:00Z',
        avg: 4.1,
      },
      {
        deviceId: 'dev-fridge-1',
        metricCode: 'COLDCHAIN_TEMP',
        time: '2026-09-26T01:00:00Z',
        avg: 4.5,
      },
      {
        deviceId: 'dev-fridge-1',
        metricCode: 'COLDCHAIN_TEMP',
        time: '2026-09-26T02:00:00Z',
        avg: 3.8,
      },
    ]);
    const wrapper = mount(ColdChainView);
    await flushPromises();
    await clickRowButton(wrapper, 'CC20260926001', '详情');
    await flushPromises();
    await clickButton(wrapper, '查询曲线');
    await flushPromises();
    expect(telemetry.series).toHaveBeenCalledTimes(1);
    const arg = vi.mocked(telemetry.series).mock.calls[0]?.[0] as Record<string, string>;
    expect(arg['scope']).toBe('device');
    expect(arg['deviceId']).toBe('dev-fridge-1');
    expect(arg['metricCode']).toBe('COLDCHAIN_TEMP');
    expect(typeof arg['from']).toBe('string');
    expect(typeof arg['to']).toBe('string');
    // 轻量渲染机器判据：SVG polyline 点数与返回聚合点数一致（不引 echarts）
    const polyline = wrapper.find('svg polyline');
    expect(polyline.exists()).toBe(true);
    expect(polyline.attributes('points')?.trim().split(/\s+/)).toHaveLength(3);
  });
});

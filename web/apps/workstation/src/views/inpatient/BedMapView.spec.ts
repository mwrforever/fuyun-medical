// 病区床位图单测（/inpatient/beds，M04 FU-M04-02 前端面）：床位图五态渲染（状态类契约
// fuy-bed-card--free/-reserved/-occupied/-disinfecting/-maintenance 机器判据）、占床床位
// 显示占用患者摘要（visitId+入科时点，姓名按脱敏口径不出网）、消毒完成操作出网并刷新
// 床位图、转科弹窗提交出网（transfer.execute 携目标病区床位）、转科未选目标床位零出网
// 显式校验。api mock 承载零出网（vi.mock('@/api/inpatient') 整模块替身），
// 断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { beds, transfer } from '@/api/inpatient';
import type { BedMapVO, TransferResultVO } from '@/api/inpatient';
import BedMapView from './BedMapView.vue';

vi.mock('@/api/inpatient', () => ({
  ADMISSION_STATUS_OPTIONS: [
    { code: 'WAITING', label: '候床中' },
    { code: 'SCHEDULED', label: '已预约' },
  ],
  ADMISSION_TYPE_OPTIONS: [
    { code: 'NORMAL', label: '普通入院' },
    { code: 'EMERGENCY', label: '急诊入院' },
    { code: 'PRE_HOSPITAL', label: '预住院' },
  ],
  SOURCE_TYPE_OPTIONS: [{ code: 'OUTPATIENT', label: '门诊转诊' }],
  INSURANCE_TYPE_OPTIONS: [{ code: '职工医保', label: '职工医保' }],
  BED_STATUS_LABELS: {
    FREE: '空床',
    RESERVED: '预占',
    OCCUPIED: '占床',
    DISINFECTING: '消毒中',
    MAINTENANCE: '维修中',
  },
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
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
}));

// 仅替身 ElMessage/ElMessageBox（提示与确认断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: {
      ...mod.ElMessageBox,
      confirm: vi.fn().mockResolvedValue('confirm'),
      prompt: vi.fn().mockResolvedValue({ value: 'I2026092500001' }),
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

/** 床位行（五态可覆写；占床行携占用就诊摘要） */
function bedMock(partial: Partial<BedMapVO> = {}): BedMapVO {
  return {
    bedId: '901',
    wardId: 'W01',
    bedNo: '01',
    bedAttr: 'NORMAL',
    bedStatus: 'FREE',
    allowGender: 'ALL',
    occupiedVisit: undefined,
    ...partial,
  };
}

/** 五态齐全的床位图（床号 01-05 各一态） */
function fiveStateBeds(): BedMapVO[] {
  return [
    bedMock({ bedId: '901', bedNo: '01', bedStatus: 'FREE' }),
    bedMock({ bedId: '902', bedNo: '02', bedStatus: 'RESERVED' }),
    bedMock({
      bedId: '903',
      bedNo: '03',
      bedStatus: 'OCCUPIED',
      occupiedVisit: {
        visitId: 'I2026092000003',
        patientId: '1932000000000000003',
        admittedAt: '2026-09-20T08:00:00+08:00',
      },
    }),
    bedMock({ bedId: '904', bedNo: '04', bedStatus: 'DISINFECTING' }),
    bedMock({ bedId: '905', bedNo: '05', bedStatus: 'MAINTENANCE' }),
  ];
}

/** 转科编排出参（前后定位面） */
function transferResultMock(): TransferResultVO {
  return {
    visitId: 'I2026092000003',
    fromWardId: 'W01',
    fromBedId: '903',
    toWardId: 'W01',
    toBedId: '902',
    transferredAt: '2026-09-25T10:00:00+08:00',
  };
}

/** 按按钮文案点击 */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

/** 定位指定床号的床位卡（状态类/操作 select 载体） */
function findBedCard(wrapper: VueWrapper, bedNo: string) {
  return wrapper.findAll('.bed-card').find((card) => card.text().includes(bedNo));
}

describe('病区床位图', () => {
  beforeEach(() => {
    for (const fn of [
      beds.map,
      beds.reserve,
      beds.assign,
      beds.release,
      beds.disinfectDone,
      beds.maintain,
      beds.maintainDone,
      transfer.execute,
      transfer.changeBed,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.confirm).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(beds.map).mockResolvedValue([]);
  });

  it('床位图五态渲染并按状态类承载色标（空床/预占/占床/消毒/维修）', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const text = wrapper.text();
    // 五态中文词表与图例可见
    expect(text).toContain('空床');
    expect(text).toContain('预占');
    expect(text).toContain('占床');
    expect(text).toContain('消毒中');
    expect(text).toContain('维修中');
    // 五态色标状态类契约（机器判据；色值经 --fuy-color-bed-* 语义 token 承载）
    expect(wrapper.find('.bed-card.fuy-bed-card--free').exists()).toBe(true);
    expect(wrapper.find('.bed-card.fuy-bed-card--reserved').exists()).toBe(true);
    expect(wrapper.find('.bed-card.fuy-bed-card--occupied').exists()).toBe(true);
    expect(wrapper.find('.bed-card.fuy-bed-card--disinfecting').exists()).toBe(true);
    expect(wrapper.find('.bed-card.fuy-bed-card--maintenance').exists()).toBe(true);
    // 头部五态计数
    expect(text).toContain('空床 1');
    expect(text).toContain('占床 1');
  });

  it('占床床位显示占用患者摘要（visitId+入科时点，姓名脱敏不出网）', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const occupiedCard = findBedCard(wrapper, '03');
    expect(occupiedCard).toBeDefined();
    const cardText = occupiedCard?.text() ?? '';
    // 占床摘要：就诊号直显；患者姓名按脱敏口径不出网（无姓名字段）
    expect(cardText).toContain('I2026092000003');
    expect(cardText).toContain('在院');
  });

  it('消毒完成操作出网并刷新床位图（DISINFECTING→FREE 由后端状态机承载）', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const disinfectingCard = findBedCard(wrapper, '04');
    expect(disinfectingCard).toBeDefined();
    // 床位卡操作 select 选择「消毒完成」（值=动作语义键）
    await disinfectingCard?.find('select').setValue('disinfectDone');
    await flushPromises();
    expect(beds.disinfectDone).toHaveBeenCalledWith('904');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 床位图重载（map 第二次调用）
    expect(beds.map).toHaveBeenCalledTimes(2);
  });

  it('转科弹窗提交出网（transfer.execute 携目标病区与床位）', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    vi.mocked(transfer.execute).mockResolvedValue(transferResultMock());
    const wrapper = mount(BedMapView);
    await flushPromises();
    // 占床卡选择「转科转床」打开弹窗
    const occupiedCard = findBedCard(wrapper, '03');
    await occupiedCard?.find('select').setValue('transferDialog');
    await flushPromises();
    // 切转科模式 → 目标病区空床清单加载 → 选目标床位 → 提交
    await wrapper.find('input[value="transfer"]').setValue();
    await flushPromises();
    await wrapper.find('select[aria-label="目标床位"]').setValue('902');
    await clickButton(wrapper, '确认转科');
    await flushPromises();
    expect(transfer.execute).toHaveBeenCalledWith('I2026092000003', {
      toWardId: 'W01',
      toBedId: '902',
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 床位图重载
    expect(beds.map).toHaveBeenCalledTimes(3);
  });

  it('转科未选目标床位零出网显式校验拦截', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const occupiedCard = findBedCard(wrapper, '03');
    await occupiedCard?.find('select').setValue('transferDialog');
    await flushPromises();
    await wrapper.find('input[value="transfer"]').setValue();
    await flushPromises();
    await clickButton(wrapper, '确认转科');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请选择目标床位');
    expect(transfer.execute).not.toHaveBeenCalled();
  });

  it('占床操作携 visit 号出网（prompt 输入经 14 位格式校验）', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const freeCard = findBedCard(wrapper, '01');
    await freeCard?.find('select').setValue('assign');
    await flushPromises();
    // ElMessageBox.prompt 替身返回 14 位 visit 号（模块级 mockResolvedValue 种子）
    expect(beds.assign).toHaveBeenCalledWith('901', { visitId: 'I2026092500001' });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 床位图重载
    expect(beds.map).toHaveBeenCalledTimes(2);
  });
});

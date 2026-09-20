// 药品字典页单测（FU-M06-01/02 前端面）：未对照药品渲染「不可医保结算」标记（对照态可视面）、
// 对照表单三字段提交以行 id 调 mapInsurance（雪花 ID string 原样入网，禁 number 处理）；
// 对照提交在途防抖（W-22⑥）：慢响应窗口内按钮禁用且二次点击零出网，结束后复位可再点。
// api mock 承载，不打真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createDrug, mapInsurance, searchDrugs, updateDrug } from '@/api/pharmacy';
import type { DrugVO } from '@/api/pharmacy';
import DrugDictView from './DrugDictView.vue';

vi.mock('@/api/pharmacy', () => ({
  searchDrugs: vi.fn(),
  createDrug: vi.fn(),
  updateDrug: vi.fn(),
  mapInsurance: vi.fn(),
}));

// 仅替身 ElMessage（前置校验提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

// jsdom 未实现 ResizeObserver：el-table 布局测量依赖，补空壳避免挂载即抛（测试环境无真实 resize）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 按按钮文案点击 el-button（避免 DOM 结构序号耦合，patient 三页 spec 同款） */
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

/** 构造药品行（insuredSettleable 由用例指定驱动标记断言） */
function drugRow(overrides: Partial<DrugVO>): DrugVO {
  return {
    id: '7',
    drugCode: 'D001',
    genericName: '阿莫西林胶囊',
    tradeName: undefined,
    dosageForm: undefined,
    specification: '0.25g*24粒',
    manufacturer: '华北制药',
    routeCodes: undefined,
    unit: '盒',
    splitRatio: undefined,
    nhsaCode: undefined,
    nhsaCatalogVersion: undefined,
    nhsaPayType: undefined,
    essentialFlag: false,
    antibioClass: 'NONE',
    hazardLevel: 'NONE',
    skinTestFlag: false,
    narcoticClass: 'NONE',
    itemCode: 'C901',
    traceCodeType: undefined,
    indication: undefined,
    maxDose: undefined,
    contraindication: undefined,
    storageCondition: undefined,
    insuredSettleable: false,
    status: 'ACTIVE',
    ...overrides,
  };
}

describe('药品字典页', () => {
  beforeEach(() => {
    vi.mocked(searchDrugs).mockReset();
    vi.mocked(createDrug).mockReset();
    vi.mocked(updateDrug).mockReset();
    vi.mocked(mapInsurance).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    // 挂载即检索：默认空页兜底，防未 stub 的 resolve 断链
    vi.mocked(searchDrugs).mockResolvedValue({ content: [], page: 0, size: 20, total: '0' });
    vi.mocked(mapInsurance).mockResolvedValue();
  });

  it('未对照药品渲染不可医保结算标记', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(DrugDictView);

    await vi.waitFor(() => {
      // 业务断言：insuredSettleable=false 行渲染警示标记（对照态可视面）
      expect(wrapper.text()).toContain('不可医保结算');
    });
    wrapper.unmount();
  });

  it('对照表单提交以行 id 调 mapInsurance', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(DrugDictView);
    await flushPromises();

    // 打开对照弹窗（el-dialog 缺省不 teleport，弹窗 DOM 留在 wrapper 内）
    await clickButton(wrapper, '医保对照');
    await flushPromises();

    await wrapper.find('input[placeholder="国家医保药品编码"]').setValue('XN01AX0900014305116');
    await wrapper.find('input[placeholder="如 2024A"]').setValue('2024A');
    await wrapper.find('input[placeholder="如 甲类/乙类"]').setValue('甲类');
    await clickButton(wrapper, '确认对照');
    await flushPromises();

    // 出网载荷：行 id string 原样入路径 + 三字段原样（对照后 insuredSettleable 翻转由后端承载）
    expect(vi.mocked(mapInsurance)).toHaveBeenCalledWith('7', {
      nhsaCode: 'XN01AX0900014305116',
      catalogVersion: '2024A',
      payType: '甲类',
    });
    wrapper.unmount();
  });

  it('医保对照提交在途：按钮禁用且二次点击零出网，结束后复位可再点（W-22⑥ 防抖）', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    // 慢响应：对照挂起至用例放行，稳定复现「请求在途」窗口（双击的第二个事件必落在窗口内）
    let releaseMapping: () => void = () => {};
    vi.mocked(mapInsurance).mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          releaseMapping = resolve;
        }),
    );
    const wrapper = mount(DrugDictView);
    await flushPromises();

    // 打开对照弹窗并录入三字段 → 首击提交（在途窗开启）
    await clickButton(wrapper, '医保对照');
    await flushPromises();
    await wrapper.find('input[placeholder="国家医保药品编码"]').setValue('XN01AX0900014305116');
    await wrapper.find('input[placeholder="如 2024A"]').setValue('2024A');
    await wrapper.find('input[placeholder="如 甲类/乙类"]').setValue('甲类');
    await clickButton(wrapper, '确认对照');
    await flushPromises();

    // 在途态：按钮原生 disabled 置位（:loading 同挂 mappingSubmitting）
    expect(findButton(wrapper, '确认对照').attributes('disabled')).toBeDefined();
    expect(vi.mocked(mapInsurance)).toHaveBeenCalledTimes(1);

    // 在途窗口内二次点击：handler 入口守卫 + 组件 loading 双保险，零第二次出网
    await clickButton(wrapper, '确认对照');
    await flushPromises();
    expect(vi.mocked(mapInsurance)).toHaveBeenCalledTimes(1);

    // 在途结束后恢复可点（防抖标记须经 finally 复位，禁把按钮永久锁死）
    releaseMapping();
    await flushPromises();
    expect(findButton(wrapper, '确认对照').attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });
});

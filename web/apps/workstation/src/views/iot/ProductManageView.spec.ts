// 产品与物模型管理页单测（/iot/products，M14 FU-M14-02 前端面）：产品列表加载与物模型
// 同步状态三态徽标渲染（fuy-sync-tag--{status} 状态类契约机器判据）、上架表单必填缺项
// 零出网显式校验、上架提交出网携表单字段并刷新列表、同步物模型行操作出网、术语映射
// 编辑弹窗（指标字典加载/缺项零出网/提交 PUT 携映射行）、命令安全等级登记 PUT 出网。
// api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），不打真实网络；
// 断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { metrics, products } from '@/api/iot';
import type { ProductVO } from '@/api/iot';
import ProductManageView from './ProductManageView.vue';

vi.mock('@/api/iot', () => ({
  PRODUCT_SYNC_STATUS_LABELS: {
    SYNCING: '同步中',
    SYNCED: '已同步',
    MISMATCH: '失配',
  },
  SAFETY_LEVEL_LABELS: {
    SAFETY: '安全级',
    TREATMENT: '治疗级',
  },
  products: {
    list: vi.fn(),
    create: vi.fn(),
    syncModel: vi.fn(),
    updateMappings: vi.fn(),
    updateCommands: vi.fn(),
    listMappings: vi.fn(),
  },
  metrics: { list: vi.fn(), create: vi.fn() },
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

/** 产品行（三态同步状态可覆写） */
function productMock(partial: Partial<ProductVO> = {}): ProductVO {
  return {
    productId: 'prod-monitor-001',
    productName: '多参数监护仪',
    deviceType: 'Monitor',
    protocolType: 'MQTT',
    dataFormat: 'JSON',
    manufacturerName: '演示厂商',
    industry: '医疗',
    syncStatus: 'SYNCED',
    createdAt: '2026-09-25T10:00:00+08:00',
    ...partial,
  };
}

/** 三态齐全的产品清单（同步中/已同步/失配各一） */
function threeStateProducts(): ProductVO[] {
  return [
    productMock({ productId: 'prod-1', productName: '监护仪', syncStatus: 'SYNCING' }),
    productMock({ productId: 'prod-2', productName: '输液泵', syncStatus: 'SYNCED' }),
    productMock({ productId: 'prod-3', productName: '呼吸机', syncStatus: 'MISMATCH' }),
  ];
}

/** 空产品分页出参（page/size/total 由后端 long→string 全局口径） */
function emptyPage() {
  return { content: [], page: '0', size: '20', total: '0' };
}

/** 按文本定位表格行（行内按钮操作载体） */
function findRow(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('tr').find((row) => row.text().includes(text));
}

/** 按按钮文案点击 */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('产品与物模型管理页', () => {
  beforeEach(() => {
    for (const fn of [
      products.list,
      products.create,
      products.syncModel,
      products.updateMappings,
      products.updateCommands,
      products.listMappings,
      metrics.list,
      metrics.create,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链（映射回显空=弹窗回落单空行）
    vi.mocked(products.list).mockResolvedValue(emptyPage());
    vi.mocked(products.listMappings).mockResolvedValue([]);
    vi.mocked(metrics.list).mockResolvedValue([]);
  });

  it('产品列表加载渲染并透出物模型同步状态三态徽标（状态类机器判据）', async () => {
    vi.mocked(products.list).mockResolvedValue({
      content: threeStateProducts(),
      page: '0',
      size: '20',
      total: '3',
    });
    const wrapper = mount(ProductManageView);
    await flushPromises();
    const text = wrapper.text();
    // 三态中文词表与产品名可见
    expect(text).toContain('监护仪');
    expect(text).toContain('输液泵');
    expect(text).toContain('呼吸机');
    expect(text).toContain('同步中');
    expect(text).toContain('已同步');
    expect(text).toContain('失配');
    // 三态色标状态类契约（机器判据；色值经 --fuy-color-product-* 语义 token 承载）
    expect(wrapper.find('.fuy-sync-tag--syncing').exists()).toBe(true);
    expect(wrapper.find('.fuy-sync-tag--synced').exists()).toBe(true);
    expect(wrapper.find('.fuy-sync-tag--mismatch').exists()).toBe(true);
  });

  it('上架表单必填缺项零出网显式校验（产品名称/设备类型/协议/数据格式）', async () => {
    const wrapper = mount(ProductManageView);
    await flushPromises();
    await clickButton(wrapper, '上架产品');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalled();
    expect(products.create).not.toHaveBeenCalled();
  });

  it('上架提交出网携表单字段并刷新产品列表', async () => {
    vi.mocked(products.create).mockResolvedValue(productMock({ productName: '输液泵' }));
    const wrapper = mount(ProductManageView);
    await flushPromises();
    await wrapper.find('input[aria-label="产品名称"]').setValue('输液泵');
    await wrapper.find('input[aria-label="设备类型"]').setValue('InfusionPump');
    await wrapper.find('input[aria-label="协议类型"]').setValue('MQTT');
    await wrapper.find('input[aria-label="数据格式"]').setValue('JSON');
    await clickButton(wrapper, '上架产品');
    await flushPromises();
    expect(products.create).toHaveBeenCalledWith({
      productName: '输液泵',
      deviceType: 'InfusionPump',
      protocolType: 'MQTT',
      dataFormat: 'JSON',
      manufacturerName: undefined,
      industry: undefined,
      description: undefined,
      modelDefinitionJson: undefined,
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 列表重载（list 第二次调用）
    expect(products.list).toHaveBeenCalledTimes(2);
  });

  it('同步物模型行操作出网并刷新列表（syncModel 携产品 ID）', async () => {
    vi.mocked(products.list).mockResolvedValue({
      content: threeStateProducts(),
      page: '0',
      size: '20',
      total: '3',
    });
    vi.mocked(products.syncModel).mockResolvedValue(productMock({ syncStatus: 'SYNCED' }));
    const wrapper = mount(ProductManageView);
    await flushPromises();
    const row = findRow(wrapper, '呼吸机');
    expect(row).toBeDefined();
    await row?.find('button').trigger('click');
    await flushPromises();
    expect(products.syncModel).toHaveBeenCalledWith('prod-3');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 列表重载（list 第二次调用）
    expect(products.list).toHaveBeenCalledTimes(2);
  });

  it('术语映射弹窗：指标字典加载、映射行缺项零出网校验、补全后提交 PUT 携映射行', async () => {
    vi.mocked(products.list).mockResolvedValue({
      content: threeStateProducts(),
      page: '0',
      size: '20',
      total: '3',
    });
    vi.mocked(metrics.list).mockResolvedValue([
      {
        metricCode: 'MDC_ECG_HEART_RATE',
        metricName: '心率',
        category: 'VITAL_SIGN',
        dataType: 'NUMERIC',
      },
    ]);
    vi.mocked(products.updateMappings).mockResolvedValue([]);
    const wrapper = mount(ProductManageView);
    await flushPromises();
    // 打开「监护仪」行的术语映射弹窗（行操作第二枚按钮）
    const row = findRow(wrapper, '监护仪');
    const mapButton = row?.findAll('button').find((b) => b.text() === '术语映射');
    await mapButton?.trigger('click');
    await flushPromises();
    // 弹窗预载指标字典（跨品牌归一编码选项源）
    expect(metrics.list).toHaveBeenCalled();
    // 缺项提交零出网（属性名/指标编码未填）
    await clickButton(wrapper, '保存映射');
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalled();
    expect(products.updateMappings).not.toHaveBeenCalled();
    // 补全映射行后提交 PUT
    await wrapper.find('input[aria-label="物模型属性名"]').setValue('heartRate');
    await wrapper.find('select[aria-label="MDC 指标编码"]').setValue('MDC_ECG_HEART_RATE');
    await clickButton(wrapper, '保存映射');
    await flushPromises();
    expect(products.updateMappings).toHaveBeenCalledWith('prod-1', {
      mappings: [{ propertyName: 'heartRate', metricCode: 'MDC_ECG_HEART_RATE' }],
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
  });

  it('术语映射弹窗回显：已配置两条再打开回显两行，直接保存 PUT 携全集不再清空（BUG-17）', async () => {
    vi.mocked(products.list).mockResolvedValue({
      content: threeStateProducts(),
      page: '0',
      size: '20',
      total: '3',
    });
    vi.mocked(products.listMappings).mockResolvedValue([
      {
        id: '1',
        productId: 'prod-1',
        propertyName: 'heartRate',
        metricCode: 'MDC_ECG_HEART_RATE',
        mismatchStrategy: 'RAW_PASSTHROUGH',
      },
      {
        id: '2',
        productId: 'prod-1',
        propertyName: 'spo2',
        metricCode: 'MDC_PULSE_OXIM_SPO2',
        mismatchStrategy: 'RAW_PASSTHROUGH',
      },
    ]);
    vi.mocked(products.updateMappings).mockResolvedValue([]);
    const wrapper = mount(ProductManageView);
    await flushPromises();
    const row = findRow(wrapper, '监护仪');
    const mapButton = row?.findAll('button').find((b) => b.text() === '术语映射');
    await mapButton?.trigger('click');
    await flushPromises();
    // 打开弹窗即拉取既有映射全集（BUG-17 修复面）
    expect(products.listMappings).toHaveBeenCalledWith('prod-1');
    // 既有两条映射回填两行（属性名输入值回显）
    const propInputs = wrapper.findAll(
      'input[aria-label="物模型属性名"], input[aria-label="物模型属性名 2"]',
    );
    expect(propInputs).toHaveLength(2);
    expect((propInputs[0].element as HTMLInputElement).value).toBe('heartRate');
    expect((propInputs[1].element as HTMLInputElement).value).toBe('spo2');
    // 未改动直接保存：PUT 携既有全集（不再仅携单行静默清空）
    await clickButton(wrapper, '保存映射');
    await flushPromises();
    expect(products.updateMappings).toHaveBeenCalledWith('prod-1', {
      mappings: [
        {
          propertyName: 'heartRate',
          metricCode: 'MDC_ECG_HEART_RATE',
          mismatchStrategy: 'RAW_PASSTHROUGH',
        },
        { propertyName: 'spo2', metricCode: 'MDC_PULSE_OXIM_SPO2', mismatchStrategy: 'RAW_PASSTHROUGH' },
      ],
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
  });

  it('命令安全等级登记提交 PUT 携命令行（safetyLevel/allowed 出网）', async () => {
    vi.mocked(products.list).mockResolvedValue({
      content: threeStateProducts(),
      page: '0',
      size: '20',
      total: '3',
    });
    vi.mocked(products.updateCommands).mockResolvedValue([]);
    const wrapper = mount(ProductManageView);
    await flushPromises();
    // 打开「监护仪」行的命令登记弹窗（行操作第三枚按钮）
    const row = findRow(wrapper, '监护仪');
    const cmdButton = row?.findAll('button').find((b) => b.text() === '命令登记');
    await cmdButton?.trigger('click');
    await flushPromises();
    await wrapper.find('input[aria-label="命令名称"]').setValue('setAlarmLimit');
    await wrapper.find('select[aria-label="命令安全等级"]').setValue('SAFETY');
    // FU-M14-09：治疗级默认禁用——allowed 复选框默认未勾选，安全级可显式放行
    await wrapper.find('input[aria-label="白名单放行"]').setValue(true);
    await clickButton(wrapper, '保存命令');
    await flushPromises();
    expect(products.updateCommands).toHaveBeenCalledWith('prod-1', {
      commands: [{ commandName: 'setAlarmLimit', safetyLevel: 'SAFETY', allowed: true }],
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
  });
});

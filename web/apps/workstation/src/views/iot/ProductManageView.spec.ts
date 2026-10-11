// 产品与物模型管理页单测（/iot/products，M14 FU-M14-02 前端面）：暖纸卷宗重排锚点（门牌
// 页首三锚/上架五步步序注记/卷宗卡结构/空态脸/钤印族/两弹窗 fuy-dialog 挂类）+ 既有业务
// 行为全数保留——产品列表加载与物模型同步状态三态徽标渲染（fuy-sync-tag--{status} 状态类
// 契约机器判据）、上架表单必填缺项零出网显式校验、上架提交出网携表单字段并刷新列表、同步
// 物模型行操作出网、术语映射编辑弹窗（指标字典加载/缺项零出网/提交 PUT 携映射行）、命令
// 安全等级登记 PUT 出网、BUG-17/18 整组替换回显、EX-45/FE-A1-07/08 映射与命令行 v-for
// 稳定 key（删中间行后剩余行 DOM 节点保位不串值）、PR-4F v-perm 出网把守位。
// 成功反馈断言按暖纸 G2 基线重写（成功=钤印 .fuy-stamp-toast 动词结果，原 ElMessage.success
// 断言随钤印族换脸同批重写留痕——失败/校验仍走 message 批注条，钤印永不承失败）。
// api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），不打真实网络；
// 断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { metrics, products } from '@/api/iot';
import type { ProductVO } from '@/api/iot';
import { permDirective } from '@/directives/perm';
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
    listCommands: vi.fn(),
  },
  metrics: { list: vi.fn(), create: vi.fn() },
}));

// 仅替身 ElMessage（校验/失败提示断言用；成功反馈归钤印），其余导出原样保留供组件解析
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

/** 三态清单分页出参 */
function threeStatePage() {
  return { content: threeStateProducts(), page: '0', size: '20', total: '3' };
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
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #37）：真实 IOT_ADMIN 会话经登录契约导出含码，既有用例语义不变
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: {
          userId: 'u5',
          loginName: 'iotadmindemo',
          displayName: '陆物联',
          orgId: null,
          roles: ['iot_admin'],
          permissions: ['iot:product:btn:manage'],
        },
      }),
    );
    for (const fn of [
      products.list,
      products.create,
      products.syncModel,
      products.updateMappings,
      products.updateCommands,
      products.listMappings,
      products.listCommands,
      metrics.list,
      metrics.create,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链（回显空=弹窗回落单空行）
    vi.mocked(products.list).mockResolvedValue(emptyPage());
    vi.mocked(products.listMappings).mockResolvedValue([]);
    vi.mocked(products.listCommands).mockResolvedValue([]);
    vi.mocked(metrics.list).mockResolvedValue([]);
  });

  it('门牌页首与页级骨架锚点：根挂 fuy-page 零 stagger、衬线标题+英文小注+批注行+页脚批注', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
    const wrapper = mount(ProductManageView);
    await flushPromises();
    // 页级骨架（契约 ⑦.4/G1）：根挂 .fuy-page 禁挂 .fuy-stagger——stagger 挂页内
    // product-flow 区块（admission-flow 同构形态），防双重进场节奏
    const root = wrapper.find('.fuy-page');
    expect(root.exists()).toBe(true);
    expect(root.classes()).toContain('product-manage');
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(wrapper.find('.product-flow').classes()).toContain('fuy-stagger');
    // 门牌页首三锚（契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+「IoTDA 产品镜像 · 谁 · 时刻」批注行
    expect(wrapper.find('.fuy-page-title').text()).toBe('产品与物模型');
    expect(wrapper.find('.fuy-page-en').text()).toBe('PRODUCT & MODEL');
    const note = wrapper.find('.fuy-page-note').text();
    expect(note).toContain('IoTDA 产品镜像');
    // 「谁」=会话显示名（空值 — 占位口径由组件 computed 承载，种子会话为陆物联）
    expect(note).toContain('陆物联');
    // 页脚批注（G1 纵向序收尾）：数据时点 + 手动刷新取数语义，禁放操作钮
    expect(wrapper.find('.fuy-page-foot').text()).toContain('数据时点');
    expect(wrapper.find('.fuy-page-foot').text()).toContain('手动刷新取数');
  });

  it('上架卡头步序注记：五步文案序号 .fuy-num 单行锚点（文书页眉语法，非交互 stepper）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
    const wrapper = mount(ProductManageView);
    await flushPromises();
    const steps = wrapper.find('.product-steps');
    expect(steps.exists()).toBe(true);
    // PM 重排裁决五步文案锚点：1 建产品 → 2 物模型 → 3 映射术语 → 4 标注命令 → 5 注册设备
    const text = steps.text();
    for (const segment of [
      '1',
      '建产品',
      '2',
      '物模型',
      '3',
      '映射术语',
      '4',
      '标注命令',
      '5',
      '注册设备',
    ]) {
      expect(text).toContain(segment);
    }
    expect(text).toContain('→');
    expect(steps.findAll('.fuy-num')).toHaveLength(5);
    // 步序注记置上架卡卡头下方（卡头下、卡体上——文书页眉语法）
    const createCard = wrapper
      .findAll('.fuy-card')
      .find((card) => card.text().includes('产品上架'));
    expect(createCard).toBeDefined();
    expect(createCard?.find('.fuy-card-head').exists()).toBe(true);
    expect(createCard?.find('.product-steps').exists()).toBe(true);
  });

  it('卷宗卡结构与空态脸锚点：左右两卡挂 fuy-card，空列表走 fuy-empty 零默认插画', async () => {
    const wrapper = mount(ProductManageView);
    await flushPromises();
    const titles = wrapper.findAll('.fuy-card-title').map((t) => t.text());
    expect(titles).toContain('产品列表');
    expect(titles).toContain('产品上架');
    // 空态脸（G6/契约 ⑥）：主句「暂无产品」+ 书法「空」装饰层 + 说明给下一步
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.find('.fuy-empty-mark').text()).toBe('空');
    expect(empty.find('.fuy-empty-title').text()).toBe('暂无产品');
    expect(empty.find('.fuy-empty-hint').text().length).toBeGreaterThan(0);
    // 默认纸箱插画在 fuy-page 子树全站压制（契约 ⑥ 挂类前缀组合由全局段承载，此处验零渲染兜底）
    expect(wrapper.find('.fuy-empty img').exists()).toBe(false);
  });

  it('两行编辑弹窗挂类锚点：术语映射与命令登记弹窗挂 fuy-dialog（行高与 fuy-form 对齐）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
    const wrapper = mount(ProductManageView);
    await flushPromises();
    await clickButton(wrapper, '术语映射');
    await flushPromises();
    // 术语映射编辑弹窗挂 .fuy-dialog（G2：衬线标题 + 头部发丝由全局段承载）
    const mappingDialog = wrapper
      .findAll('.fuy-dialog')
      .find((d) => d.text().includes('术语映射编辑'));
    expect(mappingDialog).toBeDefined();
    await clickButton(wrapper, '取消');
    await flushPromises();
    await clickButton(wrapper, '命令登记');
    await flushPromises();
    const commandDialog = wrapper
      .findAll('.fuy-dialog')
      .find((d) => d.text().includes('命令安全等级登记'));
    expect(commandDialog).toBeDefined();
  });

  it('产品列表加载渲染并透出物模型同步状态三态徽标（状态类机器判据）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
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

  it('上架提交出网携表单字段并刷新产品列表（成功反馈=钤印「已上架」）', async () => {
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
    // 成功反馈=钤印（G2 基线，域丙钤印族「已上架」；原 ElMessage.success('产品已上架…')
    // 断言随钤印换脸同批重写留痕——v-if 挂载制 + is-show 静态类）
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.classes()).toContain('is-show');
    expect(stamp.text()).toBe('已上架');
    // 列表重载（list 第二次调用）
    expect(products.list).toHaveBeenCalledTimes(2);
  });

  it('同步物模型行操作出网并刷新列表（syncModel 携产品 ID，成功反馈=钤印「已触发同步」）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
    vi.mocked(products.syncModel).mockResolvedValue(productMock({ syncStatus: 'SYNCED' }));
    const wrapper = mount(ProductManageView);
    await flushPromises();
    const row = findRow(wrapper, '呼吸机');
    expect(row).toBeDefined();
    await row?.find('button').trigger('click');
    await flushPromises();
    expect(products.syncModel).toHaveBeenCalledWith('prod-3');
    // 成功反馈=钤印「已触发同步」（丙-1 蓝图第 4 节逐字；原 ElMessage.success 断言随钤印
    // 换脸同批重写留痕）
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.classes()).toContain('is-show');
    expect(stamp.text()).toBe('已触发同步');
    // 列表重载（list 第二次调用）
    expect(products.list).toHaveBeenCalledTimes(2);
  });

  it('钤印挂载制反向断言：无成功动作时零钤印 DOM（v-if 挂载制，防常驻空朱块泄漏）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
    const wrapper = mount(ProductManageView);
    await flushPromises();
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
  });

  it('术语映射弹窗：指标字典加载、映射行缺项零出网校验、补全后提交 PUT 携映射行（保存=钤印「已保存」）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
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
    // 成功反馈=钤印「已保存」（丙-1 蓝图第 4 节逐字；原 ElMessage.success 断言随钤印
    // 换脸同批重写留痕）
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已保存');
  });

  it('术语映射弹窗回显：已配置两条再打开回显两行，直接保存 PUT 携全集不再清空（BUG-17）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
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
        {
          propertyName: 'spo2',
          metricCode: 'MDC_PULSE_OXIM_SPO2',
          mismatchStrategy: 'RAW_PASSTHROUGH',
        },
      ],
    });
    // 成功反馈=钤印「已保存」（G2 基线；原 ElMessage.success 断言随钤印换脸同批重写留痕）
    expect(wrapper.find('.fuy-stamp-toast').text()).toBe('已保存');
  });

  it('命令安全等级登记提交 PUT 携命令行（safetyLevel/allowed 出网，保存=钤印「已保存」）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
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
    // 成功反馈=钤印「已保存」（丙-1 蓝图第 4 节逐字；原 ElMessage.success 断言随钤印
    // 换脸同批重写留痕）
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已保存');
  });

  it('命令登记弹窗回显：已配置两条再打开回显两行，直接保存 PUT 携全集不再清空（BUG-18）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
    vi.mocked(products.listCommands).mockResolvedValue([
      {
        id: '1',
        productId: 'prod-1',
        commandName: 'setWorkMode',
        serviceId: 'vital',
        safetyLevel: 'SAFETY',
        allowed: true,
      },
      {
        id: '2',
        productId: 'prod-1',
        commandName: 'defibrillate',
        serviceId: 'treatment',
        safetyLevel: 'TREATMENT',
        allowed: false,
      },
    ]);
    vi.mocked(products.updateCommands).mockResolvedValue([]);
    const wrapper = mount(ProductManageView);
    await flushPromises();
    const row = findRow(wrapper, '监护仪');
    const cmdButton = row?.findAll('button').find((b) => b.text() === '命令登记');
    await cmdButton?.trigger('click');
    await flushPromises();
    // 打开弹窗即拉取既有命令标注全集（BUG-18 修复面）
    expect(products.listCommands).toHaveBeenCalledWith('prod-1');
    // 既有两条命令回填两行（命令名/安全等级/放行状态回显）
    const nameInputs = wrapper.findAll(
      'input[aria-label="命令名称"], input[aria-label="命令名称 2"]',
    );
    expect(nameInputs).toHaveLength(2);
    expect((nameInputs[0].element as HTMLInputElement).value).toBe('setWorkMode');
    expect((nameInputs[1].element as HTMLInputElement).value).toBe('defibrillate');
    const levelSelects = wrapper.findAll(
      'select[aria-label="命令安全等级"], select[aria-label="命令安全等级 2"]',
    );
    expect((levelSelects[0].element as HTMLSelectElement).value).toBe('SAFETY');
    expect((levelSelects[1].element as HTMLSelectElement).value).toBe('TREATMENT');
    const allowedBoxes = wrapper.findAll(
      'input[aria-label="白名单放行"], input[aria-label="白名单放行 2"]',
    );
    expect((allowedBoxes[0].element as HTMLInputElement).checked).toBe(true);
    expect((allowedBoxes[1].element as HTMLInputElement).checked).toBe(false);
    // 未改动直接保存：PUT 携既有全集含 serviceId 透传（不再仅携单行静默清空白名单）
    await clickButton(wrapper, '保存命令');
    await flushPromises();
    expect(products.updateCommands).toHaveBeenCalledWith('prod-1', {
      commands: [
        { commandName: 'setWorkMode', serviceId: 'vital', safetyLevel: 'SAFETY', allowed: true },
        {
          commandName: 'defibrillate',
          serviceId: 'treatment',
          safetyLevel: 'TREATMENT',
          allowed: false,
        },
      ],
    });
    // 成功反馈=钤印「已保存」（G2 基线；原 ElMessage.success 断言随钤印换脸同批重写留痕）
    expect(wrapper.find('.fuy-stamp-toast').text()).toBe('已保存');
  });

  it('「治疗级默认禁用」口径文案逐字冻结：命令登记弹窗批注句保留（FU-M14-09 白名单数据源）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
    const wrapper = mount(ProductManageView);
    await flushPromises();
    await clickButton(wrapper, '命令登记');
    await flushPromises();
    // 白名单口径文案逐字冻结（丙-1 蓝图第 4 节冻结面）
    const dialog = wrapper
      .findAll('.fuy-dialog')
      .find((d) => d.text().includes('命令安全等级登记'));
    expect(dialog?.text()).toContain('安全级默认放行，治疗级默认禁用（豁免需系统参数开启并审计）');
  });

  it('术语映射行删除中间行后剩余行输入节点保位不串值（稳定 key，EX-45/FE-A1-07）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
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
    const wrapper = mount(ProductManageView);
    await flushPromises();
    await findRow(wrapper, '监护仪')
      ?.findAll('button')
      .find((b) => b.text() === '术语映射')
      ?.trigger('click');
    await flushPromises();
    const propInputs = wrapper.findAll(
      'input[aria-label="物模型属性名"], input[aria-label="物模型属性名 2"]',
    );
    expect(propInputs).toHaveLength(2);
    // 记录第二行（spo2）输入节点身份：删除首行后该节点必须原位保留——index 键会改写首行
    // 节点承载第二行数据（节点身份漂移，焦点/输入态错位到错误行）
    const secondInputEl = propInputs[1].element;
    const firstRowRemove = wrapper
      .findAll('.product-mapping-row')[0]
      .findAll('button')
      .find((b) => b.text() === '删除');
    await firstRowRemove?.trigger('click');
    await flushPromises();
    const remaining = wrapper.findAll('input[aria-label="物模型属性名"]');
    expect(remaining).toHaveLength(1);
    expect((remaining[0].element as HTMLInputElement).value).toBe('spo2');
    expect(remaining[0].element).toBe(secondInputEl);
  });

  it('命令登记行删除中间行后剩余行输入节点保位不串值（稳定 key，EX-45/FE-A1-08）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
    vi.mocked(products.listCommands).mockResolvedValue([
      {
        id: '1',
        productId: 'prod-1',
        commandName: 'setWorkMode',
        serviceId: 'vital',
        safetyLevel: 'SAFETY',
        allowed: true,
      },
      {
        id: '2',
        productId: 'prod-1',
        commandName: 'defibrillate',
        serviceId: 'treatment',
        safetyLevel: 'TREATMENT',
        allowed: false,
      },
    ]);
    const wrapper = mount(ProductManageView);
    await flushPromises();
    await findRow(wrapper, '监护仪')
      ?.findAll('button')
      .find((b) => b.text() === '命令登记')
      ?.trigger('click');
    await flushPromises();
    const nameInputs = wrapper.findAll(
      'input[aria-label="命令名称"], input[aria-label="命令名称 2"]',
    );
    expect(nameInputs).toHaveLength(2);
    // 记录第二行（defibrillate）输入节点身份：删除首行后该节点必须原位保留（错位即窜行）
    const secondInputEl = nameInputs[1].element;
    const firstRowRemove = wrapper
      .findAll('.product-mapping-row')[0]
      .findAll('button')
      .find((b) => b.text() === '删除');
    await firstRowRemove?.trigger('click');
    await flushPromises();
    const remaining = wrapper.findAll('input[aria-label="命令名称"]');
    expect(remaining).toHaveLength(1);
    expect((remaining[0].element as HTMLInputElement).value).toBe('defibrillate');
    expect(remaining[0].element).toBe(secondInputEl);
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(ProductManageView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 iot:product:btn:manage 时上架/同步与映射/命令保存口全隐藏（D-34）', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u5' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    // 上架提交口与行内同步口无码全隐藏
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).not.toContain('上架产品');
    expect(buttonTexts).not.toContain('同步');
    // 映射/命令弹窗入口（盘点口径不挂码）可开窗，弹窗内出网口无码隐藏
    await clickButton(wrapper, '术语映射');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).not.toContain('保存映射');
    await clickButton(wrapper, '取消');
    await flushPromises();
    await clickButton(wrapper, '命令登记');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).not.toContain('保存命令');
    // 产品列表读面不受元素码影响
    expect(wrapper.text()).toContain('监护仪');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 iot:product:btn:manage 时上架/同步与映射/命令保存口可见', async () => {
    vi.mocked(products.list).mockResolvedValue(threeStatePage());
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('上架产品');
    expect(buttonTexts).toContain('同步');
    await clickButton(wrapper, '术语映射');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).toContain('保存映射');
    await clickButton(wrapper, '取消');
    await flushPromises();
    await clickButton(wrapper, '命令登记');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).toContain('保存命令');
    wrapper.unmount();
  });
});

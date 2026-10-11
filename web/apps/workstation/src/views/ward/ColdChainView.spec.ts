// 冷链台账单测（/ward/cold-chain，M16 冷链治理面前端面 · 暖纸卷宗换脸重排 丁-3）：门牌页首
// 三锚与根类骨架（契约 G1/⑦.4）、档案列表加载与用途/温区词表徽标（fuy-archive-tag--{purpose}
// 机器判据）与 overdue 已到期印泥朱徽标（丁-3 锚点④，语义 token 源码级判据）、新建档案必填
// 校验零出网与合法出网携用途/设备/温区并刷新+钤印「已登记」（丁-3 锚点⑤）、详情升 640px
// 抽屉挂 .fuy-drawer 与头部批注槽（档案号·用途·温区·设备·到期态）与三段 sign-divider 结构
// （丁-3 锚点①）、档案详情出网 detail+records 渲染记录列表（记录类型词表与双人字段可见）、
// 巡检登记出网携 recordType=INSPECTION+content 并刷新+钤印「已登记」、告警处置双人复核字段
// 缺一零出网与齐全出网携 alarmRef+secondOperator（拦截文案冻结）、温度曲线出网 series 携
// scope=device/metricCode/from/to 且 SVG 折线点数+发丝网格与温度刻度机器判据（承体温单
// chart-grid 语法）、曲线退化态（单点短横刻记无网格基准/空点空态脸，丁-3 锚点③——刻记
// 与丁-1 输液曲线同构口径：polyline 单坐标零渲染，退化落点必须可见）、列表/记录
// 空态脸「暂无冷链档案/暂无记录」（丁-3 锚点⑤）、PR-4F 权限态出网把守位显隐（D-34）。
// vi.mock('@/api/ward') 与 vi.mock('@/api/iot') 双替身承载零出网（本页无 WARD 消费，替身不再
// 提供 WARD_OPTIONS 假常量），断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { telemetry } from '@/api/iot';
import type { TelemetryPoint } from '@/api/iot';
import { coldChain } from '@/api/ward';
import type { ColdChainArchiveVO, ColdChainRecordVO } from '@/api/ward';
import { permDirective } from '@/directives/perm';
import ColdChainView from './ColdChainView.vue';

vi.mock('@/api/iot', () => ({
  telemetry: { series: vi.fn() },
}));

vi.mock('@/api/ward', () => ({
  COLD_PURPOSE_LABELS: { VACCINE: '疫苗', BLOOD: '血液', REAGENT: '试剂', PHARMA: '药品' },
  TEMP_RANGE_LABELS: { FREEZE: '冷冻', COOL: '冷藏', SHELDED: '阴凉', NORMAL: '常温' },
  COLD_RECORD_TYPE_LABELS: { INSPECTION: '巡检', ALARM_HANDLE: '告警处置', DEVIATION: '偏差记录' },
  coldChain: {
    page: vi.fn(),
    create: vi.fn(),
    detail: vi.fn(),
    records: vi.fn(),
    registerRecord: vi.fn(),
  },
}));

// 仅替身 ElMessage（警示断言用），其余导出原样保留供组件解析
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

/** 打开档案详情抽屉并等装载完成（三段结构/登记/曲线用例共用入口） */
async function openDetailDrawer(wrapper: VueWrapper): Promise<void> {
  await clickRowButton(wrapper, 'CC20260926001', '详情');
  await flushPromises();
}

describe('冷链台账页', () => {
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #40）：真实 IOT_ADMIN 会话经登录契约导出含码，既有用例语义不变
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
          permissions: ['ward:coldchain:btn:manage'],
        },
      }),
    );
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

  it('页面骨架：根挂 .fuy-page 不挂 .fuy-stagger，门牌页首三锚与页首状态位（丁-3 锚点①前置·契约 G1/⑦.4）', async () => {
    const wrapper = mount(ColdChainView);
    await flushPromises();
    // 根类骨架（契约 ⑦.4）：页面根节点禁挂 .fuy-stagger，页内级联由 flow 包裹层承接
    const root = wrapper.find('.fuy-page');
    expect(root.exists()).toBe(true);
    expect(root.classes()).toContain('cold-chain');
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(wrapper.find('.cold-flow.fuy-stagger').exists()).toBe(true);
    // 门牌页首三锚（契约 ⑧.1）：衬线标题 + 英文小注 + 批注行（谁·时刻齐备）
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('冷链台账');
    expect(wrapper.find('.fuy-page-en').text()).toBe('COLD CHAIN');
    const note = wrapper.find('.fuy-page-note');
    expect(note.text()).toContain('疫苗/血液/试剂/药品档案');
    expect(note.text()).toContain('告警处置双人复核');
    expect(note.text()).toContain('校验到期归后端承载');
    expect(note.text()).toContain('陆物联');
    expect(note.find('time').exists()).toBe(true);
    // 页首状态位（蓝图 丁-3.2）：用途筛选 + 新建档案 + 刷新挂门牌状态位
    const status = wrapper.find('.fuy-page-status');
    expect(status.find('select[aria-label="用途筛选"]').exists()).toBe(true);
    expect(status.findAll('button').map((b) => b.text())).toContain('新建档案');
    expect(status.findAll('button').map((b) => b.text())).toContain('刷新');
  });

  it('台账列表加载渲染用途/温区词表徽标与 overdue 已到期印泥朱徽标（丁-3 锚点④）', async () => {
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
    // 列表卡卷宗脸（契约 ⑧.2/⑧.4）：全宽 .fuy-card + .fuy-dense 密排 + 骨架 CLS 锁容器
    expect(wrapper.find('.fuy-card.fuy-dense').exists()).toBe(true);
    expect(wrapper.find('.cold-list-body').exists()).toBe(true);
    expect(text).toContain('共');
    // 用途徽标状态类契约（机器判据；色值经语义 token 承载）
    expect(wrapper.find('.fuy-archive-tag--vaccine').exists()).toBe(true);
    expect(wrapper.find('.fuy-archive-tag--blood').exists()).toBe(true);
    // overdue 已到期徽标（印泥朱语义通道：源码级判据——scoped 规则取 --fuy-color-danger-text，
    // 零新 hex 字面量残留）
    const overdueTag = wrapper.find('.fuy-archive-overdue');
    expect(overdueTag.exists()).toBe(true);
    expect(overdueTag.text()).toBe('已到期');
    const source = readFileSync(join(import.meta.dirname, 'ColdChainView.vue'), 'utf-8');
    const overdueRule = source.slice(
      Math.max(0, source.indexOf('.fuy-archive-overdue')),
      source.indexOf('.fuy-archive-overdue') + 200,
    );
    expect(overdueRule).toContain('var(--fuy-color-danger-text)');
    expect(source).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
  });

  it('新建档案：设备必填零出网拦截，合法出网携用途/设备/温区并刷新列表+钤印「已登记」（丁-3 锚点⑤）', async () => {
    vi.mocked(coldChain.create).mockResolvedValue(archiveMock());
    const wrapper = mount(ColdChainView);
    await flushPromises();
    await clickButton(wrapper, '新建档案');
    await flushPromises();
    // 弹层脸（契约 G2/⑤#9）：新建档案弹窗挂 .fuy-dialog，表单容器挂 .fuy-form
    expect(wrapper.find('.fuy-dialog').exists()).toBe(true);
    expect(wrapper.find('.fuy-dialog .fuy-form').exists()).toBe(true);
    // 设备必填拦截（显式校验零出网）
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
    expect(coldChain.page).toHaveBeenCalledTimes(2);
    // 成功反馈=钤印（契约 ⑧.5/丁-3 锚点⑤：建档成功盖「已登记」，失败归 message 不盖钤印）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已登记');
  });

  it('详情升 640px 抽屉挂 .fuy-drawer：头部批注槽与三段 sign-divider 结构（丁-3 锚点①）', async () => {
    vi.mocked(coldChain.page).mockResolvedValue({
      content: [archiveMock({ overdue: true })],
      page: '0',
      size: '20',
      total: '1',
    });
    // detail 回显为权威源：到期态批注以 detail 出参为准（同步覆写）
    vi.mocked(coldChain.detail).mockResolvedValue(archiveMock({ overdue: true }));
    vi.mocked(coldChain.records).mockResolvedValue([recordMock()]);
    const wrapper = mount(ColdChainView);
    await flushPromises();
    await openDetailDrawer(wrapper);
    expect(coldChain.detail).toHaveBeenCalledWith('CC20260926001');
    expect(coldChain.records).toHaveBeenCalledWith('CC20260926001');
    // 抽屉升档锚点（PM 重排裁决：720 弹窗→640 抽屉，卷宗阅读面语义；契约 G2/⑤#10）
    expect(wrapper.find('.fuy-drawer').exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'ElDrawer' }).props('size')).toBe('640px');
    // 头部批注槽=档案号 · 用途 · 温区 · 设备 · 到期态
    const note = wrapper.find('.cold-drawer-note');
    expect(note.exists()).toBe(true);
    expect(note.text()).toContain('CC20260926001');
    expect(note.text()).toContain('疫苗');
    expect(note.text()).toContain('冷藏');
    expect(note.text()).toContain('dev-fridge-1');
    expect(note.find('.fuy-archive-overdue').text()).toBe('已到期');
    // 三段结构：记录表 → 签分隔 → 登记表单 → 签分隔 → 曲线（.fuy-sign-divider 分界）
    expect(wrapper.findAll('.fuy-sign-divider')).toHaveLength(2);
    const drawerBody = wrapper.find('.cold-drawer-body');
    expect(drawerBody.exists()).toBe(true);
    const segSeq = drawerBody
      .findAll('.cold-seg-records, .cold-drawer-divider, .cold-seg-register, .cold-seg-curve')
      .map((node) => {
        const classes = node.classes();
        if (classes.includes('cold-seg-records')) {
          return 'records';
        }
        if (classes.includes('cold-seg-register')) {
          return 'register';
        }
        if (classes.includes('cold-seg-curve')) {
          return 'curve';
        }
        return 'divider';
      });
    expect(segSeq).toEqual(['records', 'divider', 'register', 'divider', 'curve']);
    // 段脸：记录表 .fuy-dense 密排 / 登记表单 .fuy-form 表单脸 / 曲线区 SVG 容器（骨架
    // CLS 锁：抽屉体 min-height 320px 容器在位）
    expect(drawerBody.find('.cold-seg-records .fuy-dense').exists()).toBe(true);
    expect(drawerBody.find('.cold-seg-register .fuy-form').exists()).toBe(true);
    expect(drawerBody.find('.cold-seg-curve svg').exists()).toBe(false);
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
    await openDetailDrawer(wrapper);
    expect(coldChain.detail).toHaveBeenCalledWith('CC20260926001');
    expect(coldChain.records).toHaveBeenCalledWith('CC20260926001');
    const text = wrapper.text();
    expect(text).toContain('巡检');
    expect(text).toContain('告警处置');
    expect(text).toContain('N002');
    expect(text).toContain('AL20260926009');
  });

  it('巡检登记出网携 recordType=INSPECTION+content 并刷新记录+钤印「已登记」', async () => {
    const wrapper = mount(ColdChainView);
    await flushPromises();
    await openDetailDrawer(wrapper);
    await wrapper.find('textarea[aria-label="登记内容"]').setValue('日检温度正常');
    await clickButton(wrapper, '提交登记');
    await flushPromises();
    expect(coldChain.registerRecord).toHaveBeenCalledWith('CC20260926001', {
      recordType: 'INSPECTION',
      alarmRef: undefined,
      secondOperator: undefined,
      content: '日检温度正常',
    });
    // 记录重载（records 第二次调用）
    expect(coldChain.records).toHaveBeenCalledTimes(2);
    // 成功反馈=钤印（契约 ⑧.5：登记成功盖「已登记」）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已登记');
  });

  it('告警处置双人复核字段缺一零出网拦截，齐全出网携 alarmRef+secondOperator（拦截文案冻结）', async () => {
    const wrapper = mount(ColdChainView);
    await flushPromises();
    await openDetailDrawer(wrapper);
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

  it('温度曲线出网 series 携 scope=device/metricCode/from/to，SVG 折线点数+发丝网格与温度刻度机器判据', async () => {
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
    await openDetailDrawer(wrapper);
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
    // 发丝网格与温度刻度（承体温单 chart-grid 语法）：有范围基准时渲染网格线与刻度值
    expect(wrapper.findAll('.fuy-chart-grid').length).toBeGreaterThanOrEqual(3);
    const ticks = wrapper.findAll('.fuy-chart-tick').map((t) => t.text());
    expect(ticks.length).toBeGreaterThanOrEqual(3);
    expect(ticks).toContain('4.5');
    expect(ticks).toContain('3.8');
  });

  it('曲线退化态：单点折线退化为短横刻记且无网格基准，空点回归曲线空态脸（丁-3 锚点③）', async () => {
    // 单点：无法构成范围基准 → 折线退化为居中短横刻记（polyline 单坐标零渲染，扩 ±3px 保
    // 「有数据」可见落点，与丁-1 同构），网格/刻度不渲染（退化态保留）
    vi.mocked(telemetry.series).mockResolvedValue([
      {
        deviceId: 'dev-fridge-1',
        metricCode: 'COLDCHAIN_TEMP',
        time: '2026-09-26T00:00:00Z',
        avg: 4.1,
      },
    ]);
    const wrapper = mount(ColdChainView);
    await flushPromises();
    await openDetailDrawer(wrapper);
    await clickButton(wrapper, '查询曲线');
    await flushPromises();
    const polyline = wrapper.find('svg polyline');
    expect(polyline.exists()).toBe(true);
    // 刻记=±3px 短横两坐标（单坐标零渲染是盲点，可见落点才是退化态的严格判据）
    expect(polyline.attributes('points')?.trim().split(/\s+/)).toHaveLength(2);
    expect(wrapper.find('.fuy-chart-grid').exists()).toBe(false);
    wrapper.unmount();

    // 空点：曲线空态脸（暂无语法 + 下一步指引），零默认纸箱插画
    vi.mocked(telemetry.series).mockResolvedValue([]);
    const emptyWrapper = mount(ColdChainView);
    await flushPromises();
    await openDetailDrawer(emptyWrapper);
    await clickButton(emptyWrapper, '查询曲线');
    await flushPromises();
    expect(emptyWrapper.find('svg polyline').exists()).toBe(false);
    const curveEmpty = emptyWrapper.find('.cold-seg-curve .fuy-empty');
    expect(curveEmpty.exists()).toBe(true);
    expect(curveEmpty.find('.fuy-empty-title').text()).toBe('暂无曲线数据');
    expect(curveEmpty.find('.fuy-empty-hint').text()).toContain('指标编码');
  });

  it('曲线查询在途守卫与「档案未关联设备」warning 保留', async () => {
    let resolveSeries: ((points: TelemetryPoint[]) => void) | undefined;
    vi.mocked(telemetry.series).mockReturnValue(
      new Promise<TelemetryPoint[]>((resolve) => {
        resolveSeries = resolve;
      }),
    );
    const wrapper = mount(ColdChainView);
    await flushPromises();
    await openDetailDrawer(wrapper);
    await clickButton(wrapper, '查询曲线');
    // 在途期间再次点击查询：守卫早退，series 仍只调 1 次
    await clickButton(wrapper, '查询曲线');
    await flushPromises();
    expect(telemetry.series).toHaveBeenCalledTimes(1);
    resolveSeries?.([]);
    await flushPromises();
    wrapper.unmount();

    // 档案未关联设备：warning 早退零出网（文案冻结；detail 回显为权威源，须同步覆写）
    vi.mocked(coldChain.page).mockResolvedValue({
      content: [archiveMock({ deviceId: undefined })],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(coldChain.detail).mockResolvedValue(archiveMock({ deviceId: undefined }));
    const noDeviceWrapper = mount(ColdChainView);
    await flushPromises();
    await openDetailDrawer(noDeviceWrapper);
    await clickButton(noDeviceWrapper, '查询曲线');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('档案未关联设备，无法查询温度曲线');
    // 仍只有首个档案的 1 次出网（无设备档案未发起查询）
    expect(telemetry.series).toHaveBeenCalledTimes(1);
  });

  it('列表/记录空态脸「暂无冷链档案/暂无记录」（丁-3 锚点⑤）', async () => {
    vi.mocked(coldChain.page).mockResolvedValue({
      content: [],
      page: '0',
      size: '20',
      total: '0',
    });
    const wrapper = mount(ColdChainView);
    await flushPromises();
    // 列表空态脸：主句=暂无冷链档案 + 说明给下一步（零默认纸箱插画）
    const listEmpty = wrapper.find('.fuy-card .fuy-empty');
    expect(listEmpty.exists()).toBe(true);
    expect(listEmpty.find('.fuy-empty-title').text()).toBe('暂无冷链档案');
    expect(listEmpty.find('.fuy-empty-hint').text()).toContain('筛选');
    wrapper.unmount();

    // 记录空态脸：有档案无记录 → 抽屉内主句=暂无记录（恢复默认档案清单后重挂）
    vi.mocked(coldChain.page).mockResolvedValue({
      content: [archiveMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    const withRecordsWrapper = mount(ColdChainView);
    await flushPromises();
    await openDetailDrawer(withRecordsWrapper);
    const recordEmpty = withRecordsWrapper.find('.cold-seg-records .fuy-empty');
    expect(recordEmpty.exists()).toBe(true);
    expect(recordEmpty.find('.fuy-empty-title').text()).toBe('暂无记录');
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(ColdChainView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 ward:coldchain:btn:manage 时建档/登记出网口全隐藏（D-34）', async () => {
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u5' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    // 建档入口（盘点口径不挂码）可开窗读表单，保存出网口无码隐藏
    await clickButton(wrapper, '新建档案');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).not.toContain('保存档案');
    await clickButton(wrapper, '取消');
    await flushPromises();
    // 详情读抽屉的登记出网口同样隐藏（详情入口与查询曲线为读面不挂码）
    await clickRowButton(wrapper, 'CC20260926001', '详情');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).not.toContain('提交登记');
    // 台账读面不受元素码影响
    expect(wrapper.text()).toContain('CC20260926001');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 ward:coldchain:btn:manage 时保存档案与提交登记可见', async () => {
    const wrapper = mountView();
    await flushPromises();
    await clickButton(wrapper, '新建档案');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).toContain('保存档案');
    await clickButton(wrapper, '取消');
    await flushPromises();
    await clickRowButton(wrapper, 'CC20260926001', '详情');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).toContain('提交登记');
    wrapper.unmount();
  });
});

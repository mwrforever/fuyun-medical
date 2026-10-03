// 药房住院摆药页单测（/pharmacy/inpatient-dispense，PR-3 Task 16）：路由权限点 meta 登记
// 断言（路由=权限点清单）、五列看板渲染锚点（列名/行卡计划号/患者号面/给药时点/类型标签/
// PIVAS 排批）、五步按钮状态机（CREATED→摆药开始/PICKING→药师核对/PICKED→出库交接/
// CHECKED 配送半步与签收分位/DELIVERED→退药）、deliver 不迁状态钉死（重拉后以服务端回包
// 为准）、行内动作在途互斥守卫、贴签弹窗数据面、医嘱检索审方状态与生成计划前置。api mock
// 承载零出网（vi.mock 整模块替身），不打真实网络；断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createDispenseReturn, dispensePlans, reviewTasks } from '@/api/pharmacy';
import type { DispensePlanVO, ReviewTaskVO } from '@/api/pharmacy';
import { router } from '@/router';
import InpatientDispenseView from './InpatientDispenseView.vue';

vi.mock('@/api/pharmacy', () => ({
  REVIEW_TASK_STATUS_OPTIONS: [
    { code: 'PENDING', label: '待审' },
    { code: 'APPROVED', label: '已通过' },
    { code: 'REJECTED', label: '已驳回' },
  ],
  DISPENSE_PLAN_COLUMNS: [
    { status: 'CREATED', label: '待摆药' },
    { status: 'PICKING', label: '摆药中' },
    { status: 'PICKED', label: '已配待核对' },
    { status: 'CHECKED', label: '已核对待交接' },
    { status: 'DELIVERED', label: '病区已签收' },
  ],
  DISPENSE_PLAN_TYPE_LABELS: { SINGLE_DOSE: '单剂量', PIVAS: 'PIVAS', WHOLE: '整包' },
  searchDrugs: vi.fn(),
  createDrug: vi.fn(),
  updateDrug: vi.fn(),
  mapInsurance: vi.fn(),
  createPrescription: vi.fn(),
  listPrescriptions: vi.fn(),
  cancelPrescription: vi.fn(),
  listDispenses: vi.fn(),
  pickDispense: vi.fn(),
  verifyDispense: vi.fn(),
  issueDispense: vi.fn(),
  createDispenseReturn: vi.fn(),
  listMedicationOccupancy: vi.fn(),
  reviewTasks: { list: vi.fn(), approve: vi.fn(), reject: vi.fn() },
  dispensePlans: {
    list: vi.fn(),
    generate: vi.fn(),
    pick: vi.fn(),
    verify: vi.fn(),
    issue: vi.fn(),
    deliver: vi.fn(),
    receive: vi.fn(),
    label: vi.fn(),
  },
}));

vi.mock('@/api/nursing', () => ({
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
}));

// 仅替身 ElMessage（提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

// jsdom 未实现 ResizeObserver：el-table/弹窗布局测量依赖（存量 spec 同款空壳）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 摆药计划行（DispensePlanVO 契约形状；visitId 沿用 I+13 位冻结口径） */
function planMock(partial: Partial<DispensePlanVO> = {}): DispensePlanVO {
  return {
    id: '9101',
    planNo: 'DP2026100200001',
    m04OrderNo: 'MO2026100200001',
    visitId: 'I2026092300001',
    patientId: '1932000000000000001',
    wardId: 'W01',
    planType: 'SINGLE_DOSE',
    planTime: '2026-10-03T08:00:00',
    status: 'CREATED',
    pivasBatchNo: undefined,
    labelPrinted: false,
    ...partial,
  };
}

/** 审方任务行（医嘱检索区回显源） */
function reviewMock(partial: Partial<ReviewTaskVO> = {}): ReviewTaskVO {
  return {
    id: '9001',
    m04OrderNo: 'MO2026100200001',
    visitId: 'I2026092300001',
    patientId: '1932000000000000001',
    freqCode: 'bid',
    items: '阿司匹林肠溶片 100mg×30',
    applyDept: 'W01',
    applyDoctor: 'D001',
    status: 'APPROVED',
    pharmacistId: undefined,
    opinion: undefined,
    ...partial,
  };
}

/** 空摆药计划分页出参（page/size/total 由后端 long→string 全局口径） */
function emptyPlans() {
  return { content: [], page: '0', size: '200', total: '0' };
}

/** 按按钮文案点击（el-button 通用） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('药房住院摆药页', () => {
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    for (const fn of [
      dispensePlans.list,
      dispensePlans.generate,
      dispensePlans.pick,
      dispensePlans.verify,
      dispensePlans.issue,
      dispensePlans.deliver,
      dispensePlans.receive,
      dispensePlans.label,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(reviewTasks.list).mockReset().mockResolvedValue({
      content: [],
      page: '0',
      size: '100',
      total: '0',
    });
    vi.mocked(createDispenseReturn).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(dispensePlans.list).mockResolvedValue(emptyPlans());
  });

  it('路由登记权限点 pharmacy:dispense:inpatient（路由=权限点清单审计形态）', () => {
    expect(router.resolve('/pharmacy/inpatient-dispense').meta.permission).toBe(
      'pharmacy:dispense:inpatient',
    );
  });

  it('五列看板渲染锚点：列名/行卡计划号/患者号面/给药时点/类型标签/PIVAS 排批', async () => {
    vi.mocked(dispensePlans.list).mockResolvedValue({
      content: [
        planMock({
          planNo: 'DP2026100200001',
          planType: 'SINGLE_DOSE',
          status: 'CREATED',
        }),
        planMock({
          planNo: 'DP2026100200002',
          planType: 'PIVAS',
          status: 'PICKING',
          pivasBatchNo: 'DPB20261002001',
        }),
        planMock({ planNo: 'DP2026100200003', planType: 'WHOLE', status: 'PICKED' }),
      ],
      page: '0',
      size: '200',
      total: '3',
    });
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 五列列名（spec 冻结渲染序）
    const headers = wrapper.findAll('.plan-column-title').map((node) => node.text());
    expect(headers).toEqual(['待摆药', '摆药中', '已配待核对', '已核对待交接', '病区已签收']);
    // 行卡锚点：计划号 + 患者号面（visitId/patientId）+ 给药时点
    expect(wrapper.findAll('.plan-card')).toHaveLength(3);
    expect(wrapper.find('.plan-card-no').text()).toContain('DP2026100200001');
    expect(wrapper.find('.plan-card-patient').text()).toContain('I2026092300001');
    expect(wrapper.find('.plan-card-patient').text()).toContain('1932000000000000001');
    expect(wrapper.find('.plan-card-plan').text()).toContain('10-03 08:00');
    // 类型标签三值词表 + PIVAS 排批号回显
    expect(wrapper.text()).toContain('单剂量');
    expect(wrapper.text()).toContain('PIVAS');
    expect(wrapper.text()).toContain('整包');
    expect(wrapper.text()).toContain('DPB20261002001');
    wrapper.unmount();
  });

  it('五步按钮状态机：行内按状态出下一动作（deliver/receive 按 issuedAt 分位，DELIVERED 出退药）', async () => {
    vi.mocked(dispensePlans.list).mockResolvedValue({
      content: [
        planMock({ planNo: 'DP1', status: 'CREATED' }),
        planMock({ planNo: 'DP2', status: 'PICKING' }),
        planMock({ planNo: 'DP3', status: 'PICKED' }),
        planMock({ planNo: 'DP4', status: 'CHECKED', issuedAt: undefined }),
        planMock({
          planNo: 'DP5',
          status: 'CHECKED',
          issuedAt: '2026-10-03T09:00:00',
        }),
        planMock({
          planNo: 'DP6',
          status: 'DELIVERED',
          planType: 'PIVAS',
          pivasBatchNo: 'DPB20261002001',
        }),
      ],
      page: '0',
      size: '200',
      total: '6',
    });
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 各行动作按钮集（列序+列内行序即 findAll 序）
    const actionTexts = wrapper
      .findAll('.plan-card')
      .map((card) => card.findAll('.plan-card-actions button').map((b) => b.text()));
    expect(actionTexts).toEqual([
      ['摆药开始'], // CREATED
      ['药师核对'], // PICKING
      ['出库交接'], // PICKED
      ['配送交接'], // CHECKED 未配送（issuedAt 空）
      ['病区签收'], // CHECKED 已配送（issuedAt 非空）
      ['贴签', '退药'], // DELIVERED（PIVAS 行附贴签查看）
    ]);
    wrapper.unmount();
  });

  it('deliver 不迁状态钉死：配送交接成功后看板重拉，行状态以服务端回包为准（仍 CHECKED 未配送）', async () => {
    const checkedRow = planMock({ planNo: 'DP4', status: 'CHECKED', issuedAt: undefined });
    vi.mocked(dispensePlans.list).mockResolvedValue({
      content: [checkedRow],
      page: '0',
      size: '200',
      total: '1',
    });
    vi.mocked(dispensePlans.deliver).mockResolvedValue(undefined);
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    await clickButton(wrapper, '配送交接');
    await flushPromises();
    // 配送交接已出网；看板初载+操作后重拉=2 次，重拉回包仍 CHECKED（issuedAt 空）——
    // 前端不本地迁移状态（deliver 为 CHECKED 态内时间线半步，签收才迁 DELIVERED）
    expect(dispensePlans.deliver).toHaveBeenCalledWith('DP4');
    expect(dispensePlans.list).toHaveBeenCalledTimes(2);
    const actions = wrapper.findAll('.plan-card-actions button').map((b) => b.text());
    expect(actions).toEqual(['配送交接']);
    expect(actions).not.toContain('病区签收');
    wrapper.unmount();
  });

  it('行内动作在途互斥：摆药开始在途期间第二击零出网，复位后可续发', async () => {
    vi.mocked(dispensePlans.list).mockResolvedValue({
      content: [planMock({ planNo: 'DP1', status: 'CREATED' })],
      page: '0',
      size: '200',
      total: '1',
    });
    // 手动闸门：控制 pick 在途窗口
    let releasePick: (() => void) | undefined;
    vi.mocked(dispensePlans.pick).mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          releasePick = resolve;
        }),
    );
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    await clickButton(wrapper, '摆药开始');
    // 在途窗口内重复点击：零出网（双击防重复受理）
    await clickButton(wrapper, '摆药开始');
    expect(dispensePlans.pick).toHaveBeenCalledTimes(1);
    releasePick?.();
    await flushPromises();
    // 复位后按钮恢复可用，可继续下一动作
    await clickButton(wrapper, '摆药开始');
    expect(dispensePlans.pick).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('PIVAS 行贴签弹窗：label 数据面出网渲染排批号与药品明细', async () => {
    vi.mocked(dispensePlans.list).mockResolvedValue({
      content: [
        planMock({
          planNo: 'DP6',
          status: 'DELIVERED',
          planType: 'PIVAS',
          pivasBatchNo: 'DPB20261002001',
        }),
      ],
      page: '0',
      size: '200',
      total: '1',
    });
    vi.mocked(dispensePlans.label).mockResolvedValue({
      planNo: 'DP6',
      m04OrderNo: 'MO2026100200001',
      patientId: '1932000000000000001',
      patientName: '王*明',
      visitId: 'I2026092300001',
      wardId: 'W01',
      // 床位无 pharmacy 侧数据源，后端恒 null 以 null 承载留衔接注记——生成物类型为
      // string|undefined，spec mock 以缺省承载同语义（展示即所得，渲染占位「—」）
      pivasBatchNo: 'DPB20261002001',
      planTime: '2026-10-03T08:00:00',
      pickedBy: '1001',
      verifiedBy: '1002',
      items: [
        {
          itemCode: 'X001',
          itemName: '0.9% 氯化钠注射液',
          dosage: '250',
          unit: 'ml',
          route: '静脉滴注',
          quantity: '1',
        },
      ],
    });
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    await clickButton(wrapper, '贴签');
    await flushPromises();
    // 贴签数据面：计划号出网锚 + 脱敏患者名 + 排批号 + 药品明细（床号无数据源恒空——展示即所得）
    expect(dispensePlans.label).toHaveBeenCalledWith('DP6');
    const openDialog = wrapper
      .findAllComponents({ name: 'ElDialog' })
      .find((dialog) => dialog.props('modelValue') === true);
    expect(openDialog).toBeDefined();
    expect(wrapper.text()).toContain('王*明');
    expect(wrapper.text()).toContain('DPB20261002001');
    expect(wrapper.text()).toContain('0.9% 氯化钠注射液');
    expect(wrapper.text()).toContain('静脉滴注');
    wrapper.unmount();
  });

  it('医嘱检索审方状态展示：检索出网并回显已通过状态与药品明细', async () => {
    vi.mocked(reviewTasks.list).mockResolvedValue({
      content: [reviewMock()],
      page: '0',
      size: '100',
      total: '1',
    });
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    await wrapper.find('input[placeholder="住院医嘱号"]').setValue('MO2026100200001');
    await clickButton(wrapper, '检索审方');
    await flushPromises();
    expect(reviewTasks.list).toHaveBeenCalledWith({ page: 0, size: 100 });
    expect(wrapper.find('.plan-review-state').text()).toContain('MO2026100200001');
    expect(wrapper.find('.plan-review-state').text()).toContain('已通过');
    expect(wrapper.text()).toContain('阿司匹林肠溶片 100mg×30');
    wrapper.unmount();
  });

  it('生成摆药计划：审方通过前置后出网 generate 并重拉看板；未审方通过零出网拦截', async () => {
    vi.mocked(dispensePlans.generate).mockResolvedValue([
      planMock({ planNo: 'DP2026100200001' }),
      planMock({ planNo: 'DP2026100200002', planTime: '2026-10-04T16:00:00' }),
    ]);
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 未检索审方状态：生成按钮禁用，点击零出网
    const generateButton = wrapper.findAll('button').find((b) => b.text() === '生成摆药计划');
    expect(generateButton).toBeDefined();
    expect(generateButton?.attributes('disabled')).toBeDefined();
    expect(dispensePlans.generate).not.toHaveBeenCalled();
    // 检索已通过医嘱后生成：出网携医嘱号+目标病区，成功提示条数并重拉看板
    vi.mocked(reviewTasks.list).mockResolvedValue({
      content: [reviewMock()],
      page: '0',
      size: '100',
      total: '1',
    });
    await wrapper.find('input[placeholder="住院医嘱号"]').setValue('MO2026100200001');
    await clickButton(wrapper, '检索审方');
    await flushPromises();
    await clickButton(wrapper, '生成摆药计划');
    await flushPromises();
    expect(dispensePlans.generate).toHaveBeenCalledWith({
      m04OrderNo: 'MO2026100200001',
      wardId: 'W01',
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalledWith(expect.stringContaining('2'));
    // 看板初载 + 生成后重拉 = 2 次
    expect(dispensePlans.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('病区签收弹窗：签收人工号必填数字校验零出网，合法出网 receive 并重拉', async () => {
    vi.mocked(dispensePlans.list).mockResolvedValue({
      content: [
        planMock({
          planNo: 'DP5',
          status: 'CHECKED',
          issuedAt: '2026-10-03T09:00:00',
        }),
      ],
      page: '0',
      size: '200',
      total: '1',
    });
    vi.mocked(dispensePlans.receive).mockResolvedValue(undefined);
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    await clickButton(wrapper, '病区签收');
    await flushPromises();
    // 工号留空提交：显式校验拦截（零出网）
    await clickButton(wrapper, '确认签收');
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalled();
    expect(dispensePlans.receive).not.toHaveBeenCalled();
    // 合法工号提交：出网携 receivedBy（string 契约承载 Long）
    await wrapper.find('.plan-receive-input').setValue('1001');
    await clickButton(wrapper, '确认签收');
    await flushPromises();
    expect(dispensePlans.receive).toHaveBeenCalledWith('DP5', { receivedBy: '1001' });
    expect(dispensePlans.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('退药弹窗：DELIVERED 行调 dispense-returns 住院扩展形态（dispensePlanNo+returnLines）', async () => {
    vi.mocked(dispensePlans.list).mockResolvedValue({
      content: [
        planMock({
          planNo: 'DP6',
          status: 'DELIVERED',
          planType: 'PIVAS',
          pivasBatchNo: 'DPB20261002001',
        }),
      ],
      page: '0',
      size: '200',
      total: '1',
    });
    vi.mocked(createDispenseReturn).mockResolvedValue(undefined);
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    await clickButton(wrapper, '退药');
    await flushPromises();
    // 明细序号留空提交：显式校验拦截（零出网）
    await clickButton(wrapper, '提交退药');
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalled();
    expect(createDispenseReturn).not.toHaveBeenCalled();
    // 合法录入提交：住院扩展形态出网（nursing 侧退药开关校验端点未建，直调 pharmacy 端点）
    await wrapper.find('.plan-return-seq').setValue('1');
    await wrapper.find('.plan-return-qty').setValue('2');
    await wrapper.find('.plan-return-trace').setValue('');
    await clickButton(wrapper, '提交退药');
    await flushPromises();
    expect(createDispenseReturn).toHaveBeenCalledWith({
      dispensePlanNo: 'DP6',
      returnLines: [{ itemSeq: '1', returnQuantity: '2', traceCodes: [] }],
    });
    expect(dispensePlans.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('空看板渲染空态（暂无摆药计划）', async () => {
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.text()).toContain('暂无摆药计划');
    wrapper.unmount();
  });
});

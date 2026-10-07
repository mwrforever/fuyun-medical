// 药房住院摆药页单测（/pharmacy/inpatient-dispense，PR-3 Task 16）：路由权限点 meta 登记
// 断言（路由=权限点清单）、五列看板渲染锚点（列名/行卡计划号/患者号面/给药时点/类型标签/
// PIVAS 排批）、五步按钮状态机（CREATED→摆药开始/PICKING→药师核对/PICKED→出库交接/
// CHECKED 配送半步与签收分位/DELIVERED→退药）、deliver 不迁状态钉死（重拉后以服务端回包
// 为准）、行内动作在途互斥守卫、贴签弹窗数据面、医嘱检索审方状态与生成计划前置、病区
// 签收弹窗零手输（W-72：签收人=会话身份展示回显，receivedBy 携会话 userId 出网、服务端
// 一律以令牌身份落值；会话缺身份零出网拦截）、退药弹窗多行化（W-66：打开拉 returnable
// 可退明细读面、逐行数量正则（小数位≤3，评审 C-F1——DECIMAL(12,3) 精度对齐）+可退净量
// 双验 D-8、缺行零出网前置拦截、换行旧回包丢弃
// EX-45 同族）。api mock 承载零出网（vi.mock 整模块替身），不打真实网络；断言业务结果
// 不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createDispenseReturn, dispensePlans, reviewTasks } from '@/api/pharmacy';
import type { DispensePlanReturnableVO, DispensePlanVO, ReviewTaskVO } from '@/api/pharmacy';
import { permDirective } from '@/directives/perm';
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
    returnable: vi.fn(),
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

/**
 * 可退明细读面回包（DispensePlanReturnableVO 契约形状；默认两行 NORMAL 明细
 * itemSeq '1'/'2'、可退净量 '3'/'2'——W-66 多行化弹窗数据源）。
 */
function returnableMock(partial: Partial<DispensePlanReturnableVO> = {}): DispensePlanReturnableVO {
  return {
    planNo: 'DP6',
    dispenseNo: 'DR2026100300001',
    dispenseStatus: 'DELIVERED',
    patientId: '1932000000000000001',
    visitId: 'I2026092300001',
    wardId: 'W01',
    items: [
      {
        itemSeq: '1',
        itemCode: 'X001',
        batchNo: 'B20260901',
        issuedQty: '3',
        returnedQty: '0',
        returnableQty: '3',
      },
      {
        itemSeq: '2',
        itemCode: 'X002',
        batchNo: 'B20260902',
        issuedQty: '2',
        returnedQty: '0',
        returnableQty: '2',
      },
    ],
    ...partial,
  };
}

/**
 * 会话种子（auth store 从 sessionStorage 恢复：签收人=登录用户 u1/王药师——PdaView.spec
 * seedAuthSession 同款形态；userId 置空用于「会话缺身份」拦截分支）；permissions 含
 * #20/#21 两元素码（PR-4F：行内动作经 rowActions hasPerm 清单过滤承载，真实 PHARMACIST
 * 会话经登录契约导出含码，既有用例行内按钮照常出位、断言语义不变）。
 */
function seedAuthSession(userId = 'u1', displayName = '王药师'): void {
  sessionStorage.setItem(
    'fy:workstation:auth',
    JSON.stringify({
      token: 'test-token',
      refreshToken: 'test-refresh',
      user: {
        userId,
        loginName: 'pharmacistdemo',
        displayName,
        orgId: null,
        roles: ['pharmacist'],
        permissions: ['pharmacy:dispense:btn:plan', 'pharmacy:dispense:btn:return'],
      },
    }),
  );
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
    seedAuthSession();
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
      dispensePlans.returnable,
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

  it('病区签收弹窗零手输：展示当前登录人，确认即出网 receivedBy=会话 userId 并重拉；会话缺身份零出网拦截', async () => {
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
    // 零手输：弹窗无工号输入面，签收人以会话身份展示回显（displayName+userId）
    expect(wrapper.find('.plan-receive-input').exists()).toBe(false);
    expect(wrapper.find('input[aria-label="签收人工号"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('王药师');
    expect(wrapper.text()).toContain('u1');
    expect(wrapper.text()).toContain('当前登录人');
    // 确认即出网：receivedBy=会话 userId（兼容保留字段，服务端一律以令牌身份落值 W-72）
    await clickButton(wrapper, '确认签收');
    await flushPromises();
    expect(dispensePlans.receive).toHaveBeenCalledWith('DP5', { receivedBy: 'u1' });
    expect(dispensePlans.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
    // 会话缺身份：确认签收显式拦截零出网（会话 userId 空=无签收主体）
    seedAuthSession('');
    vi.mocked(dispensePlans.receive).mockClear();
    const barePinia = createPinia();
    setActivePinia(barePinia);
    const bareWrapper = mount(InpatientDispenseView, { global: { plugins: [barePinia] } });
    await flushPromises();
    await clickButton(bareWrapper, '病区签收');
    await clickButton(bareWrapper, '确认签收');
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '会话缺少签收人身份，无法签收（请重新登录后再试）',
    );
    expect(dispensePlans.receive).not.toHaveBeenCalled();
    bareWrapper.unmount();
  });

  it('退药弹窗多行化：打开拉取可退明细，逐行录入后整单提交 returnLines（缺行零出网拦截）', async () => {
    vi.mocked(dispensePlans.list).mockResolvedValue({
      content: [planMock({ planNo: 'DP6', status: 'DELIVERED' })],
      page: '0',
      size: '200',
      total: '1',
    });
    vi.mocked(dispensePlans.returnable).mockResolvedValue(returnableMock());
    vi.mocked(createDispenseReturn).mockResolvedValue(undefined);
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    await clickButton(wrapper, '退药');
    await flushPromises();
    // 打开即拉可退明细读面（W-66 多行化数据源——DELIVERED 计划的 NORMAL 明细）
    expect(dispensePlans.returnable).toHaveBeenCalledWith('DP6');
    // 两行数量录入面（aria-label 含行锚 itemSeq）
    const qtyInputs = wrapper.findAll('.plan-return-qty');
    expect(qtyInputs).toHaveLength(2);
    expect(qtyInputs[0].attributes('aria-label')).toBe('明细 1 退药数量');
    expect(qtyInputs[1].attributes('aria-label')).toBe('明细 2 退药数量');
    // 仅填一行提交：缺行零出网前置拦截（全 NORMAL 行逐行交代——后端缺行守卫 400 的前端对齐）
    await qtyInputs[0].setValue('1');
    await clickButton(wrapper, '提交退药');
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '明细 2 退药数量须为不超过可退净量 2 的正数',
    );
    expect(createDispenseReturn).not.toHaveBeenCalled();
    // 全填合法值：整单出网恰一次，逐行 returnLines + 追溯码恒空集（住院采集为空，输入面已删）
    await qtyInputs[1].setValue('2');
    await clickButton(wrapper, '提交退药');
    await flushPromises();
    expect(createDispenseReturn).toHaveBeenCalledTimes(1);
    expect(createDispenseReturn).toHaveBeenCalledWith({
      dispensePlanNo: 'DP6',
      returnLines: [
        { itemSeq: '1', returnQuantity: '1', traceCodes: [] },
        { itemSeq: '2', returnQuantity: '2', traceCodes: [] },
      ],
    });
    // 受理成功后重拉看板（初载+重拉=2 次）
    expect(dispensePlans.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('D-8 数量校验收口：负数/科学计数/Infinity/4 位小数/超可退净量形态零出网拒绝', async () => {
    vi.mocked(dispensePlans.list).mockResolvedValue({
      content: [planMock({ planNo: 'DP6', status: 'DELIVERED' })],
      page: '0',
      size: '200',
      total: '1',
    });
    vi.mocked(dispensePlans.returnable).mockResolvedValue(
      // 单行明细（可退净量 3）：五非法形态逐次录入提交，均须拦在出网前
      returnableMock({
        items: [
          {
            itemSeq: '1',
            itemCode: 'X001',
            batchNo: 'B20260901',
            issuedQty: '3',
            returnedQty: '0',
            returnableQty: '3',
          },
        ],
      }),
    );
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    await clickButton(wrapper, '退药');
    await flushPromises();
    const qtyInput = wrapper.find('.plan-return-qty');
    // '-2'/'1e3'/'Infinity' 败于数字正则，'0.1234' 败于小数位≤3 正则（评审 C-F1——DECIMAL(12,3)
    // 精度对齐，后端 parseReturnQuantity scale 守卫同口径），'9.9' 过正则但超可退净量 3——五形态全拦
    for (const badQty of ['-2', '1e3', 'Infinity', '0.1234', '9.9']) {
      await qtyInput.setValue(badQty);
      await clickButton(wrapper, '提交退药');
    }
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '明细 1 退药数量须为不超过可退净量 3 的正数',
    );
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledTimes(5);
    // 零出网：受理端点零调用、看板仅初载一次
    expect(createDispenseReturn).not.toHaveBeenCalled();
    expect(dispensePlans.list).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('退药弹窗竞态：换行打开旧读面回包丢弃（EX-45 同族）', async () => {
    vi.mocked(dispensePlans.list).mockResolvedValue({
      content: [
        planMock({ planNo: 'DPA', status: 'DELIVERED' }),
        planMock({ planNo: 'DPB', status: 'DELIVERED' }),
      ],
      page: '0',
      size: '200',
      total: '2',
    });
    const aData = returnableMock({
      planNo: 'DPA',
      items: [
        {
          itemSeq: '1',
          itemCode: 'XA001',
          batchNo: 'BA20260901',
          issuedQty: '3',
          returnedQty: '0',
          returnableQty: '3',
        },
      ],
    });
    const bData = returnableMock({
      planNo: 'DPB',
      items: [
        {
          itemSeq: '1',
          itemCode: 'XB001',
          batchNo: 'BB20260902',
          issuedQty: '1',
          returnedQty: '0',
          returnableQty: '1',
        },
      ],
    });
    // 手动闸门：A 行读面慢回包，B 行即回（两行编码互异可判串台）
    let releaseA: (() => void) | undefined;
    vi.mocked(dispensePlans.returnable).mockImplementation((no: string) => {
      if (no === 'DPA') {
        return new Promise<DispensePlanReturnableVO>((resolve) => {
          releaseA = () => resolve(aData);
        });
      }
      return Promise.resolve(bData);
    });
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    const returnButtons = wrapper.findAll('button').filter((b) => b.text() === '退药');
    expect(returnButtons).toHaveLength(2);
    // 打开 A 行退药弹窗（读面在途），期间换开 B 行（读面即回落值）
    await returnButtons[0].trigger('click');
    await returnButtons[1].trigger('click');
    await flushPromises();
    expect(dispensePlans.returnable).toHaveBeenCalledWith('DPA');
    expect(dispensePlans.returnable).toHaveBeenCalledWith('DPB');
    // B 弹窗落 B 行明细，A 行明细不得串台
    expect(wrapper.text()).toContain('XB001');
    expect(wrapper.text()).not.toContain('XA001');
    // A 行旧回包后到：换行已过，丢弃不得落值 B 弹窗
    releaseA?.();
    await flushPromises();
    expect(wrapper.text()).toContain('XB001');
    expect(wrapper.text()).not.toContain('XA001');
    wrapper.unmount();
  });

  it('空看板渲染空态（暂无摆药计划）', async () => {
    const wrapper = mount(InpatientDispenseView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.text()).toContain('暂无摆药计划');
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(InpatientDispenseView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 #20 时摆药五步与生成/签收隐藏而退药入口不受扰（双码互不干扰）', async () => {
    vi.mocked(dispensePlans.list).mockResolvedValue({
      content: [
        // CREATED 行（摆药开始）+ DELIVERED 行（退药）+ PIVAS 行（贴签——纯读不挂码）
        planMock({ planNo: 'DP1', status: 'CREATED' }),
        planMock({ planNo: 'DP6', status: 'DELIVERED' }),
        planMock({ planNo: 'DP7', planType: 'PIVAS', status: 'CREATED' }),
      ],
      page: '0',
      size: '200',
      total: '3',
    });
    // 仅含退药码 #21 的会话：摆药族（#20）行内动作与生成/签收入口全隐藏
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: {
          userId: 'u1',
          loginName: 'pharmacistdemo',
          displayName: '王药师',
          orgId: null,
          roles: ['pharmacist'],
          permissions: ['pharmacy:dispense:btn:return'],
        },
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    // #20 族：行内「摆药开始」经 rowActions 清单过滤隐藏；「生成摆药计划」经 v-perm 移除
    expect(buttonTexts).not.toContain('摆药开始');
    expect(buttonTexts).not.toContain('生成摆药计划');
    // #21 族退药入口不受 #20 缺码影响（DELIVERED 行照常出位）
    expect(buttonTexts).toContain('退药');
    // 贴签为纯读数据面（附件 A 元素列未列）不挂码，保持可见
    expect(buttonTexts).toContain('贴签');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 #20/#21 时摆药/退药动作与生成入口全量出位', async () => {
    vi.mocked(dispensePlans.list).mockResolvedValue({
      content: [
        planMock({ planNo: 'DP1', status: 'CREATED' }),
        planMock({ planNo: 'DP6', status: 'DELIVERED' }),
      ],
      page: '0',
      size: '200',
      total: '2',
    });
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('摆药开始');
    expect(buttonTexts).toContain('生成摆药计划');
    expect(buttonTexts).toContain('退药');
    wrapper.unmount();
  });
});

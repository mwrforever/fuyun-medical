// 住院医生站单测（/inpatient/station，M04 FU-M04-03/04 前端面 · 暖纸卷宗换脸重排 乙-3）：
// 门牌页首三锚与 ⑦.4 根节点禁 stagger、中列拆两卡结构（锚点①：医嘱列表卡在前 + 医嘱开立卡
// 在后，卡头「成组 N 行」计数副注）、频次下拉真数据三态（锚点②：listDictItems('medication.frequency')
// 请求路径断言 + ORDER_FREQUENCY_OPTIONS 假常量清零反向断言——inpatient mock 故意不导出该常量，
// 页面仍可完整挂载即机器证据）、钤印双文案（锚点③：用药「已开立（待药师审）」/非用药「已开立」
// 分叉，v-if 挂载制零常驻泄漏）、过敏/欠费/护理级别三标识双通道（锚点④）、IP-1011 类前置校验
// 文案冻结（锚点⑤）、患者列表 >200 行分组阈值（⑨.4 分组分页路径，病区常规 <60 不触发）。
// 既有业务断言全数保留；随换脸重写的断言（成功反馈 ElMessage→钤印、空态文案 G6 脸化）逐处留痕。
// api mock 承载零出网（vi.mock('@/api/inpatient')/@/api/nursing/@/api/system 整模块替身），
// 断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { orders, visits } from '@/api/inpatient';
import type { MedicalOrderVO } from '@/api/inpatient';
import { wardPatients } from '@/api/nursing';
import type { WardPatientDetailVO, WardPatientVO } from '@/api/nursing';
import { listDictItems, listOrgs } from '@/api/system';
import type { DictItemVO, OrgVO } from '@/api/system';
import { permDirective } from '@/directives/perm';
import DoctorStationView from './DoctorStationView.vue';

// 假常量清零反向断言载体：本 mock 不导出 WARD_OPTIONS 与 ORDER_FREQUENCY_OPTIONS（G4 清零，
// 本页零消费）——页面若仍消费任一常量，装配期即 undefined 解引用失败，全部用例红。
vi.mock('@/api/inpatient', () => ({
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
  ORDER_STATUS_LABELS: {
    CREATED: '已开立待审',
    AUDITED: '审核通过',
    AUDIT_REJECTED: '审核驳回',
    TRANSFERRED: '已转抄',
    EXECUTING: '执行中',
    COMPLETED: '已完成',
    CANCELLED: '已作废',
    STOPPED: '已停嘱',
  },
  MEDICATION_ORDER_TYPES: new Set(['DRUG', 'DISCHARGE_MED']),
  TRACE_STAGE_LABELS: {
    ORDERED: '开立',
    AUDIT: '审核',
    TRANSFER: '转抄',
    PLAN: '执行',
    STATUS: '状态迁移',
  },
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

vi.mock('@/api/nursing', () => ({
  wardPatients: {
    register: vi.fn(),
    list: vi.fn(),
    detail: vi.fn(),
    remove: vi.fn(),
  },
}));

// 病区清单与频次字典真数据源替身（G4：WARD_OPTIONS/ORDER_FREQUENCY_OPTIONS 假常量随清零退场）
vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
  listDictItems: vi.fn(),
}));

// 仅替身 ElMessage（校验拦截警告断言用），其余导出原样保留供组件解析
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

/** 病区清单种子（listOrgs WARD 档出参；选项=「名称（编码）」渲染口径的载体） */
const WARD_SEED: OrgVO[] = [
  { id: '1', orgCode: 'W01', orgName: 'W01 演示病区', orgType: 'WARD', sort: 1 },
];

/** 用药频次字典种子（listDictItems('medication.frequency') 出参；条目与 V607 迁移同源子集） */
const FREQ_SEED: DictItemVO[] = [
  { itemCode: 'qd', itemName: '每日一次', sort: 1 },
  { itemCode: 'bid', itemName: '每日两次', sort: 2 },
  { itemCode: 'tid', itemName: '每日三次', sort: 3 },
  { itemCode: 'qid', itemName: '每日四次', sort: 4 },
  { itemCode: 'qn', itemName: '每晚一次', sort: 5 },
  { itemCode: 'prn', itemName: '必要时', sort: 6 },
  { itemCode: 'st', itemName: '立即', sort: 7 },
];

/** 在院患者行（病区一览；护理级别/床号可覆写） */
function wardPatientMock(partial: Partial<WardPatientVO> = {}): WardPatientVO {
  return {
    visitId: 'I2026092500001',
    patientId: '1932000000000000001',
    wardId: 'W01',
    bedNo: '01',
    nursingLevel: 'NORMAL',
    admittedAt: '2026-09-25T09:00:00+08:00',
    ...partial,
  };
}

/** 患者详情卡（过敏标识懒加载数据源；allergyFlag 可覆写） */
function wardDetailMock(partial: Partial<WardPatientDetailVO> = {}): WardPatientDetailVO {
  return {
    wardId: 'W01',
    visitId: 'I2026092500001',
    patientId: '1932000000000000001',
    patientName: '张*',
    gender: '男',
    age: 35,
    nursingLevel: 'NORMAL',
    allergyFlag: false,
    allergies: [],
    admittedAt: '2026-09-25T09:00:00+08:00',
    ...partial,
  };
}

/** 医嘱分页行（状态/类型可覆写） */
function orderMock(partial: Partial<MedicalOrderVO> = {}): MedicalOrderVO {
  return {
    orderNo: 'MO2026092500001',
    visitId: 'I2026092500001',
    patientId: '1932000000000000001',
    orderType: 'DRUG',
    orderClass: 'STAT',
    standbyFlag: false,
    groupNo: 'MO2026092500001',
    freqCode: undefined,
    beginAt: undefined,
    endAt: undefined,
    doctorId: 'D001',
    orderedAt: '2026-09-25T10:00:00+08:00',
    stopReason: undefined,
    status: 'CREATED',
    ...partial,
  };
}

/** 空医嘱分页出参（page/size/total 由后端 long→string 全局口径） */
function emptyOrders() {
  return { content: [], page: '0', size: '20', total: '0' };
}

/** 按按钮文案点击（el-button 通用） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('住院医生站', () => {
  /** 文件级 Pinia：v-perm 指令与批注行会话身份（auth store）读取，每用例新实例防串扰 */
  let pinia: Pinia;

  /** 会话种子（auth store 从 sessionStorage 恢复）：含本页元素码——真实 DOCTOR 会话经登录
   * 契约导出含码，既有用例按钮保留、断言语义不变（D-21 申报规范，评审 D-I1 全局指令补齐） */
  function seedAuthSession(): void {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: { userId: 'u1', permissions: ['inpatient:station:btn:order'] },
      }),
    );
  }

  /** 统一挂载入口：pinia + v-perm 指令（main.ts 全局注册仅应用装配态，单测自备）——
   * 页面批注行消费 auth 会话身份后全用例经此挂载 */
  function mountView() {
    return mount(DoctorStationView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  beforeEach(() => {
    for (const fn of [
      orders.create,
      orders.list,
      orders.trace,
      visits.arrears,
      wardPatients.list,
      wardPatients.detail,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(listOrgs).mockReset();
    vi.mocked(listDictItems).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链（病区/频次真数据三态默认成功态种子）
    vi.mocked(listOrgs).mockResolvedValue(WARD_SEED);
    vi.mocked(listDictItems).mockResolvedValue(FREQ_SEED);
    vi.mocked(wardPatients.list).mockResolvedValue([]);
    vi.mocked(wardPatients.detail).mockResolvedValue(wardDetailMock());
    vi.mocked(visits.arrears).mockResolvedValue([]);
    vi.mocked(orders.list).mockResolvedValue(emptyOrders());
    vi.mocked(orders.trace).mockResolvedValue({
      orderNo: 'MO2026092500001',
      visitId: 'I2026092500001',
      orderType: 'DRUG',
      orderClass: 'STAT',
      status: 'CREATED',
      entries: [],
    });
    pinia = createPinia();
    setActivePinia(pinia);
    seedAuthSession();
  });

  it('门牌页首三锚：衬线标题「住院医生站」+ INPATIENT DESK 小注 + 开立人批注行，根节点禁挂 stagger（契约 ⑦.4）', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 根挂 .fuy-page 且不携带 .fuy-stagger（stagger 挂页内 .station-flow 区块，⑦.4）
    const root = wrapper.find('.fuy-page');
    expect(root.exists()).toBe(true);
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(wrapper.find('.fuy-stagger.station-flow').exists()).toBe(true);
    // 门牌三锚：衬线标题 + 英文小注（装饰层）+ 批注行（开立人 谁 · 时刻 · 用药类待药师审口径）
    expect(wrapper.find('.fuy-page-title').text()).toBe('住院医生站');
    expect(wrapper.find('.fuy-page-en').text()).toBe('INPATIENT DESK');
    const note = wrapper.find('.fuy-page-note').text();
    expect(note).toContain('开立人');
    expect(note).toContain('用药类开立后待药师审');
    // 页脚批注（数据时点；空值 — 占位零伪数据）
    expect(wrapper.find('.fuy-page-foot').text()).toContain('数据时点');
    // 病区下拉真数据（G4）：listOrgs WARD 档出网，选项=「名称（编码）」
    expect(listOrgs).toHaveBeenCalledWith({ type: 'WARD' });
    wrapper.unmount();
  });

  it('病区下拉 listOrgs 三态（G4）：在途禁选+骨架，清单到达出选项并拉患者与欠费清单，失败恢复行说问题给重入', async () => {
    // 在途态：病区清单挂起——下拉禁选 + 患者卡内骨架（零患者面出网）
    let releaseWards: (value: OrgVO[]) => void = () => {};
    vi.mocked(listOrgs).mockImplementationOnce(
      () => new Promise<OrgVO[]>((resolve) => (releaseWards = resolve)),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('select[aria-label="病区选择"]').attributes('disabled')).toBeDefined();
    expect(wrapper.find('.station-patient-sk').exists()).toBe(true);
    expect(wardPatients.list).not.toHaveBeenCalled();
    // 就绪态：清单到达回落首项病区，患者与欠费清单携病区维度出网
    releaseWards(WARD_SEED);
    await flushPromises();
    expect(wrapper.find('select[aria-label="病区选择"]').attributes('disabled')).toBeUndefined();
    expect(wardPatients.list).toHaveBeenCalledWith('W01');
    expect(visits.arrears).toHaveBeenCalledWith('W01');
    wrapper.unmount();
    // 失败态：装配期 listOrgs 拒绝——恢复行「说问题给恢复」（不伪装空选项），零患者面出网
    vi.mocked(wardPatients.list).mockClear();
    vi.mocked(visits.arrears).mockClear();
    vi.mocked(listOrgs).mockRejectedValueOnce(new Error('network down'));
    const failed = mountView();
    await flushPromises();
    const errorRow = failed.find('.station-ward-error');
    expect(errorRow.exists()).toBe(true);
    expect(errorRow.text()).toContain('病区清单加载失败');
    expect(wardPatients.list).not.toHaveBeenCalled();
    // 重入：「重新加载」二次出网成功后恢复行消失、患者面就绪
    vi.mocked(listOrgs).mockResolvedValue(WARD_SEED);
    await clickButton(failed, '重新加载');
    await flushPromises();
    expect(failed.find('.station-ward-error').exists()).toBe(false);
    expect(wardPatients.list).toHaveBeenCalledWith('W01');
    expect(visits.arrears).toHaveBeenCalledWith('W01');
    failed.unmount();
  });

  it('在院患者列表加载渲染护理级别与欠费标识（病区维度）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([
      wardPatientMock({ visitId: 'I2026092500001', bedNo: '01' }),
      wardPatientMock({ visitId: 'I2026092500002', bedNo: '02', nursingLevel: 'CRITICAL' }),
    ]);
    vi.mocked(visits.arrears).mockResolvedValue([
      {
        visitId: 'I2026092500002',
        patientName: '李*',
        bedNo: '02',
        flaggedAt: '2026-09-25T08:00:00+08:00',
      },
    ]);
    const wrapper = mountView();
    await flushPromises();
    const text = wrapper.text();
    // 两行在院患者按床位序渲染
    expect(text).toContain('I2026092500001');
    expect(text).toContain('I2026092500002');
    // 欠费标识仅命中欠费行（机器判据类，双通道=文字在位）
    expect(wrapper.findAll('.station-arrears-flag').length).toBe(1);
    // 护理级别徽标渲染（病重护理行，双通道=文字在位）
    expect(text).toContain('病重护理');
    // 出网携病区维度
    expect(wardPatients.list).toHaveBeenCalledWith('W01');
    expect(visits.arrears).toHaveBeenCalledWith('W01');
    wrapper.unmount();
  });

  it('过敏标识懒加载回填：detail allergyFlag=true 行内与中列上下文双通道展示，未查不误标', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([
      wardPatientMock({ visitId: 'I2026092500001', bedNo: '01' }),
      wardPatientMock({ visitId: 'I2026092500002', bedNo: '02' }),
    ]);
    vi.mocked(wardPatients.detail).mockImplementation((visitId: string) =>
      Promise.resolve(wardDetailMock({ visitId, allergyFlag: visitId === 'I2026092500001' })),
    );
    const wrapper = mountView();
    await flushPromises();
    // 未查不误标：初载零过敏旗
    expect(wrapper.findAll('.station-allergy-flag').length).toBe(0);
    // 选中 01 床患者 → detail 懒加载回填 → 左列行内 + 中列上下文双通道
    await wrapper.findAll('.station-patient-row')[0].trigger('click');
    await flushPromises();
    expect(wrapper.findAll('.station-allergy-flag').length).toBe(2);
    expect(wrapper.text()).toContain('过敏');
    wrapper.unmount();
  });

  it('中列拆两卡（乙-3 锚点①）：医嘱列表卡 DOM 序先于医嘱开立卡，开立卡头「成组 N 行」计数副注随行数联动', async () => {
    const wrapper = mountView();
    await flushPromises();
    const midCards = wrapper.findAll('.station-col-mid .fuy-card');
    expect(midCards.length).toBe(2);
    // 卡序机器证据：医嘱列表卡在前（PM 重排裁决：拆两卡取代同卡纵向堆叠）
    expect(midCards[0].classes()).toContain('station-orders-card');
    expect(midCards[1].classes()).toContain('station-create-card');
    expect(midCards[0].find('.fuy-card-title').text()).toBe('在院医嘱');
    expect(midCards[1].find('.fuy-card-title').text()).toBe('医嘱开立');
    // 卡头计数副注：单行=成组 1 行；加一行后联动为成组 2 行
    expect(midCards[1].find('.station-group-count').text()).toContain('成组 1 行');
    await clickButton(wrapper, '加一行（成组）');
    expect(midCards[1].find('.station-group-count').text()).toContain('成组 2 行');
    wrapper.unmount();
  });

  it('开立用药医嘱成功盖钤印「已开立（待药师审）」（乙-3 锚点③用药分叉），出网携明细与数量', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([wardPatientMock()]);
    vi.mocked(orders.create).mockResolvedValue(orderMock({ status: 'CREATED' }));
    const wrapper = mountView();
    await flushPromises();
    // 选中在院患者 → 明细行录入 → 保存开立
    await wrapper.find('.station-patient-row').trigger('click');
    await flushPromises();
    await wrapper.find('.station-item-code').setValue('ASP500');
    await wrapper.find('.station-item-name').setValue('阿司匹林片');
    await wrapper.find('.station-item-dosage').setValue('0.5');
    await wrapper.find('.station-item-unit').setValue('g');
    await wrapper.find('.station-item-route').setValue('PO');
    await wrapper.find('.station-item-quantity').setValue('2');
    await clickButton(wrapper, '保存医嘱');
    await flushPromises();
    expect(orders.create).toHaveBeenCalledWith(
      'I2026092500001',
      expect.objectContaining({
        orderType: 'DRUG',
        orderClass: 'STAT',
        items: [
          expect.objectContaining({
            itemType: 'DRUG',
            itemCode: 'ASP500',
            itemName: '阿司匹林片',
            dosage: '0.5',
            dosageUnit: 'g',
            route: 'PO',
            quantity: 2,
          }),
        ],
      }),
    );
    // 用药类 CREATED 停留=「待药师审」钤印（留痕：旧 ElMessage.success 断言随成功反馈
    // 换约钤印删除——契约 G2「成功反馈=钤印」）
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toContain('已开立（待药师审）');
    // 开立成功后医嘱列表重载（初载+刷新=2 次）
    expect(orders.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('开立检验医嘱（非用药）成功盖钤印「已开立」且不含待药师审，钤印 v-if 挂载制零常驻泄漏', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([wardPatientMock()]);
    vi.mocked(orders.create).mockResolvedValue(orderMock({ orderType: 'LAB', status: 'AUDITED' }));
    const wrapper = mountView();
    await flushPromises();
    // v-if 挂载制机器证据：未盖印前浮层零常驻（防 stagger 误伤常显空朱块）
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    await wrapper.find('.station-patient-row').trigger('click');
    await flushPromises();
    // 切换类型为检验（非用药=系统自动过审面）
    await wrapper.find('select[aria-label="医嘱类型"]').setValue('LAB');
    await wrapper.find('.station-item-code').setValue('LAB-CBC');
    await wrapper.find('.station-item-name').setValue('血常规');
    await wrapper.find('.station-item-quantity').setValue('1');
    await clickButton(wrapper, '保存医嘱');
    await flushPromises();
    expect(orders.create).toHaveBeenCalledWith(
      'I2026092500001',
      expect.objectContaining({
        orderType: 'LAB',
        orderClass: 'STAT',
        items: [expect.objectContaining({ itemType: 'LAB', itemCode: 'LAB-CBC' })],
      }),
    );
    // 非用药分叉：钤印文案不含「待药师审」
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toContain('已开立');
    expect(stamp.text()).not.toContain('待药师审');
    expect(orders.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('医嘱频次真数据三态（乙-3 锚点②）：listDictItems("medication.frequency") 出网，条目真数据落选项', async () => {
    const wrapper = mountView();
    await flushPromises();
    // typeCode 以 V607 迁移登记为准（medication.frequency），非蓝图速记名 ORDER_FREQUENCY
    expect(listDictItems).toHaveBeenCalledWith('medication.frequency');
    // 成功态：切长期后频次可选（未选空项在册），字典条目真数据落选项（itemName 消费口径）
    await wrapper.find('input[aria-label="医嘱分类长期"]').setValue(true);
    const freqSelect = wrapper.find('select[aria-label="医嘱频次"]');
    expect(freqSelect.attributes('disabled')).toBeUndefined();
    const optionTexts = freqSelect.findAll('option').map((option) => option.text());
    expect(optionTexts).toContain('未选择');
    expect(optionTexts).toContain('每日一次');
    expect(optionTexts).toContain('必要时');
    wrapper.unmount();
  });

  it('医嘱频次字典失败或空清单：频次选择禁用（长期开立防盲选，三态之禁用态）', async () => {
    // 失败态：字典拉取出错——频次无可选项必须禁用
    vi.mocked(listDictItems).mockRejectedValue(new Error('dict service down'));
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('input[aria-label="医嘱分类长期"]').setValue(true);
    expect(wrapper.find('select[aria-label="医嘱频次"]').attributes('disabled')).toBeDefined();
    wrapper.unmount();
    // 空清单态：listDictItems 缺席兜底空数组——同失败口径禁用（三态之「无数据」）
    vi.mocked(listDictItems).mockResolvedValue([]);
    const wrapperEmpty = mountView();
    await flushPromises();
    await wrapperEmpty.find('input[aria-label="医嘱分类长期"]').setValue(true);
    expect(wrapperEmpty.find('select[aria-label="医嘱频次"]').attributes('disabled')).toBeDefined();
    wrapperEmpty.unmount();
  });

  it('闭环追溯面板渲染五环节时间线', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([wardPatientMock()]);
    vi.mocked(orders.list).mockResolvedValue({
      content: [orderMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(orders.trace).mockResolvedValue({
      orderNo: 'MO2026092500001',
      visitId: 'I2026092500001',
      orderType: 'DRUG',
      orderClass: 'STAT',
      status: 'TRANSFERRED',
      entries: [
        {
          stage: 'ORDERED',
          operator: 'D001',
          occurredAt: '2026-09-25T10:00:00+08:00',
          result: 'CREATED',
          detail: 'DRUG/STAT',
        },
        {
          stage: 'AUDIT',
          operator: 'SYSTEM',
          occurredAt: '2026-09-25T10:00:01+08:00',
          result: 'PASSED',
          detail: '系统预检通过',
        },
        {
          stage: 'TRANSFER',
          operator: 'N001',
          occurredAt: '2026-09-25T10:05:00+08:00',
          result: 'PASSED',
          detail: '第二核对人=N002',
        },
        {
          stage: 'PLAN',
          operator: 'N001',
          occurredAt: '2026-09-25T11:00:00+08:00',
          result: 'EXECUTED',
          detail: 'PL2026092500001@11:00',
        },
        {
          stage: 'STATUS',
          operator: 'N001',
          occurredAt: '2026-09-25T11:00:01+08:00',
          result: 'TRANSFERRED',
          detail: 'AUDITED→TRANSFERRED',
        },
      ],
    });
    const wrapper = mountView();
    await flushPromises();
    // 先选患者加载医嘱列表 → 中列医嘱行点选 → 右列追溯出网
    await wrapper.find('.station-patient-row').trigger('click');
    await flushPromises();
    await wrapper.find('.station-order-row').trigger('click');
    await flushPromises();
    expect(orders.trace).toHaveBeenCalledWith('MO2026092500001');
    const text = wrapper.text();
    expect(text).toContain('闭环追溯');
    // 五环节逐一渲染（开立→审核→转抄→执行→状态迁移；dot+左缘连线时间线零动画）
    expect(wrapper.findAll('.station-trace-item').length).toBe(5);
    expect(text).toContain('开立');
    expect(text).toContain('审核');
    expect(text).toContain('转抄');
    expect(text).toContain('执行');
    expect(text).toContain('状态迁移');
    wrapper.unmount();
  });

  it('长期医嘱未选频次零出网显式校验拦截（IP-1011 类文案冻结）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([wardPatientMock()]);
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.station-patient-row').trigger('click');
    await flushPromises();
    // 切检验（非用药，免剂量行校验干扰）+ 切长期但不选频次 → 显式校验拦截（零出网）
    await wrapper.find('select[aria-label="医嘱类型"]').setValue('LAB');
    await wrapper.find('input[aria-label="医嘱分类长期"]').setValue(true);
    await wrapper.find('.station-item-code').setValue('ASP500');
    await wrapper.find('.station-item-name').setValue('阿司匹林片');
    await wrapper.find('.station-item-quantity').setValue('2');
    await clickButton(wrapper, '保存医嘱');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(expect.stringContaining('频次'));
    expect(orders.create).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('切换在院患者复位开立草稿明细行与长期频次，未提交医嘱不跨患者续提（FE-A2-02）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([
      wardPatientMock({ visitId: 'I2026092500001', bedNo: '01' }),
      wardPatientMock({ visitId: 'I2026092500002', bedNo: '02' }),
    ]);
    const wrapper = mountView();
    await flushPromises();
    // 选中 01 床患者：留两行成组医嘱草稿 + 长期频次（均不提交）
    await wrapper.find('.station-patient-row').trigger('click');
    await flushPromises();
    await wrapper.find('.station-item-code').setValue('ASP500');
    await wrapper.find('.station-item-name').setValue('阿司匹林片');
    await wrapper.find('.station-item-dosage').setValue('0.5');
    await wrapper.find('.station-item-unit').setValue('g');
    await wrapper.find('.station-item-route').setValue('PO');
    await clickButton(wrapper, '加一行（成组）');
    await wrapper.find('input[aria-label="第2行项目编码"]').setValue('ASP250');
    await wrapper.find('input[aria-label="第2行项目名称"]').setValue('阿司匹林肠溶片');
    await wrapper.find('input[aria-label="医嘱分类长期"]').setValue(true);
    await wrapper.find('select[aria-label="医嘱频次"]').setValue('bid');
    // 切换 02 床患者：草稿明细与频次不得跨患者滞留（续提即开错患者——用药安全风险）
    await wrapper.findAll('.station-patient-row')[1].trigger('click');
    await flushPromises();
    // 明细行归一为 1 行空行（成组草稿清空）
    expect(wrapper.findAll('.station-item-row')).toHaveLength(1);
    expect((wrapper.find('.station-item-code').element as HTMLInputElement).value).toBe('');
    expect((wrapper.find('.station-item-name').element as HTMLInputElement).value).toBe('');
    // 长期频次清空（回「未选择」）
    expect((wrapper.find('select[aria-label="医嘱频次"]').element as HTMLSelectElement).value).toBe(
      '',
    );
    // 行为兜底：空草稿直接保存被显式校验拦截零出网（不给 02 床患者开出 01 床草稿）
    await clickButton(wrapper, '保存医嘱');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('第 1 行项目编码/名称必填');
    expect(orders.create).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('换患者竞态守卫：旧患者医嘱慢回包整包丢弃不覆盖新患者列表（EX-45/FE-A1-03）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([
      wardPatientMock({ visitId: 'I2026092500001', bedNo: '01' }),
      wardPatientMock({ visitId: 'I2026092500002', bedNo: '02' }),
    ]);
    // 旧患者（01 床）医嘱回包挂起至用例放行——复现「旧请求在途时切换患者」竞态窗口
    let releaseOldOrders: (page: unknown) => void = () => {};
    vi.mocked(orders.list).mockImplementationOnce(
      () => new Promise((resolve) => (releaseOldOrders = resolve)) as never,
    );
    // 新患者（02 床）医嘱回包先行落位
    vi.mocked(orders.list).mockResolvedValueOnce({
      content: [orderMock({ orderNo: 'MO-NEW-02', visitId: 'I2026092500002' })],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    // 选中 01 床患者（医嘱请求在途挂起）→ 切换 02 床患者（新请求先行返回）
    await wrapper.findAll('.station-patient-row')[0].trigger('click');
    await flushPromises();
    await wrapper.findAll('.station-patient-row')[1].trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('MO-NEW-02');
    // 旧患者慢回包晚到：过期回包丢弃，不得覆盖 02 床患者的医嘱列表
    releaseOldOrders({
      content: [orderMock({ orderNo: 'MO-OLD-01', visitId: 'I2026092500001' })],
      page: '0',
      size: '20',
      total: '1',
    });
    await flushPromises();
    expect(wrapper.text()).not.toContain('MO-OLD-01');
    expect(wrapper.text()).toContain('MO-NEW-02');
    wrapper.unmount();
  });

  it('病区一览回包缺省判空兜底：rows/arrears 整包缺失兜底空清单不驻留旧数据（EX-45/FE-A1-09）', async () => {
    // 首载正常返回 1 名在院患者（欠费清单走 beforeEach 兜底空）
    vi.mocked(wardPatients.list).mockResolvedValueOnce([wardPatientMock()]);
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('I2026092500001');
    // 刷新遭遇契约外空回包（一览 rows 与欠费 arrears 整包缺失）：兜底空数组渲染空态，
    // 不白屏也不驻留旧清单（旧患者行不得残留误导医护）
    vi.mocked(wardPatients.list).mockResolvedValue(undefined as never);
    vi.mocked(visits.arrears).mockResolvedValue(undefined as never);
    await clickButton(wrapper, '刷新');
    await flushPromises();
    // 留痕：旧断言「当前病区暂无在院患者」随 G6 空态脸化改写——主句=暂无〈业务客体〉语法
    expect(wrapper.text()).toContain('暂无在院患者');
    expect(wrapper.text()).not.toContain('I2026092500001');
    wrapper.unmount();
  });

  it('患者列表分组阈值（⑨.4 分组分页路径）：>200 行按床号段分组签行且默认仅首组展开，常规病区零组头平铺', async () => {
    // 常规态：3 名患者（病区常规 <60）——零组头全行平铺
    vi.mocked(wardPatients.list).mockResolvedValue([
      wardPatientMock({ visitId: 'I2026092500001', bedNo: '01' }),
      wardPatientMock({ visitId: 'I2026092500002', bedNo: '02' }),
      wardPatientMock({ visitId: 'I2026092500003', bedNo: '03' }),
    ]);
    const normal = mountView();
    await flushPromises();
    expect(normal.findAll('.station-patient-group-head').length).toBe(0);
    expect(normal.findAll('.station-patient-row').length).toBe(3);
    normal.unmount();
    // 超阈值态：210 行（床号 01-99 循环）——床号段分组签行出现，默认仅首组展开
    vi.mocked(wardPatients.list).mockResolvedValue(
      Array.from({ length: 210 }, (_, i) =>
        wardPatientMock({
          visitId: `I2026${String(i + 1).padStart(7, '0')}`,
          bedNo: String((i % 99) + 1).padStart(2, '0'),
        }),
      ),
    );
    const wrapper = mountView();
    await flushPromises();
    const heads = wrapper.findAll('.station-patient-group-head');
    expect(heads.length).toBeGreaterThan(1);
    expect(heads[0].text()).toContain('01-10 床');
    const rowsBefore = wrapper.findAll('.station-patient-row').length;
    expect(rowsBefore).toBeLessThan(210);
    // 展开次组：渲染行数增加（组头折叠开关可逐组放行，禁一次全量渲染）
    await heads[1].trigger('click');
    await flushPromises();
    expect(wrapper.findAll('.station-patient-row').length).toBeGreaterThan(rowsBefore);
    wrapper.unmount();
  });

  it('空态脸 G6：暂无在院患者/未选择在院患者/未选择医嘱三处空态，el-empty 纸箱插画清零', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 全页零 el-empty（默认纸箱插画在 .fuy-page 子树全站压制，随脸化清零）
    expect(wrapper.find('.el-empty').exists()).toBe(false);
    const text = wrapper.text();
    expect(text).toContain('暂无在院患者');
    expect(text).toContain('未选择在院患者');
    expect(text).toContain('未选择医嘱');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话无 inpatient:station:btn:order 时保存医嘱按钮不渲染（D-34 无码全隐藏）', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 1 } }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('闭环追溯');
    expect(wrapper.text()).not.toContain('保存医嘱');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 inpatient:station:btn:order 时保存医嘱按钮渲染', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, permissions: ['inpatient:station:btn:order'] },
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.findAll('button').some((b) => b.text() === '保存医嘱')).toBe(true);
    wrapper.unmount();
  });
});

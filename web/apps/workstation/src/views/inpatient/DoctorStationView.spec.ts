// 住院医生站单测（/inpatient/station，M04 FU-M04-03/04 前端面）：在院患者列表加载渲染
// （护理级别/欠费标识，病区维度）、开立用药医嘱出网携「待药师审」提示、开立检验医嘱（非用药）
// 出网、闭环追溯时间线渲染、长期医嘱缺频次显式校验拦截（IP-1011 类零出网）、
// EX-45/FE-A1-03 换患者竞态守卫（旧患者医嘱慢回包整包丢弃）、EX-45/FE-A1-09 病区一览
// 回包缺省判空兜底（rows/arrears 缺失不白屏）。
// api mock 承载零出网（vi.mock('@/api/inpatient')/@/api/nursing 整模块替身），不打真实网络；
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
import { permDirective } from '@/directives/perm';
import DoctorStationView from './DoctorStationView.vue';

vi.mock('@/api/inpatient', () => ({
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
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
  ORDER_FREQUENCY_OPTIONS: [
    { code: 'qd', label: 'qd 每日一次' },
    { code: 'bid', label: 'bid 每日两次' },
  ],
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
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

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
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
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
    const wrapper = mount(DoctorStationView);
    await flushPromises();
    const text = wrapper.text();
    // 两行在院患者按床位序渲染
    expect(text).toContain('I2026092500001');
    expect(text).toContain('I2026092500002');
    // 欠费标识仅命中欠费行（机器判据类）
    expect(wrapper.findAll('.station-arrears-flag').length).toBe(1);
    // 护理级别徽标渲染（病重护理行）
    expect(text).toContain('病重护理');
    // 出网携病区维度
    expect(wardPatients.list).toHaveBeenCalledWith('W01');
    expect(visits.arrears).toHaveBeenCalledWith('W01');
  });

  it('开立用药医嘱出网并提示待药师审（CREATED 停留语义）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([wardPatientMock()]);
    vi.mocked(orders.create).mockResolvedValue(orderMock({ status: 'CREATED' }));
    const wrapper = mount(DoctorStationView);
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
    // 用药类 CREATED 停留=「待药师审」语义提示
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalledWith(expect.stringContaining('待药师审'));
    // 开立成功后医嘱列表重载（初载+刷新=2 次）
    expect(orders.list).toHaveBeenCalledTimes(2);
  });

  it('开立检验医嘱（非用药）出网且不提示待药师审', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([wardPatientMock()]);
    vi.mocked(orders.create).mockResolvedValue(orderMock({ orderType: 'LAB', status: 'AUDITED' }));
    const wrapper = mount(DoctorStationView);
    await flushPromises();
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
    // 汇集全部成功提示文案（ElMessage 参数为 string，非串形态过滤为空串防误并）
    const successText = vi
      .mocked(ElMessage.success)
      .mock.calls.map((c) => (typeof c[0] === 'string' ? c[0] : ''))
      .join();
    expect(successText).not.toContain('待药师审');
    expect(orders.list).toHaveBeenCalledTimes(2);
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
    const wrapper = mount(DoctorStationView);
    await flushPromises();
    // 先选患者加载医嘱列表 → 中列医嘱行点选 → 右栏追溯出网
    await wrapper.find('.station-patient-row').trigger('click');
    await flushPromises();
    await wrapper.find('.station-order-row').trigger('click');
    await flushPromises();
    expect(orders.trace).toHaveBeenCalledWith('MO2026092500001');
    const text = wrapper.text();
    expect(text).toContain('闭环追溯');
    // 五环节逐一渲染（开立→审核→转抄→执行→状态迁移）
    expect(wrapper.findAll('.station-trace-item').length).toBe(5);
    expect(text).toContain('开立');
    expect(text).toContain('审核');
    expect(text).toContain('转抄');
    expect(text).toContain('执行');
    expect(text).toContain('状态迁移');
  });

  it('长期医嘱未选频次零出网显式校验拦截（IP-1011 类）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([wardPatientMock()]);
    const wrapper = mount(DoctorStationView);
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
  });

  it('切换在院患者复位开立草稿明细行与长期频次，未提交医嘱不跨患者续提（FE-A2-02）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([
      wardPatientMock({ visitId: 'I2026092500001', bedNo: '01' }),
      wardPatientMock({ visitId: 'I2026092500002', bedNo: '02' }),
    ]);
    const wrapper = mount(DoctorStationView);
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
    const wrapper = mount(DoctorStationView);
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
  });

  it('病区一览回包缺省判空兜底：rows/arrears 整包缺失兜底空清单不驻留旧数据（EX-45/FE-A1-09）', async () => {
    // 首载正常返回 1 名在院患者（欠费清单走 beforeEach 兜底空）
    vi.mocked(wardPatients.list).mockResolvedValueOnce([wardPatientMock()]);
    const wrapper = mount(DoctorStationView);
    await flushPromises();
    expect(wrapper.text()).toContain('I2026092500001');
    // 刷新遭遇契约外空回包（一览 rows 与欠费 arrears 整包缺失）：兜底空数组渲染空态，
    // 不白屏也不驻留旧清单（旧患者行不得残留误导医护）
    vi.mocked(wardPatients.list).mockResolvedValue(undefined as never);
    vi.mocked(visits.arrears).mockResolvedValue(undefined as never);
    await clickButton(wrapper, '刷新');
    await flushPromises();
    expect(wrapper.text()).toContain('当前病区暂无在院患者');
    expect(wrapper.text()).not.toContain('I2026092500001');
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(DoctorStationView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

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

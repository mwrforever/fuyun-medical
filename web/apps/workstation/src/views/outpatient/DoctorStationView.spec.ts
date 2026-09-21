// 门诊医生站页单测（FU-M03-05/06 前端面）：渲染断言（页头医生名/三栏标题/空态）、接诊链
// （confirm 带患者摘要 → admit 出网 → 上下文与在诊单据回显）、开单数量显式整数校验（W-22⑦：
// 非整数置回并 4xx 口径提示，拦截零出网）、诊毕门禁（去向+在途单据确认勾选未齐禁用，danger
// 确认弹窗后出网）、诊毕在途守卫（W-22⑥：慢响应窗口二次点击零出网）。
// api mock 承载，不打真实网络；会话经 sessionStorage 种子恢复（出诊医生=登录用户）。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElInputNumber, ElMessage, ElMessageBox } from 'element-plus';
import {
  admitVisit,
  createOrder,
  finishVisit,
  listOrdersByVisit,
  listPatientQueue,
  openPrescription,
} from '@/api/outpatient';
import type { ClinicOrderVO, DoctorQueueItemVO, VisitVO } from '@/api/outpatient';
import DoctorStationView from './DoctorStationView.vue';

vi.mock('@/api/outpatient', () => ({
  /** 诊毕去向八项常量（视图从 api 层消费的 V705 词表前端清单） */
  DISPOSITION_OPTIONS: [
    { code: 'DISCHARGE_HOME', label: '医嘱离院' },
    { code: 'TRANSFER_HOSPITAL', label: '医嘱转院' },
    { code: 'TRANSFER_COMMUNITY', label: '医嘱转社区' },
    { code: 'NON_MEDICAL_LEAVE', label: '非医嘱离院' },
    { code: 'DEATH', label: '死亡' },
    { code: 'OBSERVATION', label: '急诊留观' },
    { code: 'TRANSFER_INPATIENT', label: '急诊转住院' },
    { code: 'OTHER', label: '其他' },
  ],
  admitVisit: vi.fn(),
  createOrder: vi.fn(),
  finishVisit: vi.fn(),
  listOrdersByVisit: vi.fn(),
  listPatientQueue: vi.fn(),
  openPrescription: vi.fn(),
}));

// 仅替身 ElMessage/ElMessageBox（提示与确认断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: { ...mod.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') },
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

/** 按按钮文案点击 el-button */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

/** 按按钮文案定位 el-button 包装（在途/禁用断言用） */
function findButton(wrapper: VueWrapper, text: string): DOMWrapper<Element> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  return button;
}

/** 会话种子（auth store 从 sessionStorage 恢复：出诊医生=登录用户 u1/张三） */
function seedAuthSession(): void {
  sessionStorage.setItem(
    'fy:workstation:auth',
    JSON.stringify({
      token: 'test-token',
      refreshToken: 'test-refresh',
      user: {
        userId: 'u1',
        loginName: 'doctordemo',
        displayName: '张三',
        orgId: null,
        roles: ['doctor'],
      },
    }),
  );
}

function queueRowMock(partial: Partial<DoctorQueueItemVO> = {}): DoctorQueueItemVO {
  return {
    ticketId: '900',
    visitId: 'O2026092100001',
    ticketNo: 'A007',
    patientName: '张*',
    ticketType: 'FIRST',
    priorityScore: 320,
    status: 'WAITING',
    allergyFlag: false,
    queueTime: new Date(Date.now() - 10 * 60000).toISOString(),
    calledCount: 0,
    ...partial,
  };
}

function visitMock(): VisitVO {
  return {
    id: '701',
    visitId: 'O2026092100001',
    patientId: '1932000000000000001',
    deptCode: 'DEPT-INT',
    doctorId: 'u1',
    visitType: 'GENERAL',
    status: 'IN_CONSULT',
  };
}

function orderMock(): ClinicOrderVO {
  return {
    id: '801',
    orderNo: 'CO-1',
    visitId: 'O2026092100001',
    orderType: 'EXAM',
    status: 'CREATED',
    // 生成物 Item schema 同名坍缩（pharmacy Item 与 ClinicOrderVO.Item 共名）：按生成物类型
    // 以 drugId 键承载；后端运行时实发 itemCode 形态——契约缝登记 Task 13 报告偏差清单
    items: [{ drugId: 'EX001', quantity: '1' }],
  };
}

/** 完成接诊前置（选行 → 确认弹窗放行 → admit 出网成功） */
async function admitFirstRow(wrapper: VueWrapper): Promise<void> {
  await wrapper.findAll('.doctor-station-queue-row')[0].trigger('click');
  await clickButton(wrapper, '接诊');
  await flushPromises();
}

describe('门诊医生站', () => {
  /** 文件级 Pinia：组件读取 auth 会话 store（出诊医生身份），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    vi.mocked(admitVisit).mockReset();
    vi.mocked(createOrder).mockReset();
    vi.mocked(finishVisit).mockReset();
    vi.mocked(listOrdersByVisit).mockReset();
    vi.mocked(listPatientQueue).mockReset();
    vi.mocked(openPrescription).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    seedAuthSession();
    // 队列/单据兜底空：防未 stub 的 resolve 断链
    vi.mocked(listPatientQueue).mockResolvedValue([]);
    vi.mocked(listOrdersByVisit).mockResolvedValue([]);
  });

  it('渲染断言：页头出诊医生名、三栏标题与接诊空态齐备', async () => {
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.text()).toContain('门诊医生站');
    expect(wrapper.text()).toContain('出诊医生：张三');
    expect(wrapper.text()).toContain('候诊列表');
    expect(wrapper.text()).toContain('开检查检验单');
    expect(wrapper.text()).toContain('开处方');
    expect(wrapper.text()).toContain('诊毕');
    expect(wrapper.text()).toContain('当前诊区暂无候诊患者');
    wrapper.unmount();
  });

  it('接诊链：confirm 带患者摘要 → admit 出网 → 上下文与在诊单据回显', async () => {
    vi.mocked(listPatientQueue).mockResolvedValue([queueRowMock()]);
    vi.mocked(admitVisit).mockResolvedValue(visitMock());
    vi.mocked(listOrdersByVisit).mockResolvedValue([orderMock()]);
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();

    await admitFirstRow(wrapper);

    expect(vi.mocked(ElMessageBox.confirm)).toHaveBeenCalledWith(
      '即将接诊 A007 张*，确认？',
      '接诊确认',
    );
    expect(vi.mocked(admitVisit)).toHaveBeenCalledWith('O2026092100001');
    expect(vi.mocked(listOrdersByVisit)).toHaveBeenCalledWith({ visitId: 'O2026092100001' });
    // 渲染断言：上下文卡（就诊号）与在诊单据行（单据号/类型中文词表）
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('O2026092100001');
    });
    expect(wrapper.text()).toContain('CO-1');
    expect(wrapper.text()).toContain('检查');
    expect(wrapper.text()).toContain('已开立');
    wrapper.unmount();
  });

  it('开单数量显式整数校验：非整数置回 1 并 4xx 口径提示，拦截零出网（W-22⑦）', async () => {
    vi.mocked(listPatientQueue).mockResolvedValue([queueRowMock()]);
    vi.mocked(admitVisit).mockResolvedValue(visitMock());
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    await admitFirstRow(wrapper);

    // 展开开单表单并填项目编码
    await clickButton(wrapper, '展开');
    const codeInput = wrapper.find('input[placeholder="物价库项目 code"]');
    await codeInput.setValue('EX001');
    // 数量键入小数：v-model 更新后触发 change → 显式校验置回并提示
    const numberInput = wrapper.findComponent(ElInputNumber).find('input');
    await numberInput.setValue('2.5');
    wrapper.findComponent(ElInputNumber).vm.$emit('change', 2.5);
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('数量应为正整数，请核对后重试');

    await clickButton(wrapper, '提交开单');
    await flushPromises();
    // 数量已被置回 1：出网数量为整数串 1（校验前置生效，非法值零出网语义由置回承载）
    expect(vi.mocked(createOrder)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(createOrder)).toHaveBeenCalledWith('O2026092100001', {
      orderType: 'EXAM',
      items: [{ itemCode: 'EX001', quantity: '1' }],
    });
    wrapper.unmount();
  });

  it('诊毕门禁：去向与在途单据确认未齐时按钮禁用，确认弹窗带回显摘要后出网', async () => {
    vi.mocked(listPatientQueue).mockResolvedValue([queueRowMock()]);
    vi.mocked(admitVisit).mockResolvedValue(visitMock());
    vi.mocked(listOrdersByVisit).mockResolvedValue([orderMock()]);
    vi.mocked(finishVisit).mockResolvedValue({ ...visitMock(), status: 'FINISHED' });
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    await admitFirstRow(wrapper);

    // 未选去向 + 未勾确认：诊毕禁用
    expect(findButton(wrapper, '诊毕').attributes('disabled')).toBeDefined();

    // 勾选在途单据确认（在途 1 笔）后仍禁用（去向未选）
    wrapper.findComponent({ name: 'ElCheckbox' }).vm.$emit('update:modelValue', true);
    expect(findButton(wrapper, '诊毕').attributes('disabled')).toBeDefined();

    // 选择去向（emit 回填 v-model，口径同存量 spec 的 select 替身）
    const selects = wrapper.findAllComponents({ name: 'ElSelect' });
    // 诊毕去向是页面最后一个 ElSelect（前两个属开单/开方卡）
    const dispositionSelect = selects.at(-1);
    await dispositionSelect?.vm.$emit('update:modelValue', 'DISCHARGE_HOME');
    await flushPromises();
    expect(findButton(wrapper, '诊毕').attributes('disabled')).toBeUndefined();

    await clickButton(wrapper, '诊毕');
    await flushPromises();
    expect(vi.mocked(ElMessageBox.confirm)).toHaveBeenCalledWith(
      '即将完成就诊 O2026092100001（去向：医嘱离院），诊毕后不可恢复，确认？',
      '诊毕确认',
      expect.objectContaining({ confirmButtonText: '确认诊毕' }),
    );
    expect(vi.mocked(finishVisit)).toHaveBeenCalledWith('O2026092100001', {
      disposition: 'DISCHARGE_HOME',
      explicitConfirm: true,
    });
    wrapper.unmount();
  });

  it('诊毕在途：按钮禁用且二次点击零出网，结束后复位（W-22⑥ 防抖）', async () => {
    vi.mocked(listPatientQueue).mockResolvedValue([queueRowMock()]);
    vi.mocked(admitVisit).mockResolvedValue(visitMock());
    let releaseFinish: () => void = () => {};
    vi.mocked(finishVisit).mockImplementation(
      () =>
        new Promise<VisitVO>((resolve) => {
          releaseFinish = () => resolve({ ...visitMock(), status: 'FINISHED' });
        }),
    );
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    await admitFirstRow(wrapper);

    // 齐门禁：勾选确认（无在途单据时勾选可先行）+ 选去向
    wrapper.findComponent({ name: 'ElCheckbox' }).vm.$emit('update:modelValue', true);
    const selects = wrapper.findAllComponents({ name: 'ElSelect' });
    // 诊毕去向是页面最后一个 ElSelect（前两个属开单/开方卡）
    const dispositionSelect = selects.at(-1);
    await dispositionSelect?.vm.$emit('update:modelValue', 'DISCHARGE_HOME');
    await flushPromises();

    await clickButton(wrapper, '诊毕');
    await flushPromises();
    expect(findButton(wrapper, '诊毕').attributes('disabled')).toBeDefined();
    // 在途窗口内二次点击：入口守卫零第二次出网
    await clickButton(wrapper, '诊毕');
    await flushPromises();
    expect(vi.mocked(finishVisit)).toHaveBeenCalledTimes(1);

    releaseFinish();
    await flushPromises();
    wrapper.unmount();
  });

  it('开方出网：经门诊衔接端点携 OUTPATIENT 词表与数量串（数量校验同 W-22⑦ 口径）', async () => {
    vi.mocked(listPatientQueue).mockResolvedValue([queueRowMock()]);
    vi.mocked(admitVisit).mockResolvedValue(visitMock());
    vi.mocked(openPrescription).mockResolvedValue({
      id: '802',
      orderNo: 'CO-2',
      visitId: 'O2026092100001',
      orderType: 'RX_REF',
      extRef: 'RX-1',
      status: 'CREATED',
      items: [],
    });
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    await admitFirstRow(wrapper);

    // 第二个「展开」按钮属于开处方卡
    const expandButtons = wrapper.findAll('button').filter((b) => b.text() === '展开');
    await expandButtons[1].trigger('click');
    await wrapper.find('input[placeholder="药品字典 drugId"]').setValue('3001');
    await clickButton(wrapper, '提交开方');
    await flushPromises();

    expect(vi.mocked(openPrescription)).toHaveBeenCalledWith('O2026092100001', {
      rxType: 'OUTPATIENT',
      skinTestRequired: false,
      items: [{ drugId: '3001', quantity: '1', frequency: undefined }],
    });
    wrapper.unmount();
  });
});

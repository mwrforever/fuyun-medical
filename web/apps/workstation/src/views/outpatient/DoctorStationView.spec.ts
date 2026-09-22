// 门诊医生站页单测（FU-M03-05/06 前端面）：渲染断言（页头医生名/三栏标题/空态）、接诊链
// （confirm 带患者摘要 → admit 出网 → 上下文与在诊单据回显）、开单数量显式整数校验（W-22⑦：
// 非整数置回并 4xx 口径提示，拦截零出网）、诊毕门禁（去向+在途单据确认勾选未齐禁用，danger
// 确认弹窗后出网）、诊毕在途守卫（W-22⑥：慢响应窗口二次点击零出网）、处方引用发药状态镜像
// 两态呈现（W-29 D-3：已发药 tag 词表 / 未回流「未发药」）。
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
    triageLevel: 3,
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
    // D-4 坍缩修复（W-29）：ClinicOrderItem 分立注册后生成物回归 itemCode 权威形态，与后端
    // 运行时实发一致，workaround 不再需要
    items: [{ itemCode: 'EX001', quantity: '1' }],
  };
}

/** 完成接诊前置（选行 → 确认弹窗放行 → admit 出网成功） */
async function admitFirstRow(wrapper: VueWrapper): Promise<void> {
  await wrapper.findAll('.doctor-station-queue-row')[0].trigger('click');
  await clickButton(wrapper, '接诊');
  await flushPromises();
}

/** 诊毕去向选择（页面最后一个 ElSelect，emit 回填 v-model——口径同存量 spec 的 select 替身） */
async function selectDisposition(wrapper: VueWrapper, value: string): Promise<void> {
  const dispositionSelect = wrapper.findAllComponents({ name: 'ElSelect' }).at(-1);
  await dispositionSelect?.vm.$emit('update:modelValue', value);
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
    // 渲染断言：中列卡头=页面锚点（§8.3）——大号 visit 标识 + 分诊级别徽标（VisitVO.triageLevel=3）
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('O2026092100001');
    });
    const visitIdAnchor = wrapper.find('.doctor-station-context-head .doctor-station-visit-id');
    expect(visitIdAnchor.exists()).toBe(true);
    expect(visitIdAnchor.text()).toBe('O2026092100001');
    const levelBadge = wrapper.find('.doctor-station-context-head .fuy-triage-badge--l3');
    expect(levelBadge.exists()).toBe(true);
    expect(levelBadge.text()).toBe('Ⅲ级');
    // 在诊单据行（单据号/类型中文词表）
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

  it('诊毕门禁（有在途单据）：去向已选未勾选确认被禁，真实勾选后放行出网（§8.3 语义）', async () => {
    vi.mocked(listPatientQueue).mockResolvedValue([queueRowMock()]);
    vi.mocked(admitVisit).mockResolvedValue(visitMock());
    vi.mocked(listOrdersByVisit).mockResolvedValue([orderMock()]);
    vi.mocked(finishVisit).mockResolvedValue({ ...visitMock(), status: 'FINISHED' });
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    await admitFirstRow(wrapper);

    // 未选去向 + 未勾确认：诊毕禁用
    expect(findButton(wrapper, '诊毕').attributes('disabled')).toBeDefined();

    // 选择去向（emit 回填 v-model）后仍禁用——在途 1 笔未勾选显式确认（§8.3 门禁语义）
    await selectDisposition(wrapper, 'DISCHARGE_HOME');
    expect(findButton(wrapper, '诊毕').attributes('disabled')).toBeDefined();

    // 真实 DOM 点击路径勾选确认（在途单据存在时勾选框启用，jsdom 原生 input change）
    await wrapper.find('.el-checkbox input').setValue(true);
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

  it('诊毕门禁（纯问诊无在途单据）：勾选框禁用但无需确认即可诊毕（canFinish 短路）', async () => {
    vi.mocked(listPatientQueue).mockResolvedValue([queueRowMock()]);
    vi.mocked(admitVisit).mockResolvedValue(visitMock());
    // listOrdersByVisit 兜底空（beforeEach）：纯问诊无任何单据——门诊最常见路径
    vi.mocked(finishVisit).mockResolvedValue({ ...visitMock(), status: 'FINISHED' });
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    await admitFirstRow(wrapper);

    // 无在途单据：勾选框禁用（无可确认项）且文案承载「无需确认」语义
    expect(wrapper.find('.el-checkbox').classes()).toContain('is-disabled');
    expect(wrapper.text()).toContain('无在途单据，无需确认');

    // 仅选去向（不勾选）→ 诊毕即可提交（无单据时勾选不构成门禁，R1 死锁修复回归锚）
    await selectDisposition(wrapper, 'DISCHARGE_HOME');
    await flushPromises();
    expect(findButton(wrapper, '诊毕').attributes('disabled')).toBeUndefined();

    await clickButton(wrapper, '诊毕');
    await flushPromises();
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

    // 纯问诊路径齐门禁：仅选去向（无在途单据，无需勾选确认）
    await selectDisposition(wrapper, 'DISCHARGE_HOME');

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

  it('处方引用发药状态镜像：已发药渲染 tag 词表，未回流显示未发药（W-29 D-3 消费面）', async () => {
    vi.mocked(listPatientQueue).mockResolvedValue([queueRowMock()]);
    vi.mocked(admitVisit).mockResolvedValue(visitMock());
    vi.mocked(listOrdersByVisit).mockResolvedValue([
      {
        ...orderMock(),
        id: '801',
        orderType: 'RX_REF',
        extRef: 'RX-1',
        status: 'COMPLETED',
        dispenseStatus: 'DISPENSED',
      },
      { ...orderMock(), id: '802', orderType: 'RX_REF', extRef: 'RX-2', status: 'COMPLETED' },
    ]);
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    await admitFirstRow(wrapper);

    // 切到处方引用 Tab（发药状态列呈现面）
    const rxTab = wrapper.findAll('.el-tabs__item').find((node) => node.text() === '处方引用');
    await rxTab?.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('RX-1');
    // 有镜像值：tag 承载词表文案（DISPENSED→已发药）
    const dispensedTag = wrapper.findAll('.el-tag').find((node) => node.text() === '已发药');
    expect(dispensedTag).toBeDefined();
    // 空镜像：未发生发药回流，「未发药」纯文本承载初始语义
    expect(wrapper.text()).toContain('RX-2');
    expect(wrapper.text()).toContain('未发药');
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

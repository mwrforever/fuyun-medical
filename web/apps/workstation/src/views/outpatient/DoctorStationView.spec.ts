// 门诊医生站页单测（FU-M03-05/06 前端面 · 暖纸卷宗换脸重排，逐页蓝图 P06）：渲染断言
// （门牌页首衬线标题/批注行出诊医生、三栏标题、未选诊区引导空态）、诊区真数据三态
// （listOrgs DEPT 出网 / 未选前置拦截零出网 / 选中携码拉队列）、接诊链（confirm 带患者摘要 →
// admit 出网 → 上下文与在诊单据回显 + 门牌接诊中胶囊）、重排结构锚（诊毕收口条升格中列底部
// 与在途单据同卡——旧「右列三卡」断言随本次结构性重排全数删除，走回归红线出口四边界：
// 单点单次 / 严格度不降 / 原子同 PR / CHANGELOG 留痕）、开单数量显式整数校验（W-22⑦：非整数
// 置回并 4xx 口径提示，拦截零出网）、诊毕门禁（去向+在途单据确认勾选未齐禁用，danger 确认弹窗
// 后出网；去向词表来自 V705 门诊字典真数据 outpatient.disposition）、诊毕在途守卫（W-22⑥：
// 慢响应窗口二次点击零出网）、处方引用发药状态镜像两态呈现（W-29 D-3）。
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
import { listDictItems, listOrgs } from '@/api/system';
import type { DictItemVO, OrgVO } from '@/api/system';
import { permDirective } from '@/directives/perm';
import DoctorStationView from './DoctorStationView.vue';

vi.mock('@/api/outpatient', () => ({
  admitVisit: vi.fn(),
  createOrder: vi.fn(),
  finishVisit: vi.fn(),
  listOrdersByVisit: vi.fn(),
  listPatientQueue: vi.fn(),
  openPrescription: vi.fn(),
}));

// 诊区清单与字典条目真数据端点替身（假常量清零后本页唯二 system 消费面）
vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
  listDictItems: vi.fn(),
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

/** 会话种子（auth store 从 sessionStorage 恢复：出诊医生=登录用户 u1/张三；
 * permissions 含本页元素码——真实 DOCTOR 会话经登录契约导出含码，既有用例按钮保留、
 * 断言语义不变，D-21 申报规范，评审 D-I1 全局指令补齐） */
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
        permissions: ['outpatient:doctor:btn:admit', 'outpatient:doctor:btn:order'],
      },
    }),
  );
}

/** 诊区清单替身（OrgVO 生成物形态：orgCode 出网值与既有 DEPT-INT 队列定位口径对齐） */
function deptMock(): OrgVO[] {
  return [{ orgCode: 'DEPT-INT', orgName: '内科门诊', orgType: 'DEPT', sort: 1 }];
}

/** 离院去向字典条目替身（V705 门诊字典种子 disposition 八项真数据镜像） */
function dispositionDictMock(): DictItemVO[] {
  return [
    { itemCode: 'DISCHARGE_HOME', itemName: '医嘱离院', sort: 1 },
    { itemCode: 'TRANSFER_HOSPITAL', itemName: '医嘱转院', sort: 2 },
    { itemCode: 'TRANSFER_COMMUNITY', itemName: '医嘱转社区', sort: 3 },
    { itemCode: 'NON_MEDICAL_LEAVE', itemName: '非医嘱离院', sort: 4 },
    { itemCode: 'DEATH', itemName: '死亡', sort: 5 },
    { itemCode: 'OBSERVATION', itemName: '急诊留观', sort: 6 },
    { itemCode: 'TRANSFER_INPATIENT', itemName: '急诊转住院', sort: 7 },
    { itemCode: 'OTHER', itemName: '其他', sort: 8 },
  ];
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

/** 完成接诊前置（选诊区 → 队列出网 → 选行 → 确认弹窗放行 → admit 出网成功；未选诊区
 * 前置拦截零出网，故接诊链路必先经卡头下拉选定诊区） */
async function admitFirstRow(wrapper: VueWrapper): Promise<void> {
  await selectDept(wrapper, 'DEPT-INT');
  await wrapper.findAll('.doctor-station-queue-row')[0].trigger('click');
  await clickButton(wrapper, '接诊');
  await flushPromises();
}

/** 诊区选择（卡头 ElSelect，emit 回填 v-model 并触发 change——口径同存量 spec 的 select 替身） */
async function selectDept(wrapper: VueWrapper, value: string): Promise<void> {
  const deptSelect = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((node) => node.classes().includes('doctor-station-dept'));
  await deptSelect?.vm.$emit('update:modelValue', value);
  await deptSelect?.vm.$emit('change', value);
  await flushPromises();
}

/** 诊毕去向选择（收口条 ElSelect，按挂类精确定位——三栏重排后不再是页面最后一个 select） */
async function selectDisposition(wrapper: VueWrapper, value: string): Promise<void> {
  const dispositionSelect = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((node) => node.classes().includes('doctor-station-disposition-select'));
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
    vi.mocked(listOrgs).mockReset();
    vi.mocked(listDictItems).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    seedAuthSession();
    // 队列/单据/诊区/字典兜底真数据：防未 stub 的 resolve 断链（字典=V705 八项，诊区=内科门诊）
    vi.mocked(listPatientQueue).mockResolvedValue([]);
    vi.mocked(listOrdersByVisit).mockResolvedValue([]);
    vi.mocked(listOrgs).mockResolvedValue(deptMock());
    vi.mocked(listDictItems).mockResolvedValue(dispositionDictMock());
  });

  it('渲染断言：门牌页首衬线标题与批注行、三栏标题、未选诊区引导空态齐备', async () => {
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 门牌页首（契约 ⑧.1）：.fuy-page-head + 衬线主标题 + 「谁·何时」批注行（出诊医生=会话真值，
    // 诊区未选以 — 占位零伪数据）
    expect(wrapper.find('.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-title').text()).toBe('门诊医生站');
    const note = wrapper.find('.fuy-page-note').text();
    expect(note).toContain('出诊医生 张三');
    expect(note).toContain('诊区 —');
    // 接诊中胶囊：未接诊不显（蓝图 P06.2 状态位语义）
    expect(wrapper.find('.fuy-page-head .fuy-status-pill').exists()).toBe(false);
    // 三栏标题齐备（诊毕收口条随中列显隐，未接诊不渲染——其断言在接诊链与结构锚用例承载）
    expect(wrapper.text()).toContain('候诊列表');
    expect(wrapper.text()).toContain('开检查检验单');
    expect(wrapper.text()).toContain('开处方');
    // 队列空态两态门控：未选诊区给操作指引（选中后空态文案在诊区用例断言）
    expect(wrapper.text()).toContain('请先在卡头选择出诊诊区');
    wrapper.unmount();
  });

  it('诊区真数据三态：挂载出网 DEPT 清单，未选诊区前置拦截零出网，选中后携码拉队列', async () => {
    // 队列兜底空（beforeEach）：选中诊区后断言「选中且无候诊」空态文案
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 挂载即拉诊区真数据（listOrgs DEPT 单源），队列前置拦截不出网（无诊区不发无效请求）
    expect(vi.mocked(listOrgs)).toHaveBeenCalledWith({ type: 'DEPT' });
    expect(vi.mocked(listPatientQueue)).not.toHaveBeenCalled();
    // 选中诊区：deptCode 真数据 code 出网 + 批注行诊区名承接
    await selectDept(wrapper, 'DEPT-INT');
    expect(vi.mocked(listPatientQueue)).toHaveBeenCalledWith({
      deptCode: 'DEPT-INT',
      doctorId: 'u1',
    });
    expect(wrapper.find('.fuy-page-note').text()).toContain('内科门诊');
    // 选中且无候诊：既有空态文案保留（两态门控语义）
    expect(wrapper.text()).toContain('当前诊区暂无候诊患者');
    wrapper.unmount();
  });

  it('接诊链：confirm 带患者摘要 → admit 出网 → 上下文与在诊单据回显 + 门牌接诊中胶囊', async () => {
    vi.mocked(listPatientQueue).mockResolvedValue([queueRowMock()]);
    vi.mocked(admitVisit).mockResolvedValue(visitMock());
    vi.mocked(listOrdersByVisit).mockResolvedValue([orderMock()]);
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 未接诊：门牌无接诊中胶囊
    expect(wrapper.find('.fuy-page-head .fuy-status-pill').exists()).toBe(false);

    await admitFirstRow(wrapper);

    expect(vi.mocked(ElMessageBox.confirm)).toHaveBeenCalledWith(
      '即将接诊 A007 张*，确认？',
      '接诊确认',
    );
    expect(vi.mocked(admitVisit)).toHaveBeenCalledWith('O2026092100001');
    expect(vi.mocked(listOrdersByVisit)).toHaveBeenCalledWith({ visitId: 'O2026092100001' });
    // 门牌接诊中胶囊（蓝图 P06.2 状态位）：success 洗底胶囊承载「接诊中 · 就诊号」
    const pill = wrapper.find('.fuy-page-head .fuy-status-pill--success');
    expect(pill.exists()).toBe(true);
    expect(pill.text()).toContain('接诊中');
    expect(pill.text()).toContain('O2026092100001');
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
    // 在诊单据行（单据号/类型中文词表）+ 卷宗签挂类（契约 ⑤#8：激活签顶部朱砂签线由基础册承载）
    expect(wrapper.find('.fuy-tabs').exists()).toBe(true);
    expect(wrapper.text()).toContain('CO-1');
    expect(wrapper.text()).toContain('检查');
    expect(wrapper.text()).toContain('已开立');
    wrapper.unmount();
  });

  it('重排结构锚：诊毕收口条升格中列底部与在途单据同卡，右列仅存开单开方两折叠卡', async () => {
    vi.mocked(listPatientQueue).mockResolvedValue([queueRowMock()]);
    vi.mocked(admitVisit).mockResolvedValue(visitMock());
    vi.mocked(listOrdersByVisit).mockResolvedValue([orderMock()]);
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    await admitFirstRow(wrapper);

    // 收口条在中列底部（蓝图 P06.2/.3）：与在途单据同卡，「签」分隔线分界
    const midCol = wrapper.find('.doctor-station-mid-col');
    expect(midCol.exists()).toBe(true);
    const finishBar = midCol.find('.doctor-station-finish-bar');
    expect(finishBar.exists()).toBe(true);
    expect(finishBar.find('.fuy-sign-divider').exists()).toBe(true);
    expect(finishBar.text()).toContain('去向');
    // M09 提醒不拦截注记文案保留（随收口条展示）
    expect(finishBar.text()).toContain('M09 门诊电子病历文书校验为提醒不拦截');
    // 在途数徽标迁在诊单据卡头（在途判断依据与单据列表同位）
    expect(midCol.find('.fuy-card-head .doctor-station-ongoing').exists()).toBe(true);
    // 表单挂 .fuy-form 脸（收口条 + 右列两折叠表单，契约 ⑫.4-③）
    expect(wrapper.findAll('.fuy-form').length).toBe(3);
    // 显式 popper 统一挂 fuy-snap-popper（诊区/去向/单据类型/处方类型四下拉）
    const selectPopperClasses = wrapper
      .findAllComponents({ name: 'ElSelect' })
      .map((node) => node.props('popperClass'));
    expect(selectPopperClasses).toHaveLength(4);
    for (const popperClass of selectPopperClasses) {
      expect(popperClass).toBe('fuy-snap-popper');
    }
    // 右列仅存两折叠卡：收口条不回流右列（旧「右列三卡」结构随本次现代化删除）
    const sideCol = wrapper.find('.doctor-station-side-col');
    expect(sideCol.exists()).toBe(true);
    expect(sideCol.text()).toContain('开检查检验单');
    expect(sideCol.text()).toContain('开处方');
    expect(sideCol.find('.doctor-station-finish-bar').exists()).toBe(false);
    // 诊毕 danger 钮随收口条在中列可达
    expect(findButton(wrapper, '诊毕')).toBeDefined();
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
    // 确认弹窗回显「去向：医嘱离院」= V705 字典真数据 label（DISPOSITION 常量清零后的消费证明）
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

  it('DISPOSITION 真字典三态：V705 typeCode 出网，条目真数据落选项在册', async () => {
    vi.mocked(listPatientQueue).mockResolvedValue([queueRowMock()]);
    vi.mocked(admitVisit).mockResolvedValue(visitMock());
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    // typeCode 以 V705 门诊字典种子登记为准（outpatient.disposition），非蓝图速记名
    expect(vi.mocked(listDictItems)).toHaveBeenCalledWith('outpatient.disposition');
    await admitFirstRow(wrapper);
    // 成功态：去向下拉可用（内层 wrapper 无禁用态）且字典条目在册（医嘱离院八项真数据）
    const dispositionWrapper = wrapper.find(
      '.doctor-station-disposition-select .el-select__wrapper',
    );
    expect(dispositionWrapper.exists()).toBe(true);
    expect(dispositionWrapper.classes()).not.toContain('is-disabled');
    const optionTexts = wrapper.findAllComponents({ name: 'ElOption' }).map((node) => node.text());
    expect(optionTexts).toContain('医嘱离院');
    expect(optionTexts).toContain('急诊转住院');
    wrapper.unmount();
  });

  it('DISPOSITION 字典失败或空清单：去向选择禁用、诊毕随 canFinish 禁用（数据态守卫不变）', async () => {
    // 失败态：字典拉取出错——去向无可选项必须禁用，防医生盲选提交非法去向
    vi.mocked(listDictItems).mockRejectedValue(new Error('dict service down'));
    vi.mocked(listPatientQueue).mockResolvedValue([queueRowMock()]);
    vi.mocked(admitVisit).mockResolvedValue(visitMock());
    const wrapper = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    await admitFirstRow(wrapper);
    // EP 2.14 select 禁用态落内层 wrapper（根节点仅 el-select+尺寸档）
    let select = wrapper.find('.doctor-station-disposition-select .el-select__wrapper');
    expect(select.classes()).toContain('is-disabled');
    expect(findButton(wrapper, '诊毕').attributes('disabled')).toBeDefined();
    wrapper.unmount();

    // 空清单态：listDictItems 缺席兜底空数组——同失败口径禁用（三态之「无数据」）
    vi.mocked(listDictItems).mockResolvedValue([]);
    const wrapperEmpty = mount(DoctorStationView, { global: { plugins: [pinia] } });
    await flushPromises();
    await admitFirstRow(wrapperEmpty);
    select = wrapperEmpty.find('.doctor-station-disposition-select .el-select__wrapper');
    expect(select.classes()).toContain('is-disabled');
    expect(findButton(wrapperEmpty, '诊毕').attributes('disabled')).toBeDefined();
    wrapperEmpty.unmount();
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

  /** PR-4F 权限态挂载：注入 v-perm 指令（pinia 已由 beforeEach 激活；main.ts 全局注册仅应用装配态） */
  function mountView() {
    return mount(DoctorStationView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 outpatient:doctor:btn:admit 时接诊按钮不渲染（D-34 无码全隐藏）', async () => {
    // 覆写 beforeEach 种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u1' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('候诊列表');
    // 中列空态文案含「…选择患者并接诊」，以按钮文案精确断言接诊入口的 DOM 移除
    expect(wrapper.findAll('button').some((b) => b.text() === '接诊')).toBe(false);
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 outpatient:doctor:btn:admit 时接诊按钮渲染', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 'u1', permissions: ['outpatient:doctor:btn:admit'] },
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.findAll('button').some((b) => b.text() === '接诊')).toBe(true);
    wrapper.unmount();
  });
});

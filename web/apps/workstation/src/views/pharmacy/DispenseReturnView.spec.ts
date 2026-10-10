// 退药受理页单测（FU-M06-05 前端面 · 暖纸卷宗 P12 蓝图重排）：既有业务断言全量保留——
// 实物退缺追溯码被前端前置拦截不出网（「无码不结」受理面）、提交以回传单号+受理模式+逐行集
// 调 createDispenseReturn（单号与明细锚点均由后端回传承载，页面不自造）、提交在途防抖
// （W-22⑥：慢响应窗口内按钮禁用且二次点击零出网）、PR-4F 元素码无码 DOM 移除；并按逐页蓝图
// P12 spec 锚点新增构图断言——门牌页首锚点、受理模式 radio 前置升格检索区（.fuy-filter 内且
// 居检索钮之后末位，先定模式再录入）、检索卡/结果卡 .fuy-card 挂类与结果区 .fuy-form 表单脸、
// 两态空态 .fuy-empty 脸化（未检索引导 / 检索无单文案保留）。其中门牌/模式前置/挂类/空态脸
// 为构图性断言（先红后绿）；模式联动 placeholder 与发药中退场免码出网为保留性锚（对重排前
// 实现即绿，防换脸过程丢失既有分支）。api mock 承载，不打真实网络；门牌批注行取 auth 会话
// 真值（pinia 播种，billing 三页 spec 同款基建）。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { createDispenseReturn, listDispenses } from '@/api/pharmacy';
import type { DispenseVO } from '@/api/pharmacy';
import { permDirective } from '@/directives/perm';
import DispenseReturnView from './DispenseReturnView.vue';

vi.mock('@/api/pharmacy', () => ({
  listDispenses: vi.fn(),
  createDispenseReturn: vi.fn(),
}));

// 仅替身 ElMessage（前置拦截提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

// jsdom 未实现 ResizeObserver：el-table 布局测量依赖，补空壳避免挂载即抛（测试环境无真实 resize）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 按按钮文案点击 el-button（避免 DOM 结构序号耦合，patient 三页 spec 同款） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

/** 按按钮文案定位 el-button 包装（在途断言用；非点击入口） */
function findButton(wrapper: VueWrapper, text: string): DOMWrapper<Element> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  return button;
}

/** 构造已检回的发药单（明细一行；单号/处方号/明细 id 定值承载回传出参） */
function dispenseMock(): DispenseVO {
  return {
    id: '100',
    dispenseNo: 'D1',
    dispenseType: 'OUTPATIENT',
    rxNo: 'RX-20260919-001',
    patientId: '1932000000000000002',
    visitId: 'V001',
    storehouse: 'MAIN',
    picker: 'admin',
    verifier: 'reviewer',
    issuer: 'admin',
    status: 'ISSUED',
    items: [
      {
        id: '10',
        prescriptionItemId: '5',
        itemCode: 'C001',
        requestedQuantity: '2',
        issuedQuantity: '2',
        returnedQuantity: '0',
        batchNo: 'B20260101',
        traceCodes: ['T1', 'T2'],
        itemStatus: 'ISSUED',
      },
    ],
  };
}

/** 挂载并检回发药单（处方号录入 → 检索 → 回显编辑行） */
async function mountWithSheet(): Promise<VueWrapper> {
  const wrapper = mount(DispenseReturnView);
  await wrapper.find('input[placeholder="处方号"]').setValue('RX-20260919-001');
  await clickButton(wrapper, '检索发药单');
  await flushPromises();
  return wrapper;
}

describe('退药受理页', () => {
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #21）：真实 PHARMACIST 会话经登录契约导出含码，既有用例语义不变；
    // displayName 同时供门牌批注行「签认人」断言（王药师）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: {
          userId: 'u9',
          loginName: 'pharmacistdemo',
          displayName: '王药师',
          orgId: null,
          roles: ['pharmacist'],
          permissions: ['pharmacy:dispense:btn:return'],
        },
      }),
    );
    vi.mocked(listDispenses).mockReset();
    vi.mocked(createDispenseReturn).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(createDispenseReturn).mockResolvedValue();
  });

  it('实物退缺追溯码被前端拦截不出网', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    const wrapper = await mountWithSheet();

    // 模式保持缺省 ISSUED_RETURN（实物退），追溯码留空直接提交
    await clickButton(wrapper, '提交退药');
    await flushPromises();

    // 断言业务结果：无码不结前置拦截 + 零出网
    expect(vi.mocked(createDispenseReturn)).not.toHaveBeenCalled();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('提交以单号+模式+行集调 createDispenseReturn', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    const wrapper = await mountWithSheet();

    // 逐码录入齐备（退药数量保持默认 1）→ 提交
    await wrapper.find('input[placeholder="逐盒扫码，逗号分隔"]').setValue('T1,T2');
    await clickButton(wrapper, '提交退药');
    await flushPromises();

    // 出网载荷：回传单号 + 缺省实物退模式 + 行集（处方明细 id string 原样，追溯码拆分）
    expect(vi.mocked(createDispenseReturn)).toHaveBeenCalledWith({
      dispenseNo: 'D1',
      mode: 'ISSUED_RETURN',
      items: [
        {
          prescriptionItemId: '5',
          returnQuantity: '1',
          traceCodes: ['T1', 'T2'],
        },
      ],
    });
    wrapper.unmount();
  });

  it('提交退药在途：按钮禁用且二次点击零出网，结束后复位可再点（W-22⑥ 防抖）', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    // 慢响应：提交挂起至用例放行，稳定复现「请求在途」窗口（双击的第二个事件必落在窗口内）
    let releaseSubmit: () => void = () => {};
    vi.mocked(createDispenseReturn).mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          releaseSubmit = resolve;
        }),
    );
    const wrapper = await mountWithSheet();

    // 逐码录入齐备（退药数量保持默认 1）→ 首击提交（在途窗开启）
    await wrapper.find('input[placeholder="逐盒扫码，逗号分隔"]').setValue('T1,T2');
    await clickButton(wrapper, '提交退药');
    await flushPromises();

    // 在途态：按钮原生 disabled 置位（:disabled 与 :loading 同挂在途标志）
    expect(findButton(wrapper, '提交退药').attributes('disabled')).toBeDefined();
    expect(vi.mocked(createDispenseReturn)).toHaveBeenCalledTimes(1);

    // 在途窗口内二次点击：handler 入口守卫 + 组件 loading 双保险，零第二次出网
    await clickButton(wrapper, '提交退药');
    await flushPromises();
    expect(vi.mocked(createDispenseReturn)).toHaveBeenCalledTimes(1);

    // 在途结束后恢复可点（防抖标记须经 finally 复位，禁把按钮永久锁死）
    releaseSubmit();
    await flushPromises();
    expect(findButton(wrapper, '提交退药').attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(DispenseReturnView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  /** 权限态挂载并检回发药单（检索链路不受元素码影响，读面照常回显） */
  async function mountViewWithSheet(): Promise<VueWrapper> {
    const wrapper = mountView();
    await wrapper.find('input[placeholder="处方号"]').setValue('RX-20260919-001');
    await clickButton(wrapper, '检索发药单');
    await flushPromises();
    return wrapper;
  }

  it('PR-4F 权限态：会话无 pharmacy:dispense:btn:return 时提交退药隐藏（D-34 DOM 移除）', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u9' } }),
    );
    const wrapper = await mountViewWithSheet();
    // 发药单读面回显不受元素码影响；提交入口经 v-perm DOM 移除（检索按钮为读面不挂码）
    expect(wrapper.text()).toContain('D1');
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).not.toContain('提交退药');
    expect(buttonTexts).toContain('检索发药单');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 pharmacy:dispense:btn:return 时提交退药可见', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    const wrapper = await mountViewWithSheet();
    expect(wrapper.findAll('button').map((b) => b.text())).toContain('提交退药');
    wrapper.unmount();
  });

  it('门牌页首锚点：衬线标题「退药受理」/ 签认人·时刻批注行（蓝图 P12.2）', () => {
    const wrapper = mount(DispenseReturnView);

    // 门牌页首锚（蓝图 P12.2）：衬线标题承接原卡头页面名 + 「谁·何时」批注行取会话真值，
    // 空值 — 占位禁伪数据；2px 墨规收底走全局 .fuy-page-head 脸（样式层不在此断言）
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('退药受理');
    const note = wrapper.find('.fuy-page-note');
    expect(note.exists()).toBe(true);
    expect(note.text()).toContain('签认人');
    expect(note.text()).toContain('王药师');
    expect(note.find('time').exists()).toBe(true);
    wrapper.unmount();
  });

  it('受理模式前置：radio 升格检索区（.fuy-filter 内、居检索钮之后末位），先定模式再录入（蓝图 P12.2 禁照抄点）', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    const wrapper = await mountWithSheet();

    // 检索卡 .fuy-filter：处方号输入、检索钮与受理模式 radio 同排（模式是录入前置条件）
    const filter = wrapper.find('.fuy-filter');
    expect(filter.exists()).toBe(true);
    expect(filter.find('input[placeholder="处方号"]').exists()).toBe(true);
    expect(filter.findAll('button').map((b) => b.text())).toContain('检索发药单');

    // 模式组锚：radio 组在筛选区内，两档文案保留（实物退 / 发药中退场）
    const radioGroup = filter.find('.el-radio-group');
    expect(radioGroup.exists()).toBe(true);
    expect(radioGroup.text()).toContain('发药后实物退');
    expect(radioGroup.text()).toContain('发药中明细退场');

    // 末位锚（禁照抄点）：radio 组居检索钮之后——禁回落「明细表之后」旧版式
    const children = Array.from(filter.element.children);
    const searchButtonIndex = children.findIndex(
      (el) => el.tagName === 'BUTTON' && el.textContent === '检索发药单',
    );
    const radioGroupIndex = children.findIndex((el) => el.classList.contains('el-radio-group'));
    expect(searchButtonIndex).toBeGreaterThan(-1);
    expect(radioGroupIndex).toBeGreaterThan(searchButtonIndex);

    // 结果区不再承载模式单选（旧版式残留即违蓝图）：明细表所在结果区内零 radio
    const resultArea = wrapper.find('.fuy-form');
    expect(resultArea.exists()).toBe(true);
    expect(resultArea.find('.el-radio-group').exists()).toBe(false);
    wrapper.unmount();
  });

  it('两态空态脸化（契约 ⑥/蓝图 P12.2）：未检索给引导脸，检索无单保留「该处方无发药单」文案且 warning 弹错保留', async () => {
    // 未检索态（searched=false）：引导空态脸 + 合「尚无」语法 + 说明给下一步；纸箱插画不渲染
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    const initial = mount(DispenseReturnView);
    expect(initial.find('.el-empty').exists()).toBe(false);
    const guidance = initial.find('.fuy-empty');
    expect(guidance.exists()).toBe(true);
    expect(guidance.attributes('role')).toBe('status');
    expect(guidance.find('.fuy-empty-title').text()).toContain('尚无');
    expect(guidance.find('.fuy-empty-hint').text()).toContain('处方号');
    initial.unmount();

    // 检索无单态（searched 门控语义保留）：warning 弹错既有 + 空态脸文案锚「该处方无发药单」
    vi.mocked(listDispenses).mockResolvedValue([]);
    const wrapper = mount(DispenseReturnView);
    await wrapper.find('input[placeholder="处方号"]').setValue('RX-NONE');
    await clickButton(wrapper, '检索发药单');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('该处方无发药单');
    expect(wrapper.find('.el-empty').exists()).toBe(false);
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.find('.fuy-empty-title').text()).toBe('该处方无发药单');
    // 错误态说问题给恢复：说明句给核对/重试指引，不伪装成「暂无数据」裸词
    expect(empty.find('.fuy-empty-hint').text()).not.toBe('');
    wrapper.unmount();
  });

  it('构图挂类：检索卡/结果卡 .fuy-card + 结果卡 .fuy-dense + 结果区 .fuy-form 表单脸；页根不挂 .fuy-stagger（契约 ⑦.4）', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    const wrapper = await mountWithSheet();

    // 页根挂 .fuy-page（总则 6）且不挂 .fuy-stagger（路由进场过渡归 MainLayout）
    expect(wrapper.find('.fuy-page').exists()).toBe(true);
    expect(wrapper.find('.fuy-page').classes()).not.toContain('fuy-stagger');

    // 纵向序=检索卡 + 结果卡两张卷宗卡；行编辑表所在结果卡挂 .fuy-dense（密排后代选择器落点）
    const cards = wrapper.findAll('.fuy-card');
    expect(cards.length).toBe(2);
    expect(cards[1].classes()).toContain('fuy-dense');

    // 结果区表单脸 .fuy-form：行编辑录入（退药数/追溯码）经全局后代选择器承聚焦墨环+朱砂下划
    const formArea = wrapper.find('.fuy-form');
    expect(formArea.exists()).toBe(true);
    expect(formArea.find('table').exists()).toBe(true);
    expect(formArea.find('.el-descriptions').exists()).toBe(true);
    wrapper.unmount();
  });

  it('模式切换联动追溯码 placeholder；发药中明细退场免码可出网（模式联动校验另一侧保留锚）', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    const wrapper = await mountWithSheet();

    // 缺省实物退：placeholder 逐码引导（既有文案）
    expect(wrapper.find('input[placeholder="逐盒扫码，逗号分隔"]').exists()).toBe(true);

    // 切「发药中明细退场」：placeholder 联动为可空提示（模式仅改 placeholder，既有口径）
    const cancelRadio = wrapper.findAll('.el-radio').find((r) => r.text() === '发药中明细退场');
    expect(cancelRadio).toBeDefined();
    await cancelRadio!.find('input').setValue(true);
    expect(wrapper.find('input[placeholder="实物退才必填"]').exists()).toBe(true);

    // 追溯码留空提交：发药中退场免码放行，出网携 DISPENSING_CANCEL 模式与空码集
    await clickButton(wrapper, '提交退药');
    await flushPromises();
    expect(vi.mocked(createDispenseReturn)).toHaveBeenCalledWith({
      dispenseNo: 'D1',
      mode: 'DISPENSING_CANCEL',
      items: [
        {
          prescriptionItemId: '5',
          returnQuantity: '1',
          traceCodes: [],
        },
      ],
    });
    wrapper.unmount();
  });

  it('模式前置视觉因果：追溯码列表头随模式联动「必填/可空」标识（模式→必填性可见，先见标识后录入）', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    const wrapper = await mountWithSheet();

    // 缺省实物退（追溯码逐码必填）：列表头挂必填标识——与提交拦截同语义前置于录入位
    const header = () => wrapper.find('.el-table__header-wrapper').text();
    expect(header()).toContain('追溯码录入（必填）');

    // 切「发药中明细退场」（免码）：列表头联动可空标识，必填字样消隐（双通道：文字标识
    // 随模式切换即时翻转，操作者录码前即可见当前模式的必填性）
    const cancelRadio = wrapper.findAll('.el-radio').find((r) => r.text() === '发药中明细退场');
    expect(cancelRadio).toBeDefined();
    await cancelRadio!.find('input').setValue(true);
    expect(header()).toContain('追溯码录入（可空）');
    expect(header()).not.toContain('必填');
    wrapper.unmount();
  });
});

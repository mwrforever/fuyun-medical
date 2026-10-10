// 退费审批页单测（FU-M13-04 前端面 · 暖纸卷宗 P08 蓝图重排）：空结算号查询被前置拦截不出网、
// 审批队列按态驱动——PENDING_APPROVAL 行批准调用 approveRefund(id)、PENDING_SECOND_APPROVAL 行
// （待二级=一级已批）可批可驳不可执行、EXECUTED 行三按钮全禁用（终态不可逆的 UI 抑制；双人
// 守卫拒绝由后端 403 承载不在前端断言）；执行/驳回在途防抖（慢响应窗口内按钮禁用且二次点击零
// 出网，根除双击双 POST 的并发双退触发面）；状态筛选含待二级新态。api mock 承载，不打真实网络。
// 暖纸换脸新增锚点（蓝图 P08.2/P08.3/P08.7）：门牌页首（衬线标题/签认人·时刻批注行）、amber
// 待办胶囊（队列行本地 reduce 计数，零额外出网）、双卡 fuy-card/fuy-dense/fuy-filter 挂类、
// 节内计数徽标（申请勾选数/队列待办数）、队列表空态脸（「暂无」语法，禁默认纸箱插画）。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessageBox, ElSelect } from 'element-plus';
import type { MessageBoxData } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import {
  approveRefund,
  executeRefund,
  getSettlement,
  listFees,
  listRefunds,
  rejectRefund,
} from '@/api/billing';
import type { RefundVO } from '@/api/billing';
import { permDirective } from '@/directives/perm';
import RefundApprovalView from './RefundApprovalView.vue';

vi.mock('@/api/billing', () => ({
  applyRefund: vi.fn(),
  approveRefund: vi.fn(),
  executeRefund: vi.fn(),
  getSettlement: vi.fn(),
  listFees: vi.fn(),
  listRefunds: vi.fn(),
  rejectRefund: vi.fn(),
}));

// 仅替身 ElMessage/ElMessageBox.prompt（在途防抖用例需控制弹窗未决窗口），其余导出原样保留
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), success: vi.fn(), error: vi.fn() },
    ElMessageBox: { ...mod.ElMessageBox, prompt: vi.fn() },
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

/**
 * 构造审批队列一行（status 由各用例指定以驱动按钮启停断言）。
 *
 * @param status 退费单状态（驱动启停/计数断言）
 * @param refundNo 退费单号（计数用例多行并陈时区分行，缺省沿用既有单号）
 */
function queueRow(status: string, refundNo = 'RF-20260918-001'): RefundVO {
  return {
    id: '1932000000000000009',
    refundNo,
    visitId: 'V001',
    amount: '3500',
    reason: '多收',
    applicant: 'admin',
    status,
  };
}

describe('退费审批页', () => {
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  /** 会话种子（auth store 从 sessionStorage 恢复）：含本页元素码——真实 DOCTOR/CASHIER 会话
   * 经登录契约导出含码，既有用例按钮保留、断言语义不变（D-21 申报规范，评审 D-I1 补齐） */
  function seedAuthSession(): void {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: {
          userId: 'u1',
          permissions: [
            'billing:refund:btn:apply',
            'billing:refund:btn:approve',
            'billing:refund:btn:execute',
          ],
        },
      }),
    );
  }

  beforeEach(() => {
    vi.mocked(getSettlement).mockReset();
    vi.mocked(listFees).mockReset();
    vi.mocked(listRefunds).mockReset();
    vi.mocked(approveRefund).mockReset();
    vi.mocked(executeRefund).mockReset();
    vi.mocked(rejectRefund).mockReset();
    vi.mocked(ElMessageBox.prompt).mockReset();
    // 挂载即加载队列：默认空页兜底，防未 stub resolve 断链
    vi.mocked(listRefunds).mockResolvedValue({ content: [], page: 0, size: 20, total: '0' });
    vi.mocked(approveRefund).mockResolvedValue();
    pinia = createPinia();
    setActivePinia(pinia);
    seedAuthSession();
  });

  it('空结算号点查询结算被前置拦截，不触达结算与费用接口', async () => {
    const wrapper = mount(RefundApprovalView);
    await flushPromises();

    await clickButton(wrapper, '查询结算');
    await flushPromises();

    // 断言业务结果：空结算号拦截出网（getSettlement/listFees 零调用）
    expect(vi.mocked(getSettlement)).not.toHaveBeenCalled();
    expect(vi.mocked(listFees)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('PENDING_APPROVAL 行批准调用 approveRefund(退费单 id)', async () => {
    vi.mocked(listRefunds).mockResolvedValue({
      content: [queueRow('PENDING_APPROVAL')],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(RefundApprovalView);

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('RF-20260918-001');
    });
    await clickButton(wrapper, '批准');

    await vi.waitFor(() => {
      // 退费单 id 以 string 原样入路径（雪花 ID 禁 number 处理，web A.3-6）
      expect(vi.mocked(approveRefund)).toHaveBeenCalledWith('1932000000000000009');
    });
    wrapper.unmount();
  });

  it('PENDING_SECOND_APPROVAL 行（待二级=一级已批）批准调用 approveRefund(id)、执行按钮禁用', async () => {
    vi.mocked(listRefunds).mockResolvedValue({
      content: [queueRow('PENDING_SECOND_APPROVAL')],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(RefundApprovalView);

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('RF-20260918-001');
    });
    // 词表：待二级（一级已批）按新态透出，不落未知态原样显示
    expect(wrapper.text()).toContain('待二级');
    // 按钮启停：二级待审可批可驳；未到 APPROVED 不可执行（二级终批前零资金动作）
    expect(findButton(wrapper, '批准').attributes('disabled')).toBeUndefined();
    expect(findButton(wrapper, '驳回').attributes('disabled')).toBeUndefined();
    expect(findButton(wrapper, '执行').attributes('disabled')).toBeDefined();

    await clickButton(wrapper, '批准');

    await vi.waitFor(() => {
      // 同一 approveRefund 入口两段式复用：待二级行批准即二级终批（服务端按 status 推进）
      expect(vi.mocked(approveRefund)).toHaveBeenCalledWith('1932000000000000009');
    });
    wrapper.unmount();
  });

  it('状态筛选选中待二级（PENDING_SECOND_APPROVAL）后按新态查询队列', async () => {
    const wrapper = mount(RefundApprovalView);
    await flushPromises();

    // 筛选下拉选中待二级（一级已批）→ 出网参数携带新状态值（词表与后端枚举同源）
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'PENDING_SECOND_APPROVAL');
    wrapper.findComponent(ElSelect).vm.$emit('change');
    await flushPromises();

    expect(vi.mocked(listRefunds)).toHaveBeenLastCalledWith({ status: 'PENDING_SECOND_APPROVAL' });
    wrapper.unmount();
  });

  it('APPROVED 行执行在途：按钮禁用且二次点击不再出网（并发双退触发面收口）', async () => {
    vi.mocked(listRefunds).mockResolvedValue({
      content: [queueRow('APPROVED')],
      page: 0,
      size: 20,
      total: '1',
    });
    // 慢响应：execute 挂起至用例放行，稳定复现「请求在途」窗口（双击的第二个事件必落在窗口内）
    let releaseExecute: () => void = () => {};
    vi.mocked(executeRefund).mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          releaseExecute = resolve;
        }),
    );
    const wrapper = mount(RefundApprovalView);
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('RF-20260918-001');
    });

    await clickButton(wrapper, '执行');
    await flushPromises();

    // 在途态：按钮原生 disabled 置位（:disabled 组合判 canExecute && !executing）
    expect(findButton(wrapper, '执行').attributes('disabled')).toBeDefined();
    expect(vi.mocked(executeRefund)).toHaveBeenCalledTimes(1);

    // 在途窗口内二次点击：handler 入口守卫 + 组件 loading 双保险，零第二次出网
    await clickButton(wrapper, '执行');
    await flushPromises();
    expect(vi.mocked(executeRefund)).toHaveBeenCalledTimes(1);

    // 在途结束后恢复可点（防抖标记须经 finally 复位，禁把按钮永久锁死）
    releaseExecute();
    await flushPromises();
    expect(findButton(wrapper, '执行').attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });

  it('PENDING_APPROVAL 行驳回在途：弹窗未决时二次点击被抑制（防双窗双 POST）', async () => {
    vi.mocked(listRefunds).mockResolvedValue({
      content: [queueRow('PENDING_APPROVAL')],
      page: 0,
      size: 20,
      total: '1',
    });
    // 弹窗挂起：复现「弹窗未决」窗口（双击在窗口内下发第二个驳回事件）；
    // element-plus 的 MessageBoxData 为 interface & 字面量联合的交叠类型（不可直接构造），
    // 按弹窗确认实际兑现形态（理由 + confirm）经断言构造
    const promptResolved = { value: '凭证不符', action: 'confirm' } as unknown as MessageBoxData;
    let releasePrompt: () => void = () => {};
    vi.mocked(ElMessageBox.prompt).mockImplementation(
      () =>
        new Promise<MessageBoxData>((resolve) => {
          releasePrompt = () => resolve(promptResolved);
        }),
    );
    vi.mocked(rejectRefund).mockResolvedValue();
    const wrapper = mount(RefundApprovalView);
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('RF-20260918-001');
    });

    await clickButton(wrapper, '驳回');
    await flushPromises();
    await clickButton(wrapper, '驳回');
    await flushPromises();

    // 在途抑制：弹窗恰一次（双击不弹双窗），驳回请求尚未下发
    expect(vi.mocked(ElMessageBox.prompt)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(rejectRefund)).not.toHaveBeenCalled();
    expect(findButton(wrapper, '驳回').attributes('disabled')).toBeDefined();

    releasePrompt();
    await flushPromises();
    // 情形确认后请求以弹窗理由原样下发恰一次
    expect(vi.mocked(rejectRefund)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(rejectRefund)).toHaveBeenCalledWith('1932000000000000009', '凭证不符');
    wrapper.unmount();
  });

  it('EXECUTED 终态行批准/驳回/执行三按钮全禁用', async () => {
    vi.mocked(listRefunds).mockResolvedValue({
      content: [queueRow('EXECUTED')],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(RefundApprovalView);

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('RF-20260918-001');
    });
    for (const text of ['批准', '驳回', '执行']) {
      const button = wrapper.findAll('button').find((b) => b.text() === text);
      if (!button) {
        throw new Error(`未找到按钮：${text}`);
      }
      // 终态不可逆：三动作全禁用（防误点后靠后端报错兜底的体验断层）
      expect(button.attributes('disabled')).toBeDefined();
    }
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(RefundApprovalView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 billing:refund:btn:apply 时查询结算后申请退费按钮不渲染（D-34）', async () => {
    vi.mocked(getSettlement).mockResolvedValue({
      id: '801',
      settleNo: 'STL-1',
      visitId: 'V001',
      totalAmount: '3500',
      status: 'SETTLED',
    });
    vi.mocked(listFees).mockResolvedValue({ content: [], page: 0, size: 20, total: '0' });
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 1 } }),
    );
    const wrapper = mountView();
    await flushPromises();
    // 按结算号查询带出摘要区（申请退费入口的数据态前置）
    await wrapper.find('input[placeholder="结算号"]').setValue('STL-1');
    await clickButton(wrapper, '查询结算');
    await flushPromises();
    expect(wrapper.text()).toContain('STL-1');
    expect(wrapper.text()).not.toContain('申请退费');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 billing:refund:btn:apply 时查询结算后申请退费按钮渲染', async () => {
    vi.mocked(getSettlement).mockResolvedValue({
      id: '801',
      settleNo: 'STL-1',
      visitId: 'V001',
      totalAmount: '3500',
      status: 'SETTLED',
    });
    vi.mocked(listFees).mockResolvedValue({ content: [], page: 0, size: 20, total: '0' });
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, permissions: ['billing:refund:btn:apply'] },
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('input[placeholder="结算号"]').setValue('STL-1');
    await clickButton(wrapper, '查询结算');
    await flushPromises();
    expect(wrapper.findAll('button').some((b) => b.text() === '申请退费')).toBe(true);
    wrapper.unmount();
  });

  it('门牌页首锚点：衬线标题「退费审批」/ 签认人·时刻批注行 / amber 待办胶囊（蓝图 P08.2）', () => {
    // 批注行「谁」取会话显示名真值：播种带 displayName 的会话防 — 占位干扰断言
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, displayName: '审批员乙', permissions: [] },
      }),
    );
    const wrapper = mount(RefundApprovalView);

    // 门牌页首锚（蓝图 P08.2/契约 ⑧.1）：衬线标题承接页面名 + 「谁·何时」批注行 + 时刻元素
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-title').text()).toBe('退费审批');
    expect(wrapper.find('.fuy-page-note').text()).toContain('签认人 审批员乙');
    expect(wrapper.find('.fuy-page-note time').exists()).toBe(true);
    // 状态位=待办胶囊 amber 变体（蓝图 P08.2 资金回流待办量前置感知）
    const pill = wrapper.find('.fuy-page-status .fuy-status-pill');
    expect(pill.exists()).toBe(true);
    expect(pill.classes()).toContain('fuy-status-pill--amber');
    wrapper.unmount();
  });

  it('待办胶囊计数：队列行本地 reduce（待审批 2 / 待二级 1），零额外出网（蓝图 P08.7）', async () => {
    vi.mocked(listRefunds).mockResolvedValue({
      content: [
        queueRow('PENDING_APPROVAL', 'RF-20260918-001'),
        queueRow('PENDING_APPROVAL', 'RF-20260918-002'),
        queueRow('PENDING_SECOND_APPROVAL', 'RF-20260918-003'),
        queueRow('EXECUTED', 'RF-20260918-004'),
      ],
      page: 0,
      size: 20,
      total: '4',
    });
    const wrapper = mount(RefundApprovalView);

    // 胶囊双通道铁律（契约 ⑧.6）：文字 + 数字并陈，计数由队列行本地聚合（终态 EXECUTED 不计入）；
    // 等待队列异步回填后计数兑现（waitFor 条件落在业务数值上，非静态文字）
    await vi.waitFor(() => {
      const countsNow = wrapper
        .find('.fuy-page-status .fuy-status-pill')
        .findAll('.fuy-num')
        .map((node) => node.text());
      expect(countsNow).toEqual(['2', '1']);
    });
    const pill = wrapper.find('.fuy-page-status .fuy-status-pill');
    expect(pill.text()).toContain('待审批');
    expect(pill.text()).toContain('待二级');
    const counts = pill.findAll('.fuy-num').map((node) => node.text());
    expect(counts).toEqual(['2', '1']);

    // 零额外出网：挂载加载恰一次，胶囊计数不触发第二次队列请求（本地 reduce 语义）
    expect(vi.mocked(listRefunds)).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('构图挂类：.fuy-page 根（页根不挂 .fuy-stagger）+ 双卡 fuy-dense + 双 .fuy-filter + 节内计数徽标（蓝图 P08.2/P08.3）', async () => {
    // 操作列 class-name 落在 td 上，空队列不渲染行内单元格——播种一行使操作列结构成立
    vi.mocked(listRefunds).mockResolvedValue({
      content: [queueRow('PENDING_APPROVAL')],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(RefundApprovalView);
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('RF-20260918-001');
    });

    // 域级违律修正锚（契约 ⑦.4）：stagger 迁页内区块，页根只挂 .fuy-page
    const root = wrapper.find('.fuy-page');
    expect(root.exists()).toBe(true);
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(wrapper.find('.fuy-stagger.refund-approval-flow').exists()).toBe(true);

    // 双卡纵叠（申请在上/审批在下的资金因果序）：均为卷宗卡脸，队列表卡承 fuy-dense 密度通道
    const cards = wrapper.findAll('.fuy-card');
    expect(cards.length).toBe(2);
    expect(cards[1].classes()).toContain('fuy-dense');

    // 检索/筛选升 .fuy-filter 独立语义位：申请卡结算号检索 + 队列卡状态筛选
    expect(wrapper.findAll('.fuy-filter').length).toBe(2);

    // 节内计数徽标（禁照抄点）：申请卡头右挂勾选数 / 队列卡头右挂待办数
    const extras = wrapper.findAll('.fuy-card-extra');
    expect(extras.length).toBe(2);
    expect(extras[0].text()).toContain('已勾选');
    expect(extras[1].text()).toContain('待办');

    // 操作列间距收窄走全局工具类（td 由 el-table 内部渲染，scoped 零匹配）
    expect(wrapper.find('.fuy-ops-8').exists()).toBe(true);
    wrapper.unmount();
  });

  it('空态脸：队列表空数据走 .fuy-empty（「暂无」语法 + 下一步指引，禁默认纸箱插画）', async () => {
    const wrapper = mount(RefundApprovalView);
    await flushPromises();

    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.find('.fuy-empty-title').text()).toBe('暂无退费申请');
    expect(empty.find('.fuy-empty-hint').text()).not.toBe('');
    wrapper.unmount();
  });
});

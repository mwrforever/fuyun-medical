// 退费审批页单测（FU-M13-04 前端面）：空结算号查询被前置拦截不出网、审批队列按态驱动——
// PENDING_APPROVAL 行批准调用 approveRefund(id)、EXECUTED 行三按钮全禁用（终态不可逆的
// UI 抑制；双人守卫拒绝由后端 403 承载不在前端断言）；执行/驳回在途防抖（慢响应窗口内
// 按钮禁用且二次点击零出网，根除双击双 POST 的并发双退触发面）。api mock 承载，不打真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessageBox } from 'element-plus';
import type { MessageBoxData } from 'element-plus';
import {
  approveRefund,
  executeRefund,
  getSettlement,
  listFees,
  listRefunds,
  rejectRefund,
} from '@/api/billing';
import type { RefundVO } from '@/api/billing';
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

/** 构造审批队列一行（status 由各用例指定以驱动按钮启停断言） */
function queueRow(status: string): RefundVO {
  return {
    id: '1932000000000000009',
    refundNo: 'RF-20260918-001',
    visitId: 'V001',
    amount: '3500',
    reason: '多收',
    applicant: 'admin',
    status,
  };
}

describe('退费审批页', () => {
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
});

// 退费审批页单测（FU-M13-04 前端面）：空结算号查询被前置拦截不出网、审批队列按态驱动——
// PENDING_APPROVAL 行批准调用 approveRefund(id)、EXECUTED 行三按钮全禁用（终态不可逆的
// UI 抑制；双人守卫拒绝由后端 403 承载不在前端断言）。api mock 承载，不打真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { approveRefund, getSettlement, listFees, listRefunds } from '@/api/billing';
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

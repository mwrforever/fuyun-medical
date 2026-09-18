// 划价结算页单测（FU-M13-02/03 前端面）：空就诊号点划价被前置拦截不出网、划价结果金额经
// fenToYuanDisplay 渲染元文本且全程 string 无浮点转换（超大分值渲染即证）、确认结算以
// 预结算回传 settleNo 出网（幂等键由后端承载，页面零运算零生成）。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { listFees, previewSettlement, quote, settle } from '@/api/billing';
import PricingSettleView from './PricingSettleView.vue';

vi.mock('@/api/billing', () => ({
  quote: vi.fn(),
  manualCharge: vi.fn(),
  listFees: vi.fn(),
  previewSettlement: vi.fn(),
  settle: vi.fn(),
}));

// 仅替身 ElMessage/ElMessageBox（拦截提示与结算确认断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: { ...mod.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') },
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

describe('划价结算页', () => {
  beforeEach(() => {
    vi.mocked(quote).mockReset();
    vi.mocked(listFees).mockReset();
    vi.mocked(previewSettlement).mockReset();
    vi.mocked(settle).mockReset();
    // 待收表刷新兜底：空分页默认值，防未 stub 的 resolve 断链
    vi.mocked(listFees).mockResolvedValue({ content: [], page: 0, size: 20, total: '0' });
  });

  it('空就诊号点划价被前置拦截不出网', async () => {
    const wrapper = mount(PricingSettleView);

    await clickButton(wrapper, '划价');
    await flushPromises();

    // 断言业务结果：就诊号空即拦截，quote 与 listFees 均零调用（防全表扫描出网）
    expect(vi.mocked(quote)).not.toHaveBeenCalled();
    expect(vi.mocked(listFees)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('划价结果渲染元文本且金额字段原样 string 不转数字', async () => {
    // 超大分值 amount（>2^53）：若渲染链任何一环做 Number 转换即丢精度，输出对不上
    vi.mocked(quote).mockResolvedValue({
      visitId: 'V001',
      totalAmount: '7000',
      lines: [
        {
          itemId: '1932000000000000001',
          itemCode: 'C001',
          itemName: '检查费',
          unitPrice: '3500',
          quantity: 2,
          amount: '9007199254740993',
          selfExpenseOnly: true,
        },
      ],
    });
    const wrapper = mount(PricingSettleView);
    await wrapper.find('input[placeholder="患者号"]').setValue('1932000000000000002');
    await wrapper.find('input[placeholder="就诊号"]').setValue('V001');
    await wrapper.find('input[placeholder="项目编码"]').setValue('C001');

    await clickButton(wrapper, '划价');

    await vi.waitFor(() => {
      // 分→元展示经换算层：3500 分 → 35.00 元；超大分值无精度丢失即证零浮点参与
      expect(wrapper.text()).toContain('35.00');
      expect(wrapper.text()).toContain('90071992547409.93');
      expect(wrapper.text()).toContain('仅自费');
    });
    // 出网载荷：id 以 string 原样承载（无 Number/BigInt 收口点）
    expect(vi.mocked(quote)).toHaveBeenCalledWith({
      patientId: '1932000000000000002',
      visitId: 'V001',
      lines: [{ itemCode: 'C001', quantity: 1 }],
    });
    wrapper.unmount();
  });

  it('确认结算以预结算回传 settleNo 出网', async () => {
    vi.mocked(previewSettlement).mockResolvedValue({
      settleNo: 'SN-20260918-001',
      totalAmount: '7000',
      payerType: 'SELF_PAY',
      status: 'DRAFT',
    });
    vi.mocked(settle).mockResolvedValue({
      settleNo: 'SN-20260918-001',
      totalAmount: '7000',
      status: 'SETTLED',
    });
    const wrapper = mount(PricingSettleView);
    await wrapper.find('input[placeholder="患者号"]').setValue('1932000000000000002');
    await wrapper.find('input[placeholder="就诊号"]').setValue('V001');

    await clickButton(wrapper, '预结算');
    await vi.waitFor(() => {
      expect(vi.mocked(previewSettlement)).toHaveBeenCalled();
    });
    await clickButton(wrapper, '确认结算');

    await vi.waitFor(() => {
      // 幂等锚点红线：settle 出网的 settleNo 与预结算回传一致，页面不自造任何流水键
      expect(vi.mocked(settle)).toHaveBeenCalledWith({
        settleNo: 'SN-20260918-001',
        // 全现金单行：金额 string 分值直透（裁决①，零运算零转换）
        payments: [{ method: 'CASH', amount: '7000' }],
      });
    });
    wrapper.unmount();
  });
});

// 划价结算页单测（FU-M13-02/03 前端面）：空就诊号点划价被前置拦截不出网、划价结果金额经
// fenToYuanDisplay 渲染元文本且全程 string 无浮点转换（超大分值渲染即证）、确认结算以
// 预结算回传 settleNo 出网（幂等键由后端承载，页面零运算零生成）、EX-45/FE-A1-05 待收费用
// 回包判空兜底（content 缺省不驻留旧就诊费用）与切换就诊号在途竞态守卫（旧就诊慢回包丢弃）、
// 支付方式参数化（W-41）：默认自费档 preview 出网携 SELF_PAY、切医保档 preview 携所选值且
// 确认结算被前置守卫拦截零出网（医保 payments 组装形态归 W-80，勿造）、确认文案随所选
// 支付方式中文标签联动（不再硬编码「现金」）。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox, ElSelect } from 'element-plus';
import { listFees, manualCharge, previewSettlement, quote, settle } from '@/api/billing';
import type { FeeRecordVO } from '@/api/billing';
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

/** 待收费用行（FE-A1-05 用例数据源；status 可覆写） */
function feeRow(partial: Partial<FeeRecordVO> = {}): FeeRecordVO {
  return {
    feeNo: 'F-001',
    itemNameSnapshot: '检查费',
    unitPriceSnapshot: '3500',
    quantity: 1,
    amount: '3500',
    status: 'PENDING',
    ...partial,
  };
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

  it('默认自费档预结算：preview 出网携所选 payerType=SELF_PAY（W-41 参数化）', async () => {
    vi.mocked(previewSettlement).mockResolvedValue({
      settleNo: 'SN-20260918-001',
      totalAmount: '7000',
      payerType: 'SELF_PAY',
      status: 'DRAFT',
    });
    const wrapper = mount(PricingSettleView);
    await wrapper.find('input[placeholder="患者号"]').setValue('1932000000000000002');
    await wrapper.find('input[placeholder="就诊号"]').setValue('V001');

    await clickButton(wrapper, '预结算');

    await vi.waitFor(() => {
      // 未动下拉时默认自费档：出网载荷 payerType 携默认值（回归锚点：参数化不得改变默认口径）
      expect(vi.mocked(previewSettlement)).toHaveBeenCalledWith({
        patientId: '1932000000000000002',
        visitId: 'V001',
        payerType: 'SELF_PAY',
      });
    });
    wrapper.unmount();
  });

  it('切市医保档：preview 携 CITY_INS 出网，确认结算被前置守卫拦截零出网（W-41 裁定①）', async () => {
    vi.mocked(previewSettlement).mockResolvedValue({
      settleNo: 'SN-20260918-002',
      totalAmount: '7000',
      payerType: 'CITY_INS',
      status: 'PRESETTLED',
    });
    const wrapper = mount(PricingSettleView);
    await wrapper.find('input[placeholder="患者号"]').setValue('1932000000000000002');
    await wrapper.find('input[placeholder="就诊号"]').setValue('V001');
    // 工具栏支付方式下拉切市医保（select 替身口径同存量 spec：emit 回填 v-model）
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'CITY_INS');
    await flushPromises();

    await clickButton(wrapper, '预结算');
    await vi.waitFor(() => {
      // 医保档预结算可用（后端贯标校验+网关拆分 PRESETTLED），出网携所选支付方式
      expect(vi.mocked(previewSettlement)).toHaveBeenCalledWith({
        patientId: '1932000000000000002',
        visitId: 'V001',
        payerType: 'CITY_INS',
      });
    });

    // 清掉本用例前置流程与其他用例残留的弹框/warning 调用史，只断言守卫本次触发
    vi.mocked(ElMessageBox.confirm).mockClear();
    vi.mocked(ElMessage.warning).mockClear();
    await clickButton(wrapper, '确认结算');
    await flushPromises();

    // 医保结算通道待 W-80 接入（PaymentMethod 无医保基金通道，payments 形态勿造）：
    // 前置守卫 warning 拦截且不进确认弹框，settle 零出网（防基金部分被记作现金的误结算）
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(expect.stringContaining('医保'));
    expect(vi.mocked(ElMessageBox.confirm)).not.toHaveBeenCalled();
    expect(vi.mocked(settle)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('确认结算文案随所选支付方式联动：自费档含「自费」中文标签（W-41 去硬编码）', async () => {
    vi.mocked(previewSettlement).mockResolvedValue({
      settleNo: 'SN-20260918-003',
      totalAmount: '7000',
      payerType: 'SELF_PAY',
      status: 'DRAFT',
    });
    vi.mocked(settle).mockResolvedValue({
      settleNo: 'SN-20260918-003',
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
    await flushPromises();

    // 确认框文案插值所选支付方式中文标签（「自费」），不再是写死的「现金」
    expect(vi.mocked(ElMessageBox.confirm)).toHaveBeenCalledWith(
      expect.stringContaining('自费'),
      expect.any(String),
      expect.any(Object),
    );
    wrapper.unmount();
  });

  it('待收费用回包 content 缺省兜底空清单，旧就诊费用不驻留（EX-45/FE-A1-05 判空）', async () => {
    vi.mocked(listFees)
      .mockResolvedValueOnce({
        content: [feeRow({ feeNo: 'F-OLD' })],
        page: 0,
        size: 20,
        total: '1',
      })
      .mockResolvedValueOnce({ content: undefined, page: 0, size: 20, total: '0' } as never);
    const wrapper = mount(PricingSettleView);
    await wrapper.find('input[placeholder="就诊号"]').setValue('V001');
    await clickButton(wrapper, '查询费用');
    await flushPromises();
    expect(wrapper.text()).toContain('F-OLD');
    // 换就诊号重查遭遇契约外空回包（content 整包缺失）：兜底空清单，旧就诊费用不得驻留误导收费员
    await wrapper.find('input[placeholder="就诊号"]').setValue('V002');
    await clickButton(wrapper, '查询费用');
    await flushPromises();
    expect(wrapper.text()).not.toContain('F-OLD');
    wrapper.unmount();
  });

  it('切换就诊号在途竞态守卫：旧就诊慢回包整包丢弃（EX-45/FE-A1-05 竞态）', async () => {
    vi.mocked(manualCharge).mockReset().mockResolvedValue('fee-9001');
    // 旧就诊（V001）查询回包挂起至用例放行——复现「查询在途时改号补录」竞态窗口
    let releaseOld: (page: unknown) => void = () => {};
    vi.mocked(listFees)
      .mockImplementationOnce(() => new Promise((resolve) => (releaseOld = resolve)) as never)
      .mockResolvedValueOnce({
        content: [feeRow({ feeNo: 'F-NEW' })],
        page: 0,
        size: 20,
        total: '1',
      });
    const wrapper = mount(PricingSettleView);
    await wrapper.find('input[placeholder="就诊号"]').setValue('V001');
    await clickButton(wrapper, '查询费用');
    // 改号 V002 并经手工计费路径刷新待收表（该路径不受查询按钮 loading 态拦截，真实并发入口）
    await wrapper.find('input[placeholder="患者号"]').setValue('1932000000000000002');
    await wrapper.find('input[placeholder="就诊号"]').setValue('V002');
    await clickButton(wrapper, '手工计费');
    await flushPromises();
    await wrapper.find('input[placeholder="收费项目编码"]').setValue('C002');
    await wrapper.find('textarea[placeholder="补录理由（审计留痕）"]').setValue('补录检查费');
    await clickButton(wrapper, '确认计费');
    await flushPromises();
    // 新就诊（V002）待收费用先行落位
    expect(wrapper.text()).toContain('F-NEW');
    // 旧就诊慢回包晚到：过期回包丢弃，不得覆盖新就诊的待收表
    releaseOld({ content: [feeRow({ feeNo: 'F-OLD' })], page: 0, size: 20, total: '1' });
    await flushPromises();
    expect(wrapper.text()).not.toContain('F-OLD');
    expect(wrapper.text()).toContain('F-NEW');
    wrapper.unmount();
  });
});

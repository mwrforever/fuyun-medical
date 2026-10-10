// 划价结算页单测（FU-M13-02/03 前端面）：空就诊号点划价被前置拦截不出网、划价结果金额经
// fenToYuanDisplay 渲染元文本且全程 string 无浮点转换（超大分值渲染即证）、确认结算以
// 预结算回传 settleNo 出网（幂等键由后端承载，页面零运算零生成）、EX-45/FE-A1-05 待收费用
// 回包判空兜底（content 缺省不驻留旧就诊费用）与切换就诊号在途竞态守卫（旧就诊慢回包丢弃）、
// 支付方式参数化（W-41）：默认自费档 preview 出网携 SELF_PAY、切医保档 preview 携所选值且
// 确认结算被前置守卫拦截零出网（医保 payments 组装形态归 W-80，勿造）、确认文案随所选
// 支付方式中文标签联动（不再硬编码「现金」）；切档即作废预结算草稿（W-41 补）：医保
// PRESETTLED 草稿切回自费档不得跨档存活——按钮回禁用态拦截结算，强点亦零出网；
// preview 在途切档竞态（评审 D-1/E-1）：医保档在途回包切自费档后落地不得复活草稿；
// 拦截文案参数化（评审 D-2）：商业保险档不再统称医保，文案随所选档中文标签联动。
// 暖纸卷宗 P07 重排构图锚（蓝图 P07.7，换脸不换业务）：门牌页首（衬线标题 + 签认人·时刻
// 批注行会话真值）、主从双列（左划价卡含「签」分隔 / 右待收 sticky 辅列——资金动作链纵向
// 收口）、筛选卡 .fuy-filter、页根不挂 .fuy-stagger（契约 ⑦.4 迁页内区块）、手工计费弹窗
// .fuy-dialog + .fuy-form 挂类、待收表空态 .fuy-empty 脸。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox, ElSelect } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { listFees, manualCharge, previewSettlement, quote, settle } from '@/api/billing';
import type { FeeRecordVO, SettlementPreviewVO } from '@/api/billing';
import { permDirective } from '@/directives/perm';
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

/** 按按钮文案查找 el-button（避免 DOM 结构序号耦合，patient 三页 spec 同款） */
function findButton(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((b) => b.text() === text);
}

/** 按按钮文案点击 el-button */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = findButton(wrapper, text);
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
          permissions: ['billing:charge:btn:manual', 'billing:charge:btn:settle'],
        },
      }),
    );
  }

  beforeEach(() => {
    vi.mocked(quote).mockReset();
    vi.mocked(listFees).mockReset();
    vi.mocked(previewSettlement).mockReset();
    vi.mocked(settle).mockReset();
    // 待收表刷新兜底：空分页默认值，防未 stub 的 resolve 断链
    vi.mocked(listFees).mockResolvedValue({ content: [], page: 0, size: 20, total: '0' });
    pinia = createPinia();
    setActivePinia(pinia);
    seedAuthSession();
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

  it('医保草稿切回自费档点结算被拦：切档即作废预结算草稿（W-41 补）', async () => {
    // 复现主控审查 concerns 2 缺口：CITY_INS preview 得 PRESETTLED 草稿 → 切回 SELF_PAY →
    // 若草稿驻留，settle 守卫按当前档（自费）放行，医保单被全现金结算（勾稽语义错位）
    vi.mocked(previewSettlement).mockResolvedValue({
      settleNo: 'SN-20260918-004',
      totalAmount: '7000',
      payerType: 'CITY_INS',
      status: 'PRESETTLED',
    });
    vi.mocked(settle).mockResolvedValue({
      settleNo: 'SN-20260918-004',
      totalAmount: '7000',
      status: 'SETTLED',
    });
    const wrapper = mount(PricingSettleView);
    await wrapper.find('input[placeholder="患者号"]').setValue('1932000000000000002');
    await wrapper.find('input[placeholder="就诊号"]').setValue('V001');
    // 医保档预结算：得医保 PRESETTLED 草稿（select 替身口径同存量 spec）
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'CITY_INS');
    await flushPromises();
    await clickButton(wrapper, '预结算');
    await vi.waitFor(() => {
      expect(vi.mocked(previewSettlement)).toHaveBeenCalled();
    });
    // 切回自费档：草稿与档位强一致，医保草稿不得跨档存活
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'SELF_PAY');
    await flushPromises();

    // 清掉本用例前置流程与模块级 mock 跨用例累积的调用史，只断言本次触发
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessageBox.confirm).mockClear();
    vi.mocked(settle).mockClear();

    // 旧医保草稿已作废：确认结算按钮回禁用态（:disabled="preview === null"），真实用户
    // 无法以医保草稿发起结算——拦截点前移至按钮禁用层（jsdom 对禁用控件激活语义与真实
    // 浏览器一致地抑制，handleSettle 不可达，「请先执行预结算」守卫退居纵深防御）
    const settleBtn = wrapper.findAll('button').find((b) => b.text() === '确认结算');
    expect(settleBtn?.attributes('disabled')).toBeDefined();

    // 纵深验证：即便程序化强点禁用按钮，也不进确认弹框、settle 零出网——医保 settleNo
    // 被全现金结算的形态被彻底阻断（断言业务结果，不绑定拦截层归属）
    await settleBtn?.trigger('click');
    await flushPromises();
    expect(vi.mocked(ElMessageBox.confirm)).not.toHaveBeenCalled();
    expect(vi.mocked(settle)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('preview 在途切档竞态守卫：旧档慢回包落地不复活草稿，settle 零出网（D-1/E-1）', async () => {
    // 复现评审 D-1/E-1 缺口：医保档 preview 在途挂起 → 切回自费档（watch 清空草稿）→
    // 旧档（医保）慢回包落地。若回包无条件赋值，医保 PRESETTLED 草稿复活而当前档为
    // SELF_PAY——settle 守卫放行，医保单被全 CASH 结算（勾稽语义错位）
    let releasePreview: (vo: SettlementPreviewVO) => void = () => {};
    vi.mocked(previewSettlement).mockImplementationOnce(
      () => new Promise((resolve) => (releasePreview = resolve)) as never,
    );
    const wrapper = mount(PricingSettleView);
    await wrapper.find('input[placeholder="患者号"]').setValue('1932000000000000002');
    await wrapper.find('input[placeholder="就诊号"]').setValue('V001');
    // 医保档发起预结算：回包挂起至用例放行（在途窗口）
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'CITY_INS');
    await flushPromises();
    await clickButton(wrapper, '预结算');
    await vi.waitFor(() => {
      expect(vi.mocked(previewSettlement)).toHaveBeenCalled();
    });
    // 在途期间切回自费档：watch 已按切档作废语义清场，当前档位锚定为 SELF_PAY
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'SELF_PAY');
    await flushPromises();

    // 清掉前置流程与跨用例累积的调用史，只断言旧回包落地后的行为
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessageBox.confirm).mockClear();
    vi.mocked(settle).mockClear();

    // 旧档（医保）慢回包晚到：携带 PRESETTLED 草稿——过期回包整包丢弃，不得复活
    releasePreview({
      settleNo: 'SN-20260918-005',
      totalAmount: '7000',
      payerType: 'CITY_INS',
      status: 'PRESETTLED',
    });
    await flushPromises();

    // 草稿不复活：确认结算按钮回禁用态（旧医保草稿未跨档存活）
    const settleBtn = wrapper.findAll('button').find((b) => b.text() === '确认结算');
    expect(settleBtn?.attributes('disabled')).toBeDefined();

    // 纵深验证：程序化强点亦不进确认弹框、settle 零出网（与切档作废用例同款双锚）
    await settleBtn?.trigger('click');
    await flushPromises();
    expect(vi.mocked(ElMessageBox.confirm)).not.toHaveBeenCalled();
    expect(vi.mocked(settle)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('商业保险档拦截文案不称医保：warning 随所选档中文标签参数化（D-2）', async () => {
    vi.mocked(previewSettlement).mockResolvedValue({
      settleNo: 'SN-20260918-006',
      totalAmount: '7000',
      payerType: 'COMM_INS',
      status: 'PRESETTLED',
    });
    const wrapper = mount(PricingSettleView);
    await wrapper.find('input[placeholder="患者号"]').setValue('1932000000000000002');
    await wrapper.find('input[placeholder="就诊号"]').setValue('V001');
    // 商业保险档预结算成功（按钮回启用），拦截文案须随档位标签而非统称医保
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'COMM_INS');
    await flushPromises();
    await clickButton(wrapper, '预结算');
    await vi.waitFor(() => {
      expect(vi.mocked(previewSettlement)).toHaveBeenCalled();
    });

    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessageBox.confirm).mockClear();
    vi.mocked(settle).mockClear();
    await clickButton(wrapper, '确认结算');
    await flushPromises();

    // 文案参数化锚：携所选档中文标签「商业保险」，且不再出现「医保」字样（语义错位修复）
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '商业保险结算通道待接入，当前仅支持自费结算（可先预览商业保险拆分）',
    );
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

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(PricingSettleView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 billing:charge:btn:manual 时手工计费按钮不渲染（D-34 无码全隐藏）', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 1 } }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('划价结算');
    expect(wrapper.text()).not.toContain('手工计费');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 billing:charge:btn:manual 时手工计费按钮渲染', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, permissions: ['billing:charge:btn:manual'] },
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('手工计费');
    wrapper.unmount();
  });

  it('门牌页首：衬线标题「划价结算」+ 签认人·时刻批注行（会话真值非伪数据）', () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, displayName: '收费员甲', permissions: [] },
      }),
    );
    const wrapper = mount(PricingSettleView);

    // 门牌页首锚（蓝图 P07.7）：衬线标题承接原卡头页面名 + 「谁·何时」批注行取会话真值，
    // 空值 — 占位禁伪数据；2px 墨规收底走全局 .fuy-page-head 脸（样式层不在此断言）
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('划价结算');
    const note = wrapper.find('.fuy-page-note');
    expect(note.exists()).toBe(true);
    expect(note.text()).toContain('签认人');
    expect(note.text()).toContain('收费员甲');
    expect(note.find('time').exists()).toBe(true);
    wrapper.unmount();
  });

  it('主从双列重排：左划价卡（签分隔）+ 右待收 sticky 辅列 + 筛选卡 .fuy-filter；页根不挂 .fuy-stagger（契约 ⑦.4）', () => {
    const wrapper = mount(PricingSettleView);

    // 主从分区锚（蓝图 P07.2/7.3）：双卡纵叠改「划价主列 + 待收/结算 sticky 辅列」，
    // 资金动作链（预结算 → 确认结算 → 成功横幅）右列纵向收口；右列 sticky top 16 为 CSS
    // 契约（jsdom 不断言计算样式，以列容器挂类为结构锚——P04 主从 spec 同款口径）
    expect(wrapper.find('.pricing-settle-workarea').exists()).toBe(true);
    const main = wrapper.find('.pricing-settle-main-col');
    expect(main.classes()).toContain('fuy-stagger');
    expect(main.findAll('.fuy-card').length).toBe(1);
    // 行编辑节与划价结果节以「签」分隔线分界（蓝图 P07.2，契约 ⑧.2 卡内分区语法）
    expect(main.find('.fuy-sign-divider').exists()).toBe(true);
    const side = wrapper.find('.pricing-settle-side-col');
    expect(side.exists()).toBe(true);
    expect(side.classes()).toContain('fuy-stagger');
    expect(side.find('.fuy-card').exists()).toBe(true);
    // 页根禁挂 .fuy-stagger（琢段移交违律修正，契约 ⑦.4：路由进场过渡归 MainLayout，
    // 页根再挂会叠出双重进场节奏——stagger 迁页内主从两列各成一档）
    expect(wrapper.find('.fuy-page').classes()).not.toContain('fuy-stagger');
    // 检索先行（域级一致性）：筛选卡挂 .fuy-filter——患者号/就诊号/支付方式 label 分组，
    // 手工计费钮右挂 .fuy-filter-actions（v-perm 既有）
    const filter = wrapper.find('.fuy-filter');
    expect(filter.exists()).toBe(true);
    expect(filter.text()).toContain('患者号');
    expect(filter.text()).toContain('就诊号');
    expect(filter.text()).toContain('支付方式');
    expect(filter.find('.fuy-filter-actions').exists()).toBe(true);
    expect(findButton(wrapper, '手工计费')?.exists()).toBe(true);
    // 划价表 .fuy-dense 密排（蓝图 P07.3：密度规则为后代选择器，挂卡容器——批次 2 质量门 R1 教训）
    expect(wrapper.find('.fuy-dense .el-table').exists()).toBe(true);
    wrapper.unmount();
  });

  it('手工计费弹窗挂 .fuy-dialog 弹层脸且表单挂 .fuy-form 表单脸（契约 ⑧.5/⑤#1）', async () => {
    const wrapper = mount(PricingSettleView);
    await wrapper.find('input[placeholder="患者号"]').setValue('1932000000000000002');
    await wrapper.find('input[placeholder="就诊号"]').setValue('V001');

    await clickButton(wrapper, '手工计费');
    await flushPromises();

    // 弹层脸锚（契约 ⑤#9/⑧.5）：el-dialog 挂 .fuy-dialog（卡面底+radius 14+shadow-lg+衬线
    // 标题）、表单挂 .fuy-form（label 疏排/聚焦墨环/错误显影全局脸）。ElMessageBox 结算确认
    // 为函数式弹窗族不可挂类，维持 EP 默认皮（总则 4）——spec 不断言其内部结构
    const dialog = wrapper.find('.fuy-dialog');
    expect(dialog.exists()).toBe(true);
    expect(dialog.classes()).toContain('el-dialog');
    expect(dialog.find('.fuy-form').exists()).toBe(true);
    wrapper.unmount();
  });

  it('待收费用空态走 .fuy-empty 脸：「暂无待收费用」+ 下一步指引（禁纸箱插画）', () => {
    const wrapper = mount(PricingSettleView);

    // 空态脸锚（总则 8/⑫.4-④）：el-table 内建 el-empty 纸箱插画不渲染，主句合「暂无」
    // 语法 + 说明给下一步（输入就诊号查询 / 手工计费补录后刷新）
    expect(wrapper.find('.el-empty').exists()).toBe(false);
    const empty = wrapper.find('.pricing-settle-side-col .fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.find('.fuy-empty-title').text()).toBe('暂无待收费用');
    expect(empty.find('.fuy-empty-hint').exists()).toBe(true);
    wrapper.unmount();
  });
});

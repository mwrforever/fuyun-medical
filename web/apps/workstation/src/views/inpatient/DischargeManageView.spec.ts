// 出院管理单测（/inpatient/discharge，M04 FU-M04-07 前端面 · 暖纸卷宗换脸重排 乙-5）：
// 「换脸不换业务」——出院申请出网（+08:00 北京钉面解析）、双条件离院确认、取消链、
// 会话列表降级口径零变动；spec 按乙-5 蓝图锚点重写（蓝图乙-5.7 ①~⑤）。
// 断言现代化留痕（本次改动直接波及项，按新契约重写非放宽）：
// ① 四态 radio-button 组 → 卷宗签 .fuy-tabs（契约 ⑤#8），原「文本包含四态」断言改为
//    .fuy-tabs 挂类 + .el-tabs__item 四签文案锚点（蓝图乙-5.7-①）；
// ② 成功反馈 ElMessage.success → 钤印 .fuy-stamp-toast（申请「已提交」/离院「已离院」，
//    蓝图乙-5.4；BLOCKED=ElMessage 警示条非钤印的分叉断言——蓝图乙-5.7-④），
//    原 ElMessage.success 断言按新契约重写；
// ③ 「暂无出院申请」el-empty → .fuy-empty 空态脸（契约 ⑥/蓝图乙-5.7-⑤）；
// ④ 欠费额断言保留分→元换算（蓝图乙-5.7-③），新增 >0 印泥朱语义类与「元」单位断言
//    （蓝图乙-5.3：欠费额状态色语义）。
// 说明：冻结 REST 面无出院申请列表 GET 端点，列表由本会话发起的申请单承载（Concern 已登记，
// 页头说明逐字保留）。api mock 承载零出网（vi.mock('@/api/inpatient') 整模块替身），
// 不打真实网络；断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { discharge } from '@/api/inpatient';
import type { ClearanceVO, DischargeRequestVO } from '@/api/inpatient';
import { permDirective } from '@/directives/perm';
import DischargeManageView from './DischargeManageView.vue';

vi.mock('@/api/inpatient', () => ({
  // WARD_OPTIONS 假常量随 G4 清零退役（listOrgs({type:'WARD')} 真数据源替代），替身键一并清理
  DISCHARGE_WAY_OPTIONS: [
    { code: '1', label: '医嘱离院' },
    { code: '2', label: '医嘱转院' },
    { code: '3', label: '转社区/乡镇卫生院' },
    { code: '4', label: '非医嘱离院' },
    { code: '5', label: '死亡' },
    { code: '9', label: '其他' },
  ],
  DISCHARGE_STATUS_LABELS: {
    REQUESTED: '预审中',
    READY: '待离院确认',
    BLOCKED: '挂账审批中',
    COMPLETED: '已离院',
    CANCELLED: '已取消',
  },
  admissions: {
    create: vi.fn(),
    list: vi.fn(),
    schedule: vi.fn(),
    cancel: vi.fn(),
    register: vi.fn(),
  },
  visits: { admitWard: vi.fn(), arrears: vi.fn() },
  beds: {
    map: vi.fn(),
    reserve: vi.fn(),
    assign: vi.fn(),
    release: vi.fn(),
    disinfectDone: vi.fn(),
    maintain: vi.fn(),
    maintainDone: vi.fn(),
  },
  transfer: { execute: vi.fn(), changeBed: vi.fn() },
  orders: { create: vi.fn(), list: vi.fn(), trace: vi.fn() },
  transferWorklist: { list: vi.fn(), check: vi.fn() },
  discharge: {
    create: vi.fn(),
    cancel: vi.fn(),
    clearance: vi.fn(),
    confirm: vi.fn(),
  },
}));

// 仅替身 ElMessage/ElMessageBox（提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: {
      ...mod.ElMessageBox,
      confirm: vi.fn().mockResolvedValue('confirm'),
    },
  };
});

// jsdom 未实现 ResizeObserver：el-table/el-tabs 布局测量依赖（存量 spec 同款空壳）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 出院申请单行（状态/欠费额可覆写） */
function requestMock(partial: Partial<DischargeRequestVO> = {}): DischargeRequestVO {
  return {
    requestNo: 'DC20260925001',
    visitId: 'I2026092500001',
    dischargeWay: '1',
    expectDischargeAt: '2026-09-26T10:00:00+08:00',
    requestedAt: '2026-09-25T09:00:00+08:00',
    requesterId: 'D001',
    status: 'REQUESTED',
    arrearsAmount: undefined,
    settlementCompletedAt: undefined,
    approvalNo: undefined,
    ...partial,
  };
}

/** 清理与预审结果行（状态/欠费额/结算标记可覆写） */
function clearanceMock(partial: Partial<ClearanceVO> = {}): ClearanceVO {
  return {
    requestNo: 'DC20260925001',
    visitId: 'I2026092500001',
    status: 'REQUESTED',
    stoppedLongCount: 2,
    trackedOrders: [
      { orderNo: 'MO2026092500001', orderClass: 'LONG', status: 'STOPPED' },
      { orderNo: 'MO2026092500002', orderClass: 'STAT', status: 'TRANSFERRED' },
    ],
    cancelledPlanCount: 3,
    arrearsAmount: '0',
    settlementCompletedAt: undefined,
    approvalNo: undefined,
    ...partial,
  };
}

/** 按按钮文案点击（el-button 通用） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('出院管理', () => {
  /** 文件级 Pinia：v-perm 指令与门牌批注行签认人读取 auth 会话 store，每用例新实例防串扰 */
  let pinia: Pinia;

  /** 会话种子（auth store 从 sessionStorage 恢复）：含本页元素码与显示名——真实 DOCTOR 会话
   * 经登录契约导出含码含名，既有用例按钮保留、断言语义不变（D-21 申报规范） */
  function seedAuthSession(): void {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: {
          userId: 'u1',
          displayName: '王医生',
          permissions: ['inpatient:discharge:btn:manage'],
        },
      }),
    );
  }

  /** 统一挂载（门牌批注行签认人消费 auth store + v-perm 元素码判定，均需 pinia 在位；
   *  perm 指令显式注册=main.ts 全局注册仅应用装配态，单测自备） */
  function mountView(): VueWrapper {
    return mount(DischargeManageView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  beforeEach(() => {
    for (const fn of [discharge.create, discharge.cancel, discharge.clearance, discharge.confirm]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.confirm).mockClear();
    pinia = createPinia();
    setActivePinia(pinia);
    seedAuthSession();
  });

  it('门牌页首锚点：衬线标题「出院管理」+ 英文小注 + 事务编排批注行 + 墨实底申请钮 + 页面根不挂 stagger（契约 ⑦.4）', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 门牌三锚（蓝图乙-5.2/契约 ⑧.1）：衬线标题 + 装饰英文小注 + 批注行（hint 升批注带「谁·时刻」）
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('出院管理');
    expect(wrapper.find('.fuy-page-en').text()).toBe('DISCHARGE');
    const note = wrapper.find('.fuy-page-note');
    expect(note.text()).toContain('在途清理 + 费用预审单事务编排 · 双条件离院放行');
    expect(note.text()).toContain('签认人 王医生');
    // 状态位=「发起出院申请」墨实底钮（蓝图乙-5.2，权限码在会话内故渲染）
    expect(wrapper.findAll('button').some((b) => b.text() === '发起出院申请')).toBe(true);
    // 契约 ⑦.4：页面根节点禁挂 .fuy-stagger（flow 包裹 div 承接 flex+gap）
    const root = wrapper.find('.fuy-page.discharge-manage');
    expect(root.exists()).toBe(true);
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(root.find('.fuy-stagger.discharge-flow').exists()).toBe(true);
    wrapper.unmount();
  });

  it('四态签挂类与 tab 文案锚点（乙-5 锚点①）+「暂无出院申请」空态脸（锚点⑤）', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 卷宗签 .fuy-tabs（radio-button 组→签语法换脸留痕，蓝图乙-5.7-①）：四签文案=主链四态
    const tabs = wrapper.find('.fuy-tabs');
    expect(tabs.exists()).toBe(true);
    const tabLabels = tabs.findAll('.el-tabs__item').map((item) => item.text());
    expect(tabLabels).toEqual(['预审中', '待离院确认', '挂账审批中', '已离院']);
    // 默认落「预审中」签（REQUESTED 初态，经激活签 UI 契约断言）
    expect(tabs.find('.el-tabs__item.is-active').text()).toBe('预审中');
    // 空态脸（契约 ⑥/蓝图乙-5.7-⑤）：主句=暂无〈业务客体〉+ 说明给下一步（零默认插画）
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.find('.fuy-empty-title').text()).toBe('暂无出院申请');
    expect(empty.find('.fuy-empty-hint').text()).toContain('发起出院申请');
    wrapper.unmount();
  });

  it('出院申请出网携预出院时间与离院方式并落列表，成功反馈=钤印「已提交」（乙-5 锚点④成功半边）', async () => {
    vi.mocked(discharge.create).mockResolvedValue(requestMock({ status: 'REQUESTED' }));
    const wrapper = mountView();
    await flushPromises();
    // 打开申请弹窗 → 填就诊号/预出院时间/离院方式 → 提交
    await clickButton(wrapper, '发起出院申请');
    await wrapper.find('input[aria-label="在院就诊号"]').setValue('I2026092500001');
    // datetime-local 值为北京钟面墙钟（合法 datetime-local 形态，浏览器对带偏移值会清洗为空）
    await wrapper.find('input[aria-label="预出院时间"]').setValue('2026-09-26T10:00');
    await wrapper.find('select[aria-label="离院方式"]').setValue('1');
    await clickButton(wrapper, '提交申请');
    await flushPromises();
    // W-70①：钉北京钟面防时区漂移——组件侧按 +08:00 钉面解析（见 onCreate），出网 ISO
    // 时点=北京钟面 2026-09-26T10:00 的精确钉面（toISOString 恒 UTC：+08:00 → 02:00Z），
    // 任意运行时区下期望值唯一，不再随运行环境本地时区推导（原 TZ=GMT-14 全天窗暴露）
    expect(discharge.create).toHaveBeenCalledWith(
      'I2026092500001',
      expect.objectContaining({
        expectDischargeAt: '2026-09-26T02:00:00.000Z',
        dischargeWay: '1',
      }),
    );
    // 申请单落会话列表（自动切至返回态 tab 可见）
    expect(wrapper.text()).toContain('DC20260925001');
    // 成功反馈=钤印（蓝图乙-5.4）：文案=动词结果「已提交」（留痕见文件头②）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已提交');
    expect(stamp.attributes('aria-live')).toBe('polite');
  });

  it('预出院时间空值边界：warning 提示且零出网（必填守卫不依赖后端兜底）', async () => {
    const wrapper = mountView();
    await flushPromises();
    await clickButton(wrapper, '发起出院申请');
    await wrapper.find('input[aria-label="在院就诊号"]').setValue('I2026092500001');
    // 预出院时间保持空串（弹窗复位态），提交即被显式校验拦截
    await clickButton(wrapper, '提交申请');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请选择预出院时间');
    expect(discharge.create).not.toHaveBeenCalled();
    // 校验拦截=业务拒绝通道：零钤印（钤印只承成功确认，蓝图乙-5.4）
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
  });

  it('预出院时间非法串边界：+08:00 钉面解析 NaN 同样 warning 零出网', async () => {
    const wrapper = mountView();
    await flushPromises();
    await clickButton(wrapper, '发起出院申请');
    await wrapper.find('input[aria-label="在院就诊号"]').setValue('I2026092500001');
    // 非法串经 new Date(`abc+08:00`) 解析为 Invalid Date——钉面解析失败与空值同走显式
    // 校验拦截，不出网半解析产物
    await wrapper.find('input[aria-label="预出院时间"]').setValue('abc');
    await wrapper.find('select[aria-label="离院方式"]').setValue('1');
    await clickButton(wrapper, '提交申请');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请选择预出院时间');
    expect(discharge.create).not.toHaveBeenCalled();
  });

  it('预出院时间跨日界边界：北京钟面 02:00 墙钟出网精确 ISO 前一日（+08:00 钉面恒 UTC 前移 8h）', async () => {
    vi.mocked(discharge.create).mockResolvedValue(requestMock({ status: 'REQUESTED' }));
    const wrapper = mountView();
    await flushPromises();
    await clickButton(wrapper, '发起出院申请');
    await wrapper.find('input[aria-label="在院就诊号"]').setValue('I2026092500001');
    // 北京钟面 2026-09-26T02:00 墙钟：+08:00 钉面解析后 UTC 时刻回落至前一日 18:00——
    // 跨日界正负向都不随运行环境本地时区漂移
    await wrapper.find('input[aria-label="预出院时间"]').setValue('2026-09-26T02:00');
    await wrapper.find('select[aria-label="离院方式"]').setValue('1');
    await clickButton(wrapper, '提交申请');
    await flushPromises();
    expect(discharge.create).toHaveBeenCalledWith(
      'I2026092500001',
      expect.objectContaining({
        expectDischargeAt: '2026-09-25T18:00:00.000Z',
        dischargeWay: '1',
      }),
    );
    // 成功反馈=钤印「已提交」（蓝图乙-5.4，留痕见文件头②）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已提交');
  });

  it('BLOCKED 清理预审面板：欠费额分→元换算+印泥朱语义+「元」单位+挂账引导；BLOCKED 走警示条非钤印（乙-5 锚点③④分叉）', async () => {
    vi.mocked(discharge.create).mockResolvedValue(
      requestMock({ status: 'BLOCKED', arrearsAmount: '120500' }),
    );
    vi.mocked(discharge.clearance).mockResolvedValue(
      clearanceMock({ status: 'BLOCKED', arrearsAmount: '120500' }),
    );
    const wrapper = mountView();
    await flushPromises();
    // 创建 BLOCKED 申请（自动切至挂账审批中 tab）→ 行内清理预审
    await clickButton(wrapper, '发起出院申请');
    await wrapper.find('input[aria-label="在院就诊号"]').setValue('I2026092500001');
    await wrapper.find('input[aria-label="预出院时间"]').setValue('2026-09-26T10:00');
    await wrapper.find('select[aria-label="离院方式"]').setValue('1');
    await clickButton(wrapper, '提交申请');
    await flushPromises();
    // BLOCKED=业务拒绝实态：ElMessage 警示条（蓝图乙-5.4），钤印零出现（分叉断言锚点④）
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      expect.stringContaining('预审未通过'),
    );
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    await clickButton(wrapper, '清理预审');
    await flushPromises();
    const text = wrapper.text();
    // 欠费额渲染（分→元全站唯一件换算：120500 分=1205.00 元，蓝图乙-5.7-③保留）
    expect(text).toContain('1205.00');
    // 欠费额>0=印泥朱语义类 + 「元」单位（蓝图乙-5.3：欠费额状态色语义）
    const arrears = wrapper.find('.discharge-arrears');
    expect(arrears.exists()).toBe(true);
    expect(arrears.text()).toContain('1205.00');
    expect(arrears.text()).toContain('元');
    // 挂账审批引导（BLOCKED 走 M13 收费面，凭 billing.arrears.approved 转 READY）
    expect(wrapper.find('.discharge-blocked-guide').exists()).toBe(true);
    expect(text).toContain('挂账审批');
    // D-30 段标口径统一（契约 §④.1 字体三分矩阵：段标属黑体默认继承面）：清理预审面板
    // 分区标题「医嘱追踪清单」升全局类 .fuy-section-title（14px/600 浓墨黑体档），
    // 旧 12px 灰墨标签类 .discharge-section-label 清零（死代码零容忍的反向断言）
    const sectionTitles = wrapper.findAll('.fuy-section-title');
    expect(sectionTitles).toHaveLength(1);
    expect(sectionTitles[0].text()).toBe('医嘱追踪清单');
    expect(wrapper.find('.discharge-section-label').exists()).toBe(false);
    // 清理三清单计数渲染（长期停止 2/计划作废 3）
    expect(text).toContain('2');
    expect(discharge.clearance).toHaveBeenCalledWith('DC20260925001');
  });

  it('离院确认双条件：结算未完成禁用并提示原因，满足后放行出网且成功反馈=钤印「已离院」（乙-5 锚点②④）', async () => {
    vi.mocked(discharge.create).mockResolvedValue(requestMock({ status: 'READY' }));
    vi.mocked(discharge.clearance)
      .mockResolvedValueOnce(
        clearanceMock({
          status: 'READY',
          settlementCompletedAt: undefined,
          trackedOrders: [],
        }),
      )
      .mockResolvedValueOnce(
        clearanceMock({
          status: 'READY',
          settlementCompletedAt: '2026-09-26T09:00:00+08:00',
          trackedOrders: [],
        }),
      );
    vi.mocked(discharge.confirm).mockResolvedValue(requestMock({ status: 'COMPLETED' }));
    const wrapper = mountView();
    await flushPromises();
    // 创建 READY 申请 → 清理预审（结算未完成）
    await clickButton(wrapper, '发起出院申请');
    await wrapper.find('input[aria-label="在院就诊号"]').setValue('I2026092500001');
    await wrapper.find('input[aria-label="预出院时间"]').setValue('2026-09-26T10:00');
    await wrapper.find('select[aria-label="离院方式"]').setValue('1');
    await clickButton(wrapper, '提交申请');
    await flushPromises();
    await clickButton(wrapper, '清理预审');
    await flushPromises();
    // 双条件之一未满足（结算未完成）：确认按钮禁用 + 原因提示（文案逐字冻结，蓝图乙-5.4）
    const confirmBtn = () => wrapper.findAll('button').find((b) => b.text() === '确认离院');
    expect(confirmBtn()?.attributes('disabled')).toBeDefined();
    expect(wrapper.find('.discharge-confirm-reason').text()).toContain('结算未完成');
    expect(discharge.confirm).not.toHaveBeenCalled();
    // 重拉清理预审（结算完成后）→ 双条件满足 → 确认按钮启用 → 放行出网
    await clickButton(wrapper, '清理预审');
    await flushPromises();
    expect(confirmBtn()?.attributes('disabled')).toBeUndefined();
    await clickButton(wrapper, '确认离院');
    await flushPromises();
    expect(discharge.confirm).toHaveBeenCalledWith('DC20260925001', {});
    // 成功反馈=钤印「已离院」（蓝图乙-5.4 钤印族，留痕见文件头②）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已离院');
  });

  it('取消申请链回归锚：ElMessageBox 中档确认后出网取消，行状态随回包更新移出当前签（换脸零变动项）', async () => {
    vi.mocked(discharge.create).mockResolvedValue(requestMock({ status: 'REQUESTED' }));
    vi.mocked(discharge.cancel).mockResolvedValue(requestMock({ status: 'CANCELLED' }));
    const wrapper = mountView();
    await flushPromises();
    await clickButton(wrapper, '发起出院申请');
    await wrapper.find('input[aria-label="在院就诊号"]').setValue('I2026092500001');
    await wrapper.find('input[aria-label="预出院时间"]').setValue('2026-09-26T10:00');
    await wrapper.find('select[aria-label="离院方式"]').setValue('1');
    await clickButton(wrapper, '提交申请');
    await flushPromises();
    expect(wrapper.text()).toContain('DC20260925001');
    await clickButton(wrapper, '取消');
    await flushPromises();
    // 中档确认既有交互保留（蓝图乙-5.4：取消申请=ElMessageBox；文案冻结）
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('就诊回在院，医嘱不复活'),
      '取消出院申请',
      expect.objectContaining({ confirmButtonText: '确认取消' }),
    );
    expect(discharge.cancel).toHaveBeenCalledWith('DC20260925001');
    // 行状态随回包更新为 CANCELLED：REQUESTED 签内自然消隐（会话列表过滤语义）
    expect(wrapper.text()).not.toContain('DC20260925001');
    // 取消成功提示保留 ElMessage（蓝图钤印族未列「已取消」，既有行为零变动）
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
  });

  it('PR-4F 权限态：会话无 inpatient:discharge:btn:manage 时发起出院申请按钮不渲染（D-34 无码全隐藏）', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 1 } }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('出院管理');
    // 断言现代化留痕：原「全文 not.toContain('发起出院申请')」与新空态脸引导文案（下一步
    // 指引提及入口动作名，契约 ⑥）冲突——D-34 语义=元素级移除，断言精确到按钮级（原文本
    // 断言属误伤面，严格度不降：无码时按钮 DOM 必须不存在）
    expect(wrapper.findAll('button').some((b) => b.text() === '发起出院申请')).toBe(false);
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 inpatient:discharge:btn:manage 时发起出院申请按钮渲染', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, permissions: ['inpatient:discharge:btn:manage'] },
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.findAll('button').some((b) => b.text() === '发起出院申请')).toBe(true);
    wrapper.unmount();
  });
});

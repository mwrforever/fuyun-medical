// 护理不良事件上报页单测（PR-3 Task 14 + 暖纸卷宗换脸重排 甲-3）：既有业务断言全量保留
// （路由权限点、列表渲染锚点、上报弹窗必填拦截零出网与成功重拉、处理留痕出网、PR-4F 权限态
// 两态），新增换脸锚点：门牌页首（衬线标题/非惩罚批注行/「待处理 N」琥珀胶囊）、今日概览
// 统计带三态（骨架/三字段内容/错误恢复小卡）、时限双通道 token 断言（超时=琥珀非印泥朱、
// 按时=绿）、上报表单病区下拉 listOrgs({type:'WARD'}) 三态（假常量 WARD_OPTIONS 清零）、
// 钤印「已上报」（匿名口径不带操作人）、空态脸「暂无不良事件记录」、页脚统计时点。
// api mock 承载，不打真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adverseEvents, getAdverseEventStats } from '@/api/nursing';
import type { AdverseEventStatsVO, AdverseEventVO } from '@/api/nursing';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { permDirective } from '@/directives/perm';
import { router } from '@/router';
import AdverseEventView from './AdverseEventView.vue';

vi.mock('@/api/nursing', () => ({
  ADVERSE_CATEGORY_OPTIONS: [
    { code: 'FALL', label: '跌倒坠床' },
    { code: 'MEDICATION_ERROR', label: '用药错误' },
  ],
  SEVERITY_CLASS_OPTIONS: [
    { code: 'I', label: 'I 级（最重）' },
    { code: 'II', label: 'II 级（重）' },
  ],
  SEVERITY_GRADE_OPTIONS: [{ code: 'A', label: 'A' }],
  adverseEvents: {
    list: vi.fn(),
    report: vi.fn(),
    handle: vi.fn(),
    close: vi.fn(),
    return: vi.fn(),
  },
  getAdverseEventStats: vi.fn(),
}));

// 病区清单读端点替身（暖纸切片真数据面，G4 假常量 WARD_OPTIONS 清零）
vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
}));

// 仅替身弹层件（提示与确认断言用），其余导出原样保留
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: {
      ...mod.ElMessageBox,
      confirm: vi.fn().mockResolvedValue('confirm'),
      prompt: vi.fn().mockResolvedValue({ value: '处置记录' }),
    },
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

/** 病区种子（V1123__seed_sys_org 镜像：WARD 演示病区 ACTIVE） */
const WARD_SEED: OrgVO[] = [
  { id: '1123000000000000001', orgCode: 'W01', orgName: '演示病区', orgType: 'WARD', sort: 1 },
];

/** 统计种子（后端 AdverseEventStatsVO 生成物形态：计数 string 承载，date=统计日） */
function statsMock(partial: Partial<AdverseEventStatsVO> = {}): AdverseEventStatsVO {
  return {
    date: '2026-10-11',
    total: '12',
    deadlineTotal: '5',
    deadlineMetCount: '4',
    ...partial,
  };
}

/** 会话种子（auth store 从 sessionStorage 恢复：操作人=登录用户 u1/李护士；
 * permissions 含 #31 元素码——真实 NURSE 会话经登录契约导出含码，既有用例语义不变） */
function seedAuthSession(): void {
  sessionStorage.setItem(
    'fy:workstation:auth',
    JSON.stringify({
      token: 'test-token',
      refreshToken: 'test-refresh',
      user: {
        userId: 'u1',
        loginName: 'nursedemo',
        displayName: '李护士',
        orgId: null,
        roles: ['nurse'],
        permissions: ['nursing:adverse-event:btn:manage'],
      },
    }),
  );
}

/** 不良事件行（状态/时限可覆写） */
function eventMock(partial: Partial<AdverseEventVO> = {}): AdverseEventVO {
  return {
    eventNo: 'AE2026100100001',
    category: 'FALL',
    severityClass: 'II',
    severityGrade: 'B',
    wardId: 'W01',
    occurredAt: '2026-10-01T07:30:00',
    eventSummary: '病房走廊跌倒，右膝擦伤',
    isAnonymous: false,
    reportDeadline: '2026-10-02T07:30:00',
    deadlineMet: true,
    status: 'REPORTED',
    createdAt: '2026-10-01T08:00:00',
    ...partial,
  };
}

describe('护理不良事件上报页', () => {
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    seedAuthSession();
    // 页面清单默认空（size=20）与待处理探针（size=1 取 total）共用 list mock：默认 total=0
    vi.mocked(adverseEvents.list).mockReset().mockResolvedValue({ content: [], total: '0' });
    vi.mocked(adverseEvents.report).mockReset().mockResolvedValue(eventMock());
    vi.mocked(adverseEvents.handle).mockReset().mockResolvedValue(eventMock());
    vi.mocked(adverseEvents.close).mockReset().mockResolvedValue(eventMock());
    vi.mocked(adverseEvents.return).mockReset().mockResolvedValue(eventMock());
    vi.mocked(getAdverseEventStats).mockReset().mockResolvedValue(statsMock());
    vi.mocked(listOrgs).mockReset().mockResolvedValue(WARD_SEED);
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.prompt).mockClear();
  });

  it('路由登记权限点 nursing:adverse-event:report（路由=权限点清单审计形态）', () => {
    expect(router.resolve('/nursing/adverse-events').meta.permission).toBe(
      'nursing:adverse-event:report',
    );
  });

  it('门牌页首：衬线标题「不良事件上报」+ 英文小注 + 非惩罚批注行 + 「待处理 N」琥珀胶囊', async () => {
    vi.mocked(adverseEvents.list).mockResolvedValue({ content: [], total: '3' });
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 门牌三锚（蓝图 甲-3.2/契约 ⑧.1）：衬线标题 + 装饰英文小注 + 非惩罚口径批注行（hint 升批注）
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('不良事件上报');
    expect(wrapper.find('.fuy-page-en').text()).toBe('ADVERSE EVENTS');
    const note = wrapper.find('.fuy-page-note');
    expect(note.text()).toContain('非惩罚通道：主动报告免责 · 匿名可选');
    expect(note.text()).toContain('签认人 李护士');
    // 状态位=「待处理 N」琥珀胶囊（双通道：色+文字；N=待处理探针 total 真值）
    const pill = wrapper.find('.fuy-page-status .fuy-status-pill');
    expect(pill.classes()).toContain('fuy-status-pill--amber');
    expect(pill.text()).toBe('待处理 3');
    wrapper.unmount();
  });

  it('今日概览统计带内容态：三格三字段映射（总报数/按时上报数/待处理数）+ 时限分母副注', async () => {
    vi.mocked(getAdverseEventStats).mockResolvedValue(statsMock());
    vi.mocked(adverseEvents.list).mockResolvedValue({ content: [], total: '3' });
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    const strip = wrapper.find('.ae-stat-strip');
    expect(strip.exists()).toBe(true);
    // 三字段映射（甲-3 锚点①）：total→总报数 / deadlineMetCount→按时上报数 / 探针 total→待处理数
    const cells = strip.findAll('.stat');
    expect(cells.map((c) => c.find('.stat-label').text())).toEqual([
      '总报数',
      '按时上报数',
      '待处理数',
    ]);
    expect(cells[0]?.find('.stat-value').text()).toBe('12');
    expect(cells[1]?.find('.stat-value').text()).toBe('4');
    expect(cells[2]?.find('.stat-value').text()).toBe('3');
    // 按时上报数副注=deadlineTotal 时限分母（I/II 级 24h 时限口径）
    expect(cells[1]?.find('.stat-delta').text()).toContain('I/II 级 24h 时限共 5 件');
    // 统计出网一次（单次出网不轮询，甲-3.6）
    expect(getAdverseEventStats).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('今日概览统计带骨架态：统计在途渲染静态金洗纸骨架（min-height 锁 CLS，零伪数据）', async () => {
    vi.mocked(getAdverseEventStats).mockImplementation(
      () => new Promise<AdverseEventStatsVO>(() => {}),
    );
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.find('.ae-stat-strip--skeleton').exists()).toBe(true);
    expect(wrapper.findAll('.ae-stat-strip--skeleton .fuy-sk').length).toBeGreaterThanOrEqual(6);
    expect(wrapper.find('.stat-value').exists()).toBe(false);
    wrapper.unmount();
  });

  it('今日概览统计带错误态：说问题给恢复小卡（不伪装空态），重试成功回内容态', async () => {
    vi.mocked(getAdverseEventStats).mockRejectedValueOnce(new Error('network down'));
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    const errorCard = wrapper.find('.ae-stat-error');
    expect(errorCard.exists()).toBe(true);
    expect(errorCard.attributes('role')).toBe('alert');
    expect(errorCard.text()).toContain('今日概览加载失败');
    expect(errorCard.text()).toContain('可重试');
    // 重试（手动刷新取数）：恢复后回内容态
    const retry = errorCard.findAll('button').find((b) => b.text() === '重试');
    if (!retry) {
      throw new Error('未找到重试按钮');
    }
    await retry.trigger('click');
    await flushPromises();
    expect(wrapper.find('.ae-stat-strip').exists()).toBe(true);
    expect(wrapper.find('.ae-stat-error').exists()).toBe(false);
    expect(getAdverseEventStats).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('时限双通道取值（甲-3 锚点③）：超时留痕=琥珀 tag、按时上报=绿 tag，全页零印泥朱状态 tag', async () => {
    vi.mocked(adverseEvents.list).mockResolvedValue({
      content: [eventMock(), eventMock({ eventNo: 'AE2026100100002', deadlineMet: false })],
      total: '2',
    });
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    const successTags = wrapper.findAll('.el-tag--success');
    const warningTags = wrapper.findAll('.el-tag--warning');
    expect(successTags.some((t) => t.text() === '按时上报')).toBe(true);
    expect(warningTags.some((t) => t.text() === '超时留痕')).toBe(true);
    // 非惩罚口径：超时留痕非危急——时限通道禁印泥朱（el-tag--danger 全页零出现）
    expect(wrapper.findAll('.el-tag--danger')).toHaveLength(0);
    wrapper.unmount();
  });

  it('列表渲染锚点：单号/类别/分级+等级/状态标签/匿名标记（既有业务断言保留）', async () => {
    vi.mocked(adverseEvents.list).mockResolvedValue({
      content: [
        eventMock(),
        eventMock({
          eventNo: 'AE2026100100002',
          status: 'HANDLING',
          deadlineMet: false,
          isAnonymous: true,
        }),
      ],
      total: '2',
    });
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.text()).toContain('AE2026100100001');
    expect(wrapper.text()).toContain('跌倒坠床');
    // 分级/等级列组合展示（II / B）
    expect(wrapper.text()).toContain('II / B');
    expect(wrapper.text()).toContain('待处理');
    expect(wrapper.text()).toContain('处理中');
    expect(wrapper.text()).toContain('超时留痕');
    expect(wrapper.text()).toContain('匿名');
    wrapper.unmount();
  });

  it('空列表渲染空态脸（甲-3 锚点⑤）：.fuy-empty 主句「暂无不良事件记录」+ 说明给下一步', async () => {
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.text()).toContain('暂无不良事件记录');
    expect(empty.text()).toContain('上报事件');
    wrapper.unmount();
  });

  it('上报表单病区下拉 listOrgs 三态（甲-3 锚点④）：在途禁用零选项→到达出选项→失败给恢复', async () => {
    // 在途（deferred 手控时序）：打开弹窗，病区下拉禁用（清单未就绪零选项）
    let releaseWards: () => void = () => {};
    vi.mocked(listOrgs).mockImplementation(
      () =>
        new Promise<OrgVO[]>((resolve) => {
          releaseWards = () => resolve(WARD_SEED);
        }),
    );
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(listOrgs).toHaveBeenCalledWith({ type: 'WARD' });
    const reportButton = wrapper.findAll('button').find((b) => b.text() === '上报事件');
    if (!reportButton) {
      throw new Error('未找到上报事件按钮');
    }
    await reportButton.trigger('click');
    await flushPromises();
    const wardSelect = wrapper.findAllComponents({ name: 'ElSelect' })[3];
    if (!wardSelect) {
      throw new Error('未找到发生病区下拉');
    }
    expect(wardSelect.props('disabled')).toBe(true);

    // 清单到达：解禁，选项=种子病区（出网值仍 string code）
    releaseWards();
    await flushPromises();
    expect(wardSelect.props('disabled')).toBe(false);
    const options = wardSelect.findAllComponents({ name: 'ElOption' });
    expect(options.map((o) => o.props('value'))).toEqual(['W01']);
    expect(options[0]?.props('label')).toBe('演示病区（W01）');
    wrapper.unmount();
  });

  it('上报表单病区下拉失败态：错误行说问题给恢复，重试成功回选项（不伪装空选项）', async () => {
    vi.mocked(listOrgs).mockRejectedValueOnce(new Error('network down'));
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    const reportButton = wrapper.findAll('button').find((b) => b.text() === '上报事件');
    if (!reportButton) {
      throw new Error('未找到上报事件按钮');
    }
    await reportButton.trigger('click');
    await flushPromises();
    const errorRow = wrapper.find('.ae-ward-error');
    expect(errorRow.exists()).toBe(true);
    expect(errorRow.attributes('role')).toBe('alert');
    expect(errorRow.text()).toContain('病区清单加载失败');
    // 重试：listOrgs 二次出网，成功后错误行消失、选项就绪
    const retry = errorRow.findAll('button').find((b) => b.text() === '重试');
    if (!retry) {
      throw new Error('未找到病区重试按钮');
    }
    await retry.trigger('click');
    await flushPromises();
    expect(listOrgs).toHaveBeenCalledTimes(2);
    expect(wrapper.find('.ae-ward-error').exists()).toBe(false);
    const wardSelect = wrapper.findAllComponents({ name: 'ElSelect' })[3];
    expect(wardSelect?.findAllComponents({ name: 'ElOption' })).toHaveLength(1);
    wrapper.unmount();
  });

  it('上报弹窗：必填缺失提示且零出网；补齐后出网一次并重拉列表与待处理探针', async () => {
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    const reportButton = wrapper.findAll('button').find((b) => b.text() === '上报事件');
    if (!reportButton) {
      throw new Error('未找到上报事件按钮');
    }
    await reportButton.trigger('click');
    await flushPromises();
    // 类别未选：显式校验零出网
    const submit = wrapper.findAll('button').find((b) => b.text() === '提交上报');
    if (!submit) {
      throw new Error('未找到提交上报按钮');
    }
    await submit.trigger('click');
    await flushPromises();
    expect(adverseEvents.report).not.toHaveBeenCalled();
    expect(ElMessage.warning).toHaveBeenCalledWith('请选择事件类别（必填）');
    // 补齐必填面（select 经组件事替身 emit 回填 v-model——存量 spec 同款口径；发生时点
    // datetime-local 为原生控件直填；病区缺省空串=假常量清零，须显式选择）
    const selects = wrapper.findAllComponents({ name: 'ElSelect' });
    await selects[0].vm.$emit('update:modelValue', 'FALL');
    await selects[1].vm.$emit('update:modelValue', 'II');
    await selects[2].vm.$emit('update:modelValue', 'B');
    await selects[3].vm.$emit('update:modelValue', 'W01');
    await wrapper.find('input[type="datetime-local"]').setValue('2026-10-01T07:30');
    await wrapper.find('textarea[aria-label="事件经过"]').setValue('病房走廊跌倒');
    await submit.trigger('click');
    await flushPromises();
    expect(adverseEvents.report).toHaveBeenCalledTimes(1);
    // 出网序（甲-3 数据接线）：1=页面清单首拉 → 2=待处理探针 → 3=上报后回第一页重拉 → 4=探针联动
    expect(adverseEvents.list).toHaveBeenNthCalledWith(1, {
      category: undefined,
      status: undefined,
      page: 0,
      size: 20,
    });
    expect(adverseEvents.list).toHaveBeenNthCalledWith(2, { status: 'REPORTED', page: 0, size: 1 });
    expect(adverseEvents.list).toHaveBeenNthCalledWith(3, {
      category: undefined,
      status: undefined,
      page: 0,
      size: 20,
    });
    expect(adverseEvents.list).toHaveBeenNthCalledWith(4, { status: 'REPORTED', page: 0, size: 1 });
    wrapper.unmount();
  });

  it('上报成功盖钤印「已上报」（甲-3 锚点②）：钤印文案不带操作人与单号（匿名口径），aria-live 承载', async () => {
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    const reportButton = wrapper.findAll('button').find((b) => b.text() === '上报事件');
    if (!reportButton) {
      throw new Error('未找到上报事件按钮');
    }
    await reportButton.trigger('click');
    await flushPromises();
    const selects = wrapper.findAllComponents({ name: 'ElSelect' });
    await selects[0].vm.$emit('update:modelValue', 'FALL');
    await selects[1].vm.$emit('update:modelValue', 'II');
    await selects[2].vm.$emit('update:modelValue', 'B');
    await selects[3].vm.$emit('update:modelValue', 'W01');
    await wrapper.find('input[type="datetime-local"]').setValue('2026-10-01T07:30');
    await wrapper.find('textarea[aria-label="事件经过"]').setValue('病房走廊跌倒');
    const submit = wrapper.findAll('button').find((b) => b.text() === '提交上报');
    if (!submit) {
      throw new Error('未找到提交上报按钮');
    }
    await submit.trigger('click');
    await flushPromises();
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    // 文案=动词结果且不带操作人（匿名事件不露身份，留痕归后端）
    expect(stamp.text()).toBe('已上报');
    expect(stamp.text()).not.toContain('李护士');
    expect(stamp.text()).not.toContain('u1');
    expect(stamp.text()).not.toContain('AE');
    expect(stamp.attributes('aria-live')).toBe('polite');
    wrapper.unmount();
  });

  it('处理操作：角色留痕提示弹窗确认后出网一次（handlerId=会话用户）', async () => {
    vi.mocked(adverseEvents.list).mockResolvedValue({
      content: [eventMock()],
      total: '1',
    });
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    const handleButton = wrapper.findAll('button').find((b) => b.text() === '处理');
    if (!handleButton) {
      throw new Error('未找到处理按钮');
    }
    await handleButton.trigger('click');
    await flushPromises();
    // 弹窗文案含操作人留痕提示（角色留痕）
    expect(ElMessageBox.prompt).toHaveBeenCalledTimes(1);
    const promptMessage = vi.mocked(ElMessageBox.prompt).mock.calls[0]?.[0];
    expect(typeof promptMessage === 'string' && promptMessage.includes('u1')).toBe(true);
    expect(adverseEvents.handle).toHaveBeenCalledWith('AE2026100100001', {
      handlerId: 'u1',
      handlingNote: '处置记录',
    });
    wrapper.unmount();
  });

  it('页脚批注：统计时点（后端统计日）+ 手动刷新取数语义（单次出网不轮询）', async () => {
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    const foot = wrapper.find('.fuy-page-foot');
    expect(foot.exists()).toBe(true);
    expect(foot.text()).toContain('统计时点 2026-10-11');
    expect(foot.text()).toContain('手动刷新取数');
    expect(foot.text()).toContain('不自动轮询');
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(AdverseEventView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 nursing:adverse-event:btn:manage 时上报与行内操作全隐藏（D-34）', async () => {
    vi.mocked(adverseEvents.list).mockResolvedValue({
      content: [eventMock()],
      total: '1',
    });
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u1' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).not.toContain('上报事件');
    expect(buttonTexts).not.toContain('处理');
    expect(buttonTexts).not.toContain('退回');
    // 列表读面不受元素码影响（单号仍渲染）
    expect(wrapper.text()).toContain('AE2026100100001');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 nursing:adverse-event:btn:manage 时上报入口与行内操作可见', async () => {
    vi.mocked(adverseEvents.list).mockResolvedValue({
      content: [eventMock()],
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('上报事件');
    expect(buttonTexts).toContain('处理');
    expect(buttonTexts).toContain('退回');
    wrapper.unmount();
  });
});

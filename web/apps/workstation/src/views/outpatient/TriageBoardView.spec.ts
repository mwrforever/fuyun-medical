// 分诊台页单测（FU-M03-04 前端面 · 暖纸卷宗换脸重排，逐页蓝图 P05）：渲染断言（操作条三域
// 分组/门牌页首/轮询胶囊三态/空态脸/分诊级别徽标两态）、报到显式格式校验（空值与形态违规两道
// 前置拦截零出网——W-22⑦ 同款禁裸提交）、报到出网参数、行动作在途守卫（W-22⑥：慢响应窗口
// 按钮禁用且二次点击零出网）、过号确认带回显摘要、调级确认带回显摘要、调级理由必填前置拦截
// 与出网携带（W-29 D-9）、转队列高风险分档（danger 确认 + 目标诊区必选拦截）、诊区真数据三态
// （listOrgs 加载中/有诊区/空清单，假常量 DEPT-INT 清零）、轮询 merge 可变字段同步（真机 D-3：
// 二次分诊改派 doctorId 后叫号须携新值出网）。
// 既有业务断言全量保留（蓝图 P05.7：报到双拦截/三动作互斥/处置分档/调级必填/轮询 merge），
// 仅换脸载体断言随新构图改锚（门牌页首/胶囊/挂类/空态脸文案——总则 8 空态脸化）。
// api mock 承载，不打真实网络；轮询 5s 周期在用例时间窗内零触发（merge 用例经
// visibilitychange 事件驱动单次刷新），卸载清理定时器。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox, ElSelect } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { adjustTriage, callNext, checkIn, getQueueSnapshot, passTicket } from '@/api/outpatient';
import type { QueueTicketVO } from '@/api/outpatient';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { permDirective } from '@/directives/perm';
import TriageBoardView from './TriageBoardView.vue';

vi.mock('@/api/outpatient', () => ({
  checkIn: vi.fn(),
  adjustTriage: vi.fn(),
  callNext: vi.fn(),
  passTicket: vi.fn(),
  recallTicket: vi.fn(),
  getQueueSnapshot: vi.fn(),
  listAvailablePools: vi.fn(),
  createAppointment: vi.fn(),
}));

// 诊区清单读端点替身（暖纸切片真数据面）：默认回 V1123 种子镜像（内科/外科/儿科 sort 升序）
vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
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

/** 诊区种子（V1123__seed_sys_org 镜像：DEPT 三科室 ACTIVE，sort 升序——演示主链路内科居首） */
const DEPT_SEED: OrgVO[] = [
  { id: '1123000000000000003', orgCode: 'DEPT-INT', orgName: '内科', orgType: 'DEPT', sort: 1 },
  { id: '1123000000000000004', orgCode: 'DEPT-SUR', orgName: '外科', orgType: 'DEPT', sort: 2 },
  { id: '1123000000000000005', orgCode: 'DEPT-PED', orgName: '儿科', orgType: 'DEPT', sort: 3 },
];

/** 按按钮文案点击 el-button */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

/** 按按钮文案定位 el-button 包装（在途断言用） */
function findButton(wrapper: VueWrapper, text: string): DOMWrapper<Element> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  return button;
}

function ticketMock(partial: Partial<QueueTicketVO>): QueueTicketVO {
  return {
    id: '900',
    visitId: 'O2026092100001',
    queueId: 'DEPT-INT',
    ticketNo: 'A007',
    ticketType: 'FIRST',
    doctorId: 'd1',
    priorityScore: 320,
    queueSeq: 3,
    queueTime: new Date(Date.now() - 10 * 60000).toISOString(),
    calledCount: 0,
    status: 'WAITING',
    patientName: '张*',
    ...partial,
  };
}

describe('分诊台', () => {
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  /** 会话种子（auth store 从 sessionStorage 恢复）：含本页元素码——真实 NURSE 会话经登录
   * 契约导出含码，既有用例按钮保留、断言语义不变（D-21 申报规范，评审 D-I1 全局指令补齐） */
  function seedAuthSession(): void {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: {
          userId: 'u1',
          permissions: ['outpatient:queue:btn:call', 'outpatient:triage:btn:manage'],
        },
      }),
    );
  }

  beforeEach(() => {
    vi.mocked(checkIn).mockReset();
    vi.mocked(adjustTriage).mockReset();
    vi.mocked(passTicket).mockReset();
    vi.mocked(callNext).mockReset();
    vi.mocked(getQueueSnapshot).mockReset();
    vi.mocked(listOrgs).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 快照兜底空队列：防未 stub 的 resolve 断链（诊区清单到达默认选中首项后即首拉）
    vi.mocked(getQueueSnapshot).mockResolvedValue([]);
    // 诊区清单兜底种子：防未 stub 的 resolve 断链（挂载即拉清单）
    vi.mocked(listOrgs).mockResolvedValue(DEPT_SEED);
    pinia = createPinia();
    setActivePinia(pinia);
    seedAuthSession();
  });

  it('门牌页首：衬线标题「分诊台」+ 轮询胶囊 ok 态呼吸点右挂 + 签认人·当前诊区·时刻批注行', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 'u1', displayName: '测试分诊护士', permissions: [] },
      }),
    );
    const wrapper = mount(TriageBoardView);
    await flushPromises();
    // 门牌页首锚（蓝图 P05.2/契约 ⑧.1）：衬线标题承接页面名，批注行取会话与诊区真值
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('分诊台');
    const note = wrapper.find('.fuy-page-note');
    expect(note.text()).toContain('签认人 测试分诊护士');
    expect(note.text()).toContain('当前诊区 内科');
    // 轮询胶囊 ok 态（蓝图 P05.4）：success 变体 + 呼吸点（本域唯一常驻动画位）+ 既有文案
    const pill = wrapper.find('.fuy-page-status .fuy-status-pill');
    expect(pill.classes()).toContain('fuy-status-pill--success');
    expect(pill.find('.fuy-live-dot').exists()).toBe(true);
    expect(pill.text()).toContain('每 5 秒自动刷新');
    wrapper.unmount();
  });

  it('轮询胶囊三态：error 态 danger 转色、paused 态 info 且呼吸点撤下、恢复可见回 success', async () => {
    vi.mocked(getQueueSnapshot).mockRejectedValue(new Error('network down'));
    const wrapper = mount(TriageBoardView);
    await flushPromises();
    // error 态（快照失败转红，文案三态既有）
    let pill = wrapper.find('.fuy-page-status .fuy-status-pill');
    expect(pill.classes()).toContain('fuy-status-pill--danger');
    expect(pill.text()).toContain('上次刷新失败，自动重试中');

    // paused 态（页面隐藏暂停：呼吸点撤下——实时语义不在，光环不挂）
    const hiddenSpy = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    document.dispatchEvent(new Event('visibilitychange'));
    await flushPromises();
    pill = wrapper.find('.fuy-page-status .fuy-status-pill');
    expect(pill.classes()).toContain('fuy-status-pill--info');
    expect(pill.text()).toContain('页面隐藏已暂停');
    expect(pill.find('.fuy-live-dot').exists()).toBe(false);

    // 恢复可见：刷新成功回 ok 态
    hiddenSpy.mockReturnValue(false);
    vi.mocked(getQueueSnapshot).mockResolvedValue([]);
    document.dispatchEvent(new Event('visibilitychange'));
    await flushPromises();
    pill = wrapper.find('.fuy-page-status .fuy-status-pill');
    expect(pill.classes()).toContain('fuy-status-pill--success');
    hiddenSpy.mockRestore();
    wrapper.unmount();
  });

  it('操作条按三业务域分组挂 .fuy-toolbar：报到域/诊区域/快捷键域组间竖发丝分隔', async () => {
    const wrapper = mount(TriageBoardView);
    await flushPromises();
    // .fuy-toolbar 挂类（蓝图 P05.2/3：56px 私值撤销归 48px CLS 锁工具条）
    const toolbar = wrapper.find('.fuy-toolbar');
    expect(toolbar.exists()).toBe(true);
    // 三域分组取工具条直接子级（报到域内 checkbox-group 自带 role=group 不在其列）
    const groups = toolbar.findAll('.fuy-toolbar > [role="group"]');
    expect(groups.map((g) => g.attributes('aria-label'))).toEqual([
      '分诊报到',
      '诊区切换',
      '快捷键',
    ]);
    // 报到域：输入 240 + 优先因子组 + 报到钮同组（报到参数与动作同域既有语义）
    expect(groups[0]?.find('input[placeholder="就诊号（O+yyyyMMdd+5 位流水）"]').exists()).toBe(
      true,
    );
    expect(groups[0]?.findComponent({ name: 'ElCheckboxGroup' }).exists()).toBe(true);
    expect(groups[0]?.text()).toContain('分诊报到');
    // 诊区域：真数据下拉（160px 档）
    expect(groups[1]?.findComponent(ElSelect).exists()).toBe(true);
    // 快捷键域：Alt+R kbd 提示（§5.3 既有）
    expect(groups[2]?.find('kbd').exists()).toBe(true);
    expect(groups[2]?.text()).toContain('叫出队首');
    wrapper.unmount();
  });

  it('诊区真数据三态：清单在途选择器禁用零快照出网，到达默认选中首项并首拉快照', async () => {
    // 清单在途（deferred 手控时序）：选择器禁用、快照零出网（queue_id 必须锚定真实诊区）
    let releaseDepts: () => void = () => {};
    vi.mocked(listOrgs).mockImplementation(
      () =>
        new Promise<OrgVO[]>((resolve) => {
          releaseDepts = () => resolve(DEPT_SEED);
        }),
    );
    const wrapper = mount(TriageBoardView);
    await flushPromises();
    const deptSelect = wrapper.findComponent(ElSelect);
    expect(deptSelect.props('disabled')).toBe(true);
    expect(vi.mocked(getQueueSnapshot)).not.toHaveBeenCalled();

    // 清单到达：选择器解禁，选项=种子三码（出网值仍 string code），默认选中首项并首拉
    releaseDepts();
    await flushPromises();
    expect(deptSelect.props('disabled')).toBe(false);
    const options = deptSelect.findAllComponents({ name: 'ElOption' });
    expect(options.map((o) => o.props('value'))).toEqual(['DEPT-INT', 'DEPT-SUR', 'DEPT-PED']);
    expect(options[0]?.props('label')).toBe('内科（DEPT-INT）');
    expect(vi.mocked(getQueueSnapshot)).toHaveBeenCalledWith({
      queueId: 'DEPT-INT',
      status: undefined,
    });
    wrapper.unmount();
  });

  it('诊区真数据三态：空清单回包选择器禁用、队列给指引空态且零快照出网', async () => {
    vi.mocked(listOrgs).mockResolvedValue([]);
    const wrapper = mount(TriageBoardView);
    await flushPromises();
    expect(wrapper.findComponent(ElSelect).props('disabled')).toBe(true);
    expect(wrapper.text()).toContain('暂无可用诊区');
    expect(vi.mocked(getQueueSnapshot)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('诊区切换：选中行清空回指引空态并按新诊区编码重拉快照（既有行为语义保留）', async () => {
    vi.mocked(getQueueSnapshot).mockResolvedValue([
      ticketMock({ id: '1', ticketNo: 'A003', status: 'WAITING' }),
    ]);
    const wrapper = mount(TriageBoardView);
    await flushPromises();
    // 选中行 → 票务详情显影
    await wrapper.findAll('.el-table__row')[0].trigger('click');
    expect(wrapper.text()).toContain('已叫次数');
    // 切换诊区：详情清空、快照按新 queueId 重拉（$emit 同步回填 v-model，flushPromises 承接刷）
    const deptSelect = wrapper.findComponent(ElSelect);
    deptSelect.vm.$emit('update:modelValue', 'DEPT-SUR');
    deptSelect.vm.$emit('change', 'DEPT-SUR');
    await flushPromises();
    expect(wrapper.text()).not.toContain('已叫次数');
    expect(wrapper.text()).toContain('暂无选中票据');
    expect(vi.mocked(getQueueSnapshot)).toHaveBeenLastCalledWith({
      queueId: 'DEPT-SUR',
      status: undefined,
    });
    wrapper.unmount();
  });

  it('渲染断言：报到输入/轮询提示与队列表空态脸齐备，挂载经清单首项首拉快照', async () => {
    const wrapper = mount(TriageBoardView);
    await flushPromises();
    expect(wrapper.find('input[placeholder="就诊号（O+yyyyMMdd+5 位流水）"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('每 5 秒自动刷新');
    // 空态脸（契约 ⑥/总则 8）：暂无语法 + 下一步指引，零默认纸箱插画
    expect(wrapper.find('.fuy-empty').exists()).toBe(true);
    expect(wrapper.text()).toContain('暂无候诊票据');
    // 挂载即首拉快照（queueId=清单首项，后端契约同源）
    expect(vi.mocked(getQueueSnapshot)).toHaveBeenCalledWith({
      queueId: 'DEPT-INT',
      status: undefined,
    });
    wrapper.unmount();
  });

  it('报到显式格式校验：空值与形态违规两道前置拦截零出网（W-22⑦ 口径）', async () => {
    const wrapper = mount(TriageBoardView);
    await flushPromises();
    const input = wrapper.find('input[placeholder="就诊号（O+yyyyMMdd+5 位流水）"]');

    // 空值拦截
    await clickButton(wrapper, '分诊报到');
    await flushPromises();
    expect(vi.mocked(checkIn)).not.toHaveBeenCalled();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledTimes(1);

    // 形态拦截（缺 O 前缀且位数不足）
    await input.setValue('202609210001');
    await clickButton(wrapper, '分诊报到');
    await flushPromises();
    expect(vi.mocked(checkIn)).not.toHaveBeenCalled();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('合规就诊号报到出网：携 TRIAGE_DESK 终端标识与勾选因子，成功清空输入并重拉快照', async () => {
    vi.mocked(checkIn).mockResolvedValue(ticketMock({ status: 'WAITING' }));
    const wrapper = mount(TriageBoardView);
    await flushPromises();

    await wrapper
      .find('input[placeholder="就诊号（O+yyyyMMdd+5 位流水）"]')
      .setValue('O2026092100001');
    // 勾选老幼残优先级因子（checkbox-group 以 emit 回填 v-model，口径同 select/date-picker 替身）
    wrapper.findComponent({ name: 'ElCheckboxGroup' }).vm.$emit('update:modelValue', ['ELDERLY']);
    await flushPromises();
    await clickButton(wrapper, '分诊报到');
    await flushPromises();

    expect(vi.mocked(checkIn)).toHaveBeenCalledWith({
      visitId: 'O2026092100001',
      stationId: 'TRIAGE_DESK',
      priorityFactors: ['ELDERLY'],
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 成功后输入清空 + 快照重拉（报到新行入场由 merge 承载）
    expect(
      (
        wrapper.find('input[placeholder="就诊号（O+yyyyMMdd+5 位流水）"]')
          .element as HTMLInputElement
      ).value,
    ).toBe('');
    expect(vi.mocked(getQueueSnapshot)).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('队列表渲染断言：票号/脱敏姓名/级别徽标两态/优先级/状态 tag 与等待时长列', async () => {
    vi.mocked(getQueueSnapshot).mockResolvedValue([
      ticketMock({ id: '1', ticketNo: 'A003', status: 'WAITING', triageLevel: 2 }),
      ticketMock({
        id: '2',
        ticketNo: 'A004',
        status: 'CALLED',
        queueTime: new Date(Date.now() - 45 * 60000).toISOString(),
      }),
    ]);
    const wrapper = mount(TriageBoardView);
    await flushPromises();
    expect(wrapper.text()).toContain('A003');
    expect(wrapper.text()).toContain('A004');
    expect(wrapper.text()).toContain('张*');
    expect(wrapper.text()).toContain('候诊中');
    expect(wrapper.text()).toContain('已叫号');
    // 级别徽标两态（W-29 D-2 消费面）：票面有分级渲染对应级别徽标，可空行仅占位不出徽标
    const levelBadge = wrapper.find('.fuy-triage-badge--l2');
    expect(levelBadge.exists()).toBe(true);
    expect(levelBadge.text()).toBe('Ⅱ级');
    expect(wrapper.findAll('.fuy-triage-badge')).toHaveLength(1);
    // 等待 ≥30 分钟预警列渲染（45 分钟）
    expect(wrapper.text()).toContain('45 分钟');
    wrapper.unmount();
  });

  it('过号在途：确认弹窗带回显摘要，按钮禁用且二次点击零出网（W-22⑥ 防抖）', async () => {
    vi.mocked(getQueueSnapshot).mockResolvedValue([
      ticketMock({ id: '2', ticketNo: 'A004', status: 'CALLED' }),
    ]);
    let releasePass: () => void = () => {};
    vi.mocked(passTicket).mockImplementation(
      () =>
        new Promise<QueueTicketVO>((resolve) => {
          releasePass = () => resolve(ticketMock({ id: '2', status: 'PASSED' }));
        }),
    );
    const wrapper = mount(TriageBoardView);
    await flushPromises();

    // 行内过号（中风险档：confirm 带回显摘要）
    await wrapper.findAll('.el-table__row')[0].trigger('click');
    const passButton = wrapper.findAll('button').find((b) => b.text() === '过号');
    await passButton?.trigger('click');
    await flushPromises();
    expect(vi.mocked(ElMessageBox.confirm)).toHaveBeenCalledWith(
      '即将为 A004 张* 过号（降级重排不改号），确认？',
      '过号确认',
    );
    expect(findButton(wrapper, '过号').attributes('disabled')).toBeDefined();
    // 在途窗口内二次点击：入口守卫零第二次出网
    await passButton?.trigger('click');
    await flushPromises();
    expect(vi.mocked(passTicket)).toHaveBeenCalledTimes(1);

    releasePass();
    await flushPromises();
    expect(findButton(wrapper, '过号').attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });

  it('调级提交：confirm 带回显摘要（票号+目标级别）且出网参数携 LEVEL_ADJUST/triageLevel/reason', async () => {
    vi.mocked(getQueueSnapshot).mockResolvedValue([
      ticketMock({ id: '1', ticketNo: 'A003', status: 'WAITING' }),
    ]);
    vi.mocked(adjustTriage).mockResolvedValue(ticketMock({ id: '1' }));
    const wrapper = mount(TriageBoardView);
    await flushPromises();

    // 选中行 → 填调级理由（必填）→ 提交处置（默认动作 LEVEL_ADJUST，默认目标级别 Ⅲ级）
    await wrapper.findAll('.el-table__row')[0].trigger('click');
    await wrapper
      .find('textarea[placeholder="动作理由（调级必填，≤255 字）"]')
      .setValue('患者症状加重');
    await clickButton(wrapper, '提交处置');
    await flushPromises();
    expect(vi.mocked(ElMessageBox.confirm)).toHaveBeenCalledWith(
      '即将为 A003 张* 调至 Ⅲ级，确认？',
      '分诊处置确认',
      expect.objectContaining({ confirmButtonText: '确认调整' }),
    );
    expect(vi.mocked(adjustTriage)).toHaveBeenCalledWith({
      visitId: 'O2026092100001',
      action: 'LEVEL_ADJUST',
      triageLevel: 3,
      targetQueue: undefined,
      doctorId: undefined,
      reason: '患者症状加重',
    });
    wrapper.unmount();
  });

  it('调级理由前置校验：LEVEL_ADJUST 空理由被拦截，确认与出网零发生（W-29 D-9 必填呈现面）', async () => {
    vi.mocked(getQueueSnapshot).mockResolvedValue([
      ticketMock({ id: '1', ticketNo: 'A003', status: 'WAITING' }),
    ]);
    const wrapper = mount(TriageBoardView);
    await flushPromises();
    // 跨用例累积 mock 先清零，证明确认弹窗在本用例内未触发（拦截先于确认）
    vi.mocked(ElMessageBox.confirm).mockClear();

    await wrapper.findAll('.el-table__row')[0].trigger('click');
    await clickButton(wrapper, '提交处置');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写调级理由');
    expect(vi.mocked(ElMessageBox.confirm)).not.toHaveBeenCalled();
    expect(vi.mocked(adjustTriage)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('转队列高风险分档：目标诊区必选前置拦截零出网，confirm danger 档且出网携诊区编码', async () => {
    vi.mocked(getQueueSnapshot).mockResolvedValue([
      ticketMock({ id: '1', ticketNo: 'A003', status: 'WAITING' }),
    ]);
    vi.mocked(adjustTriage).mockResolvedValue(ticketMock({ id: '1' }));
    const wrapper = mount(TriageBoardView);
    await flushPromises();
    vi.mocked(ElMessageBox.confirm).mockClear();

    // 选中行 → 切处置动作至 QUEUE_TRANSFER（组件序：0=诊区切换 1=处置动作）
    await wrapper.findAll('.el-table__row')[0].trigger('click');
    const selects = wrapper.findAllComponents({ name: 'ElSelect' });
    selects[1]?.vm.$emit('update:modelValue', 'QUEUE_TRANSFER');
    await flushPromises();

    // 目标诊区未选：前置拦截零出网（高风险跨诊区动作禁裸提交）
    await clickButton(wrapper, '提交处置');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请选择目标诊区');
    expect(vi.mocked(ElMessageBox.confirm)).not.toHaveBeenCalled();
    expect(vi.mocked(adjustTriage)).not.toHaveBeenCalled();

    // 目标诊区下拉消费同一 listOrgs 真数据清单（组件序：2=目标诊区），出网值仍 string code
    const selectsWithTarget = wrapper.findAllComponents({ name: 'ElSelect' });
    selectsWithTarget[2]?.vm.$emit('update:modelValue', 'DEPT-SUR');
    await clickButton(wrapper, '提交处置');
    await flushPromises();
    expect(vi.mocked(ElMessageBox.confirm)).toHaveBeenCalledWith(
      '即将把 A003 张* 转至诊区 DEPT-SUR（跨诊区高风险操作），确认？',
      '分诊处置确认',
      expect.objectContaining({
        type: 'warning',
        confirmButtonText: '确认转队列',
        // 转队列跨诊区属 §5.2 高风险档：确认按钮 danger 红样式承载不可逆警示
        confirmButtonClass: 'el-button--danger',
      }),
    );
    expect(vi.mocked(adjustTriage)).toHaveBeenCalledWith({
      visitId: 'O2026092100001',
      action: 'QUEUE_TRANSFER',
      triageLevel: undefined,
      targetQueue: 'DEPT-SUR',
      doctorId: undefined,
      reason: undefined,
    });
    wrapper.unmount();
  });

  it('轮询 merge 同步可变字段（真机 D-3）：同 id 同状态回包改派 doctorId 后，叫号携新值出网', async () => {
    // 首拉：WAITING 行派给 d1
    vi.mocked(getQueueSnapshot).mockResolvedValue([
      ticketMock({ id: '1', ticketNo: 'A003', status: 'WAITING', doctorId: 'd1' }),
    ]);
    const wrapper = mount(TriageBoardView);
    await flushPromises();

    // 模拟二次分诊（RE_TRIAGE）改派后的下一拍轮询回包：同 id 同状态仅 doctorId 变化
    vi.mocked(getQueueSnapshot).mockResolvedValue([
      ticketMock({ id: '1', ticketNo: 'A003', status: 'WAITING', doctorId: 'd2' }),
    ]);
    // 轮询 5s 周期不在用例时间窗内触发：经 visibilitychange 恢复可见驱动单次刷新（同 onVisibilityChange 路径）
    document.dispatchEvent(new Event('visibilitychange'));
    await flushPromises();

    // 业务断言：叫号请求体携带改派后的 doctorId=d2（旧实现保留旧行致 doctorId 仍为 d1）
    await clickButton(wrapper, '叫号');
    await flushPromises();
    expect(vi.mocked(callNext)).toHaveBeenCalledWith({ deptCode: 'DEPT-INT', doctorId: 'd2' });
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(TriageBoardView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 outpatient:triage:btn:manage 时分诊报到按钮不渲染（D-34 无码全隐藏）', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 1 } }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('候诊队列');
    expect(wrapper.text()).not.toContain('分诊报到');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 outpatient:triage:btn:manage 时分诊报到按钮渲染', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, permissions: ['outpatient:triage:btn:manage'] },
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.findAll('button').some((b) => b.text() === '分诊报到')).toBe(true);
    wrapper.unmount();
  });
});

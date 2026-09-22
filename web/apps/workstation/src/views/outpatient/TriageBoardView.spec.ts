// 分诊台页单测（FU-M03-04 前端面）：渲染断言（操作条/轮询提示/空态/分诊级别徽标两态）、
// 报到显式格式校验（空值与形态违规两道前置拦截零出网——W-22⑦ 同款禁裸提交）、报到出网参数、
// 行动作在途守卫（W-22⑥：慢响应窗口按钮禁用且二次点击零出网）、调级确认带回显摘要、
// 调级理由必填前置拦截与出网携带（W-29 D-9）、轮询 merge 可变字段同步（真机 D-3：二次分诊
// 改派 doctorId 后叫号须携新值出网）。
// api mock 承载，不打真实网络；轮询 5s 周期在用例时间窗内零触发（merge 用例经
// visibilitychange 事件驱动单次刷新），卸载清理定时器。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adjustTriage, callNext, checkIn, getQueueSnapshot, passTicket } from '@/api/outpatient';
import type { QueueTicketVO } from '@/api/outpatient';
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
  beforeEach(() => {
    vi.mocked(checkIn).mockReset();
    vi.mocked(adjustTriage).mockReset();
    vi.mocked(passTicket).mockReset();
    vi.mocked(callNext).mockReset();
    vi.mocked(getQueueSnapshot).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 快照兜底空队列：防未 stub 的 resolve 断链（mount 即首拉）
    vi.mocked(getQueueSnapshot).mockResolvedValue([]);
  });

  it('渲染断言：报到输入/诊区切换/轮询提示与队列表空态齐备', async () => {
    const wrapper = mount(TriageBoardView);
    await flushPromises();
    expect(wrapper.find('input[placeholder="就诊号（O+yyyyMMdd+5 位流水）"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('每 5 秒自动刷新');
    expect(wrapper.text()).toContain('当前诊区候诊队列为空');
    // 挂载即首拉快照（queueId=deptCode，后端契约同源）
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
});

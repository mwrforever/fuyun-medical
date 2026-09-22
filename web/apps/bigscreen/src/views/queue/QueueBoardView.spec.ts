// 候诊叫号大屏页单测（FU-M03-05 大屏端前端面）：核心断言——未配置大屏令牌时整页横幅 +
// 【零出网】（零 REST 零订阅零建连）；已配置链路：快照首屏榜单前 8 条渲染 + 订阅挂接；
// CALLED 帧驱动当前叫号卡与该票离队；断线横幅呈现。api/STOMP 模块 mock 承载，禁真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { getQueueSnapshot } from '@/api/outpatientQueue';
import type { QueueTicketVO } from '@/api/outpatientQueue';
import QueueBoardView from './QueueBoardView.vue';

/** mock 捕获状态（hoisted：令牌配置开关 + 捕获的订阅帧回调，逐用例手动复位） */
const h = vi.hoisted(() => ({
  tokenConfigured: false,
  onFrame: null as null | ((notice: unknown) => void),
  unsubscribeCalls: 0,
}));

vi.mock('@/api/outpatientQueue', () => ({
  getQueueSnapshot: vi.fn(),
}));

vi.mock('@/composables/useQueueStomp', () => {
  // connectionState 以真实 ref 承载（组件 computed 消费其 .value 响应性）
  const connectionState = ref<string>('connecting');
  return {
    connectionState,
    isQueueTokenConfigured: () => h.tokenConfigured,
    connect: vi.fn(),
    subscribeQueue: vi.fn(
      (_deptCode: string, onFrame: (notice: unknown) => void): { unsubscribe: () => void } => {
        h.onFrame = onFrame;
        return { unsubscribe: () => void (h.unsubscribeCalls += 1) };
      },
    ),
    disconnect: vi.fn().mockResolvedValue(undefined),
    queueTopicPath: (deptCode: string) => `/topic/outpatient/queue/${deptCode}`,
  };
});

// 连接状态 ref 引用（用例内直接翻转驱动断线横幅断言）
// eslint 提示：与 mock 工厂共享模块作用域，非未使用导入
import { connectionState } from '@/composables/useQueueStomp';

function ticketMock(
  no: string,
  status: QueueTicketVO['status'] = 'WAITING',
  triageLevel?: number,
): QueueTicketVO {
  return {
    id: no,
    visitId: `O20260921${no}`,
    queueId: 'DEPT-INT',
    ticketNo: no,
    ticketType: 'FIRST',
    priorityScore: 300,
    queueSeq: 1,
    status,
    patientName: '张*',
    // 票面分诊级别（W-29 契约消费）：undefined=可空态（非分级流程票据）
    triageLevel,
  };
}

/** 挂载组件（内存 history 路由，query 可书签化形态与生产一致） */
async function mountBoard(
  query = '',
): Promise<{ wrapper: ReturnType<typeof mount>; router: ReturnType<typeof createRouter> }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/queue', component: QueueBoardView }],
  });
  await router.push(`/queue${query}`);
  await router.isReady();
  const wrapper = mount(QueueBoardView, { global: { plugins: [router] } });
  await flushPromises();
  return { wrapper, router };
}

describe('候诊叫号大屏', () => {
  beforeEach(() => {
    vi.mocked(getQueueSnapshot).mockReset();
    h.tokenConfigured = false;
    h.onFrame = null;
    h.unsubscribeCalls = 0;
    (connectionState as { value: string }).value = 'connecting';
  });

  it('未配置大屏令牌：整页横幅 + 零出网（零 REST 快照零订阅零建连）', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { wrapper } = await mountBoard();
    // 渲染断言：横幅文案与部署指引
    expect(wrapper.text()).toContain('未配置大屏令牌，已禁用数据链路');
    expect(wrapper.text()).toContain('VITE_BIGSCREEN_TOKEN');
    // 零出网断言（§8.5 链路禁用）：无 REST、无 WS 建连、无订阅
    expect(vi.mocked(getQueueSnapshot)).not.toHaveBeenCalled();
    const { connect } = await import('@/composables/useQueueStomp');
    expect(vi.mocked(connect)).not.toHaveBeenCalled();
    expect(h.onFrame).toBeNull();
    warnSpy.mockRestore();
    wrapper.unmount();
  });

  it('已配置链路：REST 快照首屏 + WS 订阅挂接，候诊榜渲染前 8 条与状态角标', async () => {
    h.tokenConfigured = true;
    // 11 行快照：榜单只渲染前 8 条（§7.2 slice 承载）
    vi.mocked(getQueueSnapshot).mockResolvedValue(
      Array.from({ length: 11 }, (_, i) => ticketMock(`A${String(i + 1).padStart(3, '0')}`)),
    );
    const { wrapper } = await mountBoard('?dept=DEPT-INT');
    await flushPromises();

    expect(vi.mocked(getQueueSnapshot)).toHaveBeenCalledWith('DEPT-INT');
    const { connect, subscribeQueue } = await import('@/composables/useQueueStomp');
    expect(vi.mocked(connect)).toHaveBeenCalledWith('DEPT-INT');
    expect(vi.mocked(subscribeQueue)).toHaveBeenCalledWith('DEPT-INT', expect.any(Function));
    // 榜单容量断言：11 行快照渲染 8 行（两列 grid 4×2）
    expect(wrapper.findAll('.queue-board-row')).toHaveLength(8);
    expect(wrapper.text()).toContain('A001');
    expect(wrapper.text()).toContain('候诊');
    wrapper.unmount();
  });

  it('分诊级别角标：快照行有分级渲染对应级别角标，可空行不渲染占位（W-29 契约消费）', async () => {
    h.tokenConfigured = true;
    vi.mocked(getQueueSnapshot).mockResolvedValue([
      ticketMock('A001', 'WAITING', 1),
      ticketMock('A002', 'WAITING'),
    ]);
    const { wrapper } = await mountBoard('?dept=DEPT-INT');
    await flushPromises();

    // 有分级行：角标携级别词表文案与四级色 modifier；可空行整段隐藏（榜单不出现第二枚角标）
    const badges = wrapper.findAll('.queue-board-row-triage');
    expect(badges).toHaveLength(1);
    expect(badges[0].text()).toBe('Ⅰ级');
    expect(badges[0].classes()).toContain('is-l1');
    wrapper.unmount();
  });

  it('CALLED 帧：当前叫号卡 :key 票号重挂（引导语/票号/脱敏姓名）且该票从榜单离队', async () => {
    h.tokenConfigured = true;
    vi.mocked(getQueueSnapshot).mockResolvedValue([
      ticketMock('A008'),
      ticketMock('A009', 'CALLED'),
    ]);
    const { wrapper } = await mountBoard();
    await flushPromises();
    expect(h.onFrame).not.toBeNull();

    // 投递 CALLED 帧（票号 A009）：当前叫号卡更新 + 榜单该票移除
    h.onFrame?.({
      type: 'CALLED',
      ticketNo: 'A009',
      patientName: '张*',
      doctorId: 'u1',
      room: '3',
    });
    await flushPromises();
    expect(wrapper.text()).toContain('请 A009 号到');
    expect(wrapper.text()).toContain('3');
    expect(wrapper.text()).toContain('诊室');
    expect(wrapper.text()).toContain('张*');
    const tickets = wrapper.findAll('.queue-board-row-ticket').map((node) => node.text());
    expect(tickets).not.toContain('A009');
    expect(tickets).toContain('A008');
    wrapper.unmount();
  });

  it('断线横幅：connectionState 回归断开态呈现「连接中断，自动重连中」', async () => {
    h.tokenConfigured = true;
    vi.mocked(getQueueSnapshot).mockResolvedValue([]);
    const { wrapper } = await mountBoard();
    await flushPromises();
    expect(wrapper.text()).not.toContain('连接中断，自动重连中');

    (connectionState as { value: string }).value = 'disconnected';
    await flushPromises();
    expect(wrapper.text()).toContain('连接中断，自动重连中');
    wrapper.unmount();
  });
});
